package com.dannycom888.banzou.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * MDX-Net vocal separation over PCM files, a Kotlin port of the reference
 * pipeline validated on the VM (which itself mirrors sherpa-onnx's UVR
 * implementation):
 *
 *   interleaved float32 stereo PCM in
 *   -> per chunk/segment: STFT -> UVR_MDXNET_9482 (outputs the VOCALS
 *      spectrogram) -> iSTFT -> vocals
 *   -> accompaniment = mix - vocals, written as interleaved float32 PCM out
 *
 * The vocal stem is never written anywhere; only the accompaniment leaves
 * this class. Output length is exactly the input length in samples.
 */
class Separator(modelFile: File) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession(modelFile)
    private val inputName: String = session.inputNames.first()
    private val stft = Stft(SegmentPlanner.N_FFT, SegmentPlanner.HOP)

    private fun createSession(modelFile: File): OrtSession {
        // Prefer NNAPI acceleration; any failure falls back to plain CPU.
        try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                addNnapi()
            }
            return env.createSession(modelFile.absolutePath, opts)
        } catch (e: Exception) {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to "4"))
                } catch (ignored: Exception) {
                    // XNNPACK is optional; CPU default is fine.
                }
            }
            return env.createSession(modelFile.absolutePath, opts)
        }
    }

    fun close() {
        try {
            session.close()
        } catch (ignored: Exception) {
        }
    }

    /**
     * @param pcmIn  interleaved float32 stereo PCM at 44.1 kHz
     * @param pcmOut receives the accompaniment, same format and length
     * @param totalSamples samples per channel
     * @param onProgress (doneSegments, totalSegments)
     */
    fun separate(
        pcmIn: File,
        pcmOut: File,
        totalSamples: Long,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
    ) {
        val plans = SegmentPlanner.plan(totalSamples)
        val totalSegments = plans.sumOf { it.segments }
        var doneSegments = 0
        onProgress(0, totalSegments)

        RandomAccessFile(pcmIn, "r").use { input ->
            pcmOut.outputStream().buffered().use { output ->
                val byteBuf = ByteBuffer.allocate(8192 * 8).order(ByteOrder.LITTLE_ENDIAN)
                val floatView = byteBuf.asFloatBuffer()
                fun writeInterleaved(ch0: FloatArray, ch1: FloatArray, from: Int, count: Int) {
                    var i = from
                    val end = from + count
                    while (i < end) {
                        byteBuf.clear()
                        val n = minOf(8192, end - i)
                        for (k in 0 until n) {
                            floatView.put(k * 2, ch0[i + k])
                            floatView.put(k * 2 + 1, ch1[i + k])
                        }
                        byteBuf.limit(n * 8)
                        output.write(byteBuf.array(), 0, n * 8)
                        i += n
                    }
                }

                plans.forEachIndexed { index, plan ->
                    if (isCancelled()) throw CancellationException("cancelled")
                    val len = plan.inputLength.toInt()
                    val mix0 = FloatArray(len)
                    val mix1 = FloatArray(len)
                    readChunk(input, plan.inputStart, len, mix0, mix1)

                    val voc = processChunk(mix0, mix1, plan) {
                        doneSegments++
                        onProgress(doneSegments, totalSegments)
                        if (isCancelled()) throw CancellationException("cancelled")
                    }
                    val kept = SegmentPlanner.keptLength(
                        plan, index == 0, index == plans.lastIndex, totalSamples,
                    ).toInt()
                    val startInChunk = (plan.outputStart - plan.inputStart).toInt()
                    // accompaniment = mix - vocals, for the chunk's core range
                    val acc0 = FloatArray(kept) { mix0[startInChunk + it] - voc.first[startInChunk + it] }
                    val acc1 = FloatArray(kept) { mix1[startInChunk + it] - voc.second[startInChunk + it] }
                    writeInterleaved(acc0, acc1, 0, kept)
                }
            }
        }
    }

    /** Reads [len] samples per channel starting at [startSample] from interleaved float PCM. */
    private fun readChunk(
        input: RandomAccessFile,
        startSample: Long,
        len: Int,
        ch0: FloatArray,
        ch1: FloatArray,
    ) {
        input.seek(startSample * 8)
        val bytes = ByteArray(len * 8)
        input.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until len) {
            ch0[i] = bb.get(i * 2)
            ch1[i] = bb.get(i * 2 + 1)
        }
    }

    /**
     * Runs one chunk through STFT -> model -> iSTFT and returns the vocal
     * waveforms for the whole chunk input (margins included).
     */
    private fun processChunk(
        mix0: FloatArray,
        mix1: FloatArray,
        plan: SegmentPlanner.ChunkPlan,
        onSegment: () -> Unit,
    ): Pair<FloatArray, FloatArray> {
        val trim = SegmentPlanner.TRIM
        val seg = SegmentPlanner.SEG
        val gen = SegmentPlanner.GEN
        val n = mix0.size
        val pad = plan.pad.toInt()

        fun padded(mix: FloatArray): FloatArray {
            val arr = FloatArray(trim + n + pad + trim)
            mix.copyInto(arr, trim)
            return arr
        }
        val pad0 = padded(mix0)
        val pad1 = padded(mix1)

        val out0 = FloatArray(plan.segments * gen)
        val out1 = FloatArray(plan.segments * gen)
        var segIndex = 0
        var i = 0
        while (i < n + pad) {
            val v = runSegment(
                pad0.copyOfRange(i, i + seg),
                pad1.copyOfRange(i, i + seg),
            )
            v.first.copyInto(out0, segIndex * gen, trim, trim + gen)
            v.second.copyInto(out1, segIndex * gen, trim, trim + gen)
            segIndex++
            onSegment()
            i += gen
        }
        // Drop the STFT padding tail: vocals length back to chunk input length + margins area.
        // out arrays hold segments*gen samples starting at chunk input sample 0.
        return out0.copyOfRange(0, n) to out1.copyOfRange(0, n)
    }

    /** One model segment: [SEG] samples per channel in, vocals out (same length). */
    private fun runSegment(seg0: FloatArray, seg1: FloatArray): Pair<FloatArray, FloatArray> {
        val dimF = SegmentPlanner.DIM_F
        val dimT = SegmentPlanner.DIM_T
        val spec0 = stft.stft(seg0)
        val spec1 = stft.stft(seg1)
        require(spec0.frames == dimT) { "frames ${spec0.frames} != $dimT" }

        val data = FloatArray(4 * dimF * dimT)
        fun put(channel: Int, spec: Stft.Spectrogram, imag: Boolean) {
            val base = channel * dimF * dimT
            val src = if (imag) spec.im else spec.re
            for (f in 0 until dimF) {
                for (t in 0 until dimT) {
                    data[base + f * dimT + t] = src[t * spec.bins + f]
                }
            }
        }
        put(0, spec0, imag = false)
        put(1, spec0, imag = true)
        put(2, spec1, imag = false)
        put(3, spec1, imag = true)

        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, 4, dimF.toLong(), dimT.toLong()),
        )
        val result = tensor.use { session.run(mapOf(inputName to it)) }
        val out = (result[0] as OnnxTensor)
        val buf = out.floatBuffer
        val outData = FloatArray(buf.remaining()).also { buf.get(it) }
        result.close()

        fun build(channelRe: Int, channelIm: Int, orig: Stft.Spectrogram): FloatArray {
            val re = FloatArray(orig.re.size)
            val im = FloatArray(orig.im.size)
            val baseRe = channelRe * dimF * dimT
            val baseIm = channelIm * dimF * dimT
            for (f in 0 until dimF) {
                for (t in 0 until dimT) {
                    re[t * orig.bins + f] = outData[baseRe + f * dimT + t]
                    im[t * orig.bins + f] = outData[baseIm + f * dimT + t]
                }
            }
            // Bins at and above dimF stay zero, exactly like the reference.
            return stft.istft(Stft.Spectrogram(orig.frames, orig.bins, re, im), seg0.size)
        }
        val v0 = build(0, 1, spec0)
        val v1 = build(2, 3, spec1)
        return v0 to v1
    }
}

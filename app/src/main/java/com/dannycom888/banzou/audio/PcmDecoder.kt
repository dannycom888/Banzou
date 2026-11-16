package com.dannycom888.banzou.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an audio file (MP3) to a temp file of interleaved float32 stereo
 * PCM at 44.1 kHz, using MediaExtractor + MediaCodec. Files keep memory
 * flat regardless of song length. Mono is duplicated to stereo; other
 * sample rates are linearly resampled in a second file pass.
 */
object PcmDecoder {

    class Decoded(val pcmFile: File, val totalSamples: Long, val sampleRate: Int = 44100)

    fun decode(context: Context, uri: Uri, outFile: File): Decoded {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "找不到音频轨道" }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val rawFile = File(outFile.parentFile, outFile.name + ".raw")
        var totalFrames = 0L
        try {
            rawFile.outputStream().buffered().use { out ->
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                val byteBuf = ByteBuffer.allocate(65536 * 4).order(ByteOrder.LITTLE_ENDIAN)
                val floatView = byteBuf.asFloatBuffer()
                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            // Decoder output is PCM 16-bit on the target devices.
                            val shorts = ShortArray(bufferInfo.size / 2)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            val frames = shorts.size / channels
                            totalFrames += frames
                            byteBuf.clear()
                            var w = 0
                            fun pushFrame(frameStart: Int) {
                                val l = shorts[frameStart] / 32768f
                                val r = if (channels >= 2) shorts[frameStart + 1] / 32768f else l
                                floatView.put(w++, l)
                                floatView.put(w++, r)
                            }
                            var f = 0
                            while (f < frames) {
                                val n = minOf(65536, frames - f)
                                byteBuf.clear()
                                w = 0
                                for (k in 0 until n) pushFrame((f + k) * channels)
                                byteBuf.limit(n * 8)
                                out.write(byteBuf.array(), 0, n * 8)
                                f += n
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } finally {
            try {
                codec.stop()
                codec.release()
            } catch (ignored: Exception) {
            }
            extractor.release()
        }

        if (sourceRate == 44100) {
            rawFile.renameTo(outFile)
            return Decoded(outFile, totalFrames)
        }
        // Resample (rare: the library is 44.1 kHz; keep a correct slow path).
        val resampled = resampleFile(rawFile, sourceRate, 44100)
        rawFile.delete()
        return Decoded(outFile.apply { resampled.renameTo(this) }, resampledSamples(totalFrames, sourceRate))
    }

    private fun resampledSamples(frames: Long, sourceRate: Int): Long =
        frames * 44100L / sourceRate

    private fun resampleFile(input: File, fromRate: Int, toRate: Int): File {
        val out = File(input.parentFile, input.name + ".rs")
        val totalIn = input.length() / 8
        val totalOut = totalIn * toRate / fromRate
        input.inputStream().buffered().use { ins ->
            val all = FloatArray((totalIn * 2).toInt())
            val bb = ByteBuffer.wrap(ins.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            bb.get(all)
            out.outputStream().buffered().use { os ->
                val ob = ByteBuffer.allocate(8192 * 8).order(ByteOrder.LITTLE_ENDIAN)
                val fv = ob.asFloatBuffer()
                var written = 0L
                var idx = 0
                val chunk = FloatArray(8192 * 2)
                while (written < totalOut) {
                    val n = minOf(8192L, totalOut - written).toInt()
                    for (k in 0 until n) {
                        val pos = (written + k).toDouble() * fromRate / toRate
                        val i0 = pos.toLong().coerceAtMost(totalIn - 1)
                        val frac = (pos - i0).toFloat()
                        val i1 = minOf(i0 + 1, totalIn - 1)
                        for (c in 0..1) {
                            val a = all[(i0 * 2 + c).toInt()]
                            val b = all[(i1 * 2 + c).toInt()]
                            chunk[k * 2 + c] = a + (b - a) * frac
                            fv.put(k * 2 + c, chunk[k * 2 + c])
                        }
                    }
                    ob.limit(n * 8)
                    os.write(ob.array(), 0, n * 8)
                    ob.clear()
                    written += n
                    idx++
                }
            }
        }
        return out
    }
}

package com.dannycom888.banzou.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams an interleaved float32 stereo PCM file through LAME and writes
 * a CBR MP3 (192 kbps, 44.1 kHz) to [out]. Used for the temporary
 * ".part" file; renaming to the final name is the caller's job.
 */
object Mp3Encoder {
    private const val BITRATE_KBPS = 192
    private const val FRAMES_PER_BLOCK = 8192

    fun encode(pcmFile: File, totalSamples: Long, out: OutputStream) {
        require(LameNative.initEncoder(2, 44100, BITRATE_KBPS)) { "LAME 初始化失败" }
        try {
            pcmFile.inputStream().buffered().use { input ->
                val byteArray = ByteArray(FRAMES_PER_BLOCK * 8)
                val shorts = ShortArray(FRAMES_PER_BLOCK * 2)
                var remaining = totalSamples
                while (remaining > 0) {
                    val frames = minOf(FRAMES_PER_BLOCK.toLong(), remaining).toInt()
                    val bytes = frames * 8
                    var read = 0
                    while (read < bytes) {
                        val r = input.read(byteArray, read, bytes - read)
                        if (r < 0) break
                        read += r
                    }
                    if (read <= 0) break
                    val gotFrames = read / 8
                    val bb = ByteBuffer.wrap(byteArray, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                    val fv = bb.asFloatBuffer()
                    for (i in 0 until gotFrames * 2) {
                        val v = fv.get(i)
                        shorts[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    val encoded = LameNative.encodeInterleaved(shorts, gotFrames)
                    if (encoded != null && encoded.isNotEmpty()) out.write(encoded)
                    remaining -= gotFrames
                }
            }
            val tail = LameNative.flushEncoder()
            if (tail != null && tail.isNotEmpty()) out.write(tail)
            out.flush()
        } finally {
            LameNative.closeEncoder()
        }
    }
}

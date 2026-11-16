package com.dannycom888.banzou.audio

import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT / iSTFT matching the reference pipeline (torch-style, as used by
 * UVR / sherpa-onnx for MDX-Net models):
 * - periodic Hann window of length [nFft]
 * - center padding of nFft/2 samples on both sides with reflection
 * - hop [hop]
 *
 * [stft] returns a complex spectrogram as parallel arrays laid out
 * [frame][bin], with bins = nFft/2 + 1.
 * [istft] inverts it (overlap-add with window-envelope normalisation) and
 * removes the center padding, returning exactly [length] samples.
 */
class Stft(val nFft: Int = 4096, val hop: Int = 1024) {
    private val bins = nFft / 2 + 1
    private val fft = Fft(nFft)
    private val window = FloatArray(nFft) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / nFft)).toFloat()
    }

    class Spectrogram(val frames: Int, val bins: Int, val re: FloatArray, val im: FloatArray) {
        fun reAt(frame: Int, bin: Int): Float = re[frame * bins + bin]
        fun imAt(frame: Int, bin: Int): Float = im[frame * bins + bin]
    }

    private fun reflectIndex(i: Int, length: Int): Int {
        if (length == 1) return 0
        var x = i
        val period = 2 * length - 2
        x = ((x % period) + period) % period
        return if (x >= length) period - x else x
    }

    fun stft(x: FloatArray): Spectrogram {
        val pad = nFft / 2
        val paddedLen = x.size + 2 * pad
        val frames = 1 + (paddedLen - nFft) / hop
        val re = FloatArray(frames * bins)
        val im = FloatArray(frames * bins)
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)
        for (f in 0 until frames) {
            val start = f * hop - pad // index into the unpadded signal
            for (i in 0 until nFft) {
                frameRe[i] = x[reflectIndex(start + i, x.size)] * window[i]
                frameIm[i] = 0f
            }
            fft.forward(frameRe, frameIm)
            frameRe.copyInto(re, f * bins, 0, bins)
            frameIm.copyInto(im, f * bins, 0, bins)
        }
        return Spectrogram(frames, bins, re, im)
    }

    fun istft(spec: Spectrogram, length: Int): FloatArray {
        val pad = nFft / 2
        val total = nFft + hop * (spec.frames - 1) // padded-domain length
        val out = FloatArray(total)
        val env = FloatArray(total)
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)
        for (f in 0 until spec.frames) {
            // Rebuild the full Hermitian spectrum from the one-sided bins.
            for (b in 0 until bins) {
                frameRe[b] = spec.re[f * bins + b]
                frameIm[b] = spec.im[f * bins + b]
            }
            for (b in 1 until nFft / 2) {
                frameRe[nFft - b] = spec.re[f * bins + b]
                frameIm[nFft - b] = -spec.im[f * bins + b]
            }
            fft.inverse(frameRe, frameIm)
            val s = f * hop
            for (i in 0 until nFft) {
                out[s + i] += frameRe[i] * window[i]
                env[s + i] += window[i] * window[i]
            }
        }
        val result = FloatArray(length)
        for (i in 0 until length) {
            val p = i + pad
            val e = env[p]
            result[i] = if (e > 1e-8f) out[p] / e else 0f
        }
        return result
    }
}

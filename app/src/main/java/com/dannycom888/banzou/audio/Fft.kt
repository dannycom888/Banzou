package com.dannycom888.banzou.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative radix-2 FFT over split real/imaginary FloatArrays.
 * [n] must be a power of two. Forward transform is unscaled;
 * [inverse] applies the 1/n scaling, so inverse(forward(x)) == x.
 */
class Fft(val n: Int) {
    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two: $n" }
    }

    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = -2.0 * Math.PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        require(re.size == n && im.size == n)
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                var k = 0
                for (m in i until i + half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) -sinTable[k] else sinTable[k]
                    val xr = re[m + half]
                    val xi = im[m + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[m + half] = re[m] - tr
                    im[m + half] = im[m] - ti
                    re[m] += tr
                    im[m] += ti
                    k += step
                }
            }
            len = len shl 1
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)
}

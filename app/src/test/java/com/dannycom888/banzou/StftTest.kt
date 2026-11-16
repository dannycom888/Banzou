package com.dannycom888.banzou.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class StftTest {

    @Test
    fun `stft istft round trip reconstructs a segment`() {
        val stft = Stft(SegmentPlanner.N_FFT, SegmentPlanner.HOP)
        val n = SegmentPlanner.SEG // one full model segment: 261_120 samples
        val random = Random(42)
        val x = FloatArray(n) { i ->
            (0.4 * sin(2.0 * PI * 440.0 * i / 44100.0) + 0.1 * (random.nextFloat() * 2 - 1)).toFloat()
        }
        val spec = stft.stft(x)
        assertEquals(SegmentPlanner.DIM_T, spec.frames)
        val back = stft.istft(spec, n)
        assertEquals(n, back.size)
        // Edges sit under the reflect-pad boundary; judge the middle 90%.
        val from = n / 20
        val to = n - n / 20
        var maxErr = 0f
        var signalMax = 0f
        for (i in from until to) {
            val err = kotlin.math.abs(back[i] - x[i])
            if (err > maxErr) maxErr = err
            val a = kotlin.math.abs(x[i])
            if (a > signalMax) signalMax = a
        }
        assertTrue("round-trip max error $maxErr too large (signal max $signalMax)", maxErr < 0.01f)
    }

    @Test
    fun `stft of silence is silence`() {
        val stft = Stft(4096, 1024)
        val x = FloatArray(SegmentPlanner.SEG)
        val spec = stft.stft(x)
        var maxMag = 0f
        for (v in spec.re) maxMag = maxOf(maxMag, kotlin.math.abs(v))
        for (v in spec.im) maxMag = maxOf(maxMag, kotlin.math.abs(v))
        assertEquals(0f, maxMag, 0.0f)
        val back = stft.istft(spec, x.size)
        for (v in back) assertEquals(0f, v, 1e-6f)
    }
}

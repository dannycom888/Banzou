package com.dannycom888.banzou.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {

    @Test
    fun `planned output length equals input length for many sizes`() {
        val sizes = listOf(
            1L, 100L, 44100L, 257023L, 257024L, 257025L,
            661499L, 661500L, 661501L, 1323000L,
            15302112L, // 任贤齐 - 心太软: 346.987 s at 44.1 kHz
            20000000L, 44100L * 600L,
        )
        for (size in sizes) {
            assertEquals(
                "output length must equal input length for $size",
                size,
                SegmentPlanner.plannedOutputLength(size),
            )
        }
    }

    @Test
    fun `xin tai ruan plan shape`() {
        val total = 15302112L
        val plans = SegmentPlanner.plan(total)
        // 15_302_112 / 661_500 = 23.13 -> 24 chunks
        assertEquals(24, plans.size)
        assertEquals(plans.sumOf { it.segments }, SegmentPlanner.totalSegments(total))
        assertTrue(plans.all { it.segments > 0 })
        // Chunks tile the song without gaps in their kept output ranges.
        var expectedStart = 0L
        plans.forEachIndexed { idx, plan ->
            assertEquals(expectedStart, plan.outputStart)
            expectedStart += SegmentPlanner.keptLength(
                plan, idx == 0, idx == plans.lastIndex, total,
            )
        }
        assertEquals(total, expectedStart)
    }

    @Test
    fun `short song is a single chunk`() {
        val plans = SegmentPlanner.plan(100000L)
        assertEquals(1, plans.size)
        assertEquals(0L, plans[0].inputStart)
        assertEquals(100000L, plans[0].inputEnd)
    }

    @Test
    fun `empty song has no plan`() {
        assertTrue(SegmentPlanner.plan(0L).isEmpty())
        assertEquals(0L, SegmentPlanner.plannedOutputLength(0L))
    }
}

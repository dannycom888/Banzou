package com.dannycom888.banzou.audio

/**
 * Pure arithmetic for the MDX chunk/segment layout, mirroring the
 * sherpa-onnx UVR implementation. Kept free of audio data so the length
 * bookkeeping can be unit-tested exactly: whatever the song length, the
 * stitched output must be sample-for-sample the same length.
 *
 * Layout:
 * - The song is processed in outer chunks of [CHUNK] samples, each chunk
 *   extended by [MARGIN] samples on both sides (except at the song edges);
 *   the margins are dropped from the chunk's output.
 * - Inside a chunk, inference runs on segments of [SEG] samples of padded
 *   signal, advancing by [GEN] samples; each segment yields [GEN] samples
 *   after trimming [TRIM] from both ends.
 */
object SegmentPlanner {
    const val SAMPLE_RATE = 44100
    const val N_FFT = 4096
    const val HOP = 1024
    const val DIM_F = 2048
    const val DIM_T = 256
    const val MARGIN = 44100
    const val CHUNK = 15 * SAMPLE_RATE // 661_500

    const val TRIM = N_FFT / 2 // 2048
    const val SEG = HOP * (DIM_T - 1) // 261_120
    const val GEN = SEG - 2 * TRIM // 257_024

    /** A chunk of the song: input range [inputStart, inputEnd) including margins. */
    data class ChunkPlan(
        val inputStart: Long,
        val inputEnd: Long,
        /** Where this chunk's kept output lands in the song. */
        val outputStart: Long,
        /** Samples of zero padding appended inside the chunk's STFT stage. */
        val pad: Long,
        val segments: Int,
    ) {
        val inputLength: Long get() = inputEnd - inputStart
    }

    fun padFor(chunkLength: Long): Long = GEN - (chunkLength % GEN)

    fun segmentsFor(chunkLength: Long): Int {
        val pad = padFor(chunkLength)
        return ((chunkLength + pad) / GEN).toInt()
    }

    fun plan(totalSamples: Long): List<ChunkPlan> {
        if (totalSamples <= 0) return emptyList()
        val chunkSize = minOf(CHUNK.toLong(), totalSamples)
        val margin = minOf(MARGIN.toLong(), chunkSize)
        val plans = mutableListOf<ChunkPlan>()
        var i = 0L
        while (i < totalSamples) {
            val start = maxOf(0L, i - margin)
            val end = minOf(i + chunkSize + margin, totalSamples)
            if (start >= end) break
            plans += ChunkPlan(
                inputStart = start,
                inputEnd = end,
                outputStart = i,
                pad = padFor(end - start),
                segments = segmentsFor(end - start),
            )
            if (end == totalSamples) break
            i += chunkSize
        }
        return plans
    }

    /** Kept output length of one chunk, given whether it is first/last. */
    fun keptLength(plan: ChunkPlan, isFirst: Boolean, isLast: Boolean, totalSamples: Long): Long {
        val stitched = plan.segments.toLong() * GEN
        val start = if (isFirst) 0L else MARGIN.toLong()
        val end = if (isLast) stitched - plan.pad else stitched - plan.pad - MARGIN
        return end - start
    }

    fun totalSegments(totalSamples: Long): Int = plan(totalSamples).sumOf { it.segments }

    /** Sum of kept lengths over the plan; must equal [totalSamples]. */
    fun plannedOutputLength(totalSamples: Long): Long {
        val plans = plan(totalSamples)
        return plans.mapIndexed { idx, p ->
            keptLength(p, idx == 0, idx == plans.lastIndex, totalSamples)
        }.sum()
    }
}

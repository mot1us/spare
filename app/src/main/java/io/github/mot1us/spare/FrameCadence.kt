package io.github.mot1us.spare

import kotlin.math.abs

/** Measures media timestamps, not screen refresh. Tolerates duplicate callbacks and occasional skipped frames. */
class FrameCadence {
    private var epoch = -1
    private var previous = Long.MIN_VALUE
    private val intervals = LongArray(32)
    private var count = 0
    private var rate = 0f
    @Synchronized fun observe(token: Int, ptsUs: Long): Float {
        if (token != epoch) { epoch = token; previous = Long.MIN_VALUE; count = 0; rate = 0f }
        if (previous == Long.MIN_VALUE) { previous = ptsUs; return rate }
        val delta = ptsUs - previous
        // Duplicate/out-of-order notifications do not erase a good sample window.
        if (delta <= 0) {
            if (delta < -250000) { previous = ptsUs; count = 0 } // Stream discontinuity, rather than one reordered frame.
            return rate
        }
        previous = ptsUs
        if (delta > 250000) { count = 0; return rate }
        intervals[count++] = delta
        if (count == intervals.size) {
            val sorted = intervals.sorted(); val middle = sorted[sorted.size / 2]
            if (sorted.count { abs(it - middle) < middle * .05 } >= 24) {
                val measured = 1_000_000f / middle
                val nominal = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f).minBy { abs(it - measured) }
                rate = if (abs(nominal - measured) < .025f) nominal else measured.takeIf { it in 10f..120f } ?: 0f
            }
            count = 0
        }
        return rate
    }
}

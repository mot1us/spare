package io.github.mot1us.spare

import kotlin.math.abs

/** Source sample timestamps arrive in decode order; B-frames must be sorted before measuring. */
class InputCadence {
    private val timestamps = LongArray(48)
    private var count = 0
    private var epoch = -1
    private var maximum = Long.MIN_VALUE
    private var rate = 0f

    fun observe(token: Int, ptsUs: Long): Float {
        if (epoch != token || (maximum != Long.MIN_VALUE && abs(ptsUs - maximum) > 2_000_000)) {
            epoch = token; count = 0; maximum = Long.MIN_VALUE; rate = 0f
        }
        maximum = maxOf(maximum, ptsUs)
        timestamps[count++] = ptsUs
        if (count == timestamps.size) {
            rate = 0f
            val ordered = timestamps.sorted().distinct()
            // Trim reorder-window boundaries, where the next packet may fill a hole.
            val intervals = ordered.drop(4).dropLast(4).zipWithNext { a, b -> b - a }.filter { it in 8_000..100_000 }.sorted()
            if (intervals.size >= 28) {
                val middle = intervals[intervals.size / 2]
                val samples = intervals.filter { abs(it - middle) <= maxOf(1100.0, middle * .05) }
                if (samples.size >= intervals.size * .9) rate = VideoRate.normalize((1_000_000.0 * samples.size / samples.sum()).toFloat())
            }
            count = 0
        }
        return rate
    }
}

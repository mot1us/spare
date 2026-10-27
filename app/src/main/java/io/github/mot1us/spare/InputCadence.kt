package io.github.mot1us.spare

import kotlin.math.abs

/** Source sample timestamps arrive in decode order; B-frames must be sorted before measuring. */
class InputCadence {
    private val timestamps = LongArray(48)
    private var count = 0
    private var epoch = -1
    private var maximum = Long.MIN_VALUE
    private var rate = 0f
    private val windows = ArrayDeque<Pair<Long, Int>>()
    private var family = -1

    fun observe(token: Int, ptsUs: Long): Float {
        if (epoch != token || (maximum != Long.MIN_VALUE && abs(ptsUs - maximum) > 2_000_000)) {
            epoch = token; count = 0; maximum = Long.MIN_VALUE; rate = 0f; windows.clear(); family = -1
        }
        maximum = maxOf(maximum, ptsUs)
        timestamps[count++] = ptsUs
        if (count == timestamps.size) {
            val ordered = timestamps.sorted().distinct()
            // Trim reorder-window boundaries, where the next packet may fill a hole.
            val intervals = ordered.drop(4).dropLast(4).zipWithNext { a, b -> b - a }.filter { it in 8_000..100_000 }.sorted()
            if (intervals.size >= 28) {
                val middle = intervals[intervals.size / 2]
                val samples = intervals.filter { abs(it - middle) <= maxOf(1100.0, middle * .05) }
                if (samples.size >= intervals.size * .9) {
                    val duration = samples.sum()
                    val measured = VideoRate.normalize((1_000_000.0 * samples.size / duration).toFloat())
                    val nextFamily = FrameRateFilter.bucket(measured)
                    if (family != nextFamily) { windows.clear(); family = nextFamily; rate = measured }
                    windows.addLast(duration to samples.size); while (windows.size > 16) windows.removeFirst()
                    val total = windows.sumOf { it.second }
                    // Short rounded timestamp windows cannot reliably separate
                    // 59.94 from 60. Settle the fractional rate over more samples.
                    if (total >= 300) rate = VideoRate.normalize((1_000_000.0 * total / windows.sumOf { it.first }).toFloat())
                } else { rate = 0f; windows.clear(); family = -1 }
            } else { rate = 0f; windows.clear(); family = -1 }
            count = 0
        }
        return rate
    }
}

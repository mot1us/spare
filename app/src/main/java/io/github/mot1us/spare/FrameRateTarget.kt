package io.github.mot1us.spare

import kotlin.math.abs

/** Saved playback timing is an early display hint; fresh, stable source timing can replace it. */
class FrameRateTarget {
    private var rate = 0f
    private var cached = false
    private var candidate = 0f
    private var since = 0L
    @Synchronized fun reset(value: Float = 0f) {
        rate = VideoRate.normalize(value); cached = rate > 0; candidate = 0f; since = 0
    }
    @Synchronized fun observe(value: Float, now: Long, reliable: Boolean): Float? {
        val next = VideoRate.normalize(value)
        if (next <= 0 || cached && !reliable) return null
        if (abs(next - rate) < .1f) { candidate = 0f; if (reliable) cached = false; return null }
        if (candidate <= 0 || abs(next - candidate) >= .1f || now < since) { candidate = next; since = now; return null }
        if (now - since < 1500) return null
        rate = next; cached = false; candidate = 0f
        return rate
    }
}

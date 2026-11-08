package io.github.mot1us.spare

import kotlin.math.abs

/** Saved playback timing is an early display hint; fresh, stable source timing can replace it. */
class FrameRateTarget {
    private var rate = 0f
    private var cached = false
    private var candidate = 0f
    private var since = 0L
    private var lastSample = -1L
    @Synchronized fun reset(value: Float = 0f) {
        rate = VideoRate.normalize(value); cached = rate > 0; candidate = 0f; since = 0; lastSample = -1
    }
    @Synchronized fun observe(value: Float, now: Long, reliable: Boolean): Float? {
        val next = VideoRate.normalize(value)
        if (lastSample >= 0 && (now < lastSample || now - lastSample > 2000)) candidate = 0f
        lastSample = now
        if (next <= 0 || cached && !reliable) { candidate = 0f; return null }
        val tolerance = .005f
        if (abs(next - rate) < tolerance) { candidate = 0f; if (reliable) cached = false; return null }
        // Live TV and movies need the fractional rate, but short/rounded windows
        // must not repeatedly switch the TV between 23.976 and 24 (or 29.97/30).
        val fractional = rate > 0 && abs(next - rate) < .1f
        if (fractional && !reliable) { candidate = 0f; return null }
        if (candidate <= 0 || abs(next - candidate) >= tolerance || now < since) { candidate = next; since = now; return null }
        if (now - since < if (fractional) 8000 else 1500) return null
        rate = next; cached = false; candidate = 0f
        return rate
    }
}

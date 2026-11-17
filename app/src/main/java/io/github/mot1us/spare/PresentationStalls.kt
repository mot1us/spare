package io.github.mot1us.spare

/**
 * Stalls while seconds of video are already buffered come from the box's picture pipeline, not the network: the data is
 * there but the TV is not taking frames. A second one within ten minutes means a display option (tunneled playback, then
 * frame-rate matching) does not suit this box, so it is switched off. Stalls with an empty buffer are the provider's.
 */
class PresentationStalls(private val bufferedMs: Long = 2000, private val windowMs: Long = 600_000, private val count: Int = 2) {
    private val seen = ArrayDeque<Long>()
    /** True when this stall is the one that should switch an option off; the count then starts again. */
    fun stalled(bufferedMs: Long, now: Long): Boolean {
        if (bufferedMs < this.bufferedMs) return false
        while (seen.isNotEmpty() && now - seen.first() !in 0 until windowMs) seen.removeFirst()
        seen.addLast(now)
        if (seen.size < count) return false
        seen.clear(); return true
    }
    fun reset() = seen.clear()
}

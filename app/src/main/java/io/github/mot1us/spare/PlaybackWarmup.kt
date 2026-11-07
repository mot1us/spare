package io.github.mot1us.spare

/** Optional metadata work waits for uninterrupted playback and a useful read-ahead. */
class PlaybackWarmup {
    private var token = -1
    private var readySince = -1L
    fun ready(epoch: Int, now: Long, playing: Boolean, bufferedMs: Long, settleMs: Long = 8000, minimumBufferMs: Long = 5000, shortWindowMs: Long = 20000): Boolean {
        if (token != epoch || !playing || now < readySince) { token = epoch; readySince = -1 }
        if (!playing) return false
        if (readySince < 0) readySince = now
        val elapsed = now - readySince
        // Short live windows must still be allowed to refresh after settling.
        return elapsed >= settleMs && (bufferedMs >= minimumBufferMs || elapsed >= shortWindowMs)
    }
    fun reset() { token = -1; readySince = -1 }
}

package io.github.mot1us.spare

import java.net.URI

/** A missing HLS segment is not proof that the channel endpoint is missing. */
class StartupFailures {
    private val missing = ArrayList<Boolean>()
    fun record(status: Int?, failedUrl: String?, channelUrl: String) {
        val sameEndpoint = runCatching { URI(failedUrl.orEmpty().substringBefore('#')).normalize() == URI(channelUrl.substringBefore('#')).normalize() }.getOrDefault(false)
        missing.add(status in setOf(404, 410) && sameEndpoint)
    }
    fun allEndpointsMissing() = missing.isNotEmpty() && missing.all { it }
}

class ReconnectBudget {
    private val attempts = ArrayDeque<Long>()
    fun take(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first() >= 120000) attempts.removeFirst()
        if (attempts.size >= 2) return false
        attempts.addLast(now); return true
    }
}

/** Monotonic, per-attempt progress checks. Brief buffering and deliberate pauses are harmless. */
class PlaybackProgress {
    private var owner: Int? = null
    private var position = 0L
    private var positionAt = 0L
    private var frame: Long? = null
    private var frameAt = 0L
    private var readyAt = 0L
    private var wasBuffering = false
    fun reset() { owner = null }
    fun stalled(token: Int, now: Long, positionMs: Long, frameUs: Long?, buffering: Boolean, active: Boolean): Boolean {
        if (!active) { reset(); return false }
        if (owner != token) {
            owner = token; position = positionMs; positionAt = now; frame = frameUs; frameAt = now; readyAt = now; wasBuffering = buffering
            return false
        }
        if (kotlin.math.abs(positionMs - position) >= 250) { position = positionMs; positionAt = now }
        if (frameUs == null || frameUs != frame) { frame = frameUs; frameAt = now }
        if (buffering || wasBuffering) readyAt = now
        wasBuffering = buffering
        // Keep an absolute no-position deadline even if READY and BUFFERING flap.
        if (now - positionAt >= 45000) return true
        // A rebuffer can leave the last video timestamp old while position resumes.
        // Give READY its full observation window instead of counting buffer time
        // toward the frozen-video deadline and reopening a recovering connection.
        return !buffering && now - readyAt >= 20000 && (now - positionAt >= 20000 || frameUs != null && now - frameAt >= 20000)
    }
}

enum class PlaybackPhase(val label: String) {
    OPENING("Opening channel…"), ALTERNATE("Trying alternate stream format…"),
    BUFFERING("Buffering…"), RECONNECTING("Reconnecting…")
}

class UnavailableChannels(private val until: MutableMap<String, Long> = linkedMapOf()) {
    fun contains(id: String, now: Long) = (until[id] ?: 0) > now
    fun hide(id: String, now: Long) { until.remove(id); until[id] = now + 15 * 60000L; while (until.size > 5000) until.remove(until.keys.first()) }
    fun restore(id: String) = until.remove(id) != null
    fun prune(now: Long): Boolean = until.entries.removeAll { it.value <= now }
    fun clear() = until.clear()
    fun snapshot() = until.toMap()
}

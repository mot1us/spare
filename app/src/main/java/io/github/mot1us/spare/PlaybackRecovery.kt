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

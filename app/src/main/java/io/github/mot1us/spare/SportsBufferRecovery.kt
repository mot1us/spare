package io.github.mot1us.spare

/** One playing connection, sustained buffering only, and no cycling through failed feeds. */
class SportsBufferRecovery {
    private var game: String? = null
    private var channel: String? = null
    private var since: Long? = null
    private var lastSwitch: Long? = null
    private var window = 0L
    private var switches = 0
    private val tried = linkedSetOf<String>()
    fun pause() { since = null }
    fun due(key: String?, id: String?, now: Long, buffering: Boolean, active: Boolean, thresholdMs: Long): Boolean {
        if (key != game || now < window || now - window >= 600000) {
            game = key; window = now; switches = 0; tried.clear(); lastSwitch = null; since = null
        }
        if (channel != id) { channel = id; since = null }
        if (!active || key == null || id == null || !buffering) { pause(); return false }
        tried.add(id)
        if (since == null || now < since!!) since = now
        return switches < 3 && now - since!! >= thresholdMs.coerceIn(10000, 30000) && lastSwitch?.let { now - it >= 30000 } != false
    }
    fun allows(id: String) = id !in tried
    fun remember(ids: Set<String>) { tried.addAll(ids) }
    fun switched(from: String, next: String, now: Long) { tried.add(from); tried.add(next); switches++; lastSwitch = now; channel = next; pause() }
}

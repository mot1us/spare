package io.github.mot1us.spare

/** Last duration of a few named stages, kept in memory only and shown in Settings to tell app time from provider time. */
object Timings {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun now() = android.os.SystemClock.elapsedRealtime()
    fun since(name: String, began: Long) { values[name] = (now() - began).coerceAtLeast(0) }
    fun summary(names: List<String>) = names.mapNotNull { name -> values[name]?.let { "$name ${"%.1f".format(java.util.Locale.ROOT, it / 1000.0)} s" } }.joinToString(" · ")
}

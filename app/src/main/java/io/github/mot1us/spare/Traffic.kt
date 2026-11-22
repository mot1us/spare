package io.github.mot1us.spare

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What Spare received from the network, second by second, for the performance report: the stream that is playing,
 * and every other download grouped by the worker that made it (guide, artwork, scoreboards, catalogs). A stall can
 * then say whether the stream stopped arriving on its own or while Spare was downloading something else.
 * Counts only; no addresses.
 */
internal object Traffic {
    const val WINDOW_SECONDS = 10
    /** A monotonic clock that also works in plain JVM tests. */
    fun now(): Long = System.nanoTime() / 1_000_000

    internal class Meter {
        private val bytes = LongArray(WINDOW_SECONDS + 2)
        private val second = LongArray(WINDOW_SECONDS + 2) { -1 }
        @Synchronized fun add(nowMs: Long, amount: Long) {
            val s = nowMs / 1000; val i = (s % bytes.size).toInt()
            if (second[i] != s) { second[i] = s; bytes[i] = 0 }
            bytes[i] += amount
        }
        /** Bytes in the current second and the [WINDOW_SECONDS] - 1 before it. */
        @Synchronized fun recent(nowMs: Long): Long {
            val s = nowMs / 1000; var sum = 0L
            for (k in 0 until WINDOW_SECONDS) { val t = s - k; if (t < 0) break; val i = (t % bytes.size).toInt(); if (second[i] == t) sum += bytes[i] }
            return sum
        }
    }

    private val stream = Meter()
    @Volatile private var streamFrom = -1L
    @Volatile private var streamTotal = 0L
    @Volatile private var streamLast = -1L
    private val others = ConcurrentHashMap<String, Meter>()

    /** A movie's or episode's size and length, so the report can say how fast it has to arrive (zero when unknown). */
    @Volatile var titleBytes = 0L
    @Volatile var titleDurationMs = 0L
    /** A new playback attempt, live or on demand: its average starts again. */
    fun streamStarted(nowMs: Long) { streamFrom = nowMs; streamTotal = 0; streamLast = -1; titleBytes = 0; titleDurationMs = 0 }
    /** The largest file a movie's source opened in this attempt: the movie itself, not its subtitle files. */
    fun titleSize(bytes: Long) { if (bytes > titleBytes) titleBytes = bytes }
    fun streamStopped() { streamFrom = -1; streamTotal = 0; streamLast = -1; titleBytes = 0; titleDurationMs = 0 }
    fun stream(nowMs: Long, amount: Long) {
        if (amount <= 0) return
        stream.add(nowMs, amount)
        synchronized(this) { streamTotal += amount }
        streamLast = nowMs
    }
    fun other(nowMs: Long, amount: Int, worker: String = Thread.currentThread().name) {
        if (amount > 0) others.getOrPut(purpose(worker), ::Meter).add(nowMs, amount.toLong())
    }

    /** "spare-idle-browse-2" → "idle-browse"; threads without a Spare name are counted together. */
    fun purpose(worker: String): String =
        if (!worker.startsWith("spare-")) "unnamed" else worker.removePrefix("spare-").replace(Regex("-\\d+$"), "").take(24).ifEmpty { "unnamed" }

    private fun rate(bytes: Long, ms: Long) = if (ms <= 0) 0.0 else bytes * 8.0 / 1000.0 / ms
    private fun mbits(value: Double) = String.format(Locale.ROOT, "%.1f Mbit/s", value)
    private fun size(bytes: Long) = if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0) else "${(bytes + 999) / 1000} KB"

    /**
     * One line for a hiccup or the report: the stream's arrival rate over the last [WINDOW_SECONDS] s against its average
     * since the attempt began, how long ago its last data came, and the other downloads in the same seconds.
     * [open] names the other requests still waiting on the network.
     */
    fun summary(nowMs: Long, open: List<String> = emptyList()): String {
        val parts = ArrayList<String>()
        val from = streamFrom
        if (from >= 0) {
            val window = minOf(WINDOW_SECONDS * 1000L, maxOf(1000L, nowMs - from))
            val average = rate(streamTotal, nowMs - from)
            val last = streamLast
            parts += "stream ${mbits(rate(stream.recent(nowMs), window))} last ${window / 1000} s (avg ${mbits(average)})" +
                if (last < 0) " · no data yet" else String.format(Locale.ROOT, " · last data %.1f s ago", (nowMs - last) / 1000.0)
            // A movie stalls over and over when it arrives more slowly than it plays: say how fast it has to arrive.
            val bytes = titleBytes; val length = titleDurationMs
            if (bytes > 0 && length > 0) parts += "title needs ${mbits(rate(bytes, length))}"
        }
        val busy = others.entries.map { it.key to it.value.recent(nowMs) }.filter { it.second > 0 }.sortedByDescending { it.second }
        parts += if (busy.isEmpty()) "other downloads: none" else
            "other downloads: " + busy.take(4).joinToString(", ") { "${it.first} ${size(it.second)}" } + if (busy.size > 4) " +${busy.size - 4} more" else ""
        if (open.isNotEmpty()) parts += "waiting: " + open.groupingBy(::purpose).eachCount().entries.sortedByDescending { it.value }
            .take(4).joinToString(", ") { if (it.value > 1) "${it.key}×${it.value}" else it.key }
        return parts.joinToString(" · ")
    }

    internal fun reset() { streamFrom = -1; streamTotal = 0; streamLast = -1; others.clear() }
}

/** Names a worker's threads "spare-<name>" so diagnostics can say what they were doing. */
internal fun namedThreads(name: String) = java.util.concurrent.ThreadFactory { task -> Thread(task, "spare-$name") }

package io.github.mot1us.spare

/** A bounded local report with structured numbers only; titles, URLs and account data never enter it. */
class PlaybackJournal {
    enum class Event { OPEN, FIRST_FRAME, BUFFERING, RECONNECT, FAILED, DROPPED_FRAMES, SAMPLE }
    private val lines = ArrayDeque<String>()
    private var started = -1L
    @Synchronized fun record(event: Event, now: Long, bufferMs: Long, heapBytes: Long, maxHeapBytes: Long, streams: Int, dropped: Int = 0) {
        if (started < 0) started = now
        val seconds = (now - started).coerceAtLeast(0) / 1000
        lines.addLast("+${seconds}s · ${event.name.lowercase().replace('_', ' ')} · main buffer ${bufferMs.coerceAtLeast(0) / 1000}s · Java heap ${heapBytes / 1048576}/${maxHeapBytes / 1048576} MiB · $streams stream(s)" + if (dropped > 0) " · $dropped dropped" else "")
        while (lines.size > 64) lines.removeFirst()
    }
    @Synchronized fun report(version: String) = "Spare $version · Local playback history\nNo channel names, addresses or credentials. Memory is Java heap; hardware decoder/GPU memory is separate.\n\n" + if (lines.isEmpty()) "No playback events yet." else lines.joinToString("\n")
    @Synchronized fun clear() { lines.clear(); started = -1 }
}

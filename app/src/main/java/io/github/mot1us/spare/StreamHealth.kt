package io.github.mot1us.spare

/** Session-only observations of streams the user actually played; never probes a connection. */
class StreamHealth {
    private data class Entry(val problems: ArrayDeque<Long> = ArrayDeque(), var since: Long = -1, var smooth: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    private var active: String? = null
    @Synchronized fun problem(id: String, now: Long, failure: Boolean = false) {
        val e = entry(id); e.since = -1; e.smooth = false; e.problems.addLast(now)
        if (failure) e.problems.addLast(now)
        while (e.problems.size > 8) e.problems.removeFirst()
    }
    @Synchronized fun observe(id: String, now: Long, playing: Boolean) {
        if (active != id) { entries[active]?.since = -1; active = id; entry(id).since = -1 }
        val e = entry(id)
        if (!playing) { e.since = -1; return }
        if (e.since < 0 || now < e.since) e.since = now
        if (now - e.since >= 30000) e.smooth = true
        if (now - e.since >= 60000) e.problems.clear()
    }
    @Synchronized fun status(id: String, now: Long): Int {
        val e = entries[id] ?: return 0
        while (e.problems.isNotEmpty() && now - e.problems.first() >= 120000) e.problems.removeFirst()
        return if (e.problems.size >= 2) 1 else if (e.smooth) -1 else 0
    }
    @Synchronized fun order(channels: List<Channel>, quality: Map<String, VideoInfo>, now: Long): List<Channel> {
        val safe = channels.filter { status(it.id, now) <= 0 }
        val groups = safe.groupBy { val q = it.quality(quality[it.id]); (q?.height ?: 0) to FrameRateFilter.bucket(q?.fps ?: 0f) }
        return groups.values.flatMap { group -> group.sortedBy { status(it.id, now) } } + channels.filter { status(it.id, now) > 0 }
    }
    @Synchronized fun clear() { entries.clear(); active = null }
    private fun entry(id: String) = entries.getOrPut(id) { Entry() }.also { while (entries.size > 128) entries.remove(entries.keys.first()) }
}

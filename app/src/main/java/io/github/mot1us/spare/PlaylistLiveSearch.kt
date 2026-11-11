package io.github.mot1us.spare

internal object PlaylistLiveSearch {
    data class Match(val channel: Channel, val programme: Programme?)
    private data class Ranked(val programme: Programme, val rank: Int)

    fun find(guide: Guide, index: GuideProgrammeIndex?, term: String, now: Long, legacyPlaylist: String): List<Match> {
        if (term.isBlank()) return emptyList()
        val airing = HashMap<String, Ranked>()
        val ready = index?.takeIf { it.isFor(guide) }
        val soon = now + 3 * 3_600_000L
        for (row in ready?.matchingTitles(term) ?: emptySequence()) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val p = row.programme
            if (p.end <= now || p.start > soon) continue
            val exact = row.titleKey == term
            val rank = when {
                exact && p.start <= now -> 0
                exact -> 1
                p.start <= now -> 3
                else -> 4
            }
            val previous = airing[row.key]
            if (previous == null || rank < previous.rank || rank == previous.rank && p.start < previous.programme.start) {
                airing[row.key] = Ranked(p, rank)
            }
        }
        val buckets = List(5) { LinkedHashMap<String, MutableList<Match>>() }
        val catalogue = ready?.searchChannels?.asSequence() ?: run {
            val groups = HashMap<String, String>()
            guide.channels.asSequence().map { GuideProgrammeIndex.SearchChannel(it, groups.getOrPut(it.group) { ChannelSearch.key(it.group) }) }
        }
        for ((channel, group) in catalogue) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val programme = if (channel.media) null else airing[guide.key(channel)]
            val named = channel.searchKey.contains(term) || group.contains(term)
            val rank = when {
                programme != null && programme.rank <= 1 -> programme.rank
                named -> 2
                programme != null -> programme.rank
                else -> continue
            }
            val playlist = channel.playlistId.ifBlank { legacyPlaylist }
            val results = buckets[rank].getOrPut(playlist, ::ArrayList)
            if (results.size < 40) results.add(Match(channel, programme?.programme ?: if (channel.media) null else guide.at(channel, now)))
        }
        val counts = HashMap<String, Int>()
        return buildList {
            for (bucket in buckets) for ((playlist, results) in bucket) {
                val remaining = 40 - counts.getOrDefault(playlist, 0)
                val count = minOf(remaining, results.size, 120 - size)
                for (i in 0 until count) add(results[i])
                counts[playlist] = 40 - remaining + count
                if (size >= 120) return@buildList
            }
        }
    }
}

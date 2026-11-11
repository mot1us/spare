package io.github.mot1us.spare

import java.util.BitSet

/** The Sports matcher and Search share a snapshot of the same guide version. */
class GuideProgrammeIndex private constructor(
    private val guide: Guide,
    val preparedAt: Long,
    private val shape: Channels,
    val programmesByKey: Map<String, List<Listing>>,
    private val sportsByTeam: Map<Pair<String, String>, List<SportsListing>>,
) {
    data class Listing(val key: String, val programme: Programme, val titleKey: String, internal val teams: Map<String, Set<String>>)
    private data class SportsListing(val row: Listing, val teams: Set<String>)
    private data class Channels(
        val video: List<Channel>,
        val videoByKey: Map<String, List<Channel>>,
        val liveByKey: Map<String, List<Channel>>,
        val namePrefixes: Map<Int, IntArray>,
    )

    fun isFor(snapshot: Guide) = guide.channels === snapshot.channels && guide.programmes === snapshot.programmes
    internal fun covers(game: SportsGame, now: Long): Boolean {
        val first = if (game.state == "pre") game.start - 90 * 60_000L else now
        val last = if (game.state == "pre") game.start + 45 * 60_000L else now
        return SportsGuide.indexesLeague(game.teams.firstOrNull()?.league) &&
            first >= preparedAt - PAST && last < preparedAt + FUTURE
    }

    internal fun sportsListings(league: String, teams: Set<String>): Sequence<Listing> {
        val smallest = teams.map { sportsByTeam[league to it].orEmpty() }.minByOrNull { it.size }.orEmpty()
        return smallest.asSequence().filter { it.teams == teams }.map { it.row }
    }

    internal fun video(key: String) = shape.videoByKey[key].orEmpty()
    internal fun live(key: String) = shape.liveByKey[key].orEmpty()
    internal val sportsKeys get() = shape.videoByKey.keys

    internal fun sportsChannels(teamHints: List<List<String>>, networkHints: List<String>): List<Channel> {
        fun positions(hints: List<String>): BitSet {
            val bits = BitSet(shape.video.size)
            for (hint in hints) {
                if (hint.length < 2) { bits.set(0, shape.video.size); break }
                shape.namePrefixes[prefix(hint[0], hint[1])]?.forEach { bits.set(it) }
            }
            return bits
        }
        val named = teamHints.firstOrNull()?.let(::positions) ?: BitSet(shape.video.size).apply { set(0, shape.video.size) }
        teamHints.drop(1).forEach { named.and(positions(it)) }
        named.or(positions(networkHints))
        return buildList {
            var index = named.nextSetBit(0)
            while (index >= 0) {
                add(shape.video[index])
                index = named.nextSetBit(index + 1)
            }
        }
    }

    /** Owned by the index worker. EPG refreshes reuse parsed titles until the channel catalog changes. */
    class Builder(private val cacheLimit: Int = 12_000) {
        private data class Text(val title: String, val description: String)
        private data class Parsed(val titleKey: String, val teams: Map<String, Set<String>>)
        private val parsed = LinkedHashMap<Text, Parsed>(128, .75f, true)
        private var previous: GuideProgrammeIndex? = null

        fun prepare(guide: Guide, requestedAt: Long): GuideProgrammeIndex {
            val prior = previous
            val sameChannels = prior?.guide?.channels === guide.channels
            if (!sameChannels) parsed.clear()
            val now = prior?.preparedAt?.takeIf { sameChannels && requestedAt - it in 0 until REFRESH_INTERVAL } ?: requestedAt
            val shape = if (sameChannels && prior != null) prior.shape else channels(guide)
            val byKey = LinkedHashMap<String, List<Listing>>()
            val byTeam = HashMap<Pair<String, String>, MutableList<SportsListing>>()
            // Use the interleaved channel order and original programme order for stable ties.
            val keys = shape.videoByKey.keys + shape.liveByKey.keys
            for (key in keys) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val entries = guide.programmes[key] ?: continue
                val priorRows = if (sameChannels && prior != null && prior.guide.programmes[key] === entries && prior.preparedAt == now) prior.programmesByKey[key] else null
                val rows = priorRows ?: entries.mapNotNull { programme ->
                    if (programme.end <= now - PAST || programme.start >= now + FUTURE) return@mapNotNull null
                    val text = Text(programme.title, programme.description)
                    val info = parsed.getOrPut(text) { Parsed(ChannelSearch.key(programme.title), SportsGuide.indexedTeams(programme)) }
                    if (parsed.size > cacheLimit) parsed.entries.iterator().apply { next(); remove() }
                    Listing(key, programme, info.titleKey, info.teams)
                }
                if (rows.isEmpty()) continue
                byKey[key] = rows
                if (key !in shape.videoByKey) continue
                rows.forEach { row ->
                    row.teams.forEach { (league, ids) ->
                        val match = SportsListing(row, ids)
                        ids.forEach { byTeam.getOrPut(league to it, ::ArrayList).add(match) }
                    }
                }
            }
            return GuideProgrammeIndex(guide, now, shape, byKey, byTeam).also { previous = it }
        }

        private fun channels(guide: Guide): Channels {
            val video = PlaylistCatalog.interleave(guide.channels.filter(SportsStreams::video))
            val prefixes = HashMap<Int, Positions>()
            video.forEachIndexed { index, channel ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val name = channel.name
                for (i in 0 until name.lastIndex) prefixes.getOrPut(prefix(name[i], name[i + 1]), ::Positions).add(index)
            }
            return Channels(video, video.groupBy(guide::key), guide.channels.filterNot { it.media }.groupBy(guide::key), prefixes.mapValues { it.value.toArray() })
        }
    }

    private class Positions {
        private var values = IntArray(4)
        private var size = 0
        fun add(position: Int) {
            if (size > 0 && values[size - 1] == position) return
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = position
        }
        fun toArray() = values.copyOf(size)
    }

    companion object {
        internal const val PAST = 4 * 3_600_000L
        internal const val FUTURE = 48 * 3_600_000L
        internal const val REFRESH_INTERVAL = 15 * 60_000L
        private fun fold(value: Char) = value.uppercaseChar().lowercaseChar().code
        private fun prefix(first: Char, second: Char) = (fold(first) shl 16) or fold(second)
    }
}

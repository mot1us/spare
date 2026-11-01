package io.github.mot1us.spare

/** Playlist destinations are separate from scoreboard events: a channel does not prove a live game. */
object SportsDestinations {
    private val redZone = Regex("(?<![\\p{L}\\p{N}])red[\\W_]*zone(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val abbreviated = Regex("(?<![\\p{L}\\p{N}])(rz|rzn|nflrz)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val nfl = Regex("(?<![\\p{L}\\p{N}])(nfl(?:rz)?|game[\\W_]*pass)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val sky = Regex("sky[\\W_]*sports", RegexOption.IGNORE_CASE)
    private val gamePass = Regex("game[\\W_]*pass", RegexOption.IGNORE_CASE)
    private fun context(channel: Channel) = "${channel.name} ${channel.group}"
    private fun possible(value: String) = redZone.containsMatchIn(value) || abbreviated.containsMatchIn(value)
    private fun nameMatches(value: String, context: String): Boolean = possible(value) &&
        !SportsGuide.excludedTitle(SportsCatalog.normalize(value)) && (redZone.containsMatchIn(value) || nfl.containsMatchIn(context))
    private fun named(channel: Channel) = !channel.media && nameMatches(channel.name, context(channel))
    private fun discovered(guide: Guide, now: Long, available: (Channel) -> Boolean): List<Channel> = guide.channels.asSequence()
        .takeWhile { !Thread.currentThread().isInterrupted }.filter { channel ->
            !channel.media && available(channel) && (named(channel) || guide.at(channel, now)?.let { programme ->
                possible(programme.title) && nfl.containsMatchIn("${context(channel)} ${programme.title}") && nameMatches(programme.title, context(channel))
            } == true)
        }.distinctBy { it.id }.toList()
    fun redZone(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>, now: Long, available: (Channel) -> Boolean = { true }): List<Channel> =
        discovered(guide, now, available).sortedWith(SportsStreams.preferenceComparator(filters, quality))
    fun redZone(channels: List<Channel>, filters: GuideFilters, quality: Map<String, VideoInfo>, available: (Channel) -> Boolean = { true }): List<Channel> =
        redZone(Guide(channels, emptyMap()), filters, quality, System.currentTimeMillis(), available)
    /** Discovery is independent of quality ranking. A new FPS reading cannot rescan the lineup. */
    class RedZoneCache {
        private class Entry(val guide: Guide, val until: Long, val channels: List<Channel>)
        private data class Ranked(val entry: Entry, val filters: GuideFilters, val quality: Map<String, VideoInfo>, val channels: List<Channel>)
        private data class Named(val lineup: List<Channel>, val channels: List<Channel>)
        @Volatile private var entry: Entry? = null
        @Volatile private var ranked: Ranked? = null
        @Volatile private var names: Named? = null
        /** Channel names alone provide useful choices before inspecting any programme guide. */
        fun named(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>): List<Channel> {
            val saved = names
            val found = if (saved?.lineup === guide.channels) saved.channels else guide.channels.asSequence()
                .takeWhile { !Thread.currentThread().isInterrupted }.filter { SportsDestinations.named(it) }.toList().also {
                    if (!Thread.currentThread().isInterrupted) names = Named(guide.channels, it)
                }
            return found.sortedWith(SportsStreams.preferenceComparator(filters, quality))
        }
        fun peek(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>, now: Long): List<Channel>? {
            val saved = entry ?: return null
            if (saved.guide.channels !== guide.channels || saved.guide.programmes !== guide.programmes || now >= saved.until) return null
            return saved.channels.sortedWith(SportsStreams.preferenceComparator(filters, quality))
        }
        fun find(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>, now: Long): List<Channel> {
            val saved = entry
            val current = if (saved != null && saved.guide.channels === guide.channels && saved.guide.programmes === guide.programmes && now < saved.until) saved else {
                val channels = discovered(guide, now) { true }
                if (Thread.currentThread().isInterrupted) return emptyList()
                val boundary = channels.mapNotNull { guide.at(it, now)?.end }.minOrNull() ?: Long.MAX_VALUE
                Entry(guide, minOf(now + 60000, boundary), channels).also { entry = it }
            }
            val ordered = ranked
            if (ordered?.entry === current && ordered.filters == filters && ordered.quality == quality) return ordered.channels
            return current.channels.sortedWith(SportsStreams.preferenceComparator(filters, quality)).also { ranked = Ranked(current, filters, quality, it) }
        }
    }
    fun guideTargets(guide: Guide, quality: Map<String, VideoInfo>, now: Long, available: (Channel) -> Boolean = { true }): List<Channel> {
        val candidates = guide.channels.filter { channel ->
            val label = context(channel)
            available(channel) && SportsStreams.speculative(channel) && (nfl.containsMatchIn(label) || sky.containsMatchIn(label)) && guide.at(channel, now) == null && !SportsGuide.excludedTitle(SportsCatalog.normalize(channel.name))
        }
        val passes = candidates.filter { gamePass.containsMatchIn(context(it)) }.map { it.id }.toSet()
        val order = compareByDescending<Channel> { it.id in passes }.then(SportsStreams.preferenceComparator(GuideFilters(), quality))
        val groups = listOf(candidates.filter { nfl.containsMatchIn(context(it)) }, candidates.filter { !nfl.containsMatchIn(context(it)) && sky.containsMatchIn(context(it)) })
            .map { SportsStreams.lookupOrder(it, order) }
        val picked = linkedMapOf<String, Channel>()
        for (index in 0 until 12) for (group in groups) group.getOrNull(index)?.let { if (picked.size < 12) picked[it.id] = it }
        return SportsStreams.lookupOrder(picked.values.toList(), SportsStreams.preferenceComparator(GuideFilters(), quality))
    }
}

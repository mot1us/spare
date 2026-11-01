package io.github.mot1us.spare

/** Playlist destinations are separate from scoreboard events: a channel does not prove a live game. */
object SportsDestinations {
    private val redZone = Regex("\\bred\\s*zone\\b")
    private val abbreviated = Regex("\\b(rz|rzn|nflrz)\\b")
    private val nfl = Regex("\\b(nfl(?:rz)?|game\\s*pass)\\b")
    private fun context(channel: Channel) = "${channel.sportsKey} ${SportsCatalog.normalize(channel.group)} "
    private fun nameMatches(value: String, context: String): Boolean {
        val name = SportsCatalog.normalize(value)
        return !SportsGuide.excludedTitle(name) && (redZone.containsMatchIn(name) || nfl.containsMatchIn(context) && abbreviated.containsMatchIn(name))
    }
    fun redZone(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>, now: Long, available: (Channel) -> Boolean = { true }): List<Channel> =
        guide.channels.asSequence().filter { channel ->
            !channel.media && available(channel) && (nameMatches(channel.name, context(channel)) ||
                guide.at(channel, now)?.let { programme ->
                    val context = "${context(channel)} ${SportsCatalog.normalize(programme.title)}"
                    nfl.containsMatchIn(context) && nameMatches(programme.title, context)
                } == true)
        }.distinctBy { it.id }.sortedWith(SportsStreams.preferenceComparator(filters, quality)).toList()
    fun redZone(channels: List<Channel>, filters: GuideFilters, quality: Map<String, VideoInfo>, available: (Channel) -> Boolean = { true }): List<Channel> =
        redZone(Guide(channels, emptyMap()), filters, quality, System.currentTimeMillis(), available)
    /** Background-only metadata cache. No stream probes and no monitor shared with UI input. */
    class RedZoneCache {
        private data class Entry(val guide: Guide, val filters: GuideFilters, val quality: Map<String, VideoInfo>, val until: Long, val channels: List<Channel>)
        @Volatile private var entry: Entry? = null
        fun find(guide: Guide, filters: GuideFilters, quality: Map<String, VideoInfo>, now: Long): List<Channel> {
            val saved = entry
            if (saved != null && saved.guide.channels === guide.channels && saved.guide.programmes === guide.programmes && saved.filters == filters && saved.quality == quality && now < saved.until) return saved.channels
            val channels = redZone(guide, filters, quality, now)
            val boundary = channels.mapNotNull { guide.at(it, now)?.end }.minOrNull() ?: Long.MAX_VALUE
            entry = Entry(guide, filters, quality, minOf(now + 60000, boundary), channels)
            return channels
        }
    }
    fun guideTargets(guide: Guide, quality: Map<String, VideoInfo>, now: Long): List<Channel> = guide.channels.asSequence()
        .filter { !it.media && nfl.containsMatchIn(context(it)) && guide.at(it, now) == null && !SportsGuide.excludedTitle(it.sportsKey) }
        .sortedWith(compareByDescending<Channel> { "game pass" in context(it) || "gamepass" in context(it) }.then(SportsStreams.preferenceComparator(GuideFilters(), quality))).take(12).toList()
}

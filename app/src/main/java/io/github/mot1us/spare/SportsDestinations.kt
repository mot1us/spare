package io.github.mot1us.spare

/** Playlist destinations are separate from scoreboard events: a channel does not prove a live game. */
object SportsDestinations {
    private val redZone = Regex("\\bred\\s*zone\\b")
    fun redZone(channels: List<Channel>, filters: GuideFilters, quality: Map<String, VideoInfo>, available: (Channel) -> Boolean = { true }): List<Channel> =
        channels.asSequence().filter { redZone.containsMatchIn(SportsCatalog.normalize(it.name)) && available(it) && filters.matches(it, quality[it.id]) }
            .distinctBy { it.id }.sortedWith(compareByDescending<Channel> { it.quality(quality[it.id])?.height ?: 0 }.thenByDescending { it.quality(quality[it.id])?.fps ?: 0f }).toList()
}

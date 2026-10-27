package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** App-local reminders. Only a fresh scoreboard can announce that a game has started. */
class GameReminders(events: List<LiveEvent> = emptyList(), delivered: Set<String> = emptySet()) {
    private val saved = events.take(64).associateBy(::key).toMutableMap()
    private val shown = delivered.toMutableSet()
    val events get() = saved.values.sortedBy { it.start }
    fun has(game: SportsGame) = key(game) in saved
    fun toggle(game: SportsGame): Boolean {
        if (saved.remove(key(game)) != null) { shown.remove(key(game)); return false }
        require(game.state == "pre" && game.eventId.isNotBlank() && saved.size < 64)
        saved[key(game)] = LiveEvent(game.eventId, game.teams.map { it.id }.toSet(), game.start, "pre", game.networks, league = game.leagueId)
        return true
    }
    fun remove(key: String) { saved.remove(key); shown.remove(key) }
    fun delivered(game: SportsGame) { if (has(game)) shown.add(key(game)) }
    fun targets(now: Long): Set<Pair<String, java.time.LocalDate>> = events.filter { key(it) !in shown && it.start in (now - 18 * 3600000L)..(now + 5 * 60000L) }
        .flatMap { event -> listOf(event.start, now).map { event.league to Instant.ofEpochMilli(it).atZone(ZoneId.of("America/New_York")).toLocalDate() } }.toSet()
    fun update(fresh: List<LiveEvent>, now: Long): List<SportsGame> {
        saved.values.filter { it.start < now - 48 * 3600000L }.map(::key).forEach(::remove)
        val ready = mutableListOf<LiveEvent>()
        fresh.forEach { event ->
            val key = key(event)
            if (key !in saved) return@forEach
            if (event.state == "post") remove(key) else {
                saved[key] = event
                if (event.state == "in" && key !in shown && event.start in (now - 18 * 3600000L)..(now + 60000L)) ready.add(event)
            }
        }
        return SportsSchedule.games(LiveSportsSnapshot(ready, now, ready.map { it.league }.toSet()), Guide.EMPTY, null, emptySet(), now, matchChannels = false)
    }
    fun json(): JSONObject = SportsSchedule.json(LiveSportsSnapshot(events, 0, emptySet())).put("delivered", JSONArray(shown.toList()))
    companion object {
        private fun key(game: SportsGame) = "${game.leagueId}:${game.eventId}"
        private fun key(event: LiveEvent) = "${event.league}:${event.id}"
        fun from(json: JSONObject): GameReminders {
            val delivered = json.optJSONArray("delivered") ?: JSONArray()
            return GameReminders(SportsSchedule.from(json).events, (0 until minOf(delivered.length(), 64)).map { delivered.optString(it) }.toSet())
        }
    }
}

object SportsStreams {
    private val preferredQuality = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
    fun qualifies(channel: Channel, observed: VideoInfo?) = preferredQuality.matches(channel, observed)
    fun rank(game: SportsGame, filters: GuideFilters, quality: Map<String, VideoInfo>, available: (Channel) -> Boolean = { true }): List<Channel> {
        val epg = game.broadcasts.map { it.channel.id }.toSet()
        val confirmed = epg + game.titleMatches.map { it.id }
        val smooth = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        return (game.broadcasts.map { it.channel } + game.titleMatches + game.candidates).distinctBy { it.id }
            .filter { available(it) && filters.matches(it, quality[it.id]) }
            .sortedWith(compareByDescending<Channel> { it.id in confirmed }
                .thenByDescending { smooth.matches(it, quality[it.id]) }
                .thenByDescending { it.quality(quality[it.id])?.height ?: 0 }
                .thenByDescending { it.quality(quality[it.id])?.fps ?: 0f }
                .thenByDescending { it.id in epg })
    }
    fun bestConfirmed(game: SportsGame, ranked: List<Channel>, quality: Map<String, VideoInfo>, now: Long): Channel? = ranked.firstOrNull { channel ->
        game.live(now) && (game.broadcasts.any { it.channel.id == channel.id && now in it.programme.start until it.programme.end } || game.titleMatches.any { it.id == channel.id }) &&
            qualifies(channel, quality[channel.id])
    }
    fun unavailableReason(game: SportsGame, ranked: List<Channel>, quality: Map<String, VideoInfo>, now: Long): String = when {
        !game.live(now) -> "This game is not live yet. Choose a stream to preview."
        ranked.isEmpty() -> "No streams match your current filters."
        ranked.any { qualifies(it, quality[it.id]) } -> "FHD+/60 streams found · game coverage unconfirmed. Choose a stream."
        ranked.any { channel -> channel.quality(quality[channel.id])?.let { it.height >= 1080 && (!it.fps.isFinite() || it.fps <= 0) } == true } -> "FHD/4K found · FPS unknown. Preview a stream to measure it."
        else -> "No FHD+/60 stream found. Choose a stream or adjust filters."
    }
}

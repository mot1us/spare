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
    private val numberedNetwork = Regex("\\b(espn|tsn|fs)\\s+([1-9])\\b")
    private fun broadcasterKey(value: String) = " " + SportsCatalog.normalize(value.replace("+", " plus ")).replace(numberedNetwork, "$1$2") + " "
    internal fun broadcasterMatches(network: String, channel: Channel): Boolean {
        val key = broadcasterKey(network)
        val name = broadcasterKey(channel.name)
        return key.trim().length >= 3 && key in name && (key.trimEnd() + " plus ") !in name
    }
    fun qualifies(channel: Channel, observed: VideoInfo?) = preferredQuality.matches(channel, observed)
    /** Sports quality settings order choices; category and availability still limit the list. */
    internal fun matchingCategory(channel: Channel, filters: GuideFilters) = filters.groups.isEmpty() || channel.group in filters.groups
    /** FPS only orders streams at the same resolution; an unknown rate stays playable. */
    internal fun preferenceRank(channel: Channel, observed: VideoInfo?, filters: GuideFilters = preferredQuality): Int {
        val fps = channel.quality(observed)?.fps ?: 0f
        if (!fps.isFinite() || fps <= 0f) return 1
        return if (FrameRateFilter.bucket(fps) !in filters.excludedFps) 2 else 0
    }
    internal fun preferenceComparator(filters: GuideFilters, quality: Map<String, VideoInfo>): Comparator<Channel> =
        compareByDescending<Channel> { it.quality(quality[it.id])?.height ?: 0 }
            .thenByDescending { preferenceRank(it, quality[it.id], filters) }
            .thenByDescending { it.quality(quality[it.id])?.fps?.takeIf { fps -> fps.isFinite() && fps > 0f } ?: 0f }
    fun rank(game: SportsGame, filters: GuideFilters, quality: Map<String, VideoInfo>, available: (Channel) -> Boolean = { true }): List<Channel> {
        val epg = game.broadcasts.map { it.channel.id }.toSet()
        val confirmed = epg + game.titleMatches.map { it.id }
        return (game.broadcasts.map { it.channel } + game.titleMatches + game.candidates).distinctBy { it.id }
            .filter { available(it) && matchingCategory(it, filters) }
            .sortedWith(compareByDescending<Channel> { it.id in confirmed }
                .then(preferenceComparator(filters, quality))
                .thenByDescending { it.id in epg })
    }
    fun bestConfirmed(game: SportsGame, ranked: List<Channel>, quality: Map<String, VideoInfo>, now: Long): Channel? = ranked.firstOrNull { channel ->
        game.live(now) && (game.broadcasts.any { it.channel.id == channel.id && now in it.programme.start until it.programme.end } || game.titleMatches.any { it.id == channel.id }) &&
            qualifies(channel, quality[channel.id])
    }
    fun bestBroadcaster(game: SportsGame, ranked: List<Channel>, quality: Map<String, VideoInfo>, guide: Guide, now: Long): Channel? {
        return ranked.firstOrNull { channel -> qualifies(channel, quality[channel.id]) && advertisedCoverage(game, channel, guide, now) }
    }
    private fun advertisedCoverage(game: SportsGame, channel: Channel, guide: Guide, now: Long): Boolean {
        if (!game.live(now)) return false
        val target = game.teams.map { it.id }.toSet()
        return game.networks.any { broadcasterMatches(it, channel) } &&
                guide.at(channel, now)?.let { programme ->
                    !SportsGuide.excludedTitle(SportsCatalog.normalize(programme.title)) &&
                    SportsGuide.teams(programme, "${game.teams.first().league} ${channel.group}")?.map { it.id }?.toSet() == target
                } == true
    }
    internal fun conflictingCoverage(game: SportsGame, channel: Channel, guide: Guide, now: Long): Boolean {
        val programme = guide.at(channel, now) ?: return false
        if (SportsGuide.excludedTitle(SportsCatalog.normalize(programme.title))) return true
        return SportsGuide.teams(programme, "${game.teams.first().league} ${channel.group}")?.map { it.id }?.toSet()
            ?.let { it != game.teams.map { team -> team.id }.toSet() } == true
    }
    /** A small, varied guide-only lookup; generic broadcaster names never prove a game match. */
    fun confirmationTargets(game: SportsGame, guide: Guide, candidates: List<Channel>, favorites: Set<String>, quality: Map<String, VideoInfo>, now: Long): List<Channel> {
        val target = game.teams.map { it.id }.toSet()
        val league = game.teams.first().league
        val words = (listOf(game.leagueId, league) + when (league) {
            "NHL" -> listOf("hockey"); "NFL" -> listOf("football"); "NBA" -> listOf("basketball"); "MLB" -> listOf("baseball"); else -> emptyList()
        }).map { " ${SportsCatalog.normalize(it)} " }.distinct()
        fun eligible(channel: Channel): Boolean {
            if (channel.media || SportsGuide.excludedTitle(channel.sportsKey)) return false
            val programme = guide.at(channel, now)
            if (programme != null && (SportsGuide.excludedTitle(SportsCatalog.normalize(programme.title)) ||
                SportsGuide.teams(programme, league)?.map { it.id }?.toSet()?.let { it != target } == true)) return false
            return SportsGuide.teams(Programme(channel.id, channel.name, game.start, game.end), league)?.map { it.id }?.toSet()?.let { it == target } != false
        }
        val variants = Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h264|h265|backup|raw|[0-9]+fps)\\b")
        fun varied(channels: List<Channel>) = channels.filter(::eligible).distinctBy { variants.replace(SportsCatalog.normalize(it.name), " ").trim().replace(Regex(" +"), " ") }
        val order = preferenceComparator(GuideFilters(), quality).thenByDescending { it.id in favorites }
        // A UHD event feed often has no league/team in its name. Its current EPG is
        // still authoritative; include these feeds before the generic network guesses.
        val highResolution = guide.channels.filter { (it.quality(quality[it.id])?.height ?: 0) >= 2160 && guide.at(it, now) == null }.sortedWith(order)
        val leagueChannels = guide.channels.filter { channel -> words.any { it in channel.sportsKey } || words.any { it in " ${SportsCatalog.normalize(channel.group)} " } }
            .sortedWith(order)
        val groups = listOf(varied(highResolution), varied(leagueChannels), varied(candidates.sortedWith(order)))
        val picked = linkedMapOf<String, Channel>()
        for (index in 0 until 12) for (group in groups) group.getOrNull(index)?.let { if (picked.size < 12 && guide.at(it, now) == null) picked[it.id] = it }
        return picked.values.sortedWith(order)
    }
    fun automaticChoices(game: SportsGame, filters: GuideFilters, quality: Map<String, VideoInfo>, guide: Guide, now: Long,
        available: (Channel) -> Boolean = { true }): List<Channel> {
        if (!game.live(now)) return emptyList()
        val exact = game.broadcasts.filter { now in it.programme.start until it.programme.end }.map { it.channel.id }.toSet() + game.titleMatches.map { it.id }
        return rank(game, filters, quality, available).filter { (it.id in exact || advertisedCoverage(game, it, guide, now)) && !conflictingCoverage(game, it, guide, now) }
    }
    fun cardSummary(game: SportsGame, filters: GuideFilters, quality: Map<String, VideoInfo>, guide: Guide, now: Long,
        available: (Channel) -> Boolean = { true }): String {
        val channel = automaticChoices(game, filters, quality, guide, now, available).firstOrNull()
        if (channel != null) {
            return channel.name + "\n" + channel.qualityLabel(quality[channel.id])
        }
        val matches = rank(game, filters, quality, available)
        if (matches.isEmpty()) return "No playlist match found"
        val unverified = matches.any { it.quality(quality[it.id])?.let { info -> info.height <= 0 || info.fps <= 0f || !info.fps.isFinite() } != false }
        return "${matches.size} ${if (matches.size == 1) "stream" else "streams"} found\n" + if (unverified) "Quality unverified · Select to compare" else "Game coverage unconfirmed · Select to compare"
    }
    fun unavailableReason(game: SportsGame, ranked: List<Channel>, quality: Map<String, VideoInfo>, now: Long): String = when {
        !game.live(now) -> "This game is not live yet. Choose a stream to preview."
        ranked.isEmpty() -> "No matching streams found in this playlist."
        ranked.any { qualifies(it, quality[it.id]) } -> "FHD+/60 streams found · game coverage unconfirmed. Choose a stream."
        ranked.any { channel -> channel.quality(quality[channel.id])?.let { it.height >= 1080 && (!it.fps.isFinite() || it.fps <= 0) } == true } -> "Highest-resolution matches shown first. FPS is learned during playback."
        else -> "Best available matches shown first. Choose a stream to watch."
    }
}

package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Schedules populate Sports independently of playlist EPG and channel quality filters. */
object SportsSchedule {
    private val teamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LiveEvent>>>()
    fun load(followed: Set<String>, partial: (LiveSportsSnapshot) -> Unit = {}, get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }): LiveSportsSnapshot {
        val now = System.currentTimeMillis(); val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val events = linkedMapOf<String, LiveEvent>(); val leagues = mutableSetOf<String>()
        val urls = mutableListOf<Pair<String, String>>()
        // NFL's undated board is the full current week, including final scores.
        urls.add("NFL" to "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?limit=100")
        listOf(date, date.minusDays(1)).forEach { day -> urls.add("MLB" to "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100") }
        followed.sorted().take(62).forEach { id -> SportsCatalog.byId[id]?.let { team ->
            val cached = teamCache[id]
            if (cached != null && now - cached.first in 0 until 6 * 3600000L) cached.second.filter { it.state == "pre" && it.start > now }.forEach { events[it.id] = it }
            else {
                val path = if (team.league == "NFL") "football/nfl" else "baseball/mlb"
                val code = id.substringAfter(':').lowercase(java.util.Locale.ROOT)
                urls.add(team.league to "https://site.api.espn.com/apis/site/v2/sports/$path/teams/$code/schedule")
            }
        } }
        if (events.isNotEmpty()) partial(LiveSportsSnapshot(events.values.toList(), now, emptySet()))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<Triple<String, String, List<LiveEvent>>>(pool)
            urls.forEach { (league, url) -> completion.submit(java.util.concurrent.Callable { Triple(league, url, LiveSports.parse(get(url), league, now, onlyLive = false, includeCompleted = url.contains("/scoreboard")).map { it.copy(scoreboard = url.contains("/scoreboard")) }) }) }
            repeat(urls.size) {
                if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
                runCatching { completion.take().get() }.onSuccess { (league, url, entries) ->
                    if (url.contains("/scoreboard")) leagues.add(league)
                    entries.forEach { event -> if (event.scoreboard || events[event.id]?.scoreboard != true) events[event.id] = event }
                    if (url.contains("/teams/")) {
                        val code = url.substringAfter("/teams/").substringBefore('/').uppercase(java.util.Locale.ROOT)
                        teamCache["${league.lowercase(java.util.Locale.ROOT)}:$code"] = now to entries
                    }
                    partial(LiveSportsSnapshot(events.values.toList(), now, leagues.toSet()))
                }
            }
        } finally { pool.shutdownNow() }
        return LiveSportsSnapshot(events.values.toList(), now, leagues)
    }
    fun games(snapshot: LiveSportsSnapshot, guide: Guide, team: String?, favorites: Set<String>, now: Long, matchChannels: Boolean = true): List<SportsGame> {
        val events = if (team == null || team.startsWith("league:")) snapshot.events.filter { it.scoreboard && (team == null || it.teams.any { id -> SportsCatalog.byId[id]?.league == team.substringAfter(':') }) }
            else snapshot.events.filter { team in it.teams && (it.state == "in" || it.state == "pre" && it.start > now) }
                .sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1)
                .ifEmpty { snapshot.events.filter { team in it.teams && it.state == "post" }.sortedByDescending { it.start }.take(1) }
        return events.sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(256).mapNotNull { event ->
            val teams = event.teams.mapNotNull { SportsCatalog.byId[it] }.sortedBy { it.id }; if (teams.size != 2) return@mapNotNull null
            val stale = event.state == "in" && !snapshot.fresh(now)
            val game = SportsGame(teams, event.start, event.start + 8 * 3600000L, emptyList(), event.id, if (stale) "hold" else event.state, event.networks, scores = event.scores, logos = event.logos, status = if (stale) "Saved score · refresh for live status" else event.status)
            if (matchChannels) match(game, guide, favorites, now) else game
        }
    }
    fun match(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long): SportsGame {
        if (game.state == "post") return game
        val broadcasts = SportsGuide.find(guide, emptySet(), favorites, now, liveOnly = false)
        val matches = broadcasts.filter { it.teams.map { t -> t.id }.toSet() == game.teams.map { it.id }.toSet() && kotlin.math.abs(it.start - game.start) <= 3 * 3600000L }
            .flatMap { it.broadcasts }.distinctBy { it.channel.id }
        val names = game.networks.map { SportsCatalog.normalize(it) }.filter { it.length >= 3 }
        val mascots = game.teams.map { SportsCatalog.normalize(it.name.substringAfterLast(' ')) }.filter { it.length >= 4 }
        val candidates = if (matches.isNotEmpty()) emptyList() else guide.channels.asSequence().filter { channel ->
            val name = " ${SportsCatalog.normalize(channel.name)} "
            (names + mascots).any { " $it " in name }
        }.sortedByDescending { it.id in favorites }.take(24).toList()
        return game.copy(broadcasts = matches, candidates = candidates)
    }
    fun json(snapshot: LiveSportsSnapshot) = JSONObject().put("checked", snapshot.checked).put("leagues", JSONArray(snapshot.leagues.toList()))
        .put("events", JSONArray(snapshot.events.take(500).map { JSONObject().put("id", it.id).put("teams", JSONArray(it.teams.toList())).put("start", it.start).put("state", it.state).put("networks", JSONArray(it.networks)).put("scores", JSONObject(it.scores)).put("logos", JSONObject(it.logos)).put("status", it.status).put("scoreboard", it.scoreboard) }))
    fun from(json: JSONObject): LiveSportsSnapshot {
        val entries = json.getJSONArray("events"); require(entries.length() <= 500)
        fun strings(array: JSONArray?) = if (array == null) emptyList() else List(minOf(array.length(), 80)) { array.optString(it) }
        fun map(j: JSONObject?) = j?.keys()?.asSequence()?.take(2)?.associateWith { j.optString(it).take(2048) }.orEmpty()
        return LiveSportsSnapshot(List(entries.length()) { i -> val j = entries.getJSONObject(i); LiveEvent(j.getString("id"), strings(j.optJSONArray("teams")).toSet(), j.getLong("start"), j.optString("state"), strings(j.optJSONArray("networks")), map(j.optJSONObject("scores")), map(j.optJSONObject("logos")), j.optString("status"), j.optBoolean("scoreboard", true)) }, json.getLong("checked"), strings(json.optJSONArray("leagues")).toSet())
    }
}

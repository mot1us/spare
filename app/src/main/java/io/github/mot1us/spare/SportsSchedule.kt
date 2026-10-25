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
        listOf("NFL" to "football/nfl", "MLB" to "baseball/mlb").forEach { (league, path) ->
            listOf(date, date.minusDays(1)).forEach { day -> urls.add(league to "https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100") }
        }
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
            urls.forEach { (league, url) -> completion.submit(java.util.concurrent.Callable { Triple(league, url, LiveSports.parse(get(url), league, now, onlyLive = false)) }) }
            repeat(urls.size) {
                if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
                runCatching { completion.take().get() }.onSuccess { (league, url, entries) ->
                    if (url.contains("/scoreboard")) leagues.add(league)
                    entries.forEach { event -> if (event.state == "in" || events[event.id]?.state != "in") events[event.id] = event }
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
    fun games(snapshot: LiveSportsSnapshot, guide: Guide, team: String?, favorites: Set<String>, now: Long): List<SportsGame> {
        val events = if (team == null) snapshot.events.filter { it.state == "in" && snapshot.fresh(now) && it.teams.any { id -> SportsCatalog.byId[id]?.league in snapshot.leagues } }
            else snapshot.events.filter { team in it.teams && ((it.state == "in" && snapshot.fresh(now)) || it.state == "pre" && it.start > now) }
                .sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1)
        val byId = guide.channels.associateBy { it.id }
        // Only inspect already cached programme entries; no provider scan is required to show games.
        val broadcasts = SportsGuide.find(guide, emptySet(), favorites, now, liveOnly = false)
        return events.sortedBy { it.start }.take(256).mapNotNull { event ->
            val teams = event.teams.mapNotNull { SportsCatalog.byId[it] }.sortedBy { it.id }; if (teams.size != 2) return@mapNotNull null
            val matches = broadcasts.filter { it.teams.map { t -> t.id }.toSet() == event.teams && kotlin.math.abs(it.start - event.start) <= 3 * 3600000L }
                .flatMap { it.broadcasts }.filter { it.channel.id in byId }.distinctBy { it.channel.id }
            val networkNames = event.networks.map { SportsCatalog.normalize(it) }.filter { it.length >= 3 }
            val candidates = if (matches.isNotEmpty()) emptyList() else guide.channels.asSequence().filter { channel ->
                val name = " ${SportsCatalog.normalize(channel.name)} "
                networkNames.any { " $it " in name }
            }.sortedByDescending { it.id in favorites }.take(12).toList()
            SportsGame(teams, event.start, event.start + 8 * 3600000L, matches, event.id, event.state, event.networks, candidates)
        }
    }
    fun json(snapshot: LiveSportsSnapshot) = JSONObject().put("checked", snapshot.checked).put("leagues", JSONArray(snapshot.leagues.toList()))
        .put("events", JSONArray(snapshot.events.take(500).map { JSONObject().put("id", it.id).put("teams", JSONArray(it.teams.toList())).put("start", it.start).put("state", it.state).put("networks", JSONArray(it.networks)) }))
    fun from(json: JSONObject): LiveSportsSnapshot {
        val entries = json.getJSONArray("events"); require(entries.length() <= 500)
        fun strings(array: JSONArray?) = if (array == null) emptyList() else List(minOf(array.length(), 80)) { array.optString(it) }
        return LiveSportsSnapshot(List(entries.length()) { i -> val j = entries.getJSONObject(i); LiveEvent(j.getString("id"), strings(j.optJSONArray("teams")).toSet(), j.getLong("start"), j.optString("state"), strings(j.optJSONArray("networks"))) }, json.getLong("checked"), strings(json.optJSONArray("leagues")).toSet())
    }
}

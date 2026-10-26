package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Schedules populate Sports independently of playlist EPG and channel quality filters. */
object SportsSchedule {
    private val boardCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LiveEvent>>>()
    private val teamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LiveEvent>>>()
    fun load(followed: Set<String>, partial: (LiveSportsSnapshot) -> Unit = {}, get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }): LiveSportsSnapshot {
        val now = System.currentTimeMillis(); val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val events = linkedMapOf<String, LiveEvent>(); val leagues = mutableSetOf<String>()
        val urls = mutableListOf<Pair<String, String>>()
        SportsLeagues.all.forEach { league ->
            if (league.id == "NFL" || league.path.startsWith("soccer/")) urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?limit=100")
            else listOf(date, date.minusDays(1)).forEach { day -> urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100") }
        }
        followed.sorted().take(24).forEach { id -> SportsCatalog.byId[id]?.let { team ->
            team.competitions.forEach { leagueId ->
                val league = SportsLeagues.byId[leagueId] ?: return@forEach
                val key = "$leagueId:$id"; val cached = teamCache[key]
                if (cached != null && now - cached.first in 0 until 6 * 3600000L) cached.second.filter { it.state == "pre" && it.start > now }.forEach { events["${it.league}:${it.id}"] = it }
                else urls.add(leagueId to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/teams/${team.apiId.ifBlank { id.substringAfter(':').lowercase(java.util.Locale.ROOT) }}/schedule")
            }
        } }
        if (events.isNotEmpty()) partial(LiveSportsSnapshot(events.values.toList(), now, emptySet()))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<Triple<String, String, List<LiveEvent>>>(pool)
            urls.forEach { (league, url) -> completion.submit(java.util.concurrent.Callable { Triple(league, url, (if (url.contains("/scoreboard")) scoreboard(league, url, now, get) else LiveSports.parse(get(url), league, now, onlyLive = false)).map { it.copy(scoreboard = url.contains("/scoreboard")) }) }) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
            repeat(urls.size) {
                if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return LiveSportsSnapshot(events.values.toList(), now, leagues)
                val finished = completion.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return LiveSportsSnapshot(events.values.toList(), now, leagues)
                runCatching { finished.get() }.onSuccess { (league, url, entries) ->
                    if (url.contains("/scoreboard")) leagues.add(league)
                    entries.forEach { event -> if (event.scoreboard || events["${event.league}:${event.id}"]?.scoreboard != true) events["${event.league}:${event.id}"] = event }
                    if (url.contains("/teams/")) {
                        val code = url.substringAfter("/teams/").substringBefore('/').uppercase(java.util.Locale.ROOT)
                        SportsCatalog.teams.firstOrNull { league in it.competitions && (it.apiId == code || it.id.substringAfter(':').equals(code, true)) }?.let { teamCache["$league:${it.id}"] = now to entries }
                    }
                    partial(LiveSportsSnapshot(events.values.toList(), now, leagues.toSet()))
                }
            }
        } finally { pool.shutdownNow() }
        return LiveSportsSnapshot(events.values.toList(), now, leagues)
    }
    private fun scoreboard(league: String, url: String, now: Long, get: (String) -> String): List<LiveEvent> {
        val cached = boardCache[url]
        if (cached != null) {
            val busy = cached.second.any { it.state == "in" || it.state == "pre" && it.start < now + 4 * 3600000L }
            if (now - cached.first in 0 until if (busy) 60000L else 6 * 3600000L) return cached.second
        }
        val text = get(url); var entries = LiveSports.parse(text, league, now, onlyLive = false, includeCompleted = true)
        if (SportsLeagues.byId[league]?.path?.startsWith("soccer/") == true && entries.none { it.state == "in" || it.state == "pre" && it.start >= now }) {
            nextMatchday(text, now)?.let { day ->
                val dated = url + "&dates=" + day
                entries = entries + LiveSports.parse(get(dated), league, now, onlyLive = false, includeCompleted = true)
            }
        }
        entries = entries.distinctBy { it.id }; boardCache[url] = now to entries; return entries
    }
    internal fun nextMatchday(text: String, now: Long): String? {
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val calendar = JSONObject(text).optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: return null
        return (0 until minOf(calendar.length(), 500)).mapNotNull { index -> runCatching { java.time.OffsetDateTime.parse(calendar.optString(index)).toLocalDate() }.getOrNull() }
            .filter { !it.isBefore(date) && !it.isAfter(date.plusDays(45)) }.minOrNull()?.format(DateTimeFormatter.BASIC_ISO_DATE)
    }
    fun games(snapshot: LiveSportsSnapshot, guide: Guide, team: String?, favorites: Set<String>, now: Long, matchChannels: Boolean = true): List<SportsGame> {
        val events = if (team == null || team.startsWith("league:")) snapshot.events.filter { it.scoreboard && (team == null || it.teams.any { id -> (it.league.ifBlank { SportsCatalog.byId[id]?.league.orEmpty() }) == team.substringAfter(':') }) }
            else snapshot.events.filter { team in it.teams && (it.state == "in" || it.state == "pre" && it.start > now) }
                .sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1)
                .ifEmpty { snapshot.events.filter { team in it.teams && it.state == "post" }.sortedByDescending { it.start }.take(1) }
        return events.sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(256).mapNotNull { event ->
            val teams = event.teams.mapNotNull { SportsCatalog.byId[it] }.sortedBy { it.id }; if (teams.size != 2) return@mapNotNull null
            val stale = event.state == "in" && !snapshot.fresh(now)
            val game = SportsGame(teams, event.start, event.start + 8 * 3600000L, emptyList(), event.id, if (stale) "hold" else event.state, event.networks, scores = event.scores, logos = event.logos, status = if (stale) "Saved score · refresh for live status" else event.status, competition = event.league)
            if (matchChannels) match(game, guide, favorites, now) else game
        }
    }
    fun match(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long): SportsGame {
        if (game.state == "post") return game
        val target = game.teams.map { it.id }.toSet()
        val parsed = HashMap<Pair<String, String>, List<SportsTeam>?>()
        val byGuideKey = guide.channels.filter { !it.media }.groupBy { guide.key(it) }
        val matches = mutableListOf<SportsBroadcast>()
        for ((key, channels) in byGuideKey) {
            if (Thread.currentThread().isInterrupted) return game
            val programme = guide.programmes[key].orEmpty().firstOrNull { p ->
                p.end > now && kotlin.math.abs(p.start - game.start) <= 3 * 3600000L && run {
                    val cacheKey = p.title to p.description
                    val teams = if (parsed.containsKey(cacheKey)) parsed[cacheKey] else SportsGuide.teams(p).also { if (parsed.size < 10000) parsed[cacheKey] = it }
                    teams?.map { it.id }?.toSet() == target
                }
            }
            if (programme != null) channels.forEach { matches.add(SportsBroadcast(it, programme)) }
        }
        val names = game.networks.map { SportsCatalog.normalize(it) }.filter { it.length >= 3 }
        val mascots = game.teams.map { SportsCatalog.normalize(it.name.substringAfterLast(' ')) }.filter { it.length >= 4 }
        val confirmed = matches.map { it.channel.id }.toSet()
        val candidates = guide.channels.asSequence().filter { channel ->
            val name = " ${SportsCatalog.normalize(channel.name)} "
            !channel.media && channel.id !in confirmed && (names + mascots).any { " $it " in name }
        }.sortedByDescending { it.id in favorites }.take(512).toList()
        return game.copy(broadcasts = matches, candidates = candidates)
    }
    fun json(snapshot: LiveSportsSnapshot) = JSONObject().put("checked", snapshot.checked).put("leagues", JSONArray(snapshot.leagues.toList()))
        .put("events", JSONArray(snapshot.events.take(500).map { JSONObject().put("id", it.id).put("teams", JSONArray(it.teams.toList())).put("start", it.start).put("state", it.state).put("networks", JSONArray(it.networks)).put("scores", JSONObject(it.scores)).put("logos", JSONObject(it.logos)).put("status", it.status).put("scoreboard", it.scoreboard).put("league", it.league) }))
    fun from(json: JSONObject): LiveSportsSnapshot {
        val entries = json.getJSONArray("events"); require(entries.length() <= 500)
        fun strings(array: JSONArray?) = if (array == null) emptyList() else List(minOf(array.length(), 80)) { array.optString(it) }
        fun map(j: JSONObject?) = j?.keys()?.asSequence()?.take(2)?.associateWith { j.optString(it).take(2048) }.orEmpty()
        return LiveSportsSnapshot(List(entries.length()) { i -> val j = entries.getJSONObject(i); LiveEvent(j.getString("id"), strings(j.optJSONArray("teams")).toSet(), j.getLong("start"), j.optString("state"), strings(j.optJSONArray("networks")), map(j.optJSONObject("scores")), map(j.optJSONObject("logos")), j.optString("status"), j.optBoolean("scoreboard", true), j.optString("league")) }, json.getLong("checked"), strings(json.optJSONArray("leagues")).toSet())
    }
}

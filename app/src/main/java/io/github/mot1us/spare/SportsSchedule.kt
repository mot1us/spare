package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Schedules populate Sports independently of playlist EPG and channel quality filters. */
object SportsSchedule {
    fun reminderEvents(targets: Set<Pair<String, java.time.LocalDate>>, now: Long = System.currentTimeMillis(), get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }): List<LiveEvent> {
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<List<LiveEvent>>(pool)
            val wanted = targets.take(14)
            wanted.forEach { (id, day) -> completion.submit(java.util.concurrent.Callable {
                val league = SportsLeagues.byId[id] ?: return@Callable emptyList()
                runCatching { scoreboard(id, "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100", now, get, maxCacheMs = 30000) }.getOrDefault(emptyList())
            }) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            val result = mutableListOf<LiveEvent>()
            repeat(wanted.size) {
                val left = deadline - System.nanoTime(); if (left <= 0) return result
                val done = completion.poll(left, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return result
                result.addAll(done.get())
            }
            return result.distinctBy { "${it.league}:${it.id}" }
        } finally { pool.shutdownNow() }
    }
    private val boardCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LiveEvent>>>()
    private val teamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LiveEvent>>>()
    fun calendarDates(day: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<java.time.LocalDate> {
        val eastern = ZoneId.of("America/New_York")
        val first = day.atStartOfDay(zone).withZoneSameInstant(eastern).toLocalDate()
        val last = day.plusDays(1).atStartOfDay(zone).minusNanos(1).withZoneSameInstant(eastern).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }
    fun load(followed: Set<String>, requestedDate: java.time.LocalDate? = null, partial: (LiveSportsSnapshot) -> Unit = {}, enabledLeagues: Set<String> = SportsLeagues.byId.keys, get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }, calendarOnly: Boolean = false): LiveSportsSnapshot {
        val now = System.currentTimeMillis(); val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val events = linkedMapOf<String, LiveEvent>(); val leagues = mutableSetOf<String>()
        val urls = mutableListOf<Pair<String, String>>()
        val enabled = SportsLeagues.all.filter { it.id in enabledLeagues }
        if (requestedDate != null) {
            (if (calendarOnly) calendarDates(requestedDate) else listOf(requestedDate, requestedDate.minusDays(1), requestedDate.plusDays(1))).forEach { day -> enabled.forEach { league ->
                urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100")
            } }
        } else {
        enabled.forEach { league ->
            urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=${date.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100")
        }
        enabled.forEach { league ->
            if (league.id == "NFL" || league.path.startsWith("soccer/")) urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?limit=100")
            urls.add(league.id to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard?dates=${date.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100")
        }
        }
        (if (requestedDate == null) followed else emptySet()).sorted().take(24).forEach { id -> SportsCatalog.byId[id]?.let { team ->
            team.competitions.filter { it in enabledLeagues }.forEach { leagueId ->
                val league = SportsLeagues.byId[leagueId] ?: return@forEach
                val key = "$leagueId:$id"; val cached = teamCache[key]
                if (cached != null && now - cached.first in 0 until 6 * 3600000L) cached.second.filter { it.state == "pre" && it.start > now }.forEach { events["${it.league}:${it.id}"] = it }
                else urls.add(leagueId to "https://site.api.espn.com/apis/site/v2/sports/${league.path}/teams/${team.apiId.ifBlank { id.substringAfter(':').lowercase(java.util.Locale.ROOT) }}/schedule")
            }
        } }
        if (events.isNotEmpty()) partial(LiveSportsSnapshot(events.values.toList(), now, emptySet()))
        val completedBoards = mutableMapOf<String, Int>(); val expectedBoards = urls.groupingBy { it.first }.eachCount()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<Triple<String, String, List<LiveEvent>>>(pool)
            urls.forEach { (league, url) -> completion.submit(java.util.concurrent.Callable { Triple(league, url, (if (url.contains("/scoreboard")) scoreboard(league, url, now, get) else LiveSports.parse(get(url), league, now, onlyLive = false)).map { it.copy(scoreboard = url.contains("/scoreboard")) }) }) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(if (calendarOnly) 6 else 12)
            repeat(urls.size) {
                if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return LiveSportsSnapshot(events.values.toList(), now, leagues)
                val finished = completion.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return LiveSportsSnapshot(events.values.toList(), now, leagues)
                runCatching { finished.get() }.onSuccess { (league, url, entries) ->
                    if (url.contains("/scoreboard")) {
                        completedBoards[league] = (completedBoards[league] ?: 0) + 1
                        if (!calendarOnly || completedBoards[league] == expectedBoards[league]) leagues.add(league)
                    }
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
    private fun scoreboard(league: String, url: String, now: Long, get: (String) -> String, maxCacheMs: Long = Long.MAX_VALUE): List<LiveEvent> {
        val cached = boardCache[url]
        if (cached != null) {
            val busy = cached.second.any { it.state == "in" || it.state == "pre" && it.start < now + 4 * 3600000L }
            if (now - cached.first in 0 until minOf(maxCacheMs, if (busy) 60000L else 6 * 3600000L)) return cached.second
        }
        val text = get(url); var entries = LiveSports.parse(text, league, now, onlyLive = false, includeCompleted = true)
        if (!url.contains("dates=") && SportsLeagues.byId[league]?.path?.startsWith("soccer/") == true && entries.none { it.state == "in" || it.state == "pre" && it.start >= now }) {
            nextMatchday(text, now)?.let { day ->
                val dated = url + "&dates=" + day
                entries = entries + LiveSports.parse(get(dated), league, now, onlyLive = false, includeCompleted = true)
            }
        }
        entries = entries.distinctBy { it.id }; boardCache[url] = now to entries
        while (boardCache.size > 96) boardCache.entries.minByOrNull { it.value.first }?.let { boardCache.remove(it.key, it.value) }
        return entries
    }
    internal fun nextMatchday(text: String, now: Long): String? {
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val calendar = JSONObject(text).optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar") ?: return null
        return (0 until minOf(calendar.length(), 500)).mapNotNull { index -> runCatching { java.time.OffsetDateTime.parse(calendar.optString(index)).toLocalDate() }.getOrNull() }
            .filter { !it.isBefore(date) && !it.isAfter(date.plusDays(45)) }.minOrNull()?.format(DateTimeFormatter.BASIC_ISO_DATE)
    }
    fun selectedDate(scope: String?, now: Long = System.currentTimeMillis()): java.time.LocalDate? = when {
        scope == "tomorrow" -> Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        scope?.startsWith("date:") == true -> runCatching { java.time.LocalDate.parse(scope.removePrefix("date:")) }.getOrNull()
        else -> null
    }
    internal fun onDay(start: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(start).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun games(snapshot: LiveSportsSnapshot, guide: Guide, team: String?, favorites: Set<String>, now: Long, matchChannels: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): List<SportsGame> {
        val selectedDay = selectedDate(team, now)
        val events = if (selectedDay != null) snapshot.events.filter { it.scoreboard && Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == selectedDay }
            else if (team == "live") snapshot.events.filter { it.scoreboard && it.state == "in" && it.start <= now + 60000 && snapshot.fresh(now) }
            else if (team == "today") snapshot.events.filter { it.scoreboard && (onDay(it.start, now, zone) || it.state == "in" && it.start in (now - 18 * 3600000L)..now) }
            else if (team == null || team.startsWith("league:")) snapshot.events.filter { it.scoreboard && (team == null || it.teams.any { id -> (it.league.ifBlank { SportsCatalog.byId[id]?.league.orEmpty() }) == team.substringAfter(':') }) }
            else snapshot.events.filter { team in it.teams && (it.state == "in" || it.state == "pre" && it.start > now) }
                .sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1)
                .ifEmpty { snapshot.events.filter { team in it.teams && it.state == "post" }.sortedByDescending { it.start }.take(1) }
        return events.sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(256).mapNotNull { event ->
            val teams = event.teams.mapNotNull { SportsCatalog.byId[it] }.sortedBy { it.id }; if (teams.size != 2) return@mapNotNull null
            val stale = event.state == "in" && !snapshot.fresh(now)
            val game = SportsGame(teams, event.start, event.start + 8 * 3600000L, emptyList(), event.id, if (stale) "hold" else event.state, event.networks, scores = event.scores, logos = event.logos, status = if (stale) "Saved score · checking live status" else event.status, competition = event.league)
            if (matchChannels) match(game, guide, favorites, now) else game
        }
    }
    fun match(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long, deadlineNanos: Long = Long.MAX_VALUE, quality: Map<String, VideoInfo> = emptyMap(), progress: (Int, Int) -> Unit = { _, _ -> }): SportsGame {
        if (game.state == "post") return game
        val target = game.teams.map { it.id }.toSet()
        val hints = game.teams.map { team -> (team.aliases + team.name.split(' ').filter { it.length >= 4 }).distinct() }
        fun relevant(title: String, description: String = "") = hints.all { aliases -> aliases.any { alias -> title.contains(alias, true) || description.contains(alias, true) } }
        val parsed = HashMap<Pair<String, String>, Boolean>()
        fun matchesTarget(p: Programme, context: String = ""): Boolean {
            if (!relevant(p.title, p.description)) return false
            val key = p.title to (p.description + context)
            return parsed.getOrPut(key) { SportsGuide.teams(p, context)?.map { it.id }?.toSet() == target }
        }
        val names = game.networks.map { " ${SportsCatalog.normalize(it)} " }.filter { it.length >= 5 }
        val mascots = game.teams.map { SportsCatalog.normalize(it.name.substringAfterLast(' ')) }.filter { it.length >= 4 }.map { " $it " }
        val patterns = names + mascots
        val dates = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)")
        val gameDates = listOf(ZoneId.systemDefault(), ZoneId.of("America/New_York")).map { Instant.ofEpochMilli(game.start).atZone(it).toLocalDate() }
        fun currentTitle(title: String): Boolean {
            val declared = dates.findAll(title).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.filter { it.first in 1..12 && it.second in 1..31 }.toList()
            return declared.isEmpty() || declared.any { date -> gameDates.any { it.monthValue == date.first && it.dayOfMonth == date.second } }
        }
        val candidates = mutableListOf<Channel>(); val titles = mutableListOf<Channel>()
        val channels = guide.channels.filter { !it.media }
        var complete = true
        for ((index, channel) in channels.withIndex()) {
            if (Thread.currentThread().isInterrupted) return game
            if (index % 128 == 0 && System.nanoTime() >= deadlineNanos) { complete = false; break }
            if (SportsGuide.excludedTitle(channel.sportsKey) || relevant(channel.name) && !currentTitle(channel.name)) continue
            if (game.live(now) && relevant(channel.name) && currentTitle(channel.name) && matchesTarget(Programme(channel.id, channel.name, game.start, game.end), channel.group)) titles.add(channel)
            else if (patterns.any { it in channel.sportsKey }) candidates.add(channel)
            if (index % 512 == 0) progress(index, channels.size * 2)
        }
        val byGuideKey = channels.groupBy { guide.key(it) }
        val matches = mutableListOf<SportsBroadcast>()
        val at = if (game.state == "pre") game.start else now
        var checkedKeys = 0
        for ((index, entry) in byGuideKey.entries.withIndex()) {
            if (Thread.currentThread().isInterrupted) return game
            if (System.nanoTime() >= deadlineNanos) { complete = false; break }
            checkedKeys++
            val programme = guide.programmes[entry.key].orEmpty().firstOrNull { p ->
                p.start <= at && p.end > at && (game.state != "pre" || kotlin.math.abs(p.start - game.start) <= 3 * 3600000L) && matchesTarget(p, game.teams.first().league)
            }
            if (programme != null) entry.value.forEach { matches.add(SportsBroadcast(it, programme)) }
            if (index % 512 == 0) progress(channels.size + index, channels.size + byGuideKey.size)
        }
        val confirmed = matches.map { it.channel.id }.toSet()
        val titleOnly = titles.filter { it.id !in confirmed }
        val found = confirmed + titleOnly.map { it.id }
        progress(channels.size + checkedKeys, channels.size + byGuideKey.size)
        return game.copy(broadcasts = matches, titleMatches = titleOnly, candidates = candidates.filter { it.id !in found }
            .sortedWith(SportsStreams.preferenceComparator(GuideFilters(), quality).thenByDescending { it.id in favorites }).take(512), coverageComplete = complete)
    }
    internal fun newlyConfirmed(game: SportsGame, before: Guide, after: Guide, now: Long, ignore: Set<String> = emptySet()): Boolean {
        val target = game.teams.map { it.id }.toSet()
        val hints = game.teams.map { it.aliases + it.name.split(' ').filter { word -> word.length >= 4 } }
        return after.programmes.any { (key, entries) -> key !in ignore && entries != before.programmes[key] && entries.any { programme ->
            now in programme.start until programme.end && hints.all { aliases -> aliases.any { programme.title.contains(it, true) || programme.description.contains(it, true) } } &&
                SportsGuide.teams(programme, game.teams.first().league)?.map { it.id }?.toSet() == target && after.channels.any { after.key(it) == key }
        } }
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

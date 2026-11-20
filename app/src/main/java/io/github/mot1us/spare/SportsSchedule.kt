package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Schedules populate Sports independently of playlist EPG and channel quality filters. */
object SportsSchedule {
    private fun fetch(url: String) = Network.get(url, limit = (if ("college-" in url) 6 else 2) * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8)
    fun reminderEvents(targets: Set<Pair<String, java.time.LocalDate>>, now: Long = System.currentTimeMillis(), get: (String) -> String = ::fetch): List<LiveEvent> {
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(4) { task -> Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "spare-scoreboard").apply { priority = Thread.MIN_PRIORITY } }
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<List<LiveEvent>>(pool)
            val wanted = targets.take(14).flatMap { (id, day) -> SportsLeagues.byId[id]?.scoreboards(day).orEmpty().map { id to it } }
            wanted.forEach { (id, url) -> completion.submit(java.util.concurrent.Callable {
                runCatching { scoreboard(id, url, now, get, maxCacheMs = 30000) }.getOrDefault(emptyList())
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
    fun load(followed: Set<String>, requestedDate: java.time.LocalDate? = null, partial: (LiveSportsSnapshot) -> Unit = {}, enabledLeagues: Set<String> = SportsLeagues.byId.keys, get: (String) -> String = ::fetch, calendarOnly: Boolean = false, priorityLeague: String? = null): LiveSportsSnapshot {
        val now = System.currentTimeMillis(); val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val events = linkedMapOf<String, LiveEvent>(); val leagues = mutableSetOf<String>()
        val urls = mutableListOf<Pair<String, String>>()
        val enabled = SportsLeagues.all.filter { it.id in enabledLeagues }.sortedBy { if (it.id == priorityLeague) 0 else 1 }
        if (requestedDate != null) {
            (if (calendarOnly) calendarDates(requestedDate) else listOf(requestedDate, requestedDate.minusDays(1), requestedDate.plusDays(1))).forEach { day -> enabled.forEach { league ->
                urls.addAll(league.scoreboards(day).map { league.id to it })
            } }
        } else {
        enabled.forEach { league ->
            urls.addAll(league.scoreboards(date).map { league.id to it })
        }
        enabled.forEach { league ->
            if (league.id in setOf("NFL", "NCAAF") || league.path.startsWith("soccer/")) urls.addAll(league.scoreboards().map { league.id to it })
            urls.addAll(league.scoreboards(date.minusDays(1)).map { league.id to it })
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
        val revisions = mutableMapOf<String, Long>()
        val completedBoards = mutableMapOf<String, Int>(); val expectedBoards = urls.groupingBy { it.first }.eachCount()
        val pool = Executors.newFixedThreadPool(4) { task -> Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "spare-scoreboard").apply { priority = Thread.MIN_PRIORITY } }
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
                    val revision = if (url.contains("/scoreboard")) boardCache[url]?.first ?: now else now
                    entries.forEach { event ->
                        val key = "${event.league}:${event.id}"
                        if (preferEvent(events[key], revisions[key] ?: Long.MIN_VALUE, event, revision)) {
                            events[key] = event; revisions[key] = revision
                        }
                    }
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
    /** One scoreboard for idle preloading, shared with the normal Sports scoreboard cache. */
    internal fun warmToday(leagueId: String, now: Long, get: (String) -> String = ::fetch): LiveSportsSnapshot {
        val league = SportsLeagues.byId[leagueId] ?: return LiveSportsSnapshot.EMPTY
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val url = league.scoreboards(day).firstOrNull() ?: return LiveSportsSnapshot.EMPTY
        val events = scoreboard(leagueId, url, now, get)
        return LiveSportsSnapshot(events, boardCache[url]?.first ?: now, setOf(leagueId))
    }
    /** Multiple endpoints can describe the same game. Arrival order is not freshness. */
    internal fun preferEvent(previous: LiveEvent?, previousChecked: Long, incoming: LiveEvent, incomingChecked: Long): Boolean {
        if (previous == null) return true
        if (previous.scoreboard != incoming.scoreboard) return incoming.scoreboard
        if (incomingChecked != previousChecked) return incomingChecked > previousChecked
        fun stage(event: LiveEvent) = when (event.state) { "post" -> 2; "in" -> 1; else -> 0 }
        return stage(incoming) >= stage(previous)
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
    const val SAVED_LIVE_MS = 20 * 60000L
    fun selectedDate(scope: String?, now: Long = System.currentTimeMillis()): java.time.LocalDate? = when {
        scope == "tomorrow" -> Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
        scope?.startsWith("date:") == true -> runCatching { java.time.LocalDate.parse(scope.removePrefix("date:")) }.getOrNull()
        else -> null
    }
    internal fun onDay(start: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(start).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun games(snapshot: LiveSportsSnapshot, guide: Guide, team: String?, favorites: Set<String>, now: Long, matchChannels: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): List<SportsGame> {
        val selectedDay = selectedDate(team, now)
        val events = if (selectedDay != null) snapshot.events.filter { it.scoreboard && Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == selectedDay }
            // Live now shows games from a saved copy up to 20 minutes old (marked as checking) instead of an empty
            // screen while the first scoreboard reply downloads; the fresh reply replaces them in place.
            else if (team == "live") snapshot.events.filter { it.scoreboard && it.state == "in" && it.start <= now + 60000 && (snapshot.fresh(now) || now - snapshot.checked in 0..SAVED_LIVE_MS) }
            else if (team == "today") snapshot.events.filter { it.scoreboard && (onDay(it.start, now, zone) || it.state == "in" && it.start in (now - 18 * 3600000L)..now) }
            else if (team == null || team.startsWith("league:")) snapshot.events.filter { it.scoreboard && (team == null || it.teams.any { id -> (it.league.ifBlank { SportsCatalog.byId[id]?.league.orEmpty() }) == team.substringAfter(':') }) }
            else snapshot.events.filter { team in it.teams && (it.state == "in" || it.state == "pre" && it.start > now) }
                .sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1)
                .ifEmpty { snapshot.events.filter { team in it.teams && it.state == "post" }.sortedByDescending { it.start }.take(1) }
        return events.sortedWith(compareBy<LiveEvent> { it.state != "in" }.thenBy { it.start }).take(1024).mapNotNull { event ->
            val teams = event.teams.mapNotNull { SportsCatalog.byId[it] }.sortedBy { it.id }; if (teams.size != 2) return@mapNotNull null
            val stale = event.state == "in" && !snapshot.fresh(now)
            val game = SportsGame(teams, event.start, event.start + 8 * 3600000L, emptyList(), event.id, if (stale) "hold" else event.state, event.networks, scores = event.scores, logos = event.logos, status = if (stale) "Saved score · checking live status" else event.status, competition = event.league, records = event.records, situation = if (stale) "" else event.situation, possession = if (stale) "" else event.possession)
            if (matchChannels) match(game, guide, favorites, now) else game
        }
    }
    /** Broadcaster channels kept as possible streams for one game; a provider's ESPN+ slots alone can number over a hundred. */
    internal const val MAX_CANDIDATES = 96
    fun match(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long, deadlineNanos: Long = Long.MAX_VALUE,
        quality: Map<String, VideoInfo> = emptyMap(), trace: SportsClickTrace? = null, index: GuideProgrammeIndex? = null,
        progress: (Int, Int) -> Unit = { _, _ -> }): SportsGame {
        if (game.state == "post") return game
        val prepared = index?.takeIf { deadlineNanos == Long.MAX_VALUE && it.isFor(guide) && it.covers(game, now) }
        val measurement = trace?.matchStarted()
        val result = matchLocal(game, guide, favorites, now, deadlineNanos, quality, progress, measurement, prepared)
        if (measurement != null) {
            trace.matchEnded(measurement)
            countNearMisses(game, guide, now, measurement, trace, prepared?.sportsKeys ?: measurement.guideKeys)
            trace.matched(measurement)
        }
        return result
    }
    private fun matchLocal(game: SportsGame, guide: Guide, favorites: Set<String>, now: Long, deadlineNanos: Long,
        quality: Map<String, VideoInfo>, progress: (Int, Int) -> Unit, measurement: SportsMatchMeasurement?,
        prepared: GuideProgrammeIndex?): SportsGame {
        val target = game.teams.map { it.id }.toSet()
        val hints = game.teams.map { team -> (team.aliases + team.name.split(' ').filter { it.length >= 4 }).distinct() }
        fun relevant(title: String, description: String = "") = hints.all { aliases -> aliases.any { alias -> title.contains(alias, true) || description.contains(alias, true) } }
        val parsed = HashMap<Pair<String, String>, Boolean>()
        fun matchesTarget(p: Programme, context: String = ""): Boolean {
            if (!relevant(p.title, p.description)) return false
            val key = p.title to (p.description + context)
            return parsed.getOrPut(key) { SportsGuide.teams(p, context)?.map { it.id }?.toSet() == target }
        }
        // Possible streams are only the game's own broadcasters' channels. "ESPN+" once became "espn" and every ESPN channel
        // counted, and team nicknames ("Bulls", "Tigers") matched hundreds of unrelated channels: a college card read
        // "512 streams found" when no channel named the game.
        val networkKeys = game.networks.map(SportsStreams::broadcasterKey).filter { it.trim().length >= 3 }.distinct()
        // Words to look for first, including spelled-out names ("BTN" is "Big Ten Network" in most playlists).
        val candidateHints = (networkKeys + game.networks.flatMap(SportsStreams::networkNames)).flatMap { it.trim().split(' ') }
            .filter { it.length >= 3 && it != "plus" }.distinct()
        val league = game.teams.first().league
        val candidates = mutableListOf<Channel>(); val titles = mutableListOf<Channel>()
        val indexed = prepared?.sportsChannels(hints, candidateHints) ?: PlaylistCatalog.interleave(guide.channels.filter(SportsStreams::video))
        // Event channels renamed since the channel list was saved ("Game Pass 11: NO EVENT" -> "...: SEA @ LV").
        val channels = EventChannelNames.renamedIn(guide.channels).filter(SportsStreams::video).let { renamed ->
            if (renamed.isEmpty()) indexed else (indexed + renamed).distinctBy { it.id } }
        var complete = true
        val matches = mutableListOf<SportsBroadcast>()
        var checkedKeys = 0
        val total: Int
        if (prepared != null) {
            val best = linkedMapOf<String, Programme>()
            val visited = if (measurement == null) null else HashSet<String>()
            for (row in prepared.sportsListings(game.teams.first().league, target)) {
                if (Thread.currentThread().isInterrupted) return game
                if (measurement != null) {
                    measurement.programmes++
                    if (visited?.add(row.key) == true) measurement.guideKeys.add(row.key)
                }
                val programme = row.programme
                if (!broadcastWindow(game, programme, now) || !relevant(programme.title, programme.description)) continue
                val prior = best[row.key]
                if (prior == null || kotlin.math.abs(programme.start - game.start) < kotlin.math.abs(prior.start - game.start)) best[row.key] = programme
            }
            best.forEach { (key, programme) -> prepared.video(key).forEach { matches.add(SportsBroadcast(it, programme)) } }
            total = channels.size
        } else {
            val byGuideKey = channels.groupBy(guide::key)
            total = channels.size + byGuideKey.size
            for ((index, entry) in byGuideKey.entries.withIndex()) {
                if (Thread.currentThread().isInterrupted) return game
                if (System.nanoTime() >= deadlineNanos) { complete = false; break }
                checkedKeys++
                val listings = guide.programmes[entry.key].orEmpty()
                measurement?.visited(entry.key, listings.size)
                val programme = listings.asSequence().filter { p ->
                    broadcastWindow(game, p, now) && matchesTarget(p, game.teams.first().league)
                }.minByOrNull { kotlin.math.abs(it.start - game.start) }
                if (programme != null) entry.value.forEach { matches.add(SportsBroadcast(it, programme)) }
                if (index % 512 == 0) progress(index, total)
            }
        }
        var checkedChannels = 0
        for ((index, channel) in channels.withIndex()) {
            if (Thread.currentThread().isInterrupted) return game
            if (index % 128 == 0 && System.nanoTime() >= deadlineNanos) { complete = false; break }
            checkedChannels++
            if (index % 512 == 0) progress(checkedKeys + checkedChannels, total)
            val name = EventChannelNames.name(channel)
            val named = relevant(name)
            val possibleNetwork = candidateHints.any { name.contains(it, true) } && SportsStreams.speculative(channel)
            if (!named && !possibleNetwork) continue
            val renamed = EventChannelNames.renamed(channel)
            val sportsKey = if (renamed) " ${SportsCatalog.normalize(name)} " else channel.sportsKey
            if (SportsGuide.excludedTitle(sportsKey) || named && EventChannelNames.declaresOtherTime(name, game.start)) continue
            // The game's league is context for a bare "Basketball: Richmond vs. Towson": event categories often
            // say only the sport, and the channel's category alone did not say college.
            if (game.live(now) && named && matchesTarget(Programme(channel.id, name, game.start, game.end), "$league ${channel.group}")) titles.add(channel)
            else if (possibleNetwork) {
                val key = if (renamed) SportsStreams.broadcasterKey(name) else channel.broadcastKey
                if (networkKeys.any { it in key && (it.trimEnd() + " plus ") !in key }) candidates.add(channel)
            }
        }
        val confirmed = matches.map { it.channel.id }.toSet()
        if (measurement != null) measurement.channels = checkedChannels
        // A broadcaster's channel (CBS, FOX…) whose own guide names a different matchup at this time is carrying another
        // game: on a Sunday each local station shows its region's game, so the network name alone pointed at wrong feeds.
        val elsewhere = HashMap<String, Boolean>()
        fun otherGame(channel: Channel): Boolean = elsewhere.getOrPut(guide.key(channel)) {
            guide.programmes[guide.key(channel)].orEmpty().asSequence().filter { broadcastWindow(game, it, now) }.any { p ->
                SportsGuide.teams(p, league)?.map { it.id }?.toSet()?.let { it.size == 2 && it != target } == true
            }
        }
        val titleOnly = titles.filter { it.id !in confirmed }
        val found = confirmed + titleOnly.map { it.id }
        progress(checkedChannels + checkedKeys, total)
        return game.copy(broadcasts = matches, titleMatches = titleOnly, candidates = candidates.filter { it.id !in found && !otherGame(it) }
            .let { PlaylistCatalog.interleave(it.sortedWith(SportsStreams.preferenceComparator(GuideFilters(), quality).thenByDescending { it.id in favorites })) }.take(MAX_CANDIDATES), coverageComplete = complete)
    }
    /** Run after the timed match so this diagnostic pass cannot consume its deadline. */
    private fun countNearMisses(game: SportsGame, guide: Guide, now: Long, measurement: SportsMatchMeasurement,
        trace: SportsClickTrace, keys: Iterable<String>) {
        if (game.teams.size != 2 || !trace.active()) return
        val aliases = game.teams.map { team -> (team.aliases + team.name).map { it to " ${SportsCatalog.normalize(it)} " }.distinct() }
        val league = game.teams.first().league
        val ids = game.teams.map { it.id }.toSet()
        for (key in keys) {
            if (Thread.currentThread().isInterrupted || !trace.active()) return
            for (programme in guide.programmes[key].orEmpty()) {
                if (!aliases.all { names -> names.any { (raw, _) -> programme.title.contains(raw, true) || programme.description.contains(raw, true) } }) continue
                val title = SportsCatalog.normalize(programme.title)
                if (SportsGuide.excludedTitle(title)) continue
                val words = " $title ${SportsCatalog.normalize(programme.description)} "
                if (!aliases.all { names -> names.any { (_, phrase) -> words.contains(phrase) } }) continue
                if (!broadcastWindow(game, programme, now)) measurement.outsideWindow++
                else if (!SportsGuide.hasMatchupSeparator(title)) {
                    measurement.separator++
                    if (SportsGuide.teams(programme, league)?.map { it.id }?.toSet() != ids) measurement.separatorRejected++
                }
            }
        }
    }
    internal fun broadcastWindow(game: SportsGame, programme: Programme, now: Long): Boolean =
        if (game.state == "pre") programme.end > game.start && programme.start in (game.start - 90 * 60000L)..(game.start + 45 * 60000L)
        else now in programme.start until programme.end
    internal fun newlyConfirmed(game: SportsGame, before: Guide, after: Guide, now: Long, ignore: Set<String> = emptySet()): Boolean {
        val target = game.teams.map { it.id }.toSet()
        val hints = game.teams.map { it.aliases + it.name.split(' ').filter { word -> word.length >= 4 } }
        return after.programmes.any { (key, entries) -> key !in ignore && entries != before.programmes[key] && entries.any { programme ->
            broadcastWindow(game, programme, now) && hints.all { aliases -> aliases.any { programme.title.contains(it, true) || programme.description.contains(it, true) } } &&
                SportsGuide.teams(programme, game.teams.first().league)?.map { it.id }?.toSet() == target && after.channels.any { after.key(it) == key }
        } }
    }
    fun json(snapshot: LiveSportsSnapshot) = JSONObject().put("checked", snapshot.checked).put("leagues", JSONArray(snapshot.leagues.toList()))
        .put("events", JSONArray(snapshot.events.take(1024).map { JSONObject().put("id", it.id).put("teams", JSONArray(it.teams.toList())).put("start", it.start).put("state", it.state).put("networks", JSONArray(it.networks)).put("scores", JSONObject(it.scores)).put("logos", JSONObject(it.logos)).put("status", it.status).put("scoreboard", it.scoreboard).put("league", it.league).put("records", JSONObject(it.records)).put("situation", it.situation).put("possession", it.possession) }))
    fun from(json: JSONObject): LiveSportsSnapshot {
        val entries = json.getJSONArray("events"); require(entries.length() <= 1024)
        fun strings(array: JSONArray?) = if (array == null) emptyList() else List(minOf(array.length(), 80)) { array.optString(it) }
        fun map(j: JSONObject?) = j?.keys()?.asSequence()?.take(2)?.associateWith { j.optString(it).take(2048) }.orEmpty()
        return LiveSportsSnapshot(List(entries.length()) { i -> val j = entries.getJSONObject(i); LiveEvent(j.getString("id"), strings(j.optJSONArray("teams")).toSet(), j.getLong("start"), j.optString("state"), strings(j.optJSONArray("networks")), map(j.optJSONObject("scores")), map(j.optJSONObject("logos")), j.optString("status"), j.optBoolean("scoreboard", true), j.optString("league"), map(j.optJSONObject("records")).filterValues { it.matches(Regex("[0-9]{1,3}-[0-9]{1,3}(-[0-9]{1,3})?")) }, j.optString("situation").take(120), j.optString("possession").take(80).takeIf { it in strings(j.optJSONArray("teams")) }.orEmpty()) }, json.getLong("checked"), strings(json.optJSONArray("leagues")).toSet())
    }
}

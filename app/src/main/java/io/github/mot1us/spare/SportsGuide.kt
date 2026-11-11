package io.github.mot1us.spare

import kotlin.math.abs

data class SportsBroadcast(val channel: Channel, val programme: Programme)
data class SportsGame(val teams: List<SportsTeam>, val start: Long, val end: Long, val broadcasts: List<SportsBroadcast>, val eventId: String = "", val state: String = "in", val networks: List<String> = emptyList(), val candidates: List<Channel> = emptyList(), val scores: Map<String, String> = emptyMap(), val logos: Map<String, String> = emptyMap(), val status: String = "", val competition: String = "", val titleMatches: List<Channel> = emptyList(), val coverageComplete: Boolean = true, val records: Map<String, String> = emptyMap(), val situation: String = "", val possession: String = "") {
    val leagueId get() = competition.ifBlank { teams.first().league }
    val key: String get() = (if (competition.isNotBlank() && eventId.isNotBlank()) "$competition:$eventId" else eventId).ifBlank { teams.map { it.id }.sorted().joinToString("|") + ":$start" }
    val title: String get() = teams.joinToString(" vs ") { it.name }
    val scoreLine: String get() = if (scores.isEmpty() || state == "pre") title else teams.joinToString("   —   ") { it.name + (scores[it.id]?.let { score -> "  $score" } ?: "") }
    fun statusLine(now: Long): String = when { state == "post" -> status.ifBlank { "Final" }; live(now) -> "LIVE · ${status.ifBlank { "In progress" }}"; state == "pre" -> "${GuideClock.sportsDate(start, now)}"; else -> status.ifBlank { "Status unavailable" } }
    fun live(now: Long) = state == "in" && if (eventId.isNotBlank()) start <= now + 60000 else start <= now && end > now
    fun upcoming(now: Long) = state == "pre" && start > now
}

/** Conservative EPG matching. A listing is evidence of a broadcast, not a guarantee that a stream is online. */
object SportsGuide {
    const val HORIZON = 48 * 3600000L
    const val MAX_WARM_CHANNELS = 64
    private val matchup = Regex("\\b(vs|v|versus|at)\\b")
    internal fun hasMatchupSeparator(normalizedTitle: String) = matchup.containsMatchIn(normalizedTitle)
    private val baseball = Regex("\\b(mlb|baseball|world series)\\b")
    private val basketball = Regex("\\b(nba|basketball)\\b")
    private val hockey = Regex("\\b(nhl|hockey)\\b")
    private val soccer = Regex("\\b(soccer|premier league|champions league|mls|uefa)\\b")
    private val football = Regex("\\b(nfl|football|super bowl)\\b")
    private val collegeFootball = Regex("\\b(ncaaf|ncaafb|cfb|fbs|fcs|(?:ncaa|college) football)\\b")
    private val collegeBaseball = Regex("\\b(ncaabsb|ncaabb|(?:ncaa|college) (?:baseball|world series))\\b")
    private val collegeBasketball = Regex("\\b(ncaam|ncaamb|ncaab|cbb|(?:ncaa|college)(?: men s| mens)? basketball)\\b")
    private val collegeWomen = Regex("\\b(ncaaw|ncaawb|wcbb|(?:women s|womens|women)(?: (?:ncaa|college))? basketball|(?:ncaa|college) women s basketball)\\b")
    private val collegeMen = Regex("\\b(ncaam|ncaamb|men s|mens)\\b")
    private val majorLeague = Regex("\\b(mlb|world series|nfl|super bowl|nba|nhl|soccer|premier league|champions league|mls|uefa)\\b")
    private fun declaredLeague(text: String): String? = when {
        collegeWomen.containsMatchIn(text) -> "NCAAW"
        collegeFootball.containsMatchIn(text) -> "NCAAF"
        collegeBaseball.containsMatchIn(text) -> "NCAABSB"
        collegeBasketball.containsMatchIn(text) -> "NCAAM"
        else -> null
    }
    private val noise = Regex("\\b(replay|rerun|classic|highlights?|pregame|postgame|pre game|post game|preview|review|news|countdown|re air|reair|encore|condensed|talk|documentary|sportscenter|nfl live|baseball tonight|quick pitch|good morning football)\\b")
    fun excludedTitle(normalized: String) = noise.containsMatchIn(normalized)
    private val sportsChannel = Regex("\\b(sports?|mlb|nfl|espn|fox|fs1|fs2|nfln|nbc|cbs|abc|tbs|tnt|yes|sny|nesn|masn|root|fanduel|bally|space city|marquee|snla|snny|sn|tsn)\\b")
    private val leagues = listOf("MLB" to baseball, "NFL" to football, "NBA" to basketball, "NHL" to hockey, "Soccer" to soccer)
    private val teamLeagues = leagues.map { it.first } + listOf("NCAAF", "NCAAM", "NCAAW", "NCAABSB")
    private data class LeagueContext(val college: String?, val sport: String?)
    private fun leagueContext(label: String): LeagueContext {
        val normalized = SportsCatalog.normalize(label)
        return LeagueContext(declaredLeague(normalized), leagues.filter { it.second.containsMatchIn(normalized) }.singleOrNull()?.first)
    }
    private val teamContexts by lazy { teamLeagues.associateWith(::leagueContext) }
    internal fun indexesLeague(league: String?) = league in teamLeagues

    fun teams(programme: Programme, channelContext: String = ""): List<SportsTeam>? {
        val title = SportsCatalog.normalize(programme.title)
        if (excludedTitle(title)) return null
        return Listing(title, SportsCatalog.normalize(programme.description)).let { it.resolve(it.league(leagueContext(channelContext))) }
    }

    /** Share text analysis across league contexts while retaining the parser's exact rules. */
    internal fun indexedTeams(programme: Programme): Map<String, Set<String>> {
        val title = SportsCatalog.normalize(programme.title)
        if (excludedTitle(title)) return emptyMap()
        val listing = Listing(title, SportsCatalog.normalize(programme.description))
        val resolved = HashMap<String?, Set<String>>()
        return buildMap {
            for ((league, context) in teamContexts) {
                val explicit = listing.league(context)
                val ids = resolved.getOrPut(explicit) { listing.resolve(explicit)?.mapTo(hashSetOf()) { it.id }.orEmpty() }
                if (ids.isNotEmpty()) put(league, ids)
            }
        }
    }

    private class Listing(private val title: String, private val description: String) {
        private val context = "$title $description"
        private val detected = leagues.filter { it.second.containsMatchIn(context) }.map { it.first }
        private val declared = declaredLeague(context)
        private val major = majorLeague.containsMatchIn(context)
        private val hasMatchup = matchup.containsMatchIn(title)
        private val descriptionMatchup = matchup.containsMatchIn(description)
        private val titleMentions by lazy(LazyThreadSafetyMode.NONE) { SportsCatalog.mentionScores(title, true) }
        private val descriptionMentions by lazy(LazyThreadSafetyMode.NONE) {
            if (noise.containsMatchIn(description)) emptyMap() else SportsCatalog.mentionScores(description, true)
        }

        fun league(channel: LeagueContext): String? {
            val fromChannel = channel.college.takeIf { college ->
            val sport = when (college) { "NCAAF" -> "NFL"; "NCAAM", "NCAAW" -> "NBA"; "NCAABSB" -> "MLB"; else -> null }
                !major && (detected.isEmpty() || sport in detected)
            }
            val college = if (declared == "NCAAM" && fromChannel == "NCAAW" && !collegeMen.containsMatchIn(context)) "NCAAW" else declared ?: fromChannel
            return college ?: detected.singleOrNull() ?: channel.sport.takeIf { detected.isEmpty() }
        }

        fun resolve(explicitLeague: String?): List<SportsTeam>? {
            if (!hasMatchup && explicitLeague == null) return null
            val mentions = titleMentions.ifEmpty {
                if (explicitLeague != null && !hasMatchup) descriptionMentions else emptyMap()
            }
            val cities = if (explicitLeague != null && !explicitLeague.startsWith("NCAA") && (hasMatchup || descriptionMatchup)) {
                SportsCatalog.cities(if (hasMatchup) title else description, explicitLeague)
            } else emptySet()
            if (mentions.size + cities.size < 2) return null
            val resolved = mentions.toMutableMap()
            cities.forEach { resolved.putIfAbsent(it, 1) }
            val candidates = if (explicitLeague == null) teamLeagues else listOf(explicitLeague)
            val ranked = candidates.mapNotNull { league -> resolved.filterKeys { it.league == league }.takeIf { it.size == 2 } }
                .sortedByDescending { it.values.sum() }
            if (ranked.isEmpty() || (ranked.size > 1 && ranked[0].values.sum() == ranked[1].values.sum())) return null
            return ranked.first().keys.sortedBy { it.id }
        }
    }
    fun find(guide: Guide, followed: Set<String>, favorites: Set<String>, now: Long, liveOnly: Boolean = false): List<SportsGame> {
        data class Pending(val teams: List<SportsTeam>, var start: Long, var end: Long, val broadcasts: MutableList<SportsBroadcast>)
        val games = LinkedHashMap<String, MutableList<Pending>>()
        val parsed = HashMap<Triple<String, String, String>, List<SportsTeam>?>()
        val channelsByKey = guide.channels.groupBy { guide.key(it) }
        var checked = 0
        var count = 0
        for ((key, channels) in channelsByKey) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val listings = if (liveOnly) listOfNotNull(guide.at(channels.first(), now)) else guide.programmes[key].orEmpty().take(72)
            for (programme in listings) {
                if (++checked > 360000) break
                if (programme.end <= now || programme.start >= now + HORIZON || programme.end <= programme.start) continue
                val channelContext = channels.first().let { "${it.group} ${it.name}" }
                val cacheKey = Triple(programme.title, programme.description, channelContext)
                val teams = if (parsed.containsKey(cacheKey)) parsed[cacheKey] else teams(programme, channelContext).also { if (parsed.size < 10000) parsed[cacheKey] = it }
                if (teams == null || (followed.isNotEmpty() && teams.none { it.id in followed })) continue
                val pair = teams.joinToString("|") { it.id }
                val matches = games.getOrPut(pair) { mutableListOf() }
                var match = matches.firstOrNull { abs(it.start - programme.start) <= 30 * 60000 && it.end > programme.start && programme.end > it.start }
                if (match == null) {
                    // Bound retained objects even when provider listings contain fabricated matchups.
                    if (count >= 1024) continue
                    match = Pending(teams, programme.start, programme.end, mutableListOf()); matches.add(match); count++
                }
                match.start = minOf(match.start, programme.start); match.end = maxOf(match.end, programme.end)
                val retained = match
                channels.forEach { channel -> if (retained.broadcasts.size < 64 && retained.broadcasts.none { it.channel.id == channel.id }) retained.broadcasts.add(SportsBroadcast(channel, programme)) }
            }
            if (checked > 360000) break
        }
        return games.values.flatten().map { game -> SportsGame(game.teams, game.start, game.end,
            game.broadcasts.sortedByDescending { it.channel.id in favorites }) }.sortedBy { it.start }.take(256)
    }
    /** Order likely game networks first, never open streams, and cap provider guide requests. */
    fun warmChannels(channels: List<Channel>, favorites: Set<String>, followed: Set<String>): List<Channel> {
        val scores = channels.mapIndexedNotNull { index, channel ->
            val label = SportsCatalog.normalize("${channel.group} ${channel.name}")
            val team = SportsCatalog.mentions(label, false)
            val score = (if (team.any { it.id in followed }) 100 else 0) + (if (team.isNotEmpty()) 20 else 0) +
                (if (channel.id in favorites) 40 else 0) + (if (sportsChannel.containsMatchIn(label)) 10 else 0)
            if (score == 0) null else Triple(channel, score, index)
        }
        val sorted = scores.sortedWith(compareByDescending<Triple<Channel, Int, Int>> { it.second }.thenBy { it.third })
            .distinctBy { it.first.guideId.ifBlank { it.first.id } }
        // Round-robin duplicate quality/country variants so one network cannot fill the scan budget.
        val variants = Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|backup|raw|us|usa|uk|ca|[0-9]+)\\b")
        val buckets = sorted.groupBy { variants.replace(SportsCatalog.normalize(it.first.name), " ").trim().replace(Regex(" +"), " ") }
            .values.map { it.toMutableList() }
        val result = mutableListOf<Channel>()
        while (result.size < MAX_WARM_CHANNELS && buckets.any { it.isNotEmpty() }) {
            for (bucket in buckets) if (bucket.isNotEmpty() && result.size < MAX_WARM_CHANNELS) result.add(bucket.removeAt(0).first)
        }
        return result
    }
}

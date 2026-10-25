package io.github.mot1us.spare

import kotlin.math.abs

data class SportsBroadcast(val channel: Channel, val programme: Programme)
data class SportsGame(val teams: List<SportsTeam>, val start: Long, val end: Long, val broadcasts: List<SportsBroadcast>) {
    val key: String get() = teams.map { it.id }.sorted().joinToString("|") + ":$start"
    val title: String get() = teams.joinToString(" vs ") { it.name }
    fun live(now: Long) = start <= now && end > now
}

/** Conservative EPG matching. A listing is evidence of a broadcast, not a guarantee that a stream is online. */
object SportsGuide {
    const val HORIZON = 48 * 3600000L
    const val MAX_WARM_CHANNELS = 160
    private val matchup = Regex("\\b(vs|v|versus|at)\\b")
    private val baseball = Regex("\\b(mlb|baseball|world series)\\b")
    private val football = Regex("\\b(nfl|football|super bowl)\\b")
    private val noise = Regex("\\b(replay|rerun|classic|highlights?|pregame|postgame|pre game|post game|preview|review|news|countdown|re air|reair|encore|condensed|talk|documentary|sportscenter|nfl live|baseball tonight|quick pitch|good morning football)\\b")
    private val sportsChannel = Regex("\\b(sports?|mlb|nfl|espn|fox|fs1|fs2|nfln|nbc|cbs|abc|tbs|tnt|yes|sny|nesn|masn|root|fanduel|bally|space city|marquee|snla|snny|sn|tsn)\\b")
    fun teams(programme: Programme): List<SportsTeam>? {
        val title = SportsCatalog.normalize(programme.title)
        if (noise.containsMatchIn(title)) return null
        val description = SportsCatalog.normalize(programme.description)
        val context = "$title $description"
        val mlb = baseball.containsMatchIn(context); val nfl = football.containsMatchIn(context)
        val explicitLeague = if (mlb && !nfl) "MLB" else if (nfl && !mlb) "NFL" else null
        val hasMatchup = matchup.containsMatchIn(title)
        if (!hasMatchup && explicitLeague == null) return null
        // Descriptions may contain long previews; only use them when the title identifies a game/league.
        val mentions = SportsCatalog.mentionScores(title, explicitLeague != null || hasMatchup).ifEmpty {
            if (explicitLeague != null && !hasMatchup && !noise.containsMatchIn(description)) SportsCatalog.mentionScores(description, true) else emptyMap()
        }
        val candidates = listOf("MLB", "NFL").mapNotNull { league ->
            if (explicitLeague != null && explicitLeague != league) null
            else mentions.filterKeys { it.league == league }.takeIf { it.size == 2 }
        }
        val ranked = candidates.sortedByDescending { it.values.sum() }
        if (ranked.isEmpty() || (ranked.size > 1 && ranked[0].values.sum() == ranked[1].values.sum())) return null
        return ranked.first().keys.sortedBy { it.id }
    }
    fun find(guide: Guide, followed: Set<String>, favorites: Set<String>, now: Long): List<SportsGame> {
        data class Pending(val teams: List<SportsTeam>, var start: Long, var end: Long, val broadcasts: MutableList<SportsBroadcast>)
        val games = LinkedHashMap<String, MutableList<Pending>>()
        val parsed = HashMap<Pair<String, String>, List<SportsTeam>?>()
        val channelsByKey = guide.channels.groupBy { guide.key(it) }
        var checked = 0
        var count = 0
        for ((key, channels) in channelsByKey) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            for (programme in guide.programmes[key].orEmpty().take(72)) {
                if (++checked > 360000) break
                if (programme.end <= now || programme.start >= now + HORIZON || programme.end <= programme.start) continue
                val cacheKey = programme.title to programme.description
                val teams = if (parsed.containsKey(cacheKey)) parsed[cacheKey] else teams(programme).also { if (parsed.size < 10000) parsed[cacheKey] = it }
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
        return scores.sortedWith(compareByDescending<Triple<Channel, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }.distinctBy { it.guideId.ifBlank { it.id } }.take(MAX_WARM_CHANNELS)
    }
}

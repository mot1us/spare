package io.github.mot1us.spare

/** A live golf, tennis, motor racing or fight broadcast found in the guide rather than on a scoreboard. */
data class SportsCoverage(val key: String, val sport: String, val title: String, val channels: List<Channel>, val start: Long, val end: Long)

/**
 * Finds events airing now that the guide clearly marks as live, for sports with no team scoreboard in Spare.
 * Conservative on purpose: the listing must say "live", must name the sport (in its title or description, or the
 * channel's name when the title names no other sport), and studio shows, replays and previews are left out.
 */
object SportsGuideCoverage {
    const val MAX_CARDS = 24
    private const val MAX_CHANNELS = 64
    private val sports = listOf(
        "Golf" to Regex("\\b(golf|pga|lpga|liv golf|dp world tour|korn ferry|ryder cup|solheim cup|presidents cup|the masters|open championship)\\b"),
        "Tennis" to Regex("\\b(tennis|atp|wta|wimbledon|roland garros|french open|australian open|davis cup|billie jean king cup|laver cup)\\b"),
        "Racing" to Regex("\\b(formula 1|formula one|f1|formula e|grand prix|nascar|indycar|motogp|supercars|imsa|wec|le mans|nhra|daytona 500|indy 500)\\b"),
        "Fights" to Regex("\\b(ufc|boxing|mma|bellator|pfl|one championship|fight night)\\b"))
    private val live = Regex("\\blive\\b")
    private val notTheEvent = Regex("\\b(live from|central|tonight|today|highlights?|show|talk|preview|countdown|pre ?race|post ?race|pre ?game|post ?game|pre ?fight|post ?fight|studio|insider|weekly|magazine|classic (?:fights?|matches|races?|bouts?)|replay|re ?air|encore|best of|review|recap|update|news|academy|school|lessons?|weigh ?ins?|press conference|embedded|documentary|qualifying show|channel)\\b")
    private val otherSports = Regex("\\b(nfl|nba|nhl|mlb|football|soccer|basketball|baseball|hockey|premier league|champions league|cricket|rugby|darts|snooker)\\b")
    private val prefix = Regex("^\\s*(?:\\(?live\\)?\\s*[:|\\-–]\\s*|live\\s+)", RegexOption.IGNORE_CASE)
    private val suffix = Regex("\\s*(?:[\\-–|]\\s*live|\\(live\\))\\s*$", RegexOption.IGNORE_CASE)

    fun sport(programme: Programme, channel: Channel): String? {
        val title = SportsCatalog.normalize(programme.title)
        if (!live.containsMatchIn(title) && !live.containsMatchIn(SportsCatalog.normalize(programme.description.take(160)))) return null
        // Not SportsGuide's team-game filter: tournaments such as The RSM Classic are live events, not reruns.
        if (notTheEvent.containsMatchIn(title)) return null
        val text = "$title ${SportsCatalog.normalize(programme.description.take(400))}"
        sports.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
        if (otherSports.containsMatchIn(title)) return null
        val name = SportsCatalog.normalize("${channel.name} ${channel.group}")
        return sports.firstOrNull { it.second.containsMatchIn(name) }?.first
    }

    fun title(programme: Programme) = programme.title.replace(prefix, "").replace(suffix, "").trim().ifBlank { programme.title.trim() }.take(120)

    fun find(guide: Guide, now: Long, usable: (Channel) -> Boolean = { true }): List<SportsCoverage> {
        data class Found(val sport: String, val title: String, val channels: MutableList<Channel>, var start: Long, var end: Long)
        val found = LinkedHashMap<String, Found>()
        for ((key, channels) in guide.channels.groupBy(guide::key)) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val channel = channels.firstOrNull { !it.media && usable(it) } ?: continue
            val programme = guide.at(channel, now) ?: continue
            // Cheap check first: tens of thousands of listings are read on a TV, and most never say "live".
            if (!programme.title.contains("live", true) && !programme.description.contains("live", true)) continue
            val sport = sport(programme, channel) ?: continue
            val title = title(programme)
            val entry = found.getOrPut("${sport.lowercase()}:${SportsCatalog.normalize(title)}") { Found(sport, title, mutableListOf(), programme.start, programme.end) }
            entry.start = minOf(entry.start, programme.start); entry.end = maxOf(entry.end, programme.end)
            channels.filter { !it.media && usable(it) }.forEach { if (entry.channels.size < MAX_CHANNELS) entry.channels.add(it) }
        }
        val order = sports.map { it.first }
        return found.map { (key, it) -> SportsCoverage("coverage:$key", it.sport, it.title, it.channels.distinctBy { channel -> channel.id }, it.start, it.end) }
            .sortedWith(compareBy<SportsCoverage> { order.indexOf(it.sport) }.thenByDescending { it.channels.size }.thenBy { it.title })
            .take(MAX_CARDS)
    }
}

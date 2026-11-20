package io.github.mot1us.spare


/**
 * Event channels ("NFL Game Pass 11: SEA @ LV", "PPV 3: NO EVENT") are renamed by the provider as games start, so a
 * channel list saved hours earlier still shows "NO EVENT". While Sports is open, Spare reads just those categories
 * again from Xtream providers (a small request) and uses the fresh names for finding a game's channel.
 */
object EventChannelNames {
    private val numbered = Regex("^[^:|]{2,60}?\\b\\d{1,3}\\s*[:|]")
    private val idle = Regex("\\bno event\\b|\\bnot live\\b|\\boff ?air\\b", RegexOption.IGNORE_CASE)
    private const val MIN_EVENT_CHANNELS = 3
    const val MAX_CATEGORIES = 24
    const val REFRESH_MS = 10 * 60000L
    /**
     * A large lineup has dozens of event categories (PPV, Game Pass, ESPN+, NCAAB…). Reading only the first eight in list
     * order left a college category's names days old, so categories that sound like the chosen leagues come first, then
     * any that sound like sports.
     */
    private val sportsCategory = Regex("\\b(ncaa\\w*|college|cbb|cfb|espn|nba|wnba|nfl|nhl|mlb|mls|epl|premier|soccer|football|basketball|hockey|baseball|sports?|game pass|league pass|center ice|extra innings|season pass|peacock|paramount|dazn|fubo|flo\\w*|b1g|big ten|sec|acc|big 12|events?|ppv|live)\\b")
    private fun categoryKey(value: String) = " ${SportsCatalog.normalize(value.replace("+", " plus "))} "
    /** Words a provider's event category for a league tends to use ("NCAAB", "College Basketball", "NHL Center Ice"). */
    fun leagueWords(league: String): List<String> = when (league) {
        "NFL" -> listOf("nfl", "game pass", "redzone"); "MLB" -> listOf("mlb", "baseball", "extra innings")
        "NBA" -> listOf("nba", "league pass"); "NHL" -> listOf("nhl", "hockey", "center ice")
        "EPL" -> listOf("epl", "premier league"); "MLS" -> listOf("mls", "season pass"); "UCL" -> listOf("ucl", "champions league", "uefa")
        "NCAAF" -> listOf("ncaaf", "ncaa", "college", "cfb"); "NCAAM", "NCAAW" -> listOf("ncaab", "ncaa", "college", "cbb", "basketball")
        "NCAABSB" -> listOf("ncaa", "college", "baseball")
        else -> emptyList()
    }

    private val slashDate = Regex("(?<![\\d/.])(\\d{1,2})/(\\d{1,2})(?:/(?:\\d{4}|\\d{2}))?(?![\\d/])")
    private val dotDate = Regex("(?<![\\d.:])(\\d{1,2})\\.(\\d{2})(?:\\.(?:\\d{4}|\\d{2}))?(?![\\d.])(?!\\s*(?:[ap]\\.?m\\b|[ecmp][sd]?t\\b|uk\\b|gmt\\b|bst\\b|cet\\b|h\\b))", RegexOption.IGNORE_CASE)
    private const val MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?"
    private val namedDate = Regex("\\b$MONTH\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b|\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+$MONTH(?![a-z])", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*([ap])\\.?m\\.?\\s*([ecmp])[sd]?t\\b", RegexOption.IGNORE_CASE)
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val usZones = mapOf('e' to "America/New_York", 'c' to "America/Chicago", 'm' to "America/Denver", 'p' to "America/Los_Angeles")

    /**
     * True when a channel name declares a day or a start time that is not this game's. Event slots keep a finished game's
     * name for days ("Live 7: Richmond vs. Towson @ Dec 5 1:00PM ET", "College 12: … | 12.05 1:00 PM"), and a
     * rematch or the other school's women's game must not inherit it. "12/5" reads as month/day, "12.05" either way round,
     * and a start time counts only with a US zone and only when it is more than three hours from the game's.
     */
    fun declaresOtherTime(name: String, start: Long): Boolean {
        val instant = java.time.Instant.ofEpochMilli(start)
        val days = listOf(java.time.ZoneId.systemDefault(), java.time.ZoneId.of("America/New_York"))
            .map { instant.atZone(it).toLocalDate().let { date -> date.monthValue to date.dayOfMonth } }.toSet()
        fun valid(day: Pair<Int, Int>) = day.first in 1..12 && day.second in 1..31
        val declared = mutableListOf<Set<Pair<Int, Int>>>()
        slashDate.findAll(name).forEach { m -> (m.groupValues[1].toInt() to m.groupValues[2].toInt()).takeIf(::valid)?.let { declared += setOf(it) } }
        dotDate.findAll(name).forEach { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            setOf(a to b, b to a).filter(::valid).toSet().takeIf { it.isNotEmpty() }?.let { declared += it }
        }
        namedDate.findAll(name).forEach { m ->
            val month = (m.groupValues[1].ifEmpty { m.groupValues[4] }).take(3).lowercase()
            val day = m.groupValues[2].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return@forEach
            (monthNames.indexOf(month) + 1 to day).takeIf(::valid)?.let { declared += setOf(it) }
        }
        if (declared.isNotEmpty() && declared.none { options -> options.any { it in days } }) return true
        val time = clock.find(name) ?: return false
        val hour = time.groupValues[1].toInt(); val minute = time.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 1..12 || minute !in 0..59) return false
        val minutes = (hour % 12 + if (time.groupValues[3].equals("p", true)) 12 else 0) * 60 + minute
        val local = instant.atZone(java.time.ZoneId.of(usZones.getValue(time.groupValues[4].lowercase()[0])))
        val difference = kotlin.math.abs(minutes - (local.hour * 60 + local.minute)).let { minOf(it, 1440 - it) }
        return difference > 180
    }

    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile var version = 0; private set

    /** The freshest known name of a channel, for matching it to a game. */
    fun name(channel: Channel): String = names[channel.id] ?: channel.name
    fun renamed(channel: Channel) = channel.id in names
    /** Renamed channels of [channels]: their saved names cannot have put them in any name index. */
    fun renamedIn(channels: List<Channel>): List<Channel> = if (names.isEmpty()) emptyList() else channels.filter { it.id in names }

    fun looksLikeEvent(name: String) = idle.containsMatchIn(name) || numbered.containsMatchIn(name)

    /**
     * Categories holding several numbered or "no event" channels: the provider's event slots. [preferred] names the
     * chosen leagues and today's broadcasters; categories naming one of them are read first.
     */
    fun eventGroups(channels: List<Channel>, preferred: Collection<String> = emptyList()): Set<String> {
        val words = preferred.map(::categoryKey).filter { it.trim().length >= 2 }.distinct()
        fun relevance(group: String): Int { val key = categoryKey(group); return if (words.any { it in key }) 2 else if (sportsCategory.containsMatchIn(key)) 1 else 0 }
        return channels.asSequence().filter { !it.media && looksLikeEvent(it.name) }
            .groupingBy { it.group }.eachCount().filterValues { it >= MIN_EVENT_CHANNELS }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { relevance(it.key) }.thenByDescending { it.value })
            .take(MAX_CATEGORIES).mapTo(linkedSetOf()) { it.key }
    }

    /** Fresh names by native channel ID ("xc:123") for the given category names of one Xtream playlist. */
    fun fetch(source: Source, groups: Set<String>, get: (String) -> String = { url -> Network.get(url, source.allowHttp, 4 * 1024 * 1024, timeoutMs = 8000).toString(Charsets.UTF_8) }): Map<String, String> {
        if (source.kind != "xtream" || groups.isEmpty()) return emptyMap()
        val categories = Importers.xtreamArray(get(Importers.endpoint(source, "player_api.php", "get_live_categories")))
        val wanted = (0 until minOf(categories.length(), 20000)).mapNotNull { i -> categories.optJSONObject(i) }
            .filter { it.optString("category_name").take(128) in groups }.map { it.optString("category_id") }
            .filter { it.matches(Regex("[0-9]{1,12}")) }.take(MAX_CATEGORIES)
        val fresh = HashMap<String, String>()
        for (id in wanted) {
            if (Thread.currentThread().isInterrupted) break
            val streams = Importers.xtreamArray(get(Importers.endpoint(source, "player_api.php", "get_live_streams") + "&category_id=$id"))
            for (i in 0 until minOf(streams.length(), 5000)) {
                val stream = streams.optJSONObject(i) ?: continue
                val streamId = stream.optString("stream_id").takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: continue
                stream.optString("name").trim().take(256).takeIf { it.isNotBlank() }?.let { fresh["xc:$streamId"] = it }
            }
        }
        return fresh
    }

    /**
     * Record fresh names for the guide's channels (matched by playlist and native ID). Returns true when any name the
     * Sports matcher sees changed.
     */
    fun update(channels: List<Channel>, playlist: (Channel) -> String?, fresh: Map<String, Map<String, String>>): Boolean {
        val next = HashMap(names)
        var changed = false
        for (channel in channels) {
            val name = fresh[playlist(channel)]?.get(channel.nativeId) ?: continue
            if (name != channel.name || channel.id in next) {
                if (next[channel.id] != name) changed = true
                if (name == channel.name) next.remove(channel.id) else next[channel.id] = name
            }
        }
        if (changed) { names = next; version++ }
        return changed
    }

    fun clear() { if (names.isNotEmpty()) { names = emptyMap(); version++ } }
}

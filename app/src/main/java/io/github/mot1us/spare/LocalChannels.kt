package io.github.mot1us.spare

/** Offline station identities plus explicit provider location labels. Never opens a stream. */
class LocalChannels(text: String) {
    private data class Station(val call: String, val city: String, val state: String, val name: String, val displayCity: String, val displayState: String, val displayName: String)
    private val stations = text.lineSequence().take(10000).mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 4 && p.all { it.isNotBlank() && it.length <= 80 })
            Station(p[0].trim(), key(p[1]), key(p[2]), key(p[3]), p[1].trim(), p[2].trim().uppercase(java.util.Locale.ROOT), p[3].trim()) else null
    }.toList()
    private data class Location(val label: String, val aliases: List<String>) { val labelKey = key(label) }
    private val locations = (
        stations.distinctBy { it.state }.map { Location(it.displayName, listOf(it.name, it.state)) } +
            stations.distinctBy { it.city to it.state }.map { Location("${it.displayCity}, ${it.displayState}", listOf(it.city, "${it.city} ${it.state}", "${it.city} ${it.name}")) }
        ).distinctBy { it.labelKey }.sortedBy { it.labelKey }

    /** Bounded typeahead from the bundled reference; unknown places can still match provider labels. */
    fun locations(query: String, limit: Int = 6): List<String> {
        val normalized = key(query.take(80)); if (normalized.isBlank() || limit <= 0) return emptyList()
        return locations.asSequence().mapNotNull { location ->
            val rank = when {
                location.aliases.any { it == normalized } -> 0
                location.aliases.any { it.startsWith(normalized) } -> 1
                else -> return@mapNotNull null
            }
            rank to location.label
        }.sortedBy { it.first }.take(limit.coerceAtMost(20)).map { it.second }.toList()
    }
    fun find(channels: List<Channel>, location: String): Set<String> {
        val query = key(location.take(80)); if (query.isBlank()) return emptySet()
        val selected = stations.filter { query in setOf(it.city, it.state, it.name, "${it.city} ${it.state}", "${it.city} ${it.name}") }
        val calls = selected.map { it.call.lowercase(java.util.Locale.ROOT) }.distinct()
        val callPattern = calls.takeIf { it.isNotEmpty() }?.joinToString("|", "(?<![a-z])(?:", ")(?=(?:dt|tv|hd)?(?:\\d|\\b))") { Regex.escape(it) }?.let(::Regex)
        val statewide = selected.any { query == it.state || query == it.name }
        val places = (selected.flatMap { if (statewide) listOf(it.city, it.name) else listOf(it.city) } + query.takeIf { it.length > 2 }).filterNotNull().filter { it.length > 2 }.distinct()
        val sports = Regex("\\b(nfl|nba|mlb|nhl|ppv)\\b")
        val affiliate = Regex("\\b(local|locals|abc|nbc|cbs|fox|pbs|cw|telemundo|univision)\\b")
        val result = mutableSetOf<String>()
        for (channel in channels) {
            if (Thread.currentThread().isInterrupted) return emptySet()
            if (channel.media) continue
            val name = key(channel.name); val group = key(channel.group); val guide = key(channel.guideId.substringBefore('.'))
            val identity = "$name $guide"
            if (callPattern?.containsMatchIn(identity) == true ||
                affiliate.containsMatchIn("$name $group") && !sports.containsMatchIn(group) && places.any { " $it " in " $name $group " }) result.add(channel.id)
        }
        return result
    }
    companion object {
        private val punctuation = Regex("[^\\p{L}\\p{N}]+")
        private fun key(value: String) = punctuation.replace(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT), " ").trim()
    }
}

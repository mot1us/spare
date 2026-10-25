package io.github.mot1us.spare

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LiveEvent(val id: String, val teams: Set<String>, val start: Long, val state: String = "in", val networks: List<String> = emptyList(), val scores: Map<String, String> = emptyMap(), val logos: Map<String, String> = emptyMap(), val status: String = "", val scoreboard: Boolean = true)
data class LiveSportsSnapshot(val events: List<LiveEvent>, val checked: Long, val leagues: Set<String>) {
    fun fresh(now: Long) = now - checked in 0..120000L
    fun confirm(games: List<SportsGame>, now: Long): List<SportsGame> {
        if (!fresh(now)) return emptyList()
        return games.filter { game ->
            game.teams.first().league in leagues && events.any { event ->
                event.state == "in" && event.teams == game.teams.map { it.id }.toSet() && kotlin.math.abs(game.start - event.start) < 12 * 3600000L
            }
        }
    }
    companion object { val EMPTY = LiveSportsSnapshot(emptyList(), 0, emptySet()) }
}

/** Public ESPN scoreboards provide event state, never stream URLs. No playlist data is sent. */
object LiveSports {
    fun load(now: Long = System.currentTimeMillis(), get: (String) -> String = { Network.get(it, limit = 2 * 1024 * 1024, timeoutMs = 4000).toString(Charsets.UTF_8) }): LiveSportsSnapshot {
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("America/New_York")).toLocalDate()
        val events = mutableListOf<LiveEvent>(); val leagues = mutableSetOf<String>()
        listOf("NFL" to "football/nfl", "MLB" to "baseball/mlb").forEach { (league, path) ->
            if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
            // Single dates are supported by both scoreboards; ranges are rejected by some leagues.
            // Include yesterday for games still in progress after midnight Eastern.
            listOf(date, date.minusDays(1)).forEach { day ->
                if (Thread.currentThread().isInterrupted) return LiveSportsSnapshot.EMPTY
                runCatching { parse(get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?dates=${day.format(DateTimeFormatter.BASIC_ISO_DATE)}&limit=100"), league, now) }
                    .onSuccess { events.addAll(it); leagues.add(league) }
            }
        }
        return LiveSportsSnapshot(events.distinctBy { it.id }, now, leagues)
    }
    fun parse(text: String, league: String, now: Long, onlyLive: Boolean = true, includeCompleted: Boolean = false): List<LiveEvent> {
        require(league in setOf("NFL", "MLB"))
        val entries = JSONObject(text).getJSONArray("events"); require(entries.length() <= 500)
        val result = mutableListOf<LiveEvent>()
        for (index in 0 until entries.length()) {
            val event = entries.optJSONObject(index) ?: continue
            val competitions = event.optJSONArray("competitions") ?: continue
            for (match in 0 until minOf(competitions.length(), 8)) {
                val competition = competitions.optJSONObject(match) ?: continue
                val status = (competition.optJSONObject("status") ?: event.optJSONObject("status"))?.optJSONObject("type") ?: continue
                val state = status.optString("name")
                val live = status.optString("state") == "in" && !status.optBoolean("completed") && state in setOf("STATUS_IN_PROGRESS", "STATUS_HALFTIME", "STATUS_END_PERIOD", "STATUS_OVERTIME", "STATUS_INTERMISSION")
                val upcoming = !onlyLive && status.optString("state") == "pre" && state == "STATUS_SCHEDULED" && !status.optBoolean("completed")
                if (!live && !upcoming && !includeCompleted) continue
                val date = competition.optString("date").ifBlank { event.optString("date") }
                val start = runCatching { java.time.OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull() ?: continue
                // A malformed or stale schedule cannot turn a future/old listing into a live event.
                if (live && (start > now + 60000L || start < now - 18 * 3600000L)) continue
                if (upcoming && !includeCompleted && (start < now - 300000L || start > now + 370 * 86400000L)) continue
                if (kotlin.math.abs(start - now) > 370 * 86400000L) continue
                val competitors = competition.optJSONArray("competitors") ?: continue
                if (competitors.length() != 2) continue
                val teams = (0 until competitors.length()).mapNotNull { i ->
                    val team = competitors.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
                    resolve(league, team.optString("abbreviation"), team.optString("displayName"))
                }.toSet()
                if (teams.size == 2) {
                    val networks = mutableListOf<String>()
                    competition.optJSONArray("broadcasts")?.let { array -> for (i in 0 until minOf(array.length(), 20)) {
                        val names = array.optJSONObject(i)?.optJSONArray("names") ?: continue
                        for (n in 0 until minOf(names.length(), 20)) networks.add(names.optString(n).take(80))
                    } }
                    competition.optJSONArray("geoBroadcasts")?.let { array -> for (i in 0 until minOf(array.length(), 20)) array.optJSONObject(i)?.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotBlank() }?.let { networks.add(it.take(80)) } }
                    val scores = linkedMapOf<String, String>(); val logos = linkedMapOf<String, String>()
                    for (i in 0 until competitors.length()) {
                        val side = competitors.getJSONObject(i); val team = side.getJSONObject("team")
                        val id = resolve(league, team.optString("abbreviation"), team.optString("displayName")) ?: continue
                        val score = side.optJSONObject("score")?.optString("displayValue") ?: side.optString("score")
                        if (score.matches(Regex("[0-9]{1,3}"))) scores[id] = score
                        val logo = team.optString("logo").ifBlank { team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() }
                        if (logo.length <= 2048 && Network.allowed(logo, false)) logos[id] = logo
                    }
                    result.add(LiveEvent(event.optString("id").take(80), teams, start, when { live -> "in"; status.optBoolean("completed") || status.optString("state") == "post" -> "post"; upcoming -> "pre"; else -> "hold" }, networks.distinct(), scores, logos, status.optString("shortDetail").ifBlank { status.optString("description") }.take(100)))
                }
            }
        }
        return result.distinctBy { it.id }
    }
    private fun resolve(league: String, code: String, name: String): String? {
        val aliases = when (league to code.uppercase(java.util.Locale.ROOT)) { "NFL" to "WSH", "NFL" to "WAS" -> "WSH"; "NFL" to "JAC" -> "JAX"; "MLB" to "OAK" -> "ATH"; else -> code.uppercase(java.util.Locale.ROOT) }
        return SportsCatalog.byId["${league.lowercase(java.util.Locale.ROOT)}:$aliases"]?.id ?: SportsCatalog.teams.firstOrNull { it.league == league && SportsCatalog.normalize(it.name) == SportsCatalog.normalize(name) }?.id
    }
}

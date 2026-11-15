package io.github.mot1us.spare

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks Spare's team names against ESPN's complete team lists, captured by scripts/sports_team_directory.py.
 * Skipped unless SPARE_TEAM_DIRECTORY names a capture, so normal builds never need the network.
 * Each league's teams are paired with the next team in the list and written as guide titles in four common shapes.
 * Prints a report (and writes it to SPARE_TEAM_DIRECTORY_REPORT when set); it measures, it does not fail the build.
 */
class SportsTeamDirectoryAuditTest {
    private val prefixes = mapOf("NFL" to "NFL", "MLB" to "MLB", "NBA" to "NBA", "NHL" to "NHL", "EPL" to "Premier League",
        "MLS" to "MLS", "UCL" to "Champions League", "NCAAF" to "College Football", "NCAAM" to "College Basketball",
        "NCAAW" to "Women's College Basketball", "NCAABSB" to "College Baseball")
    private data class Team(val id: String, val code: String, val full: String, val short: String)

    @Test fun teamDirectoryAudit() {
        val path = System.getenv("SPARE_TEAM_DIRECTORY")
        assumeTrue("Set SPARE_TEAM_DIRECTORY to run the team audit", !path.isNullOrBlank() && File(path).isFile)
        val directory = JSONObject(File(path!!).readText())
        val report = StringBuilder()
        var totalTeams = 0; var totalUnknown = 0; val totals = IntArray(4); val misses = IntArray(4); val wrong = IntArray(4)
        for (league in prefixes.keys) {
            val entries = directory.optJSONArray(league) ?: continue
            val unknown = mutableListOf<String>(); val teams = mutableListOf<Team>()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val id = LiveSports.resolve(league, e.optString("abbreviation"), e.optString("displayName"), e.optString("id"))
                if (id == null) unknown += "${e.optString("abbreviation")} ${e.optString("displayName")}"
                else teams += Team(id, e.optString("abbreviation"), e.optString("displayName"), e.optString("shortDisplayName"))
            }
            totalTeams += entries.length(); totalUnknown += unknown.size
            report.append("\n== $league: ${entries.length()} ESPN teams, ${unknown.size} unknown to Spare (their games never appear)\n")
            unknown.take(40).forEach { report.append("  unknown: $it\n") }
            if (unknown.size > 40) report.append("  … ${unknown.size - 40} more\n")
            val prefix = prefixes.getValue(league)
            teams.zipWithNext().forEach { (a, b) ->
                val shapes = listOf(
                    "$prefix: ${a.full} vs ${b.full}",
                    "${a.full} at ${b.full}",
                    "$prefix: ${a.short} vs ${b.short}",
                    "$prefix: ${a.code} @ ${b.code}")
                shapes.forEachIndexed { shape, title ->
                    if (a.full == b.full || a.short == b.short && shape == 2 || a.code == b.code && shape == 3) return@forEachIndexed
                    totals[shape]++
                    // The same per-league lookup Sports uses when it matches a game to the guide.
                    val found = SportsGuide.indexedTeams(Programme("audit", title, 0, 3600000))[if (league in setOf("EPL", "MLS", "UCL")) "Soccer" else league]
                    when {
                        found == setOf(a.id, b.id) -> Unit
                        found == null || found.isEmpty() -> { misses[shape]++; if (misses[shape] <= 400) report.append("  miss[$shape] $league: $title\n") }
                        else -> { wrong[shape]++; report.append("  WRONG[$shape] $league: $title -> $found\n") }
                    }
                }
            }
        }
        val names = listOf("league + full names", "full names, no league", "league + short names", "league + codes")
        val summary = buildString {
            append("Teams: $totalTeams from ESPN, $totalUnknown unknown to Spare\n")
            names.forEachIndexed { i, name -> append("$name: ${totals[i] - misses[i] - wrong[i]}/${totals[i]} recognised, ${misses[i]} missed, ${wrong[i]} wrong\n") }
        }
        println("TEAM AUDIT\n$summary$report")
        System.getenv("SPARE_TEAM_DIRECTORY_REPORT")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(summary + report) }
    }
}

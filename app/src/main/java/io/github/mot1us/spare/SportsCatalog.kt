package io.github.mot1us.spare

import java.text.Normalizer
import java.util.Locale

data class SportsTeam(val id: String, val league: String, val name: String, val aliases: List<String>)

/** Local names only: no schedule service, account, tracking, or stream probing. */
object SportsCatalog {
    private fun team(league: String, code: String, name: String, vararg aliases: String) =
        SportsTeam("${league.lowercase(Locale.ROOT)}:$code", league, name, (listOf(name, code) + aliases).distinct())
    val teams = listOf(
        team("MLB", "ARI", "Arizona Diamondbacks", "Diamondbacks", "Dbacks", "D backs"),
        team("MLB", "ATH", "Athletics", "Oakland Athletics", "Oakland A's", "Athletics"),
        team("MLB", "ATL", "Atlanta Braves", "Braves"),
        team("MLB", "BAL", "Baltimore Orioles", "Orioles"),
        team("MLB", "BOS", "Boston Red Sox", "Red Sox"),
        team("MLB", "CHC", "Chicago Cubs", "Cubs"),
        team("MLB", "CWS", "Chicago White Sox", "White Sox", "CHW"),
        team("MLB", "CIN", "Cincinnati Reds", "Reds"),
        team("MLB", "CLE", "Cleveland Guardians", "Guardians", "Cleveland Indians"),
        team("MLB", "COL", "Colorado Rockies", "Rockies"),
        team("MLB", "DET", "Detroit Tigers", "Tigers"),
        team("MLB", "HOU", "Houston Astros", "Astros"),
        team("MLB", "KC", "Kansas City Royals", "Royals", "KCR"),
        team("MLB", "LAA", "Los Angeles Angels", "Angels", "LA Angels"),
        team("MLB", "LAD", "Los Angeles Dodgers", "Dodgers", "LA Dodgers"),
        team("MLB", "MIA", "Miami Marlins", "Marlins"),
        team("MLB", "MIL", "Milwaukee Brewers", "Brewers"),
        team("MLB", "MIN", "Minnesota Twins", "Twins"),
        team("MLB", "NYM", "New York Mets", "Mets", "NY Mets"),
        team("MLB", "NYY", "New York Yankees", "Yankees", "NY Yankees"),
        team("MLB", "PHI", "Philadelphia Phillies", "Phillies"),
        team("MLB", "PIT", "Pittsburgh Pirates", "Pirates"),
        team("MLB", "SD", "San Diego Padres", "Padres", "SDP"),
        team("MLB", "SF", "San Francisco Giants", "Giants", "SF Giants", "SFG"),
        team("MLB", "SEA", "Seattle Mariners", "Mariners"),
        team("MLB", "STL", "St. Louis Cardinals", "St Louis Cardinals", "Cardinals"),
        team("MLB", "TB", "Tampa Bay Rays", "Rays", "TBR"),
        team("MLB", "TEX", "Texas Rangers", "Rangers"),
        team("MLB", "TOR", "Toronto Blue Jays", "Blue Jays"),
        team("MLB", "WSH", "Washington Nationals", "Nationals", "WSN"),
        team("NFL", "ARI", "Arizona Cardinals", "Cardinals"),
        team("NFL", "ATL", "Atlanta Falcons", "Falcons"),
        team("NFL", "BAL", "Baltimore Ravens", "Ravens"),
        team("NFL", "BUF", "Buffalo Bills", "Bills"),
        team("NFL", "CAR", "Carolina Panthers", "Panthers"),
        team("NFL", "CHI", "Chicago Bears", "Bears"),
        team("NFL", "CIN", "Cincinnati Bengals", "Bengals"),
        team("NFL", "CLE", "Cleveland Browns", "Browns"),
        team("NFL", "DAL", "Dallas Cowboys", "Cowboys"),
        team("NFL", "DEN", "Denver Broncos", "Broncos"),
        team("NFL", "DET", "Detroit Lions", "Lions"),
        team("NFL", "GB", "Green Bay Packers", "Packers", "GBP"),
        team("NFL", "HOU", "Houston Texans", "Texans"),
        team("NFL", "IND", "Indianapolis Colts", "Colts"),
        team("NFL", "JAX", "Jacksonville Jaguars", "Jaguars", "JAC"),
        team("NFL", "KC", "Kansas City Chiefs", "Chiefs", "KCC"),
        team("NFL", "LV", "Las Vegas Raiders", "Raiders", "Oakland Raiders", "LVR"),
        team("NFL", "LAC", "Los Angeles Chargers", "Chargers", "LA Chargers"),
        team("NFL", "LAR", "Los Angeles Rams", "Rams", "LA Rams"),
        team("NFL", "MIA", "Miami Dolphins", "Dolphins"),
        team("NFL", "MIN", "Minnesota Vikings", "Vikings"),
        team("NFL", "NE", "New England Patriots", "Patriots", "NEP"),
        team("NFL", "NO", "New Orleans Saints", "Saints", "NOS"),
        team("NFL", "NYG", "New York Giants", "Giants", "NY Giants"),
        team("NFL", "NYJ", "New York Jets", "Jets", "NY Jets"),
        team("NFL", "PHI", "Philadelphia Eagles", "Eagles"),
        team("NFL", "PIT", "Pittsburgh Steelers", "Steelers"),
        team("NFL", "SF", "San Francisco 49ers", "49ers", "Niners", "SFO"),
        team("NFL", "SEA", "Seattle Seahawks", "Seahawks"),
        team("NFL", "TB", "Tampa Bay Buccaneers", "Buccaneers", "Bucs", "TBB"),
        team("NFL", "TEN", "Tennessee Titans", "Titans"),
        team("NFL", "WSH", "Washington Commanders", "Commanders", "Washington Football Team", "WAS"),
    )
    val byId = teams.associateBy { it.id }
    private val punctuation = Regex("[^a-z0-9]+")
    fun normalize(value: String): String = punctuation.replace(Normalizer.normalize(value.take(4096), Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).replace("@", " at ").replace(Regex("\\p{M}+"), ""), " ").trim()
    private data class Alias(val team: SportsTeam, val phrase: String, val short: Boolean)
    private val index = teams.flatMap { t -> t.aliases.map { alias ->
        val phrase = normalize(alias); Alias(t, " $phrase ", phrase.length <= 3 && !phrase.contains(' '))
    } }.groupBy { it.phrase.trim().substringBefore(' ') }
    fun mentions(normalized: String, allowShort: Boolean): Set<SportsTeam> = mentionScores(normalized, allowShort).keys
    fun mentionScores(normalized: String, allowShort: Boolean): Map<SportsTeam, Int> {
        val padded = " $normalized "
        val scores = mutableMapOf<SportsTeam, Int>()
        normalized.split(' ').distinct().flatMap { index[it].orEmpty() }.forEach { alias ->
            if ((allowShort || !alias.short) && padded.contains(alias.phrase)) {
                val score = if (alias.short) 1 else if (alias.phrase.trim().contains(' ')) 3 else 2
                scores[alias.team] = maxOf(scores[alias.team] ?: 0, score)
            }
        }
        return scores
    }
}

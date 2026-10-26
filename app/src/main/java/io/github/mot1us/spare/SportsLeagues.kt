package io.github.mot1us.spare

data class SportsLeague(val id: String, val name: String, val path: String)
object SportsLeagues {
    val all = listOf(SportsLeague("NFL", "NFL", "football/nfl"), SportsLeague("MLB", "MLB", "baseball/mlb"),
        SportsLeague("NBA", "NBA", "basketball/nba"), SportsLeague("NHL", "NHL", "hockey/nhl"),
        SportsLeague("EPL", "Premier League", "soccer/eng.1"), SportsLeague("MLS", "MLS", "soccer/usa.1"),
        SportsLeague("UCL", "Champions League", "soccer/uefa.champions"))
    val byId = all.associateBy { it.id }
    fun label(id: String) = byId[id]?.name ?: id
}

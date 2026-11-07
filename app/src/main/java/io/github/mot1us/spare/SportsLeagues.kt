package io.github.mot1us.spare

data class SportsLeague(val id: String, val name: String, val path: String, val groups: List<Int> = emptyList()) {
    val college get() = "college-" in path
    fun scoreboards(day: java.time.LocalDate? = null): List<String> {
        val query = day?.let { "dates=${it.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}&" }.orEmpty() + "limit=${if (college) 500 else 100}"
        val base = "https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?$query"
        return if (groups.isEmpty()) listOf(base) else groups.map { "$base&groups=$it" }
    }
}
object SportsLeagues {
    val all = listOf(SportsLeague("NFL", "NFL", "football/nfl"), SportsLeague("MLB", "MLB", "baseball/mlb"),
        SportsLeague("NBA", "NBA", "basketball/nba"), SportsLeague("NHL", "NHL", "hockey/nhl"),
        SportsLeague("EPL", "Premier League", "soccer/eng.1"), SportsLeague("MLS", "MLS", "soccer/usa.1"),
        SportsLeague("UCL", "Champions League", "soccer/uefa.champions"),
        SportsLeague("NCAAF", "NCAA Football", "football/college-football", listOf(80, 81)),
        SportsLeague("NCAAM", "NCAA Men's Basketball", "basketball/mens-college-basketball", listOf(50)),
        SportsLeague("NCAAW", "NCAA Women's Basketball", "basketball/womens-college-basketball", listOf(50)),
        SportsLeague("NCAABSB", "NCAA Baseball", "baseball/college-baseball"))
    val byId = all.associateBy { it.id }
    fun label(id: String) = byId[id]?.name ?: id
}

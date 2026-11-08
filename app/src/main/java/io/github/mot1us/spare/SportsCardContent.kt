package io.github.mot1us.spare

/** Small immutable text model, built only for the cards actually on screen. */
internal data class SportsCardTeamLine(val name: String, val record: String?, val score: String?, val possession: Boolean, val leading: Boolean) {
    val label get() = name + record?.let { " ($it)" }.orEmpty()
}
internal data class SportsCardScoreboard(val message: String?, val values: List<String>)

internal object SportsCardContent {
    fun scoreboard(display: SportsGame, hidden: Boolean, startTime: String): SportsCardScoreboard {
        val message = when {
            hidden -> "Score hidden"
            display.status == SportsScoreDelay.WAITING -> "Score delayed"
            display.state == "pre" -> startTime
            display.scores.isEmpty() -> "Score unavailable"
            else -> null
        }
        return SportsCardScoreboard(message, if (message != null) emptyList() else display.teams.map { display.scores[it.id] ?: "–" })
    }
    fun teams(display: SportsGame, hidden: Boolean): List<SportsCardTeamLine> {
        val showScore = !hidden && display.state != "pre"
        val scores = if (showScore) display.teams.map { display.scores[it.id]?.toIntOrNull() } else emptyList()
        val leader = scores.takeIf { it.size == 2 && it.all { value -> value != null } && it[0] != it[1] }?.maxOf { it!! }
        return display.teams.map { team ->
            val score = display.scores[team.id].takeIf { showScore }
            SportsCardTeamLine(team.name, display.records[team.id].takeUnless { hidden }, score,
                !hidden && display.state == "in" && display.possession == team.id,
                leader != null && score?.toIntOrNull() == leader)
        }
    }
    fun situation(display: SportsGame, hidden: Boolean): String =
        if (hidden || display.status == SportsScoreDelay.WAITING || display.state != "in") "" else display.situation
}

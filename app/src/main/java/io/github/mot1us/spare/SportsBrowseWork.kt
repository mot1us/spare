package io.github.mot1us.spare

/** Keep a large college slate cheap: warm the viewport, not every fixture on the schedule. */
object SportsBrowseWork {
    fun targets(games: List<SportsGame>, visible: Set<String>, focused: SportsGame?, followed: Set<String>, limit: Int = 8): List<SportsGame> {
        if (limit <= 0 || games.isEmpty()) return emptyList()
        val result = linkedMapOf<String, SportsGame>()
        fun add(game: SportsGame) { if (result.size < limit) result.putIfAbsent(game.key, game) }
        focused?.let { target -> games.firstOrNull { it.key == target.key }?.let(::add) }
        games.asSequence().filter { it.key in visible }.forEach(::add)
        games.asSequence().filter { game -> game.teams.any { it.id in followed } }.take(2).forEach(::add)
        if (result.isEmpty()) games.take(minOf(3, limit)).forEach(::add)
        return result.values.toList()
    }
}

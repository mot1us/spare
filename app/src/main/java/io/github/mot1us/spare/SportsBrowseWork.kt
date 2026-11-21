package io.github.mot1us.spare

/**
 * The order in which live cards look for their streams. The card under the cursor and the cards on screen go first, then
 * two games of followed teams; every other live game follows in the order shown, so a large college slate fills in
 * eventually without being searched all at once.
 */
object SportsBrowseWork {
    /** Far above any real "Live now" slate; the stream cache holds 128 games. */
    const val MAX_GAMES = 96

    data class Plan(val near: List<SportsGame>, val later: List<SportsGame>) {
        val all get() = near + later
    }

    fun plan(games: List<SportsGame>, visible: Set<String>, focused: SportsGame?, followed: Set<String>, limit: Int = MAX_GAMES): Plan {
        if (limit <= 0 || games.isEmpty()) return Plan(emptyList(), emptyList())
        val near = linkedMapOf<String, SportsGame>()
        fun add(game: SportsGame) { if (near.size < limit) near.putIfAbsent(game.key, game) }
        focused?.let { target -> games.firstOrNull { it.key == target.key }?.let(::add) }
        games.asSequence().filter { it.key in visible }.forEach(::add)
        games.asSequence().filter { game -> game.teams.any { it.id in followed } }.take(2).forEach(::add)
        // Before the grid has laid out its cards, the top row stands in for the screen.
        if (near.isEmpty()) games.take(minOf(3, limit)).forEach(::add)
        val later = games.asSequence().filter { it.key !in near }.take(limit - near.size).toList()
        return Plan(near.values.toList(), later)
    }
}

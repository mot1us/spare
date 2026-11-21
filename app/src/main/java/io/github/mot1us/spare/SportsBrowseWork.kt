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

    /** What kind of look a game is due for, which also sets the pause before the next one. */
    enum class Look { FIRST_NEAR, FIRST, UPGRADE, AGAIN }
    data class Step(val game: SportsGame, val look: Look)

    /**
     * The games to look up next, in order. Every live game gets its first look before any game is looked at again: on a
     * big college slate, the cards on screen were re-checked every 30 seconds and the rest stayed on "Select to find
     * stream" until the cursor reached them. [looked] covers a look made in the background before Sports opened.
     */
    fun queue(plan: Plan, looked: (SportsGame) -> Boolean, upgrade: (SportsGame) -> Boolean, due: (SportsGame, Boolean) -> Boolean, lastLook: (SportsGame) -> Long): List<Step> {
        val near = plan.near.mapTo(hashSetOf()) { it.key }
        val first = plan.near.filterNot(looked).map { Step(it, Look.FIRST_NEAR) } + plan.later.filterNot(looked).map { Step(it, Look.FIRST) }
        val upgrades = plan.near.filter { looked(it) && upgrade(it) }.map { Step(it, Look.UPGRADE) }
        val again = plan.all.filter { looked(it) && !(it.key in near && upgrade(it)) && due(it, it.key in near) }.sortedBy(lastLook).map { Step(it, Look.AGAIN) }
        return first + upgrades + again
    }

    /**
     * Cards keep their places while the viewer's cursor rests on the grid. When a live game ended, every card after it
     * moved back one place and the highlighted card jumped up a row and across. The viewer's card and the cards before it
     * stay where they are (a game that ended shows its final score from [latest]); the cards after it follow [next].
     * The full order returns once the cursor leaves the grid.
     */
    fun hold(current: List<SportsGame>, next: List<SportsGame>, anchor: String, latest: Map<String, SportsGame> = emptyMap()): List<SportsGame> {
        val at = current.indexOfFirst { it.key == anchor }
        if (at < 0) return next
        val fresh = next.associateBy { it.key }
        val kept = current.take(at + 1).mapNotNull { fresh[it.key] ?: latest[it.key] }
        // A card before the cursor that the scoreboard no longer lists at all has nothing truthful to show.
        if (kept.size != at + 1) return next
        val keys = kept.mapTo(hashSetOf()) { it.key }
        return kept + next.filter { it.key !in keys }
    }
}

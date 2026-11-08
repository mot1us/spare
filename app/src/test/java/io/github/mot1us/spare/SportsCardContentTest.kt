package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsCardContentTest {
    private val teams = listOf(SportsTeam("nba:POR", "NBA", "Portland Trail Blazers", emptyList()), SportsTeam("nba:LAL", "NBA", "Los Angeles Lakers", emptyList()))
    private val game = SportsGame(teams, 1, 2, emptyList(), "game", "in", scores = mapOf("nba:POR" to "101", "nba:LAL" to "98"), records = mapOf("nba:POR" to "2-4", "nba:LAL" to "4-2"), situation = "Portland possession", possession = "nba:POR")

    @Test fun recordsStayBesideTheTeamNameAndScoresHaveTheirOwnRole() {
        val lines = SportsCardContent.teams(game, false)
        assertEquals("Portland Trail Blazers (2-4)", lines[0].label)
        assertFalse(lines[0].label.contains('\n')); assertEquals("101", lines[0].score)
        assertTrue(lines[0].possession); assertTrue(lines[0].leading)
        assertFalse(lines[1].possession); assertFalse(lines[1].leading)
        assertEquals("Portland possession", SportsCardContent.situation(game, false))
    }
    @Test fun hiddenOrWaitingPresentationsCannotLeakPossessionSituationOrFinalNumbers() {
        val hidden = SportsCardContent.teams(game.copy(state = "post"), true)
        assertTrue(hidden.all { it.score == null && it.record == null && !it.possession && !it.leading })
        assertEquals("", SportsCardContent.situation(game, true))
        assertEquals("", SportsCardContent.situation(game.copy(status = SportsScoreDelay.WAITING), false))
        assertFalse(SportsCardContent.teams(game.copy(state = "hold"), false).any { it.possession })
    }
    @Test fun upcomingGamesAndIncompleteOrTiedScoresDoNotInventALeader() {
        val upcoming = SportsCardContent.teams(game.copy(state = "pre"), false)
        assertTrue(upcoming.all { it.score == null && !it.leading && !it.possession }); assertEquals("2-4", upcoming[0].record)
        assertFalse(SportsCardContent.teams(game.copy(scores = mapOf("nba:POR" to "101")), false).any { it.leading })
        assertFalse(SportsCardContent.teams(game.copy(scores = mapOf("nba:POR" to "98", "nba:LAL" to "98")), false).any { it.leading })
    }
    @Test fun bannerScoresAreClearedForHiddenWaitingAndScheduledGames() {
        assertEquals(listOf("101", "98"), SportsCardContent.scoreboard(game, false, "7:15 PM").values)
        assertNull(SportsCardContent.scoreboard(game, false, "7:15 PM").message)
        val hidden = SportsCardContent.scoreboard(game.copy(state = "post"), true, "7:15 PM")
        assertEquals("Score hidden", hidden.message); assertTrue(hidden.values.isEmpty())
        val waiting = SportsCardContent.scoreboard(game.copy(status = SportsScoreDelay.WAITING), false, "7:15 PM")
        assertEquals("Score delayed", waiting.message); assertTrue(waiting.values.isEmpty())
        val upcoming = SportsCardContent.scoreboard(game.copy(state = "pre"), false, "7:15 PM")
        assertEquals("7:15 PM", upcoming.message); assertTrue(upcoming.values.isEmpty())
    }
}

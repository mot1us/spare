package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsScoreDelayTest {
    private val wall = 1800000000000L
    private val teams = listOf("nba:POR", "nba:LAL").map { SportsCatalog.byId.getValue(it) }
    private val game = SportsGame(teams, wall - 60000, wall + 3600000, emptyList(), "delay", scores = mapOf(teams[0].id to "20"), status = "Q1", competition = "NBA", records = mapOf(teams[0].id to "2-4"))
    private fun snapshot(value: SportsGame) = LiveSportsSnapshot(listOf(LiveEvent(value.eventId, teams.map { it.id }.toSet(), value.start, value.state, scores = value.scores, status = value.status, league = value.leagueId, records = value.records)), wall, setOf("NBA"))
    @Test fun coldStartNeverFlashesTheCurrentScoreAndOffIsImmediate() {
        val delay = SportsScoreDelay(); delay.observe(snapshot(game), 1000)
        val pending = delay.display(game, 30, 2000, wall)
        assertTrue(pending.scores.isEmpty()); assertTrue(pending.records.isEmpty()); assertEquals(SportsScoreDelay.WAITING, pending.status)
        assertSame(game, delay.display(game, 0, 2000, wall))
        assertEquals(game.scores, delay.display(game, 30, 31000, wall).scores)
    }
    @Test fun scoresClockRecordsAndFinalStatusAdvanceTogetherWithoutChangingRealGame() {
        val delay = SportsScoreDelay(); delay.observe(snapshot(game), 0)
        val final = game.copy(state = "post", scores = mapOf(teams[0].id to "104"), status = "Final", records = mapOf(teams[0].id to "3-4"))
        delay.observe(snapshot(final), 20000)
        val past = delay.display(final, 30, 40000, wall)
        assertEquals("in", past.state); assertEquals(game.scores, past.scores); assertEquals("Q1", past.status); assertEquals(game.records, past.records)
        assertEquals("post", final.state); assertEquals(final, delay.display(final, 30, 50000, wall))
    }
    @Test fun unchangedPollsDoNotRestartTheDelayAndChangingDelayUsesAvailableHistory() {
        val delay = SportsScoreDelay(); delay.observe(snapshot(game), 0); delay.observe(snapshot(game), 20000)
        assertEquals(game.scores, delay.display(game, 30, 30000, wall).scores)
        assertTrue(delay.display(game, 90, 30000, wall).scores.isEmpty())
        assertEquals(game.scores, delay.display(game, 15, 30000, wall).scores)
    }
    @Test fun teamsAndEventsStayIndependentAndBoundsDoNotInventScores() {
        val delay = SportsScoreDelay(capacity = 1, framesPerGame = 2); delay.observe(snapshot(game), 0)
        val other = game.copy(eventId = "other", scores = mapOf(teams[0].id to "7")); delay.observe(snapshot(other), 1)
        assertTrue(delay.display(game, 30, 40000, wall).scores.isEmpty())
        assertEquals(other.scores, delay.display(other, 30, 40000, wall).scores)
        delay.observe(snapshot(other.copy(status = "Q2")), 50000); delay.observe(snapshot(other.copy(status = "Q3")), 60000)
        assertTrue(delay.display(other, 300, 70000, wall).scores.isEmpty())
    }
    @Test fun clockResetHidesFutureHistoryAndOldFinalsAndUpcomingGamesNeedNoDelay() {
        val delay = SportsScoreDelay(); delay.observe(snapshot(game), 40000); delay.observe(snapshot(game.copy(status = "Q2")), 1000)
        assertTrue(delay.display(game, 30, 2000, wall).scores.isEmpty())
        val upcoming = game.copy(state = "pre"); assertSame(upcoming, delay.display(upcoming, 30, 2000, wall))
        val old = game.copy(state = "post", start = wall - 24 * 3600000L); assertSame(old, delay.display(old, 30, 2000, wall))
    }
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsScoreStripTest {
    private val now = java.time.Instant.parse("2026-11-01T23:00:00Z").toEpochMilli()
    @Test fun overlayShowsTodaysLiveAndFinalGamesAndPrioritizesFollowedTeams() {
        val first = LiveEvent("early", setOf("nfl:BUF", "nfl:KC"), now - 20000, league = "NFL")
        val favorite = LiveEvent("favorite", setOf("nfl:SEA", "nfl:SF"), now - 10000, scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "7"), league = "NFL")
        val events = listOf(first, favorite, favorite.copy(id = "later", state = "pre", start = now + 3600000), first.copy(id = "final", state = "post"), first.copy(id = "yesterday", state = "post", start = now - 86400000), LiveEvent("baseball", setOf("mlb:NYY", "mlb:BOS"), now - 1000, league = "MLB"))
        val snapshot = LiveSportsSnapshot(events, now, setOf("NFL", "MLB"))
        val games = SportsScoreStrip.todayGames(snapshot, setOf("NFL"), setOf("nfl:SEA"), now)
        assertEquals(listOf("favorite", "early", "final"), games.map { it.eventId }); assertEquals("14", games.first().scores["nfl:SEA"])
        val stale = SportsScoreStrip.todayGames(snapshot.copy(checked = now - 180000), setOf("NFL"), emptySet(), now)
        assertFalse(stale.any { it.state == "in" }); assertTrue(stale.any { it.state == "post" }); assertTrue(stale.any { it.state == "hold" })
        assertTrue(SportsScoreStrip.todayGames(snapshot, emptySet(), emptySet(), now).isEmpty())
    }
}

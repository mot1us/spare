package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsScoreStripTest {
    private val now = java.time.Instant.parse("2026-11-01T23:00:00Z").toEpochMilli()
    @Test fun stripShowsFreshEnabledLiveGamesAndPrioritizesFollowedTeams() {
        val first = LiveEvent("early", setOf("nfl:BUF", "nfl:KC"), now - 20000, league = "NFL")
        val favorite = LiveEvent("favorite", setOf("nfl:SEA", "nfl:SF"), now - 10000, scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "7"), league = "NFL")
        val events = listOf(first, favorite, favorite.copy(id = "later", state = "pre", start = now + 3600000), first.copy(id = "final", state = "post"), LiveEvent("baseball", setOf("mlb:NYY", "mlb:BOS"), now - 1000, league = "MLB"))
        val snapshot = LiveSportsSnapshot(events, now, setOf("NFL", "MLB"))
        val games = SportsScoreStrip.liveGames(snapshot, setOf("NFL"), setOf("nfl:SEA"), now)
        assertEquals(listOf("favorite", "early"), games.map { it.eventId }); assertEquals("14", games.first().scores["nfl:SEA"])
        assertTrue(SportsScoreStrip.liveGames(snapshot.copy(checked = now - 180000), setOf("NFL"), emptySet(), now).isEmpty())
        assertTrue(SportsScoreStrip.liveGames(snapshot, emptySet(), emptySet(), now).isEmpty())
    }
}

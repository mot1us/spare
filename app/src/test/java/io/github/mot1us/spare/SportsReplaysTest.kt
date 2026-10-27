package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsReplaysTest {
    private val now = java.time.Instant.parse("2026-10-26T23:00:00Z").toEpochMilli()
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 3 * 3600000, now - 3600000, emptyList(), "replay", "post", networks = listOf("ESPN"), scores = mapOf("nfl:SEA" to "24", "nfl:SF" to "21"), status = "Seahawks win 24–21", competition = "NFL")
    private val channel = Channel("xc:1", "ESPN FHD", "NFL", "https://example.com/live/user/pass/1.ts", catchupDays = 2, catchupMode = "xc")
    private fun listing(start: Long = game.start, end: Long = game.end, title: String = "NFL: Seattle Seahawks vs San Francisco 49ers") = Programme(channel.id, title, start, end)

    @Test fun archiveMatchesTheHistoricalBroadcastRatherThanTheLiveOrDifferentGame() {
        val correct = listing()
        val otherDate = listing(game.start - 86400000, game.end - 86400000)
        val live = listing(now - 60000, now + 3600000)
        val wrongTeams = listing(title = "NFL: Seahawks vs Rams")
        val pregame = listing(title = "NFL: Seahawks vs 49ers Pregame")
        val replay = SportsReplays.find(game, Guide(listOf(channel), mapOf(channel.id to listOf(otherDate, live, wrongTeams, pregame, correct))), now).single()
        assertEquals(correct, replay.programme)
        val item = replay.item(game, false, now)!!
        assertEquals("catchup", item.kind); assertEquals(game.title, item.title)
        assertTrue(item.url.contains("/timeshift/user/pass/120/")); assertFalse(item.url.contains("/live/"))
        assertEquals(correct.start + 2 * 86400000L, replay.expires)
    }
    @Test fun retentionUsesEachChannelAndRechecksExpiryAndHttpConsent() {
        val old = game.copy(start = now - 49 * 3600000, end = now - 47 * 3600000)
        val p = listing(old.start, old.end)
        assertTrue(SportsReplays.find(old, Guide(listOf(channel), mapOf(channel.id to listOf(p))), now).isEmpty())
        val longer = channel.copy(catchupDays = 7)
        assertEquals(1, SportsReplays.find(old, Guide(listOf(longer), mapOf(longer.id to listOf(p))), now).size)
        assertFalse(SportsReplays.eligible(game.copy(state = "in"), 2, now))
        assertFalse(SportsReplays.eligible(game.copy(start = now + 1), 2, now))
        assertNull(SportsReplay(channel, listing()).item(game, false, game.start + 2 * 86400000L + 1))
        val http = channel.copy(url = channel.url.replace("https:", "http:"))
        assertNull(SportsReplay(http, listing()).item(game, false, now)); assertNotNull(SportsReplay(http, listing()).item(game, true, now))
    }
    @Test fun providerLookupUsesOnlyABoundedArchiveShortlist() {
        val channels = (1..100).map { channel.copy(id = "xc:$it", name = if (it <= 9) "ESPN $it" else "News $it") } + channel.copy(id = "no-archive", catchupDays = 0)
        val candidates = SportsReplays.candidates(game, Guide(channels, emptyMap()), setOf("xc:9"), now)
        assertEquals(6, candidates.size); assertEquals("xc:9", candidates.first().id)
        assertTrue(candidates.all { it.name.startsWith("ESPN") && it.catchupDays > 0 })
    }
    @Test fun spoilerTextRemovesFinalScoresAndWinnerStatusButKeepsLiveScores() {
        assertEquals(game.title, SportsText.score(game, true)); assertEquals("Completed", SportsText.status(game, true, now))
        assertTrue(SportsText.score(game, false).contains("24")); assertTrue(SportsText.status(game, false, now).contains("win"))
        assertTrue(SportsText.score(game.copy(state = "in"), true).contains("24"))
    }
}

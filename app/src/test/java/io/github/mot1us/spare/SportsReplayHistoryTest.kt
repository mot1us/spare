package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsReplayHistoryTest {
    private val at = 1793232000000L
    private val channel = Channel("xc:7", "FOX HD", "USA", "https://example.com/live/user/password/7.ts", catchupDays = 2, catchupMode = "xc")
    private val programme = Programme(channel.id, "World Series: Tampa Bay Rays vs Milwaukee Brewers", at - 10800000, at)
    private val replay = SportsReplay(channel, programme)
    @Test fun confirmedReplaySurvivesTheNextDayWithoutHistoricalGuideOrProviderRequests() {
        val bytes = SportsReplayHistory.encode(mapOf("MLB:world-series" to listOf(replay)), at + 1000)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("password"))
        val nextDay = at + 86400000L
        val guide = Guide(listOf(channel), emptyMap())
        val found = SportsReplayHistory.decode(bytes, guide, nextDay).getValue("MLB:world-series").single()
        assertEquals(replay, found); assertNotNull(Catchup.url(found.channel, found.programme, false, nextDay))
        assertTrue(SportsReplayHistory.decode(bytes, guide, programme.start + 2 * 86400000L + 1).isEmpty())
    }
    @Test fun restoringRevalidatesCurrentStreamIdentityAndProviderRetention() {
        val bytes = SportsReplayHistory.encode(mapOf("game" to listOf(replay)), at)
        for (changed in listOf(channel.copy(url = "https://example.com/changed.ts"), channel.copy(id = "xc:8"), channel.copy(catchupDays = 0))) {
            assertTrue(SportsReplayHistory.decode(bytes, Guide(listOf(changed), emptyMap()), at + 1000).isEmpty())
        }
        val shorter = channel.copy(catchupDays = 1)
        assertTrue(SportsReplayHistory.decode(bytes, Guide(listOf(shorter), emptyMap()), at + 86400000L).isEmpty())
        assertTrue(SportsReplays.retained(listOf(replay), mapOf(shorter.id to shorter), at + 86400000L).isEmpty())
        assertEquals(shorter, SportsReplays.retained(listOf(replay), mapOf(shorter.id to shorter), at + 1000).single().channel)
    }
}

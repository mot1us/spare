package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsReplayHistoryTest {
    private val at = 1793232000000L
    private val channel = Channel("xc:7", "FOX HD", "USA", "https://example.com/live/user/password/7.ts", catchupDays = 2, catchupMode = "xc")
    private val programme = Programme(channel.id, "World Series: Tampa Bay Rays vs Milwaukee Brewers", at - 10800000, at)
    private val replay = SportsReplay(channel, programme)
    @Test fun aLargeMultibyteReplayCacheRemainsReadableAndPrioritizesRecentGames() {
        val channels = (1..4).map { channel.copy(id = "xc:$it", url = "https://example.com/live/user/password/$it.ts") }
        val choices = channels.map { SportsReplay(it, programme.copy(channel = it.id, title = "★".repeat(512))) }
        val matches = (1..256).associate { "$it:${"★".repeat(500)}" to choices }
        val bytes = SportsReplayHistory.encode(matches, at)
        assertTrue(bytes.size <= 2 * 1024 * 1024)
        val restored = SportsReplayHistory.decode(bytes, Guide(channels, emptyMap()), at + 1000)
        assertTrue(matches.keys.last() in restored); assertFalse(restored.isEmpty())
        assertTrue(restored.values.all { it.size <= 4 })
    }
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

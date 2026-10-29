package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuideHistoryTest {
    private val now = 1793318400000L
    @Test fun liveRefreshPreservesYesterdayArchiveAndRoomForCurrentAndFutureGuide() {
        val yesterday = Programme("channel", "World Series: Tampa Bay Rays vs Milwaukee Brewers", now - 86400000, now - 75600000)
        val upcoming = (0..100).map { Programme("channel", "Show $it", now + it * 1800000, now + (it + 1) * 1800000) }
        val merged = GuideHistory.merge(listOf(yesterday), upcoming, 2, now)
        assertTrue(yesterday in merged); assertEquals(73, merged.size)
        assertEquals("Show 0", merged.first { now in it.start until it.end }.title)
        assertEquals("Show 71", merged.last().title)
        assertFalse(yesterday in GuideHistory.merge(listOf(yesterday), upcoming, 0, now))
    }
    @Test fun expiredArchiveIsRemovedAndIncomingCorrectionsWinWithoutUnboundedHistory() {
        val old = Programme("channel", "Old", now - 49 * 3600000L, now - 48 * 3600000L)
        val p = Programme("channel", "Before", now - 60000, now + 60000)
        assertEquals(listOf(p.copy(title = "Corrected")), GuideHistory.merge(listOf(old, p), listOf(p.copy(title = "Corrected")), 2, now))
        val dense = (1..1000).map { Programme("channel", "$it", now - it * 60000L, now - (it - 1) * 60000L) }
        assertEquals(500, GuideHistory.merge(dense, emptyList(), 2, now).size)
    }
}

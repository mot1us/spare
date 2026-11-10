package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PerformanceSamplesTest {
    @Test fun frameWindowReportsNearestRankAndDeadlineMisses() {
        val frames = PerformanceFrames()
        frames.record(0, 1000, 50, 0)
        for (i in 1..100) frames.record(500 + i.toLong(), i.toLong(), 80, if (i == 3) 2 else 0)
        val summary = frames.summary(10_000)!!
        assertEquals(100, summary.count)
        assertEquals(50L, summary.p50)
        assertEquals(95L, summary.p95)
        assertEquals(99L, summary.p99)
        assertEquals(21.0, summary.jankyPercent, .001)
        assertEquals(2L, summary.missedReports)
        assertNull(frames.summary(10_600))
    }

    @Test fun windowsBoundTheirHistoryAndExpireDropBatches() {
        val frames = PerformanceFrames(2)
        for (i in 1..3) frames.record(i.toLong(), i.toLong(), 100, 0)
        assertEquals(2L, frames.summary(3)!!.p50)
        val drops = PerformanceDrops()
        drops.record(20, 3); drops.record(21, 4); drops.record(30, -9)
        assertEquals(7L, drops.count(10019))
        assertEquals(4L, drops.count(10020))
        drops.clear()
        assertEquals(0L, drops.count(10020))
    }

    @Test fun displaySettleRequiresAStableCompatibleModeAfterTune() {
        val settle = DisplaySettle()
        assertNull(settle.observe(100, 1, 60f, 30f, 30f, true))
        settle.tune(100)
        assertNull(settle.observe(300, 1, 60f, 29.97f, 29.97f, true))
        assertNull(settle.observe(900, 1, 60f, 30f, 29.97f, true))
        assertNull(settle.observe(1000, 2, 59.94f, 29.97f, 29.97f, false))
        assertNull(settle.observe(1100, 2, 59.94f, 29.97f, 29.97f, true))
        assertEquals(1000L, settle.observe(1600, 2, 59.94f, 29.97f, 29.97f, true))
        assertNull(settle.observe(1650, 3, 119.88f, 29.97f, 29.97f, true))
        assertEquals(1550L, settle.observe(2150, 3, 119.88f, 29.97f, 29.97f, true))
    }
}

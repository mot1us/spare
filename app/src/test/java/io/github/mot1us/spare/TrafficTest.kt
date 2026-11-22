package io.github.mot1us.spare

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    @After fun clear() = Traffic.reset()

    @Test fun meterKeepsOnlyTheLastTenSeconds() {
        val meter = Traffic.Meter()
        meter.add(1_000, 100); meter.add(1_500, 50); meter.add(5_000, 200)
        assertEquals(350, meter.recent(5_999))
        assertEquals("Bytes from second 1 drop out after ten seconds", 200, meter.recent(11_000))
        meter.add(13_000, 7)
        assertEquals("Second 1's reused slot starts from zero; second 5 is still in the window", 207, meter.recent(13_000))
        assertEquals(0, meter.recent(40_000))
    }

    @Test fun workersAreGroupedByPurpose() {
        assertEquals("idle-browse", Traffic.purpose("spare-idle-browse-2"))
        assertEquals("artwork", Traffic.purpose("spare-artwork"))
        assertEquals("epg-order", Traffic.purpose("spare-epg-order-1"))
        assertEquals("unnamed", Traffic.purpose("pool-3-thread-1"))
        assertEquals("unnamed", Traffic.purpose("spare-"))
    }

    @Test fun summaryComparesTheStreamWithItsAverageAndNamesOtherDownloads() {
        Traffic.streamStarted(0)
        // 20 s at 4 Mbit/s (500 KB a second), then 10 s with almost nothing while artwork downloads.
        for (s in 0 until 20) Traffic.stream(s * 1000L, 500_000)
        Traffic.stream(20_500, 25_000)
        for (s in 20 until 30) Traffic.other(s * 1000L, 300_000, "spare-idle-browse-1")
        Traffic.other(25_000, 40_000, "spare-epg-2")
        val line = Traffic.summary(29_900, listOf("spare-artwork", "spare-artwork", "spare-epg-1"))
        assertTrue(line, line.startsWith("stream 0.0 Mbit/s last 10 s (avg 2.7 Mbit/s) · last data 9.4 s ago"))
        assertTrue(line, "other downloads: idle-browse 3.0 MB, epg 40 KB" in line)
        assertTrue(line, line.endsWith("waiting: artwork×2, epg"))
    }

    @Test fun summaryWithoutALiveStreamOnlyListsOtherDownloads() {
        Traffic.streamStopped()
        assertEquals("other downloads: none", Traffic.summary(5_000))
        Traffic.streamStarted(4_000)
        assertEquals("stream 0.0 Mbit/s last 1 s (avg 0.0 Mbit/s) · no data yet · other downloads: none", Traffic.summary(4_200))
    }

    @Test fun aMovieSaysHowFastItHasToArrive() {
        Traffic.streamStarted(0)
        // A 4K remux: 48 GB over 2 hours averages about 53 Mbit/s; it arrives at 40 Mbit/s (5 MB a second).
        Traffic.titleSize(1_000_000); Traffic.titleSize(48_000_000_000); Traffic.titleSize(80_000)
        Traffic.titleDurationMs = 7_200_000
        for (s in 0 until 10) Traffic.stream(s * 1000L, 5_000_000)
        val line = Traffic.summary(9_900)
        assertTrue(line, line.startsWith("stream 40.8 Mbit/s last 9 s (avg 40.4 Mbit/s)"))
        assertTrue("The movie's own size counts, not a subtitle file's: $line", "title needs 53.3 Mbit/s" in line)
        Traffic.streamStarted(20_000)
        assertTrue("A new attempt forgets the last title", "title needs" !in Traffic.summary(20_500))
    }
}

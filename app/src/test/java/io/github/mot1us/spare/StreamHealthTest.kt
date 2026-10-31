package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class StreamHealthTest {
    private fun channel(id: String, height: Int) = Channel(id, id, "Sports", "https://example.test/$id", reportedQuality = VideoInfo(height * 16 / 9, height, 60f))
    @Test fun repeatedFailuresPreferAPlayableLowerResolutionWithoutHidingAnyResult() {
        val health = StreamHealth(); val uhd = channel("uhd", 2160); val hd = channel("hd", 720)
        health.problem(uhd.id, 1000); assertEquals(listOf(uhd, hd), health.order(listOf(uhd, hd), emptyMap(), 1001))
        health.problem(uhd.id, 2000); assertEquals(listOf(hd, uhd), health.order(listOf(uhd, hd), emptyMap(), 2001))
        assertEquals(listOf(uhd, hd), health.order(listOf(uhd, hd), emptyMap(), 122001))
    }
    @Test fun SmoothPlaybackBreaksQualityTiesAndDoesNotOutrankHigherResolution() {
        val health = StreamHealth(); val first = channel("first", 1080); val smooth = channel("smooth", 1080); val uhd = channel("uhd", 2160)
        health.observe(smooth.id, 0, true); health.observe(smooth.id, 30000, true)
        assertEquals(listOf(uhd, smooth, first), health.order(listOf(uhd, first, smooth), emptyMap(), 30001))
        health.problem(smooth.id, 31000, true); health.observe(smooth.id, 32000, true); health.observe(smooth.id, 92000, true)
        assertEquals(-1, health.status(smooth.id, 92001))
    }
    @Test fun timeSpentOnOtherChannelsDoesNotCountAsSmoothPlayback() {
        val health = StreamHealth()
        health.observe("a", 0, true); health.observe("b", 5000, true); health.observe("a", 40000, true)
        assertEquals(0, health.status("a", 40001))
        health.observe("a", 41000, false); health.observe("a", 100000, true)
        assertEquals(0, health.status("a", 100001))
    }

}

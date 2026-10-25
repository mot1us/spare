package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuideFiltersTest {
    private val channel = Channel("one", "NFL 4K 60FPS", "NFL", "https://example.com/stream")
    @Test fun strictFiltersNeverTrustLabelsOrUnknownMetadata() {
        assertTrue(GuideFilters().matches(channel, null))
        assertFalse(GuideFilters(1080).matches(channel, null))
        assertFalse(GuideFilters(sixtyOnly = true).matches(channel, null))
        assertFalse(GuideFilters(1080, true).matches(channel, VideoInfo(1280, 720, 60f)))
        assertFalse(GuideFilters(1080, true).matches(channel, VideoInfo(3840, 2160, 50f)))
    }
    @Test fun acceptsBroadcastSixtyRatesAndRejectsLowerOrUnknownRates() {
        val filters = GuideFilters(1080, true)
        listOf(59.94f, 60f).forEach { assertTrue(filters.matches(channel, VideoInfo(1920, 1080, it))) }
        listOf(0f, 23.976f, 25f, 29.97f, 30f, 50f, Float.NaN).forEach { assertFalse(filters.matches(channel, VideoInfo(1920, 1080, it))) }
    }
    @Test fun resolutionThresholdsAndCategoriesIntersect() {
        val filters = GuideFilters(1080, true, setOf("NFL", "MLB"))
        assertTrue(filters.matches(channel, VideoInfo(3840, 2160, 60f)))
        assertTrue(filters.matches(channel, VideoInfo(1080, 1920, 60f)))
        assertFalse(filters.matches(channel.copy(group = "News"), VideoInfo(1920, 1080, 60f)))
        assertFalse(GuideFilters(2160).matches(channel, VideoInfo(1920, 1080, 60f)))
    }
    @Test fun streamFormatChangePreservesEncodedCredentialsAndQuery() {
        val url = "https://example.com:8443/live/user%2Fname/p%40ss/123.ts?token=a%2Fb"
        assertEquals("https://example.com:8443/live/user%2Fname/p%40ss/123.m3u8?token=a%2Fb", StreamUrls.alternate(url))
        assertEquals(url, StreamUrls.alternate(StreamUrls.alternate(url)!!))
        assertNull(StreamUrls.alternate("https://example.com/channel.mp4"))
        assertNull(StreamUrls.format("https://example.com/channel.mp4", "ts"))
    }
}

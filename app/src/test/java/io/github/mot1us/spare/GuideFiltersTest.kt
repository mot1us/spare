package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuideFiltersTest {
    private val channel = Channel("one", "NFL 4K 60FPS", "NFL", "https://example.com/stream")
    @Test fun strictFiltersNeverTrustLabelsOrUnknownMetadata() {
        assertTrue(GuideFilters().matches(channel, null))
        assertFalse(GuideFilters(1080).matches(channel, null))
        assertFalse(GuideFilters(excludedFps = FrameRateFilter.onlySixty).matches(channel, null))
        assertFalse(GuideFilters(1080, FrameRateFilter.onlySixty).matches(channel, VideoInfo(1280, 720, 60f)))
        assertFalse(GuideFilters(1080, FrameRateFilter.onlySixty).matches(channel, VideoInfo(3840, 2160, 50f)))
    }
    @Test fun acceptsBroadcastSixtyRatesAndRejectsLowerOrUnknownRates() {
        val filters = GuideFilters(1080, FrameRateFilter.onlySixty)
        listOf(59.94f, 60f).forEach { assertTrue(filters.matches(channel, VideoInfo(1920, 1080, it))) }
        listOf(0f, 23.976f, 25f, 29.97f, 30f, 50f, Float.NaN).forEach { assertFalse(filters.matches(channel, VideoInfo(1920, 1080, it))) }
    }
    @Test fun resolutionThresholdsAndCategoriesIntersect() {
        val filters = GuideFilters(1080, FrameRateFilter.onlySixty, setOf("NFL", "MLB"))
        assertTrue(filters.matches(channel, VideoInfo(3840, 2160, 60f)))
        assertTrue(filters.matches(channel, VideoInfo(1080, 1920, 60f)))
        assertFalse(filters.matches(channel.copy(group = "News"), VideoInfo(1920, 1080, 60f)))
        assertFalse(GuideFilters(2160).matches(channel, VideoInfo(1920, 1080, 60f)))
    }
    @Test fun fpsExclusionsKeepTheOtherBroadcastFamilies() {
        val profile = GuideFilters(excludedFps = setOf(25, 50))
        listOf(23.976f, 24f, 29.97f, 30f, 59.94f, 60f).forEach { assertTrue(profile.matches(channel, VideoInfo(1920, 1080, it))) }
        listOf(25f, 50f, 0f, Float.NaN).forEach { assertFalse(profile.matches(channel, VideoInfo(1920, 1080, it))) }
        assertFalse(profile.matches(channel, null))
        assertTrue(GuideFilters().matches(channel, null))
        assertFalse(GuideFilters(excludedFps = setOf(24)).matches(channel, VideoInfo(1920, 1080, 23.976f)))
    }
    @Test fun temporaryUnknownFpsKeepsVerifiedRateOnlyForTheSameVideoSize() {
        val prior = VideoInfo(1920, 1080, 59.94f)
        assertEquals(prior, VideoInfo(1920, 1080, 0f).retainingKnownRate(prior))
        assertEquals(prior, VideoInfo(1920, 1080, Float.NaN).retainingKnownRate(prior))
        assertEquals(50f, VideoInfo(1920, 1080, 50f).retainingKnownRate(prior).fps)
        assertEquals(0f, VideoInfo(1280, 720, 0f).retainingKnownRate(prior).fps)
        assertEquals(0f, VideoInfo(1920, 1080, 0f).retainingKnownRate(null).fps)
    }
    @Test fun sixtyPlusUsesDisplayedHeightAndIncludesBroadcastRates() {
        val filter = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        listOf(59.94f, 60f, 100f, 119.88f).forEach { fps ->
            assertTrue(filter.matches(channel, VideoInfo(1440, 1080, fps)))
            assertTrue(filter.matches(channel, VideoInfo(960, 1080, fps, interlaced = true)))
        }
        assertFalse(filter.matches(channel, VideoInfo(1920, 1080, 50f)))
        assertFalse(filter.matches(channel, VideoInfo(1920, 720, 60f)))
        assertFalse(filter.matches(channel, VideoInfo(1920, 1080, 0f)))
    }
    @Test fun streamFormatChangePreservesEncodedCredentialsAndQuery() {
        val url = "https://example.com:8443/live/user%2Fname/p%40ss/123.ts?token=a%2Fb"
        assertEquals("https://example.com:8443/live/user%2Fname/p%40ss/123.m3u8?token=a%2Fb", StreamUrls.alternate(url))
        assertEquals(url, StreamUrls.alternate(StreamUrls.alternate(url)!!))
        assertNull(StreamUrls.alternate("https://example.com/channel.mp4"))
        assertNull(StreamUrls.format("https://example.com/channel.mp4", "ts"))
    }
}

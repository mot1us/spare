package io.github.mot1us.spare

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QualityDataTest {
    private val filter = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
    @Test fun providerRatesSurviveEmptyRootFieldsNestedStringsAndResolutionDeclarations() {
        val examples = listOf(
            """{"fps":"0","height":0,"video":{"height":1080,"r_frame_rate":"60000/1001"}}""",
            """{"stream_info":"{\"video\":{\"height\":2160,\"fps\":\"60 fps\"}}"}""",
            """{"resolution":"1080p59.94"}""",
            """{"stream_resolution":"3840x2160","video_fps":"59.94 Hz"}"""
        )
        examples.forEach { json ->
            val quality = ProviderQuality.parse(JSONObject(json))
            assertNotNull(json, quality)
            assertTrue(json, filter.matches(Channel("one", "A channel", "", "https://example.test/live", reportedQuality = quality), null))
        }
    }
    @Test fun partialPlaybackCannotEraseProviderResolutionAndCachedExplicitRate() {
        val channel = Channel("one", "USA FHD 60fps", "", "https://example.test/live", reportedQuality = VideoInfo(1920, 1080, 0f))
        val restored = Channel.from(channel.json())
        assertTrue(filter.matches(restored, VideoInfo(0, 0, 59.94f)))
        assertTrue(filter.matches(restored, VideoInfo(1920, 1080, Float.NaN)))
        assertFalse(filter.matches(restored, VideoInfo(1280, 720, 59.94f)))
        assertFalse(filter.matches(restored, VideoInfo(1920, 1080, 50f)))
        assertFalse(filter.matches(channel.copy(name = "FHD"), null))
        val known = VideoInfo(1920, 1080, 59.94f)
        assertEquals(known, VideoInfo(0, 0, 0f).retainingKnownRate(known))
        assertEquals(1080, VideoInfo(0, 0, 60f).retainingKnownRate(known).height)
    }
}

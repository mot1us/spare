package io.github.mot1us.spare

import androidx.media3.common.text.Cue
import org.junit.Assert.*
import org.junit.Test

class SubtitleAppearanceTest {
    @Test fun imageSubtitleSizeUsesTheSharedPreferenceAndPreservesAspectRatio() {
        val small = SubtitleAppearance.bitmapScale("Small", .5f, .1f)
        val medium = SubtitleAppearance.bitmapScale("Medium", .5f, .1f)
        val large = SubtitleAppearance.bitmapScale("Large", .5f, .1f)
        assertTrue(small < medium); assertEquals(1f, medium, .001f); assertTrue(large > medium)
        assertEquals(5f, (.5f * small) / (.1f * small), .001f)
        assertEquals(small, SubtitleAppearance.bitmapScale("Unknown", .5f, .1f), .001f)
        assertEquals(large, SubtitleAppearance.bitmapScale("Large", .5f, Cue.DIMEN_UNSET), .001f)
    }
    @Test fun imageSubtitlesNeverGrowBeyondTheViewport() {
        val factor = SubtitleAppearance.bitmapScale("Extra large", .95f, .8f)
        assertEquals(1f, .95f * factor, .001f)
        assertTrue(.8f * factor <= 1f)
    }
}

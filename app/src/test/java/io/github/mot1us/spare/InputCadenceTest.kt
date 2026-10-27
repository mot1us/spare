package io.github.mot1us.spare

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class InputCadenceTest {
    @Test fun roundedSixtyFamilySettlesWithoutFlippingAndStillDetectsRealChanges() {
        for (fps in listOf(59.94f, 60f)) {
            val cadence = InputCadence(); val settled = mutableSetOf<Float>()
            repeat(2400) { frame ->
                val rate = cadence.observe(1, (frame * 1000.0 / fps).roundToLong() * 1000)
                if (frame >= 1000) settled.add(rate)
            }
            assertEquals(setOf(fps), settled)
            var rate = 0f
            repeat(100) { rate = cadence.observe(2, (it * 1_000_000.0 / 50).roundToLong()) }
            assertEquals(50f, rate, .001f)
        }
    }
    @Test fun measuresSourceSamplesInDecodeOrderWithBFramesAndRoundedTimestamps() {
        listOf(23.976f, 25f, 29.97f, 50f, 59.94f, 60f).forEach { fps ->
            val cadence = InputCadence(); var result = 0f
            repeat(80) { group ->
                listOf(0, 3, 1, 2).forEach { offset ->
                    val pts = ((group * 4 + offset) * 1000.0 / fps).roundToLong() * 1000
                    result = cadence.observe(1, pts)
                }
            }
            // A sub-second window of millisecond-rounded timestamps cannot
            // always distinguish 59.94 from 60, but must retain its FPS family.
            assertEquals("Source $fps fps with B-frames", fps, result, .08f)
            assertEquals(FrameRateFilter.bucket(fps), FrameRateFilter.bucket(result))
            assertEquals(0f, cadence.observe(2, 0), 0f)
        }
    }

    @Test fun inputSixtySurvivesThirtyDecoderAndDisplayAndFeedsFhdFilterForAvcAndHevc() {
        val channel = Channel("1", "Game", "Sports", "https://example.com/stream")
        for (codec in listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)) {
            val format = Format.Builder().setSampleMimeType(codec).setWidth(3840).setHeight(2160).setFrameRate(30f).build()
            val info = VideoRate.observation(format, 29.97f, 30f, AvcHeader.Info(), 59.94f)
            assertEquals(59.94f, info.fps, .001f)
            assertEquals("input timestamps", info.evidence)
            assertTrue(GuideFilters(1080, FrameRateFilter.sixtyOrBetter).matches(channel, info))
            assertTrue(GuideFilters(2160, FrameRateFilter.sixtyOrBetter).matches(channel, VideoInfo.from(info.json())))
            val thirty = VideoRate.observation(format, 29.97f, 30f, AvcHeader.Info(), 29.97f)
            assertEquals(29.97f, thirty.fps, .001f)
            assertFalse(GuideFilters(1080, FrameRateFilter.sixtyOrBetter).matches(channel, thirty))
        }
    }

    @Test fun unstableOrDuplicateInputDoesNotInventSixtyAndDiscontinuitiesReset() {
        val cadence = InputCadence(); var pts = 0L; var result = 0f
        repeat(288) { pts += if (it % 2 == 0) 16667 else 50000; result = cadence.observe(1, pts) }
        assertEquals(0f, result, 0f)
        repeat(288) { result = cadence.observe(2, (it * 1_000_000.0 / 59.94).roundToLong()) }
        assertEquals(59.94f, result, .04f)
        assertEquals(0f, cadence.observe(2, 0), 0f)
    }
}

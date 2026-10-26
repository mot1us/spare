package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class FrameCadenceTest {
    @Test fun shortMetadataFreeStreamsGetARateBeforeTheyLoop() {
        listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f).forEachIndexed { token, fps ->
            val cadence = FrameCadence(); var measured = 0f
            for (frame in 0..(fps * 2.4f).toInt()) measured = cadence.observe(token, (frame * 1000.0 / fps).roundToLong() * 1000)
            assertEquals("Short $fps fps stream", fps, measured, .03f)
        }
    }
    @Test fun millisecondRoundedTimestampsDoNotInventThirtyPointThree() {
        listOf(23.976f, 24f, 29.97f, 30f, 50f, 59.94f, 60f).forEachIndexed { token, fps ->
            val cadence = FrameCadence(); var measured = 0f
            for (frame in 0..300) measured = cadence.observe(token, (frame * 1000.0 / fps).roundToLong() * 1000)
            assertEquals("Rounded $fps fps timestamps", fps, measured, .03f)
        }
    }
    @Test fun sourceRateSurvivesMissingRenderCallbacksAndInterlacedMpeg2UsesFields() {
        assertEquals(59.94f, VideoRate.resolve(59.94f, 29.97f), .001f)
        assertEquals(60f, VideoRate.resolve(-1f, 30.303f, 60f), .001f)
        assertEquals(30f, VideoRate.resolve(30f, 30f), .001f)
        assertEquals(0f, VideoRate.resolve(30.3f, 0f, 30.3f), .001f)
        fun format(progressive: Boolean) = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_MPEG2)
            .setInitializationData(listOf(byteArrayOf(0, 0, 1, 0xB5.toByte(), 0x14, if (progressive) 0x88.toByte() else 0x80.toByte())))
            .build()
        assertTrue(VideoRate.interlaced(format(false)))
        assertFalse(VideoRate.interlaced(format(true)))
        assertEquals(59.94f, VideoRate.resolve(29.97f, 29.97f, interlaced = VideoRate.interlaced(format(false))), .001f)
    }
    @Test fun measuresMediaRatesAndDoesNotCarryThemAcrossChannels() {
        val cadence = FrameCadence()
        listOf(23.976f, 25f, 29.97f, 50f, 59.94f, 60f).forEachIndexed { token, fps ->
            assertEquals(0f, cadence.observe(token, 0), 0f)
            var measured = 0f
            for (frame in 1..100) measured = cadence.observe(token, (frame * 1_000_000.0 / fps).roundToLong())
            assertEquals(fps, measured, .025f)
        }
    }
    @Test fun duplicatesAndOccasionalSkippedFramesStillMeasure() {
        val cadence = FrameCadence(); var measured = 0f
        for (frame in 0..120) {
            if (frame % 9 == 8) continue
            val pts = (frame * 1_000_000.0 / 59.94).roundToLong()
            measured = cadence.observe(1, pts)
            cadence.observe(1, pts); cadence.observe(1, pts - 100)
        }
        assertEquals(59.94f, measured, .025f)
        assertFalse(VideoInfo(1920, 1080, 0f).label().contains("Measuring"))
    }
    @Test fun unstableIntervalsDoNotBecomeVerifiedFps() {
        val cadence = FrameCadence(); var pts = 0L; var rate = 0f
        repeat(200) { pts += if (it % 2 == 0) 16667 else 50000; rate = cadence.observe(0, pts) }
        assertEquals(0f, rate, 0f)
        assertTrue(VideoInfo(1920, 1080, 59.94f).label().contains("59.94 fps"))
    }
}

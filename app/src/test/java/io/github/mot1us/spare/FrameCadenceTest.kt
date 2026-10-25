package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class FrameCadenceTest {
    @Test fun measuresMediaRatesAndDoesNotCarryThemAcrossChannels() {
        val cadence = FrameCadence()
        listOf(23.976f, 25f, 29.97f, 50f, 59.94f, 60f).forEachIndexed { token, fps ->
            assertEquals(0f, cadence.observe(token, 0), 0f)
            var measured = 0f
            for (frame in 1..100) measured = cadence.observe(token, (frame * 1_000_000.0 / fps).roundToLong())
            assertEquals(fps, measured, .025f)
        }
    }
    @Test fun unstableIntervalsDoNotBecomeVerifiedFps() {
        val cadence = FrameCadence(); var pts = 0L; var rate = 0f
        repeat(200) { pts += if (it % 2 == 0) 16667 else 50000; rate = cadence.observe(0, pts) }
        assertEquals(0f, rate, 0f)
        assertTrue(VideoInfo(1920, 1080, 59.94f).label().contains("59.94 fps"))
    }
}

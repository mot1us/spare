package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NavigationSoundTest {
    @Test fun theTickIsShortQuietAndFadesToSilence() {
        val samples = NavigationSound.samples()
        assertEquals("30 ms at 48 kHz", 1440, samples.size)
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("Never near full scale: $peak", peak < Short.MAX_VALUE * 0.45)
        assertTrue("Starts softly, not with a click", abs(samples[0].toInt()) < 200)
        assertTrue("Fades out completely", samples.takeLast(48).all { abs(it.toInt()) < 400 })
    }
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class FrameRateTargetTest {
    @Test fun cachedSourceTimingSurvivesTemporaryHalfRateDecoderOrRenderSamples() {
        val target = FrameRateTarget(); target.reset(59.94f)
        assertNull(target.observe(29.97f, 0, false))
        assertNull(target.observe(29.97f, 2000, false))
        assertNull(target.observe(60f, 2500, true))
        assertNull(target.observe(59.94f, 3000, true))
    }
    @Test fun genuineRateChangesMustSettleAndFractionalJitterCannotTriggerSwitches() {
        val target = FrameRateTarget(); target.reset(59.94f)
        assertNull(target.observe(50f, 100, true))
        assertNull(target.observe(50f, 1599, true))
        assertEquals(50f, target.observe(50f, 1600, true)!!, .001f)
        assertNull(target.observe(59.94f, 1700, true))
        assertNull(target.observe(50f, 2000, true))
        assertNull(target.observe(59.94f, 2100, true))
        assertEquals(59.94f, target.observe(59.94f, 3600, true)!!, .001f)
        assertNull(target.observe(60f, 5600, true))
    }
    @Test fun freshStreamsAndNewSessionsDoNotReuseAnOldCandidate() {
        val target = FrameRateTarget()
        assertNull(target.observe(Float.NaN, 0, true))
        assertNull(target.observe(24f, 10, true))
        target.reset()
        assertNull(target.observe(24f, 2000, true))
        assertEquals(24f, target.observe(24f, 3500, true)!!, .001f)
    }
    @Test fun gapsAndInvalidSamplesRestartTheStabilityWindow() {
        val target = FrameRateTarget(); target.reset(60f)
        assertNull(target.observe(50f, 0, true)); assertNull(target.observe(50f, 30000, true))
        assertNull(target.observe(0f, 30500, true)); assertNull(target.observe(50f, 31000, true))
        assertNull(target.observe(50f, 32499, true)); assertEquals(50f, target.observe(50f, 32500, true)!!, .001f)
    }

    @Test fun vodCorrectsFractionalRatesOnlyAfterStableSourceTiming() {
        for ((rounded, fractional) in listOf(24f to 23.976f, 30f to 29.97f, 60f to 59.94f)) {
            val target = FrameRateTarget(); target.reset(rounded, exact = true)
            // Rounded decoder/render samples cannot correct the display hint.
            assertNull(target.observe(fractional, 0, false))
            assertNull(target.observe(fractional, 10000, false))
            for (now in 11000L..18500L step 500) assertNull(target.observe(fractional, now, true))
            assertEquals(fractional, target.observe(fractional, 19000, true)!!, .001f)
            assertNull(target.observe(fractional, 19500, true))
            // A brief rounded sample must not switch back.
            assertNull(target.observe(rounded, 20000, true))
            assertNull(target.observe(fractional, 20500, true))
        }
    }

    @Test fun vodCanStartWithoutARateAndReturningToLiveRestoresItsJitterTolerance() {
        val target = FrameRateTarget(); target.reset(exact = true)
        assertNull(target.observe(23.976f, 0, true))
        assertEquals(23.976f, target.observe(23.976f, 1500, true)!!, .001f)
        target.reset(59.94f)
        for (now in 2000L..15000L step 500) assertNull(target.observe(60f, now, true))
    }

}

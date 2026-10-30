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
}

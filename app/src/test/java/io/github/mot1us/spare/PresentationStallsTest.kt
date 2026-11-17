package io.github.mot1us.spare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationStallsTest {
    @Test fun twoStallsWithVideoBufferedSwitchAnOptionOff() {
        val stalls = PresentationStalls()
        assertFalse(stalls.stalled(9500, 1_000))
        assertTrue("The Google TV Streamer report: 9.5 s then 10.2 s buffered, five seconds apart", stalls.stalled(10_200, 6_000))
        assertFalse("The count starts again after switching", stalls.stalled(9_000, 7_000))
    }

    @Test fun anEmptyBufferIsTheNetworkNotTheBox() {
        val stalls = PresentationStalls()
        repeat(10) { assertFalse(stalls.stalled(0, it * 1_000L)) }
    }

    @Test fun stallsFarApartDoNotCount() {
        val stalls = PresentationStalls()
        assertFalse(stalls.stalled(5_000, 0))
        assertFalse(stalls.stalled(5_000, 700_000))
        assertTrue(stalls.stalled(5_000, 701_000))
    }
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaybackWarmupTest {
    @org.junit.Test fun bulkMaintenanceWaitsForLongerStablePlaybackAndAUsefulBuffer() {
        val warmup = PlaybackWarmup()
        assertFalse(warmup.ready(1, 0, true, 20000, 30000, 15000, 60000))
        assertFalse(warmup.ready(1, 29999, true, 20000, 30000, 15000, 60000))
        assertTrue(warmup.ready(1, 30000, true, 20000, 30000, 15000, 60000))
        assertFalse(warmup.ready(1, 31000, false, 20000, 30000, 15000, 60000))
        assertFalse(warmup.ready(1, 32000, true, 2000, 30000, 15000, 60000))
        assertFalse(warmup.ready(1, 91999, true, 2000, 30000, 15000, 60000))
        assertTrue(warmup.ready(1, 92000, true, 2000, 30000, 15000, 60000))
    }
    @Test fun bulkMetadataWaitsForStablePlaybackAndReadAhead() {
        val warmup = PlaybackWarmup()
        assertFalse(warmup.ready(1, 100, false, 60000))
        assertFalse(warmup.ready(1, 1000, true, 6000))
        assertFalse(warmup.ready(1, 8999, true, 6000))
        assertFalse(warmup.ready(1, 9000, true, 1000))
        assertTrue(warmup.ready(1, 9000, true, 6000))
    }
    @Test fun rebufferingAndNewTunesRestartTheQuietWindow() {
        val warmup = PlaybackWarmup()
        warmup.ready(1, 0, true, 6000)
        assertTrue(warmup.ready(1, 8000, true, 6000))
        assertFalse(warmup.ready(1, 9000, false, 0))
        assertFalse(warmup.ready(1, 10000, true, 6000))
        assertTrue(warmup.ready(1, 18000, true, 6000))
        assertFalse(warmup.ready(2, 18001, true, 6000))
        warmup.reset()
        assertFalse(warmup.ready(2, 27000, true, 6000))
    }
    @Test fun shortLiveWindowsCanEventuallyRefreshWithoutGrowingAnImpossibleBuffer() {
        val warmup = PlaybackWarmup()
        assertFalse(warmup.ready(1, 100, true, 1500))
        assertFalse(warmup.ready(1, 20099, true, 1500))
        assertTrue(warmup.ready(1, 20100, true, 1500))
    }
}

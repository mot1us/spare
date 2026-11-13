package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LibraryFramesTest {
    @Test fun tallyCountsSlowFramesAndTheRefreshesTheyCost() {
        val tally = FrameTally()
        assertNull("Nothing drawn, nothing to report", tally.summary())
        val period = 16_666_667L
        repeat(97) { tally.record(8_000_000, period, period) }
        tally.record(period, period, period)            // exactly on the deadline is on time
        tally.record(20_000_000, period, period)        // one refresh late
        tally.record(84_400_000, period, period)        // five refreshes late
        tally.record(0, period, period); tally.record(5, 0, period); tally.record(5, 5, 0)   // unusable reports are ignored
        assertEquals("100 drawn · 2 slow (2.0%) · about 6 dropped · worst 84 ms", tally.summary())
        tally.reset(); assertNull(tally.summary())
        // A longer system deadline (Android 12+) decides what is slow; refreshes are still counted in screen periods.
        tally.record(40_000_000, 33_333_334, period); tally.record(30_000_000, 33_333_334, period)
        assertEquals("2 drawn · 1 slow (50.0%) · about 1 dropped · worst 40 ms", tally.summary())
    }
}

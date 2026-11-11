package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    private val now = 1_000_000L

    @Test fun choicesCycleFromOffThroughTwoHoursAndBackToOff() {
        assertEquals(30, SleepTimer.next(0, now))
        assertEquals(60, SleepTimer.next(now + 30 * 60_000L, now))
        assertEquals(90, SleepTimer.next(now + 60 * 60_000L, now))
        assertEquals(120, SleepTimer.next(now + 90 * 60_000L, now))
        assertEquals(0, SleepTimer.next(now + 120 * 60_000L, now))
    }

    @Test fun aRunningTimerAdvancesFromWhatIsLeft() {
        // 42 minutes left: the next step up is an hour from now.
        assertEquals(60, SleepTimer.next(now + 42 * 60_000L, now))
        assertEquals("Stops playback in 42 min · select to change", SleepTimer.label(now + 41 * 60_000L + 1, now))
        assertEquals("Off", SleepTimer.label(0, now))
    }
}

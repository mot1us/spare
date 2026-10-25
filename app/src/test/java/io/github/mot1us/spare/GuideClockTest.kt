package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GuideClockTest {
    private val now = Instant.parse("2026-10-25T19:17:00Z").toEpochMilli()
    @Test fun timeBrowsingIsIndependentReversibleAndExpiresAtLive() {
        val guide = GuideTimeline(); val live = GuideTimeline.live(now)
        guide.move("one", 2, now)
        assertEquals(live + 3600000, guide.start("one", now))
        assertEquals(live, guide.start("two", now))
        guide.move("one", -1, now); assertEquals(live + GuideTimeline.SLOT, guide.start("one", now))
        guide.move("two", 4, now); guide.reset("one")
        assertFalse(guide.ahead("one", now)); assertTrue(guide.ahead("two", now))
        assertFalse(guide.ahead("two", now + 3 * 3600000))
    }
    @Test fun timeWindowsAreBoundedAndCannotMoveBeforeNow() {
        val guide = GuideTimeline(); guide.move("one", -100, now)
        assertEquals(GuideTimeline.live(now), guide.start("one", now))
        guide.move("one", 1000, now)
        assertEquals(GuideTimeline.live(now) + 48 * 3600000, guide.start("one", now))
        guide.clear(); assertFalse(guide.ahead("one", now))
    }
    @Test fun bothClockFormatsRespectTheTvTimeZone() {
        assertEquals("7:17 PM", GuideClock.time(now, false, ZoneId.of("UTC")))
        assertEquals("19:17", GuideClock.time(now, true, ZoneId.of("UTC")))
        assertEquals("12:17 PM", GuideClock.time(now, false, ZoneId.of("America/Los_Angeles")))
    }
}

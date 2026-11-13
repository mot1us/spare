package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsScoreboardMergeTest {
    private val scheduled = LiveEvent("tnf", setOf("nfl:SEA", "nfl:SF"), 1000, "pre", league = "NFL")
    @Test fun cachedWeeklyScheduleCannotOverwriteFreshLiveDailyScoreboard() {
        assertFalse(SportsSchedule.preferEvent(scheduled.copy(state = "in"), 200, scheduled, 100))
        assertTrue(SportsSchedule.preferEvent(scheduled, 100, scheduled.copy(state = "in"), 200))
    }
    @Test fun sameRefreshNeverRegressesLiveOrFinalBecauseRequestsFinishOutOfOrder() {
        assertFalse(SportsSchedule.preferEvent(scheduled.copy(state = "in"), 200, scheduled, 200))
        assertFalse(SportsSchedule.preferEvent(scheduled.copy(state = "post"), 200, scheduled.copy(state = "in"), 200))
        assertTrue(SportsSchedule.preferEvent(scheduled, 200, scheduled.copy(state = "in"), 200))
    }
    @Test fun newerCorrectionAndScoreboardAuthorityArePreserved() {
        assertTrue(SportsSchedule.preferEvent(scheduled.copy(state = "in"), 200, scheduled.copy(status = "Delayed"), 300))
        assertFalse(SportsSchedule.preferEvent(scheduled, 100, scheduled.copy(scoreboard = false), 300))
        assertTrue(SportsSchedule.preferEvent(null, 0, scheduled, 200))
    }
}

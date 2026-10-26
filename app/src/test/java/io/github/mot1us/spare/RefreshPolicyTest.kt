package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RefreshPolicyTest {
    @Test fun manualAndLaunchAreIndependentOfIntervals() {
        assertFalse(RefreshPolicy().playlistDue(0, 100000000))
        assertFalse(RefreshPolicy().epgDue(0, 100000000, true))
        val launch = RefreshPolicy(playlistOnLaunch = true)
        assertTrue(launch.playlistDue(100, 200, true)); assertFalse(launch.playlistDue(100, 200))
        val timed = RefreshPolicy(playlistHours = 24, epgHours = 6)
        assertFalse(timed.playlistDue(1000, 6 * 3600000L + 1000)); assertTrue(timed.epgDue(1000, 6 * 3600000L + 1000))
        assertTrue(timed.playlistDue(2000, 1000)); assertTrue(timed.epgDue(0, 1000))
    }
    @Test fun gameDatesKeepCalendarDateAndRespectMidnightAndClockPreference() {
        val zone = ZoneId.of("America/Los_Angeles"); val now = Instant.parse("2026-10-26T21:00:00Z").toEpochMilli()
        val game = Instant.parse("2026-10-27T00:15:00Z").toEpochMilli()
        assertTrue(GuideClock.sportsDate(game, now, zone, false).startsWith("Today"))
        assertTrue(GuideClock.sportsDate(game, now, zone, true).contains("17:15"))
        assertTrue(GuideClock.sportsDate(game + 86400000, now, zone).startsWith("Tomorrow"))
        assertTrue(GuideClock.sportsDate(game + 2 * 86400000, now, zone).contains("Oct"))
    }
    @Test fun leagueHeroPrefersTonightOverFollowedTeamsNextWeekAndOldResults() {
        val now = System.currentTimeMillis(); fun game(a: String, b: String, state: String, start: Long) = SportsGame(listOf(SportsCatalog.byId.getValue(a), SportsCatalog.byId.getValue(b)), start, start + 3600000, emptyList(), "$a:$state", state)
        val tonight = game("nfl:DAL", "nfl:PHI", "pre", now + 1000)
        val later = game("nfl:SEA", "nfl:SF", "pre", now + 4 * 86400000L)
        val final = tonight.copy(eventId = "final", state = "post", start = now - 86400000)
        assertEquals(tonight, SportsPanel.featured(listOf(final, later, tonight), setOf("nfl:SEA"), now, true))
        val live = tonight.copy(state = "in", start = now - 1000)
        assertEquals(live, SportsPanel.featured(listOf(tonight, later, live), setOf("nfl:SEA"), now, true))
    }
}

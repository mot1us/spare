package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GameRemindersTest {
    private val now = 1_800_000_000_000L
    private val teams = listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }
    private fun game(start: Long = now + 60_000L) = SportsGame(teams, start, start + 8 * 3600000L, emptyList(), "reminder-test", "pre", competition = "NFL")
    private fun event(start: Long = now, state: String = "in") = LiveEvent("reminder-test", teams.map { it.id }.toSet(), start, state, league = "NFL")

    @Test fun scheduledTimeAloneCannotFireAndDelayedStartsAreUpdated() {
        val saved = GameReminders(); val game = game()
        assertTrue(saved.toggle(game)); assertTrue(saved.has(game)); assertTrue(saved.targets(now).isNotEmpty())
        assertTrue(saved.update(listOf(event(state = "pre")), now).isEmpty())
        assertTrue(saved.update(emptyList(), now + 300_000L).isEmpty())
        saved.update(listOf(event(now + 86400000L, "pre")), now)
        assertTrue(saved.targets(now).isEmpty())
        assertEquals(now + 86400000L, saved.events.single().start)
    }
    @Test fun confirmedLiveReminderSurvivesRestartAndIsDeliveredOnlyOnce() {
        val saved = GameReminders(); saved.toggle(game())
        val restored = GameReminders.from(saved.json())
        val live = restored.update(listOf(event()), now).single()
        assertTrue(live.live(now)); assertEquals(game().key, live.key)
        restored.delivered(live)
        val again = GameReminders.from(restored.json())
        assertTrue(again.update(listOf(event()), now).isEmpty()); assertTrue(again.targets(now).isEmpty())
        again.update(listOf(event(state = "post")), now)
        assertTrue(again.events.isEmpty())
    }
    @Test fun cancellationAndLeagueIdentityPreventWrongAlerts() {
        val saved = GameReminders(); saved.toggle(game())
        assertTrue(saved.update(listOf(event().copy(league = "MLB")), now).isEmpty())
        assertFalse(saved.toggle(game())); assertFalse(saved.has(game()))
        assertTrue(saved.update(listOf(event()), now).isEmpty())
        saved.toggle(game()); saved.update(emptyList(), now + 3 * 86400000L)
        assertTrue(saved.events.isEmpty())
    }
    @Test fun bestStreamRequiresCurrentGameCoverageAndVerifiedFhdSixty() {
        val fhd = Channel("fhd", "FHD", "Sports", "https://example.com/fhd")
        val uhdThirty = fhd.copy(id = "4k30", name = "4K 30")
        val possible = fhd.copy(id = "possible", name = "4K 60")
        val reported = fhd.copy(id = "reported", reportedQuality = VideoInfo(3840, 2160, 60f))
        val programme = Programme("game", "Seattle vs San Francisco", now - 60000, now + 3600000)
        val live = game(now - 60000).copy(state = "in", broadcasts = listOf(fhd, uhdThirty, reported).map { SportsBroadcast(it, programme) }, candidates = listOf(possible))
        val quality = mapOf(fhd.id to VideoInfo(1920, 1080, 59.94f), uhdThirty.id to VideoInfo(3840, 2160, 29.97f), possible.id to VideoInfo(3840, 2160, 60f))
        val ranked = SportsStreams.rank(live, GuideFilters(), quality)
        assertEquals(reported, SportsStreams.bestConfirmed(live, ranked, quality, now))
        assertEquals("A measured 30 fps overrides the provider's 60 fps declaration", fhd, SportsStreams.bestConfirmed(live, ranked, quality + (reported.id to VideoInfo(3840, 2160, 30f)), now))
        assertEquals(listOf(reported, fhd, uhdThirty, possible), ranked)
        assertEquals(listOf(reported, fhd, possible), SportsStreams.rank(live, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), quality))
        assertNull(SportsStreams.bestConfirmed(live.copy(state = "pre"), ranked, quality, now))
        assertNull(SportsStreams.bestConfirmed(live.copy(broadcasts = listOf(SportsBroadcast(fhd, programme.copy(start = now + 60000)))), listOf(fhd), quality, now))
    }
}

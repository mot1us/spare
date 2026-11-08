package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsResultsTest {
    private val now = 1_800_000_000_000L
    private val game = SportsGame(listOf("nfl:CLE", "nfl:NO").map { SportsCatalog.byId.getValue(it) },
        now - 60_000, now + 3_600_000, emptyList(), "results", "in", networks = listOf("CBS"), competition = "NFL")
    private fun channel(id: String, height: Int) = Channel(id, "CBS $id", "NFL", "https://example.test/$id",
        reportedQuality = VideoInfo(0, height, 0f))
    private fun listing(channel: Channel, title: String = game.title) = Programme(channel.id, title, game.start, game.end)

    @Test fun knownHdGameHidesHigherResolutionBroadcasterGuessesFromNormalResults() {
        val hd = channel("hd", 720)
        val fhd = channel("fhd", 1080)
        val uhd = channel("uhd", 2160)
        val programme = listing(hd)
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(hd, programme)), candidates = listOf(fhd, uhd))
        val guide = Guide(listOf(hd, fhd, uhd), mapOf(hd.id to listOf(programme)))
        assertEquals(listOf(hd), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        assertEquals(SportsStreams.Coverage.EPG, SportsStreams.coverage(matched, hd, guide, now))
        assertEquals(SportsStreams.Coverage.UNCONFIRMED, SportsStreams.coverage(matched, uhd, guide, now))
        assertEquals("Unknown FPS does not remove the actual game", listOf(hd),
            SportsStreams.automaticChoices(matched, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), emptyMap(), guide, now))
    }

    @Test fun freshFhdMatchIsIncludedAboveHdEvenBeforeCachedCandidatesArePromoted() {
        val hd = channel("hd", 720)
        val fhd = channel("fhd", 1080)
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(hd, listing(hd))), candidates = listOf(fhd))
        val guide = Guide(listOf(hd, fhd), mapOf(hd.id to listOf(listing(hd)), fhd.id to listOf(listing(fhd))))
        assertEquals(listOf(fhd, hd), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        assertEquals(SportsStreams.Coverage.EPG, SportsStreams.coverage(matched, fhd, guide, now))
        assertEquals("Availability still excludes a failed/missing feed", listOf(hd),
            SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now) { it.id != fhd.id })
    }

    @Test fun exactLiveChannelTitleWithoutEpgRemainsARealAlternative() {
        val title = channel("named", 1080).copy(name = "NFL Cleveland Browns vs New Orleans Saints FHD")
        val guess = channel("guess", 2160)
        val guide = Guide(listOf(title, guess), emptyMap())
        val matched = SportsSchedule.match(game, guide, emptySet(), now)
        assertTrue(title in matched.titleMatches)
        assertEquals(listOf(title), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        assertEquals(SportsStreams.Coverage.CHANNEL_TITLE, SportsStreams.coverage(matched, title, guide, now))
    }

    @Test fun expiredAndReplacedListingsCannotKeepAnEpgMatchBadge() {
        val hd = channel("hd", 720)
        val old = channel("old", 2160)
        val wrong = channel("wrong", 1080)
        val replaced = channel("replaced", 1080)
        val expired = listing(old).copy(start = now - 3_600_000, end = now - 1)
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(hd, listing(hd)), SportsBroadcast(old, expired),
            SportsBroadcast(wrong, listing(wrong)), SportsBroadcast(replaced, listing(replaced))))
        val guide = Guide(listOf(hd, old, wrong, replaced), mapOf(hd.id to listOf(listing(hd)), old.id to listOf(expired),
            wrong.id to listOf(listing(wrong, "NFL: Buffalo Bills vs Kansas City Chiefs")),
            replaced.id to listOf(listing(replaced, "Local headlines"))))
        assertEquals(listOf(hd), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        listOf(old, wrong, replaced).forEach {
            assertEquals(SportsStreams.Coverage.UNCONFIRMED, SportsStreams.coverage(matched, it, guide, now))
        }
        assertFalse("A known other game is never offered as a fallback",
            wrong in SportsStreams.results(matched.copy(broadcasts = matched.broadcasts.drop(1)), GuideFilters(), emptyMap(), guide, now))
    }

    @Test fun suggestionsRemainAvailableOnlyWhenNoActualGameFeedIsKnown() {
        val fhd = channel("fhd", 1080)
        val uhd = channel("uhd", 2160)
        val possible = game.copy(candidates = listOf(fhd, uhd))
        val guide = Guide(listOf(fhd, uhd), emptyMap())
        assertEquals(listOf(uhd, fhd), SportsStreams.results(possible, GuideFilters(), emptyMap(), guide, now))
        assertTrue(SportsStreams.automaticChoices(possible, GuideFilters(), emptyMap(), guide, now).isEmpty())
        assertEquals(SportsStreams.Coverage.UNCONFIRMED, SportsStreams.coverage(possible, uhd, guide, now))
    }

    @Test fun refreshedUnrelatedProgrammeCannotAutoplayAnOldFourKGameMatch() {
        val hd = channel("working", 720)
        val stale = channel("stale", 2160)
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(hd, listing(hd)), SportsBroadcast(stale, listing(stale))))
        val guide = Guide(listOf(hd, stale), mapOf(hd.id to listOf(listing(hd)), stale.id to listOf(listing(stale, "Local headlines"))))
        assertEquals(listOf(hd), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        assertEquals(listOf(hd), SportsStreams.automaticChoices(matched, GuideFilters(), emptyMap(), guide, now))
        assertTrue(SportsStreams.cardSummary(matched, GuideFilters(), emptyMap(), guide, now).startsWith(hd.name + "\n"))
        val missing = guide.copy(programmes = guide.programmes - stale.id)
        assertEquals("A still-active saved EPG match remains usable without fresh guide data", listOf(stale, hd),
            SportsStreams.automaticChoices(matched, GuideFilters(), emptyMap(), missing, now))
        val title = channel("title", 1080).copy(name = "NFL Cleveland Browns vs New Orleans Saints FHD")
        assertEquals("Exact titles still play even with unknown FPS and no EPG", listOf(title),
            SportsStreams.automaticChoices(game.copy(titleMatches = listOf(title)), GuideFilters(1080, FrameRateFilter.sixtyOrBetter), emptyMap(), Guide.EMPTY, now))
    }

    @Test fun futureGameStillUsesItsScheduledListingAfterKickoffNotTheCurrentHighlights() {
        val future = game.copy(state = "pre", start = now + 3_600_000, end = now + 7_200_000)
        val scheduled = channel("scheduled", 1080)
        val programme = Programme(scheduled.id, game.title, future.start + 5 * 60_000, future.end)
        val matched = future.copy(broadcasts = listOf(SportsBroadcast(scheduled, programme)))
        val guide = Guide(listOf(scheduled), mapOf(scheduled.id to listOf(
            Programme(scheduled.id, "NFL Highlights", now - 60_000, programme.start), programme)))
        assertEquals(listOf(scheduled), SportsStreams.results(matched, GuideFilters(), emptyMap(), guide, now))
        assertEquals(SportsStreams.Coverage.EPG, SportsStreams.coverage(matched, scheduled, guide, now))
        assertTrue(SportsStreams.automaticChoices(matched, GuideFilters(), emptyMap(), guide, now).isEmpty())
    }
}

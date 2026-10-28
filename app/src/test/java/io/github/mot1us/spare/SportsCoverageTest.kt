package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsCoverageTest {
    private val now = java.time.Instant.parse("2026-10-26T23:00:00Z").toEpochMilli()
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60000, now + 3600000, emptyList(), "game", "in", networks = listOf("ESPN"), competition = "NFL")
    private fun channel(id: String, name: String) = Channel(id, name, "NFL", "https://example.com/$id")
    @Test fun watchBestCanTryTheAdvertisedBroadcasterButNeverKnownOtherGamesOrReplays() {
        val espn = channel("network", "USA ESPN 4K")
        val other = channel("other", "USA ESPN 2 4K")
        val mascot = channel("mascot", "Seattle Seahawks TV")
        val quality = listOf(espn, other, mascot).associate { it.id to VideoInfo(3840, 2160, 59.94f) }
        val possible = game.copy(candidates = listOf(espn, other, mascot))
        assertEquals(espn, SportsStreams.bestBroadcaster(possible, listOf(mascot, espn), quality, Guide.EMPTY, now))
        assertNull(SportsStreams.bestBroadcaster(possible, listOf(other, mascot), quality, Guide.EMPTY, now))
        assertNull(SportsStreams.bestBroadcaster(possible, listOf(espn.copy(name = "ESPN+ 4K")), quality, Guide.EMPTY, now))
        assertNull(SportsStreams.bestBroadcaster(possible.copy(networks = listOf("ESPN+")), listOf(espn), quality, Guide.EMPTY, now))
        val different = Programme(espn.id, "NFL: Buffalo Bills vs Kansas City Chiefs", now - 60000, now + 3600000)
        assertNull(SportsStreams.bestBroadcaster(possible, listOf(espn), quality, Guide(listOf(espn), mapOf(espn.id to listOf(different))), now))
        assertNull(SportsStreams.bestBroadcaster(possible, listOf(espn), quality, Guide(listOf(espn), mapOf(espn.id to listOf(different.copy(title = "NFL Highlights")))), now))
        assertNull(SportsStreams.bestBroadcaster(possible, listOf(espn), emptyMap(), Guide.EMPTY, now))
        assertNull(SportsStreams.bestBroadcaster(possible.copy(state = "pre"), listOf(espn), quality, Guide.EMPTY, now))
        assertNull(SportsStreams.bestBroadcaster(possible.copy(networks = emptyList()), listOf(espn, mascot), quality, Guide.EMPTY, now))
    }
    @Test fun watchBestUsesTheSameProviderQualityAsFiltersAndExplainsMissingEvidence() {
        val reported = channel("reported", "ESPN 4K").copy(reportedQuality = VideoInfo(3840, 2160, 59.94f))
        val listing = Programme(reported.id, game.title, now - 60000, now + 3600000)
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(reported, listing)))
        val ranked = SportsStreams.rank(matched, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), emptyMap())
        assertEquals(reported, SportsStreams.bestConfirmed(matched, ranked, emptyMap(), now))
        assertNull(SportsStreams.bestConfirmed(matched, ranked, mapOf(reported.id to VideoInfo(3840, 2160, 50f)), now))
        val unknown = reported.copy(reportedQuality = VideoInfo(3840, 2160, 0f))
        assertNull(SportsStreams.bestConfirmed(matched, listOf(unknown), emptyMap(), now))
        assertTrue(SportsStreams.unavailableReason(matched, listOf(unknown), emptyMap(), now).contains("FPS is learned during playback"))
        val possible = game.copy(candidates = listOf(reported))
        assertNull(SportsStreams.bestConfirmed(possible, ranked, emptyMap(), now))
        assertTrue(SportsStreams.unavailableReason(possible, ranked, emptyMap(), now).contains("coverage unconfirmed"))
    }
    @Test fun schedulesOnlyRequestChosenLeagues() {
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val snapshot = SportsSchedule.load(emptySet(), enabledLeagues = setOf("NFL"), get = { url -> urls.add(url); """{"events":[]}""" })
        assertTrue(urls.isNotEmpty()); assertTrue(urls.all { "/football/nfl/" in it }); assertEquals(setOf("NFL"), snapshot.leagues)
    }
    @Test fun liveEventTitlesWithoutEpgAreRankedButReplaysGenericNetworksAndOldDatesAreNotConfirmed() {
        val good = channel("good", "NFL 01 · Seattle Seahawks vs San Francisco 49ers 4K 60FPS")
        val replay = channel("replay", "Replay Seattle Seahawks vs San Francisco 49ers")
        val generic = channel("generic", "ESPN FHD")
        val old = channel("old", "NFL Seattle Seahawks vs San Francisco 49ers 10/15")
        val result = SportsSchedule.match(game, Guide(listOf(good, replay, generic, old), emptyMap()), emptySet(), now)
        assertEquals(listOf(good), result.titleMatches)
        assertFalse(result.candidates.contains(replay)); assertTrue(result.candidates.contains(generic))
        val quality = mapOf(good.id to VideoInfo(3840, 2160, 59.94f))
        val ranked = SportsStreams.rank(result, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), quality)
        assertEquals(good, SportsStreams.bestConfirmed(result, ranked, quality, now))
        val epgFhd = channel("epg", "ESPN FHD")
        val combined = result.copy(broadcasts = listOf(SportsBroadcast(epgFhd, Programme(epgFhd.id, game.title, now - 60000, now + 3600000))))
        val both = quality + (epgFhd.id to VideoInfo(1920, 1080, 60f))
        assertEquals("A 4K/60 title match outranks an FHD/60 EPG match", good, SportsStreams.bestConfirmed(combined, SportsStreams.rank(combined, GuideFilters(), both), both, now))
        assertTrue(SportsSchedule.match(game.copy(state = "pre", start = now + 3600000), Guide(listOf(good), emptyMap()), emptySet(), now).titleMatches.isEmpty())
    }
    @Test fun largeLineupChecksCurrentEntriesAndUsesOnlyRelevantTeamDescriptions() {
        val channels = (0 until 25000).map { channel("$it", if (it == 24999) "ESPN" else "News $it") }
        val programmes = channels.associate { it.id to listOf(Programme(it.id, if (it.id == "24999") "NFL: Seahawks vs 49ers" else "Local headlines", now - 60000, now + 3600000, "News, weather and community coverage")) }
        var updates = 0
        val started = System.nanoTime()
        val found = SportsSchedule.match(game, Guide(channels, programmes), emptySet(), now, progress = { _, _ -> updates++ })
        assertEquals("24999", found.broadcasts.single().channel.id); assertTrue(updates > 0)
        assertTrue("Large-lineup matching should take less than four seconds", System.nanoTime() - started < 4_000_000_000L)
        val partial = SportsSchedule.match(game, Guide(channels, programmes), emptySet(), now, deadlineNanos = 0)
        assertFalse(partial.coverageComplete)
    }
}

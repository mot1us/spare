package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsCoverageTest {
    private val now = java.time.Instant.parse("2026-10-26T23:00:00Z").toEpochMilli()
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60000, now + 3600000, emptyList(), "game", "in", networks = listOf("ESPN"), competition = "NFL")
    private fun channel(id: String, name: String) = Channel(id, name, "NFL", "https://example.com/$id")
    @Test fun blazersGuideAliasesPromoteTheOtherProvidersFhdGameAboveHdCoverage() {
        val basketball = game.copy(teams = listOf("nba:POR", "nba:MIN").map { SportsCatalog.byId.getValue(it) }, competition = "NBA")
        val hd = channel("hd", "NBA Event 01 HD").copy(playlistId = "main", group = "NBA", reportedQuality = VideoInfo(1280, 720, 60f))
        val fhd = channel("fhd", "NBA Event 02 FHD").copy(playlistId = "other", group = "NBA", reportedQuality = VideoInfo(1920, 1080, 0f))
        for (title in listOf("NBA: Timberwolves at Blazers", "NBA: Minnesota Timberwolves vs Portland Trailblazers")) {
            val guide = Guide(listOf(hd, fhd), mapOf(hd.id to listOf(Programme(hd.id, basketball.title, game.start, game.end)),
                fhd.id to listOf(Programme(fhd.id, title, game.start, game.end))))
            val matched = SportsSchedule.match(basketball, guide, emptySet(), now)
            assertEquals(setOf(hd.id, fhd.id), matched.broadcasts.map { it.channel.id }.toSet())
            assertEquals(listOf(fhd, hd), SportsStreams.automaticChoices(matched, GuideFilters(), emptyMap(), guide, now))
        }
    }
    @Test fun confirmedHdCoverageStillChecksOnlyHigherResolutionMetadataOnce() {
        val hd = channel("hd", "NFL 01 HD").copy(reportedQuality = VideoInfo(1280, 720, 60f))
        val equal = channel("equal", "NFL 02 HD").copy(reportedQuality = VideoInfo(1280, 720, 60f))
        val fhd = channel("fhd", "ESPN FHD").copy(reportedQuality = VideoInfo(1920, 1080, 0f))
        val wrong = channel("wrong", "ESPN 4K").copy(reportedQuality = VideoInfo(3840, 2160, 60f))
        val listing = Programme(hd.id, game.title, game.start, game.end)
        val ready = game.copy(broadcasts = listOf(SportsBroadcast(hd, listing)), candidates = listOf(equal, fhd, wrong))
        val guide = Guide(listOf(hd, equal, fhd, wrong), mapOf(hd.id to listOf(listing), wrong.id to listOf(listing.copy(channel = wrong.id, title = "NFL: Buffalo Bills at Kansas City Chiefs"))))
        assertEquals(720, SportsStreams.confirmationFloor(ready, GuideFilters(), emptyMap(), now))
        assertEquals(listOf(fhd), SportsStreams.confirmationTargets(ready, guide, ready.candidates, emptySet(), emptyMap(), now, betterThanHeight = 720))
        val cached = SportsStreamCache.Match(ready)
        assertTrue(cached.needsUpgrade(GuideFilters(), emptyMap(), now))
        assertFalse("A completed metadata upgrade check must not run on every card visit", cached.copy(upgradeChecked = true).needsUpgrade(GuideFilters(), emptyMap(), now))
        assertFalse(cached.needsUpgrade(GuideFilters(groups = setOf("Hidden")), emptyMap(), now))
        assertNull(SportsStreams.confirmationFloor(ready.copy(candidates = listOf(equal)), GuideFilters(), emptyMap(), now))
    }
    @Test fun basketballFindsTheOtherProvidersLateEpgAndExcludesRadio() {
        val basketball = game.copy(teams = listOf("nba:BOS", "nba:MIL").map { SportsCatalog.byId.getValue(it) }, competition = "NBA", networks = listOf("ESPN"))
        val filler = (1..26000).map { channel("filler$it", "Local news $it").copy(playlistId = if (it % 2 == 0) "main" else "other") }
        val radio = channel("radio", "Bucks at Celtics Radio 4K").copy(playlistId = "main", group = "Radio")
        val video = channel("second-provider", "Boston Celtics FHD").copy(playlistId = "other", group = "NBA Teams")
        val title = Programme(video.id, "NBA: Bucks at Celtics", game.start, game.end)
        val guide = Guide(filler + radio + video, mapOf(radio.id to listOf(title.copy(channel = radio.id)), video.id to listOf(title)))
        var checked = 0; var total = 0
        val found = SportsSchedule.match(basketball, guide, emptySet(), now, progress = { a, b -> checked = a; total = b })
        assertTrue(found.coverageComplete); assertEquals(total, checked)
        assertEquals(listOf(video), found.broadcasts.map { it.channel })
        assertEquals(listOf(video), SportsStreams.automaticChoices(found, GuideFilters(), emptyMap(), guide, now))
        val normalized = Channel::class.java.getDeclaredField("sportsKey\$delegate").apply { isAccessible = true }.get(filler.first()) as Lazy<*>
        assertFalse("Unrelated channels need no sports-name normalization", normalized.isInitialized())
    }
    @Test fun missingEpgChecksNamedTeamFeedsBeforeGenericUhdAndBalancesProviders() {
        val basketball = game.copy(teams = listOf("nba:BOS", "nba:MIL").map { SportsCatalog.byId.getValue(it) }, competition = "NBA")
        val generic = (1..60).map { channel("nba$it", "NBA Event $it 4K").copy(group = "NBA", reportedQuality = VideoInfo(3840, 2160, 0f)) }
        val a = channel("a", "Boston Celtics FHD").copy(playlistId = "main", group = "NBA Teams", reportedQuality = VideoInfo(1920, 1080, 0f))
        val b = a.copy(id = "b", playlistId = "other")
        val radio = a.copy(id = "radio", name = "Celtics Radio")
        val targets = SportsStreams.confirmationTargets(basketball, Guide(generic + a + b + radio, emptyMap()), generic, emptySet(), emptyMap(), now)
        assertEquals(setOf(a, b), targets.take(2).toSet()); assertFalse(radio in targets); assertTrue(targets.size <= 12)
    }
    @Test fun watchBestCanTryTheAdvertisedBroadcasterButNeverKnownOtherGamesOrReplays() {
        val espn = channel("network", "USA ESPN 4K")
        val other = channel("other", "USA ESPN 2 4K")
        val mascot = channel("mascot", "Seattle Seahawks TV")
        val quality = listOf(espn, other, mascot).associate { it.id to VideoInfo(3840, 2160, 59.94f) }
        val possible = game.copy(candidates = listOf(espn, other, mascot))
        assertNull("A broadcaster name alone cannot trigger automatic tuning", SportsStreams.bestBroadcaster(possible, listOf(mascot, espn), quality, Guide.EMPTY, now))
        val confirmedGuide = Guide(listOf(espn), mapOf(espn.id to listOf(Programme(espn.id, game.title, game.start, game.end))))
        assertEquals(espn, SportsStreams.bestBroadcaster(possible, listOf(mascot, espn), quality, confirmedGuide, now))
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
    @Test fun krakenGuideMatchBeatsUnconfirmedFourKAndKeepsHdThirtyAndUnknownFpsPlayable() {
        val hockey = game.copy(teams = listOf("nhl:TOR", "nhl:SEA").map { SportsCatalog.byId.getValue(it) }, competition = "NHL")
        val correct = channel("nhl7", "NHL 07 HD").copy(group = "NHL", reportedQuality = VideoInfo(1280, 720, 30f))
        val guess = channel("guess", "ESPN 4K").copy(reportedQuality = VideoInfo(3840, 2160, 60f))
        val other = correct.copy(id = "other", name = "NHL 08 4K", reportedQuality = VideoInfo(3840, 2160, 60f))
        for (title in listOf("Maple Leafs at Kraken", "TOR @ SEA")) {
            val guide = Guide(listOf(guess, correct, other), mapOf(correct.id to listOf(Programme(correct.id, title, game.start, game.end)),
                other.id to listOf(Programme(other.id, "NHL: Boston Bruins at Carolina Hurricanes", game.start, game.end))))
            val found = SportsSchedule.match(hockey, guide, emptySet(), now)
            assertEquals(listOf(correct), found.broadcasts.map { it.channel })
            val profile = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
            assertEquals(listOf(correct), SportsStreams.automaticChoices(found, profile, emptyMap(), guide, now))
            assertEquals(listOf(correct), SportsStreams.automaticChoices(found, profile, mapOf(correct.id to VideoInfo(1280, 720, 0f)), guide, now))
            assertTrue(SportsStreams.automaticChoices(hockey.copy(candidates = listOf(guess)), profile, emptyMap(), guide, now).isEmpty())
        }
    }
    @Test fun coverageLookupIncludesLeagueEventChannelsAndDoesNotFillWithBroadcasterVariantsOrKnownOtherGames() {
        val hockey = game.copy(teams = listOf("nhl:TOR", "nhl:SEA").map { SportsCatalog.byId.getValue(it) }, competition = "NHL")
        val event = channel("event", "NHL 07 HD").copy(group = "NHL", reportedQuality = VideoInfo(1280, 720, 30f))
        val networks = (1..20).map { channel("espn$it", "ESPN ${if (it % 2 == 0) "4K" else "FHD"}") }
        val other = event.copy(id = "wrong", name = "NHL 08")
        val guide = Guide(networks + event + other, mapOf(other.id to listOf(Programme(other.id, "Bruins at Hurricanes", game.start, game.end))))
        val targets = SportsStreams.confirmationTargets(hockey, guide, networks, emptySet(), emptyMap(), now)
        assertTrue(event in targets); assertFalse(other in targets)
        assertEquals(2, targets.size); assertTrue(targets.size <= 8)
    }
    @Test fun genericFourKFeedIsCheckedEvenWhenAnHdGameIsAlreadyKnown() {
        val teams = listOf("nfl:CAR", "nfl:GB").map { SportsCatalog.byId.getValue(it) }
        val football = game.copy(teams = teams, networks = listOf("Prime Video"))
        val hd = channel("hd", "NFL 01 HD").copy(reportedQuality = VideoInfo(1280, 720, 60f))
        val uhd = channel("uhd", "USA UHD Event 1").copy(group = "4K", reportedQuality = VideoInfo(3840, 2160, 0f))
        val programme = Programme(hd.id, "NFL Football: Carolina Panthers at Green Bay Packers", now - 4 * 3600000L, now + 3600000L)
        val before = Guide(listOf(hd, uhd), mapOf(hd.id to listOf(programme)))
        val first = SportsSchedule.match(football, before, emptySet(), now)
        assertEquals(listOf(hd), first.broadcasts.map { it.channel })
        assertTrue(uhd in SportsStreams.confirmationTargets(first, before, SportsStreams.rank(first, GuideFilters(), emptyMap()), emptySet(), emptyMap(), now))
        val after = before.copy(programmes = before.programmes + (uhd.id to listOf(programme.copy(channel = uhd.id))))
        assertFalse("New UHD coverage can enrich the ready game without discarding its working HD result", SportsStreamCache.Match(first).needsRefresh(before, after, now))
        val matched = SportsSchedule.match(football, after, emptySet(), now)
        assertEquals(listOf(uhd, hd), SportsStreams.automaticChoices(matched, GuideFilters(), emptyMap(), after, now))
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
    @Test fun identicalProviderNamesKeepBothOwnersInsideTheLookupBudget() {
        val a = Source(id = "main", name = "Main", kind = "xtream", url = "https://main.example")
        val b = a.copy(id = "second", name = "Second", url = "https://second.example")
        val catalog = PlaylistCatalog(a.id).apply { configure(listOf(a, b)) }
        val raw = (1..20).map { channel("xc:$it", "NFL Event $it FHD").copy(reportedQuality = VideoInfo(1920, 1080, 0f)) }
        val channels = raw.map { catalog.scope(a, it) } + raw.map { catalog.scope(b, it) }
        val targets = SportsStreams.confirmationTargets(game, Guide(channels, emptyMap()), emptyList(), emptySet(), emptyMap(), now)
        assertEquals(12, targets.size)
        assertEquals(setOf(a.id, b.id), targets.take(2).map { catalog.owner(it)!!.id }.toSet())
        assertEquals(6, targets.count { catalog.owner(it) == b })
        assertTrue(targets.any { it.nativeId == "xc:1" && catalog.owner(it) == a })
        assertTrue(targets.any { it.nativeId == "xc:1" && catalog.owner(it) == b })
    }
    @Test fun existingProgrammeEvidenceMatchesSecondaryProviderBeforeNameDiscovery() {
        val a = Source(id = "main", name = "Main", kind = "xtream", url = "https://main.example")
        val b = a.copy(id = "second", url = "https://second.example")
        val catalog = PlaylistCatalog(a.id).apply { configure(listOf(a, b)) }
        val raw = channel("xc:1", "ESPN FHD")
        val first = catalog.scope(a, raw); val second = catalog.scope(b, raw)
        val other = Programme(first.id, "NFL: Bills at Chiefs", game.start, game.end)
        val correct = Programme(second.guideId, "NFL: Seattle Seahawks at San Francisco 49ers", game.start, game.end)
        val guide = Guide(listOf(first, second), mapOf(first.id to listOf(other), second.guideId to listOf(correct)))
        val found = SportsSchedule.match(game, guide, emptySet(), now)
        assertEquals(listOf(second), found.broadcasts.map { it.channel })
        assertEquals(listOf(second), SportsStreams.automaticChoices(found, GuideFilters(), emptyMap(), guide, now))
    }
    @Test fun nonSportsCategoriesSkipSpeculationButCannotHideAnExactGameListing() {
        val movie = channel("movie", "NFL Football 4K").copy(group = "Movies")
        val loop = channel("loop", "ESPN 24/7 4K").copy(group = "USA 24/7")
        val pbs = channel("pbs", "PBS 4K").copy(group = "USA")
        val actual = movie.copy(id = "actual", name = "Unusually categorized feed")
        val guide = Guide(listOf(movie, loop, pbs, actual), mapOf(actual.id to listOf(Programme(actual.id, game.title, game.start, game.end))))
        assertTrue(SportsStreams.confirmationTargets(game, guide, listOf(movie, loop, pbs), emptySet(), emptyMap(), now).isEmpty())
        val match = SportsSchedule.match(game, guide, emptySet(), now)
        assertEquals(listOf(actual), match.broadcasts.map { it.channel })
        assertEquals(listOf(actual), SportsStreams.automaticChoices(match, GuideFilters(), emptyMap(), guide, now))
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
    @Test fun upcomingCoverageUsesFutureEpgAndAllowsListingsThatStartJustAfterKickoff() {
        val future = game.copy(state = "pre", start = now + 4 * 3600000L, end = now + 7 * 3600000L)
        val right = channel("scheduled", "ESPN 4K"); val wrong = channel("other", "NFL 02")
        val current = Programme(right.id, "NFL Highlights", now - 60000, now + 3600000)
        val scheduled = Programme(right.id, "NFL: Seahawks vs 49ers", future.start + 5 * 60000L, future.end)
        val guide = Guide(listOf(right, wrong), mapOf(right.id to listOf(current, scheduled), wrong.id to listOf(
            scheduled.copy(channel = wrong.id, title = "Buffalo Bills at Kansas City Chiefs"),
            scheduled.copy(channel = wrong.id, start = scheduled.start + 86400000L, end = scheduled.end + 86400000L))))
        val found = SportsSchedule.match(future, guide, emptySet(), now)
        assertEquals(listOf(right), found.broadcasts.map { it.channel })
        assertTrue(SportsStreams.cardSummary(found, GuideFilters(), emptyMap(), guide, now).startsWith("Scheduled · ${right.name}"))
        assertTrue(SportsStreams.automaticChoices(found, GuideFilters(), emptyMap(), guide, now).isEmpty())
        assertFalse(SportsStreams.conflictingCoverage(found, right, guide, now))
        val before = guide.copy(programmes = mapOf(right.id to listOf(current)))
        assertTrue(SportsSchedule.newlyConfirmed(future, before, guide, now))
        assertTrue(right in SportsStreams.confirmationTargets(future, before, listOf(right), emptySet(), emptyMap(), now))
    }

}

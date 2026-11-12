package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsStreamCacheTest {
    private val now = 1793055600000L
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60000, now + 3600000,
        emptyList(), "cache-game", "in", listOf("ESPN"), competition = "NFL")
    private fun channel(id: String, fps: Float = 60f) = Channel(id, "ESPN $id", "NFL", "https://example.com/$id", reportedQuality = VideoInfo(3840, 2160, fps))
    @Test fun fastClickPromotesKnownHigherResolutionCandidateFromNewEpgWithoutReadingTheLineup() {
        val hd = channel("hd").copy(reportedQuality = VideoInfo(1280, 720, 60f))
        val fhd = channel("fhd").copy(reportedQuality = VideoInfo(1920, 1080, 0f))
        var reads = 0
        val backing = listOf(hd, fhd) + (1..26000).map { channel("unrelated$it") }
        val channels = object : AbstractList<Channel>() {
            override val size get() = backing.size
            override fun get(index: Int): Channel { reads++; return backing[index] }
        }
        val listing = Programme(hd.id, game.title, now - 1000, now + 3600000)
        val before = Guide(channels, mapOf(hd.id to listOf(listing)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, before, emptySet())
        val known = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(hd, listing)), candidates = listOf(fhd)))
        cache.put(token, known, now)
        val after = before.copy(programmes = before.programmes + (fhd.id to listOf(listing.copy(channel = fhd.id))))
        reads = 0
        val clicked = cache.peek("source", 1, after, emptySet(), game, now)!!
        assertEquals(listOf(fhd, hd), SportsStreams.automaticChoices(clicked.game, GuideFilters(), emptyMap(), after, now))
        assertEquals("Known candidate EPG enrichment must not scan 26,000 channels on the UI thread", 0, reads)
        assertEquals(listOf(hd), cache.get(token, game, now)!!.game.broadcasts.map { it.channel })
        val refreshed = cache.use("source", 1, after, emptySet())
        assertEquals(setOf(hd, fhd), cache.get(refreshed, game, now)!!.game.broadcasts.map { it.channel }.toSet())
        assertTrue(cache.put(token, known, now + 1))
        assertEquals("A late local pass cannot erase the promoted FHD result", setOf(hd, fhd), cache.get(refreshed, game, now + 1)!!.game.broadcasts.map { it.channel }.toSet())
    }
    @Test fun mergingLiveLookupPreservesArchiveHistoryAndDoesNotInvalidateItsOwnCachedResult() {
        val c = channel("one").copy(catchupDays = 2)
        val archived = Programme(c.id, "Yesterday's game", now - 86400000L, now - 80000000L)
        val current = Programme(c.id, game.title, now - 1000, now + 3600000)
        val original = Guide(listOf(c), mapOf(c.id to listOf(archived)))
        val match = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, current))), mapOf(c.id to listOf(current)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, original, emptySet())
        val (merged, retained) = match.mergeGuide(original, now)
        assertEquals(listOf(archived, current), merged.programmes[c.id])
        assertTrue(cache.put(token, retained, now))
        val updated = cache.use("source", 1, merged, emptySet())
        assertNotNull("Applying lookup data must not immediately erase the completed match", cache.get(updated, game, now))
    }
    @Test fun completedCoverageIgnoresUnrelatedGuideRefreshButRechecksChangedGameDataAndLineups() {
        val c = channel("one"); val p = Programme(c.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val match = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, p))))
        val news = guide.copy(programmes = guide.programmes + ("news" to listOf(p.copy(title = "Headlines"))))
        assertFalse(match.needsRefresh(guide, news, now))
        assertTrue(match.needsRefresh(guide, guide.copy(programmes = mapOf(c.id to listOf(p.copy(title = "Other game")))), now))
        assertTrue(match.needsRefresh(guide, guide.copy(channels = listOf(c.copy(url = "https://example.com/new"))), now))
        assertFalse("Unrelated listings must not restart an unresolved search", SportsStreamCache.Match(game.copy(candidates = listOf(c))).needsRefresh(guide, news, now))
        val missing = guide.copy(programmes = emptyMap())
        assertTrue("A newly arrived matching programme must still trigger a recheck", SportsStreamCache.Match(game.copy(candidates = listOf(c))).needsRefresh(missing, guide, now))
    }
    @Test fun confirmedChannelTitlesRemainReadyForTheGameInsteadOfExpiringEveryTwoMinutes() {
        val c = channel("one"); val guide = Guide(listOf(c), emptyMap())
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, guide, emptySet())
        cache.put(token, SportsStreamCache.Match(game.copy(titleMatches = listOf(c))), now)
        val later = now + 120001
        assertNotNull("A confirmed channel title remains playable for this game", cache.get(token, game, later))
        assertNotNull(cache.get(token, game, later, forAuto = false))
        val updated = game.copy(scores = mapOf("nfl:SEA" to "14"))
        val card = cache.get(token, updated, later, forAuto = false, allowStale = true)!!
        assertEquals(listOf(c), card.game.titleMatches); assertEquals(updated.scores, card.game.scores)
        assertNull("Finished games cannot retain a live-channel label", cache.get(token, game.copy(state = "post"), later, forAuto = false, allowStale = true))
        assertNull(cache.get(token, game, game.end, forAuto = false, allowStale = true))
    }
    @Test fun staleCardsStillExpireAtProgrammeBoundariesAndInvalidateOnRelevantGuideChanges() {
        val c = channel("one"); val p = Programme(c.id, game.title, now - 1000, now + 300000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, guide, emptySet())
        val match = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, p))))
        cache.put(token, match, now)
        val unrelated = cache.use("source", 1, guide.copy(programmes = guide.programmes + ("news" to listOf(p))), emptySet())
        assertNotNull(cache.get(unrelated, game, now + 120001, forAuto = false, allowStale = true))
        assertNull(cache.get(unrelated, game, p.end, forAuto = false, allowStale = true))
        cache.put(unrelated, match, now)
        val changed = cache.use("source", 1, guide.copy(programmes = mapOf(c.id to listOf(p.copy(title = "Other game")))), emptySet())
        assertNull(cache.get(changed, game, now + 120001, forAuto = false, allowStale = true))
        cache.put(changed, SportsStreamCache.Match(game), now)
        assertNull("Empty searches must be allowed to discover new coverage", cache.get(changed, game, now + 120001, forAuto = false, allowStale = true))
    }
    @Test fun cachedEvidenceRetainsFreshScoresButExpiresAtProgrammeBoundaries() {
        val c = channel("one"); val programme = Programme(c.id, game.title, now - 1000, now + 5000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(programme)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, guide, emptySet())
        assertTrue(cache.put(token, SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, programme)))), now))
        assertEquals(mapOf("nfl:SEA" to "7"), cache.get(token, game.copy(scores = mapOf("nfl:SEA" to "7")), now + 10)!!.game.scores)
        assertNull(cache.get(token, game, now + 5000))
    }
    @Test fun contextsRejectOldPlaylistWorkAndInvalidateChangedCoverageOnly() {
        val c = channel("one"); val p = Programme(c.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(clock = { now }); val first = cache.use("source", 1, guide, emptySet())
        val match = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, p))))
        cache.put(first, match, now)
        val unrelated = cache.use("source", 1, guide.copy(programmes = guide.programmes + ("news" to listOf(p))), emptySet())
        assertNotNull(cache.get(unrelated, game, now)); assertTrue("Unrelated EPG updates cannot discard completed work", cache.put(first, match, now))
        val changed = cache.use("source", 1, guide.copy(programmes = mapOf(c.id to listOf(p.copy(title = "Other game")))), emptySet())
        assertNull(cache.get(changed, game, now)); assertFalse("Changed game coverage must reject old work", cache.put(first, match, now))
        cache.put(changed, match, now)
        val otherSource = cache.use("other", 2, guide, emptySet())
        assertNull(cache.get(otherSource, game, now)); assertFalse(cache.put(changed, match, now))
    }
    @Test fun cachedWarmMetadataCanMergeWithoutThrowingAwayItsOwnMatch() {
        val c = channel("one"); val guide = Guide(listOf(c), emptyMap()); val cache = SportsStreamCache(clock = { now })
        val token = cache.use("source", 1, guide, emptySet())
        val warm = mapOf(c.id to listOf(Programme(c.id, game.title, now - 1000, now + 3600000)))
        cache.put(token, SportsStreamCache.Match(game.copy(candidates = listOf(c)), warm), now)
        val merged = cache.use("source", 1, guide.copy(programmes = warm), emptySet())
        assertNotNull(cache.get(merged, game, now))
        assertNull(cache.get(merged, game.copy(state = "post"), now))
        assertNull(cache.get(merged, game.copy(networks = listOf("CBS")), now))
    }
    @Test fun memoryIsBoundedAndIncompleteOrManualLookupsCannotBecomeAutoReady() {
        val cache = SportsStreamCache(capacity = 2, clock = { now }); val token = cache.use("source", 1, Guide.EMPTY, emptySet())
        assertFalse(cache.put(token, SportsStreamCache.Match(game.copy(coverageComplete = false)), now))
        cache.put(token, SportsStreamCache.Match(game, autoReady = false), now)
        assertNull(cache.get(token, game, now)); assertNotNull(cache.get(token, game, now, forAuto = false))
        cache.put(token, SportsStreamCache.Match(game.copy(eventId = "two")), now)
        cache.put(token, SportsStreamCache.Match(game.copy(eventId = "three")), now)
        assertNull(cache.get(token, game, now, forAuto = false))
        assertNull(cache.get(token, game.copy(eventId = "two"), now + 120000))
        cache.clear(); assertFalse(cache.put(token, SportsStreamCache.Match(game), now))
    }
    @Test fun automaticChoicesPreferKnownSixtyButKeepLowerQualityFallbacks() {
        val unknown = channel("unknown", 0f); val known = channel("known", 59.94f); val low = channel("low", 50f)
        val matched = game.copy(titleMatches = listOf(unknown, low, known))
        val filters = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        val guide = Guide(listOf(unknown, known, low), emptyMap())
        assertEquals(listOf(known, unknown, low), SportsStreams.automaticChoices(matched, filters, emptyMap(), guide, now))
        assertEquals(listOf(unknown, low, known), SportsStreams.automaticChoices(matched, filters, mapOf(known.id to VideoInfo(3840,2160,30f)), guide, now))
        assertFalse("Live guide remains strict", filters.matches(unknown, null))
        assertTrue(SportsStreams.automaticChoices(matched.copy(state = "pre"), filters, emptyMap(), guide, now).isEmpty())
    }
    @Test fun fallbackSkipsUnavailableCandidatesAndNeverLoopsBeyondThreeAttempts() {
        val channels = (1..6).map { channel("$it") }; val plan = SportsPlaybackPlan(game, channels + channels)
        assertEquals(channels[1], plan.next { it.id != "1" })
        assertEquals(channels[2], plan.next { it.id != "1" }); assertEquals(channels[3], plan.next { it.id != "1" })
        assertNull(plan.next { true })
    }
    @Test fun knownQualityIsConsideredBeforeTheBroadcasterCandidateLimit() {
        val unknown = (1..600).map { channel("variant$it", 0f) }
        val declared = channel("declared", 59.94f)
        val observed = channel("observed", 0f)
        val guide = Guide(unknown + declared + observed, emptyMap())
        val match = SportsSchedule.match(game, guide, emptySet(), now, quality = mapOf(observed.id to VideoInfo(3840,2160,60f)))
        assertEquals(512, match.candidates.size)
        assertTrue(declared in match.candidates); assertTrue(observed in match.candidates)
    }
    @Test fun cardDiscoveryRemainsVisibleWithoutVerifiedAutomaticPlayQuality() {
        val unknown = channel("unknown").copy(name = "Seahawks vs 49ers", reportedQuality = null)
        val low = channel("50fps", 50f)
        val filters = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        val unverified = game.copy(titleMatches = listOf(unknown))
        val guide = Guide(listOf(unknown, low), emptyMap())
        assertTrue(SportsStreams.cardSummary(unverified, filters, emptyMap(), guide, now).contains(unknown.name))
        assertFalse("Cards omit an unknown quality instead of repeating it on every game", SportsStreams.cardSummary(unverified, filters, emptyMap(), guide, now).contains("Quality unknown"))
        assertEquals(listOf(unknown), SportsStreams.automaticChoices(unverified, filters, emptyMap(), guide, now))
        assertTrue(SportsStreams.cardSummary(game.copy(titleMatches = listOf(low)), filters, emptyMap(), guide, now).contains("50 fps"))
        val hd = channel("720", 29.97f).copy(reportedQuality = VideoInfo(1280, 720, 29.97f))
        assertEquals("A lone HD/30 game must still be playable under FHD/60 preferences", listOf(hd),
            SportsStreams.automaticChoices(game.copy(titleMatches = listOf(hd)), filters, emptyMap(), Guide(listOf(hd), emptyMap()), now))
        assertTrue("Unavailable streams stay excluded", SportsStreams.automaticChoices(unverified, filters, emptyMap(), guide, now) { false }.isEmpty())
        assertTrue("Category visibility remains respected", SportsStreams.automaticChoices(unverified, filters.copy(groups = setOf("Other")), emptyMap(), guide, now).isEmpty())
        val verified = mapOf(unknown.id to VideoInfo(3840, 2160, 59.94f))
        assertTrue(SportsStreams.cardSummary(unverified, filters, verified, guide, now).contains("59.94"))
    }
    @Test fun scheduledMatchesAreDisplayOnlyAndExpireAtKickoff() {
        val future = game.copy(state = "pre", start = now + 3600000L, end = now + 4 * 3600000L)
        val c = channel("scheduled"); val p = Programme(c.id, future.title, future.start, future.end)
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, Guide(listOf(c), mapOf(c.id to listOf(p))), emptySet())
        assertTrue(cache.put(token, SportsStreamCache.Match(future.copy(broadcasts = listOf(SportsBroadcast(c, p)))), now))
        assertNotNull(cache.get(token, future, now, forAuto = false))
        assertNull("An upcoming match must not tune automatically", cache.get(token, future, now))
        assertNull(cache.get(token, future.copy(state = "in"), now, forAuto = false))
        assertNull(cache.get(token, future, future.start, forAuto = false, allowStale = true))
    }

    @Test fun unrelatedCandidateGuideUpdatesAndRefreshAgeDoNotDiscardExactLiveCoverage() {
        val c = channel("known"); val candidate = channel("guess")
        val p = Programme(c.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(c, candidate), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, guide, emptySet())
        val matched = game.copy(broadcasts = listOf(SportsBroadcast(c, p)), candidates = listOf(candidate))
        cache.put(token, SportsStreamCache.Match(matched), now)
        val changed = guide.copy(programmes = guide.programmes + (candidate.id to listOf(p.copy(channel = candidate.id, title = "Headlines"))))
        val updated = cache.use("source", 1, changed, emptySet())
        assertNotNull(cache.get(updated, game, now + 120001))
        assertFalse(SportsStreamCache.Match(matched).needsRefresh(guide, changed, now))
        assertNull(cache.get(updated, game, p.end))
    }

    @Test fun publishedFastPathNeverWaitsForCacheWorkAndValidatesOnlyTheKnownFeeds() {
        val c = channel("ready"); val p = Programme(c.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, guide, emptySet())
        cache.put(token, SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, p)))), now)
        val locked = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val owner = java.util.concurrent.Executors.newSingleThreadExecutor(); val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            owner.submit { synchronized(cache) { locked.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
            assertTrue(locked.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val result = reader.submit<SportsStreamCache.Match?> { cache.peek("source", 1, guide, emptySet(), game, now) }
            assertNotNull(result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            val changed = guide.copy(programmes = guide.programmes + ("news" to listOf(p.copy(title = "Headlines"))))
            assertNotNull("Unrelated EPG data must not send a confirmed card back to the search worker", cache.peek("source", 1, changed, emptySet(), game, now))
            val conflict = guide.copy(programmes = mapOf(c.id to listOf(p.copy(title = "Other game"))))
            assertNull(cache.peek("source", 1, conflict, emptySet(), game, now))
            assertNull(cache.peek("other", 1, guide, emptySet(), game, now))
        } finally { release.countDown(); owner.shutdownNow(); reader.shutdownNow() }
        assertNull(cache.get(token, game.copy(networks = listOf("CBS")), now))
        assertNull(cache.peek("source", 1, guide, emptySet(), game, now))
        cache.clear(); assertNull(cache.peek("source", 1, guide, emptySet(), game, now))
    }

    @Test fun aReadyGameSurvivesNewCoverageAndAnEquivalentLineupWithoutAnotherScan() {
        val known = channel("known"); val better = channel("better")
        val programme = Programme(known.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(known, better), mapOf(known.id to listOf(programme)))
        val cache = SportsStreamCache(clock = { now }); val first = cache.use("source", 1, guide, emptySet())
        val ready = SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(known, programme))))
        assertTrue(cache.put(first, ready, now))
        val refreshed = guide.copy(channels = guide.channels.toList(), programmes = guide.programmes + (better.id to listOf(programme.copy(channel = better.id))))
        assertFalse(ready.needsRefresh(guide, refreshed, now + 600000))
        val next = cache.use("source", 1, refreshed, emptySet())
        assertEquals(setOf(known.id, better.id), cache.get(next, game, now)!!.game.broadcasts.map { it.channel.id }.toSet())
        assertNotNull(cache.get(next, game, now + 600000, forAuto = false))
        assertNotNull(cache.peek("source", 1, refreshed, emptySet(), game, now + 600000))
        assertTrue("Late completed work for an equivalent lineup stays reusable", cache.put(first, ready, now + 600000))
    }

    @Test fun completedGameArchiveHintsReuseOnlyConfirmedFeedsFromTheSameSession() {
        val feed = channel("known"); val guess = channel("guess")
        val listing = Programme(feed.id, game.title, now - 1000, now + 3600000)
        val cache = SportsStreamCache(clock = { now }); val token = cache.use("source", 1, Guide(listOf(feed, guess), emptyMap()), emptySet())
        cache.put(token, SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(feed, listing)), candidates = listOf(guess))), now)
        val final = game.copy(state = "post")
        assertEquals(setOf(feed.id), cache.replayFeeds("source", 1, final))
        assertTrue(cache.replayFeeds("other", 1, final).isEmpty()); assertTrue(cache.replayFeeds("source", 2, final).isEmpty())
    }

}

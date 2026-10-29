package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsStreamCacheTest {
    private val now = 1793055600000L
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60000, now + 3600000,
        emptyList(), "cache-game", "in", listOf("ESPN"), competition = "NFL")
    private fun channel(id: String, fps: Float = 60f) = Channel(id, "ESPN $id", "NFL", "https://example.com/$id", reportedQuality = VideoInfo(3840, 2160, fps))
    @Test fun refreshDueKeepsDiscoveredCardsVisibleWithoutReusingStaleAutoPlayEvidence() {
        val c = channel("one"); val guide = Guide(listOf(c), emptyMap())
        val cache = SportsStreamCache(); val token = cache.use("source", 1, guide, emptySet())
        cache.put(token, SportsStreamCache.Match(game.copy(titleMatches = listOf(c))), now)
        val later = now + 120001
        assertNull("A due refresh still blocks stale automatic playback", cache.get(token, game, later))
        assertNull(cache.get(token, game, later, allowStale = true))
        val updated = game.copy(scores = mapOf("nfl:SEA" to "14"))
        val card = cache.get(token, updated, later, forAuto = false, allowStale = true)!!
        assertEquals(listOf(c), card.game.titleMatches); assertEquals(updated.scores, card.game.scores)
        assertNull("Finished games cannot retain a live-channel label", cache.get(token, game.copy(state = "post"), later, forAuto = false, allowStale = true))
        assertNull(cache.get(token, game, game.end, forAuto = false, allowStale = true))
    }
    @Test fun staleCardsStillExpireAtProgrammeBoundariesAndInvalidateOnRelevantGuideChanges() {
        val c = channel("one"); val p = Programme(c.id, game.title, now - 1000, now + 300000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(); val token = cache.use("source", 1, guide, emptySet())
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
        val cache = SportsStreamCache(); val token = cache.use("source", 1, guide, emptySet())
        assertTrue(cache.put(token, SportsStreamCache.Match(game.copy(broadcasts = listOf(SportsBroadcast(c, programme)))), now))
        assertEquals(mapOf("nfl:SEA" to "7"), cache.get(token, game.copy(scores = mapOf("nfl:SEA" to "7")), now + 10)!!.game.scores)
        assertNull(cache.get(token, game, now + 5000))
    }
    @Test fun contextsRejectOldPlaylistWorkAndInvalidateChangedCoverageOnly() {
        val c = channel("one"); val p = Programme(c.id, game.title, now - 1000, now + 3600000)
        val guide = Guide(listOf(c), mapOf(c.id to listOf(p)))
        val cache = SportsStreamCache(); val first = cache.use("source", 1, guide, emptySet())
        val match = SportsStreamCache.Match(game.copy(candidates = listOf(c)))
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
        val c = channel("one"); val guide = Guide(listOf(c), emptyMap()); val cache = SportsStreamCache()
        val token = cache.use("source", 1, guide, emptySet())
        val warm = mapOf(c.id to listOf(Programme(c.id, game.title, now - 1000, now + 3600000)))
        cache.put(token, SportsStreamCache.Match(game.copy(candidates = listOf(c)), warm), now)
        val merged = cache.use("source", 1, guide.copy(programmes = warm), emptySet())
        assertNotNull(cache.get(merged, game, now))
        assertNull(cache.get(merged, game.copy(state = "post"), now))
        assertNull(cache.get(merged, game.copy(networks = listOf("CBS")), now))
    }
    @Test fun memoryIsBoundedAndIncompleteOrManualLookupsCannotBecomeAutoReady() {
        val cache = SportsStreamCache(capacity = 2); val token = cache.use("source", 1, Guide.EMPTY, emptySet())
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
        assertTrue(SportsStreams.cardSummary(unverified, filters, emptyMap(), guide, now).contains("Quality unknown"))
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
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsStreamCacheTest {
    private val now = 1793055600000L
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, now - 60000, now + 3600000,
        emptyList(), "cache-game", "in", listOf("ESPN"), competition = "NFL")
    private fun channel(id: String, fps: Float = 60f) = Channel(id, "ESPN $id", "NFL", "https://example.com/$id", reportedQuality = VideoInfo(3840, 2160, fps))
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
        assertNotNull(cache.get(unrelated, game, now)); assertFalse(cache.put(first, match, now))
        val changed = cache.use("source", 1, guide.copy(programmes = mapOf(c.id to listOf(p.copy(title = "Other game")))), emptySet())
        assertNull(cache.get(changed, game, now))
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
    @Test fun automaticTrialsKeepKnownSixtyFirstAllowUnknownAndRejectKnownLowRates() {
        val unknown = channel("unknown", 0f); val known = channel("known", 59.94f); val low = channel("low", 50f)
        val matched = game.copy(titleMatches = listOf(unknown, low, known))
        val filters = GuideFilters(1080, FrameRateFilter.sixtyOrBetter)
        val guide = Guide(listOf(unknown, known, low), emptyMap())
        assertEquals(listOf(known, unknown), SportsStreams.automaticChoices(matched, filters, emptyMap(), guide, now))
        assertEquals(listOf(unknown), SportsStreams.automaticChoices(matched, filters, mapOf(known.id to VideoInfo(3840,2160,30f)), guide, now))
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
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RefinementTest {
    @Test fun numericRowIdsRetainExistingRecyclerIdentity() {
        listOf("", "xc:12345", "group:USA | Sports", "日本語", "synthetic-99999").forEach { id ->
            val expected = Channel.stableId(id).take(16).toULong(16).toLong()
            assertEquals(expected, Channel.stableLongId(id))
            assertEquals(expected, Channel(id, "Name", "", "https://example.test").rowId)
        }
    }
    private val now = 1800000000000L
    @Test fun channelSearchIgnoresProviderSpacingAndStyling() {
        listOf("US | KING5 FHD", "KING-5 Seattle", "ＫＩＮＧ ５ NBC", "𝙆𝙄𝙉𝙂𝟱").forEach { assertTrue(it, ChannelSearch.matches(it, "king 5")) }
        assertTrue(ChannelSearch.matches("US KING 5 FHD", "king5"))
        assertFalse(ChannelSearch.matches("KING 7", "king 5"))
        assertTrue(ChannelSearch.matches("Channel 2", "Channel 2"))
    }
    @Test fun providerQualityFiltersBeforePlaybackButObservedDataWins() {
        val quality = ProviderQuality.parse(org.json.JSONObject("""{"video":{"width":1920,"height":1080,"r_frame_rate":"60000/1001"}}"""))!!
        val channel = Channel("1", "A channel", "USA", "https://example.com/live", reportedQuality = quality)
        val filter = GuideFilters(1080, FrameRateFilter.onlySixty)
        assertTrue(filter.matches(channel, null))
        assertTrue(filter.matches(channel, VideoInfo(1920, 1080, 0f)))
        assertFalse(filter.matches(channel, VideoInfo(1280, 720, 60f)))
        assertFalse(filter.matches(channel, VideoInfo(1920, 1080, 50f)))
        assertEquals(channel, Channel.from(channel.json()))
        val labelled = ProviderQuality.parse(org.json.JSONObject(), "USA ESPN FHD 60fps")!!
        assertEquals(1080, labelled.height); assertEquals(60f, labelled.fps, .01f)
        assertFalse(filter.matches(channel.copy(reportedQuality = ProviderQuality.parse(org.json.JSONObject(), "FHD")), null))
        assertNull(ProviderQuality.parse(org.json.JSONObject(), "Channel 60"))
    }
    @Test fun scoreboardKeepsFinalScoresAndLogos() {
        val event = LiveEvent("final", setOf("nfl:SEA", "nfl:SF"), now - 3600000, "post", scores = mapOf("nfl:SEA" to "27", "nfl:SF" to "24"), logos = mapOf("nfl:SEA" to "https://images.example/sea.png"), status = "Final")
        val snapshot = LiveSportsSnapshot(listOf(event), now, setOf("NFL"))
        assertEquals(snapshot, SportsSchedule.from(SportsSchedule.json(snapshot)))
        val game = SportsSchedule.games(snapshot, Guide.EMPTY, "league:NFL", emptySet(), now).single()
        assertFalse(game.live(now)); assertEquals("Final", game.statusLine(now)); assertTrue(game.scoreLine.contains("27"))
    }
    @Test fun measuredSixtyOverridesMisdeclaredThirtyPointThreeWithoutDoublingProgressiveThirty() {
        assertEquals(59.94f, VideoRate.resolve(30.3f, 59.94f), .001f)
        assertEquals(30f, VideoRate.resolve(30.3f, 30f), .001f)
        assertEquals(29.97f, VideoRate.resolve(29.97f, 29.97f), .001f)
        assertEquals(59.94f, VideoRate.resolve(29.97f, 29.97f, interlaced = true), .001f)
        assertEquals(50f, VideoRate.resolve(25f, 25f, interlaced = true), .001f)
        assertEquals(60f, VideoRate.resolve(30f, 60f, interlaced = true), .001f)
        assertTrue(VideoInfo(1920, 1080, 59.94f, true).label().contains("1080i"))
        assertTrue(VideoInfo(1920, 1080, 59.94f, true).label().contains("fields/s"))
        assertEquals(60, FrameRateFilter.bucket(VideoRate.resolve(30.3f, 59.94f)))
    }
    @Test fun redirectsResolveRelativeAndUpgradeButRequireConsentBeforeDowngrade() {
        assertEquals("https://example.com/video.mp4", Redirects.next("https://example.com/movie/1", "../video.mp4", false))
        assertEquals("https://cdn.example.com/movie.mp4", Redirects.next("http://example.com/movie", "https://cdn.example.com/movie.mp4", false))
        assertThrows(IOException::class.java) { Redirects.next("https://example.com/movie", "http://cdn.example.com/movie.mp4", false) }
        assertEquals("http://cdn.example.com/movie.mp4", Redirects.next("https://example.com/movie", "http://cdn.example.com/movie.mp4", true))
        listOf("file:///etc/passwd", "content://example/movie", "https://user:secret@example.com/movie").forEach { target -> assertThrows(IOException::class.java) { Redirects.next("https://example.com/movie", target, true) } }
        assertThrows(java.net.ProtocolException::class.java) { Redirects.next("https://example.com/movie", null, false) }
        assertFalse(Redirects.sameOrigin("https://example.com", "https://cdn.example.com"))
    }
    @Test fun sportsShowsLiveAndNextGameWithoutAnyProviderEpgOrChannels() {
        val live = LiveEvent("live", setOf("nfl:SEA", "nfl:SF"), now - 600000, "in", listOf("FOX"))
        val next = LiveEvent("next", setOf("mlb:NYY", "mlb:BOS"), now + 5 * 86400000L, "pre", listOf("ESPN"))
        val feed = LiveSportsSnapshot(listOf(live, next), now, setOf("NFL", "MLB"))
        val all = SportsSchedule.games(feed, Guide.EMPTY, null, emptySet(), now)
        assertEquals(listOf("live", "next"), all.map { it.eventId }); assertTrue(all.all { it.broadcasts.isEmpty() })
        val followed = SportsSchedule.games(feed, Guide.EMPTY, "mlb:NYY", emptySet(), now)
        assertEquals("next", followed.single().eventId); assertFalse(followed.single().live(now))
        assertEquals(feed, SportsSchedule.from(SportsSchedule.json(feed)))
        assertTrue(SportsSchedule.games(feed.copy(checked = now - 120001), Guide.EMPTY, null, emptySet(), now).none { it.live(now) })
    }
    @Test fun broadcasterMatchesAreExplicitCandidatesUntilEpgConfirmsGame() {
        val channel = Channel("one", "USA FOX FHD", "USA", "https://example.com/live")
        val event = LiveEvent("live", setOf("nfl:SEA", "nfl:SF"), now - 600000, "in", listOf("FOX"))
        val snapshot = LiveSportsSnapshot(listOf(event), now, setOf("NFL"))
        val first = SportsSchedule.games(snapshot, Guide(listOf(channel), emptyMap()), null, emptySet(), now).single()
        assertTrue(first.broadcasts.isEmpty()); assertEquals(listOf(channel), first.candidates)
        val matched = SportsSchedule.games(snapshot, Guide(listOf(channel), mapOf("one" to listOf(Programme("one", "Seahawks vs 49ers", now - 600000, now + 3600000)))), null, emptySet(), now).single()
        assertEquals(channel, matched.broadcasts.single().channel)
    }
    @Test fun addonEnrichmentUsesOnlyMetadataIdAndPreservesProviderPlayback() {
        val item = OnDemand("movie:1", "A film", "Films", "https://provider.example/movie/secret-user/secret-pass/1.mp4", externalId = "tt1234567")
        val requests = mutableListOf<String>()
        val enriched = MetadataAddon.enrich("https://addon.example/user-config/manifest.json", item) { url -> requests.add(url); """{"meta":{"description":"A description","poster":"https://images.example/poster.jpg","year":"2025"}}""" }
        assertEquals(item.url, enriched.url); assertEquals("A description", enriched.description); assertEquals("2025", enriched.year)
        assertTrue(requests.single().endsWith("/meta/movie/tt1234567.json")); assertFalse(requests.single().contains("secret-pass"))
        assertThrows(IllegalArgumentException::class.java) { MetadataAddon.validate("http://addon.example/manifest.json") }
        assertEquals("https://addon.example/manifest.json", MetadataAddon.validate("stremio://addon.example/manifest.json"))
    }
    @Test fun addonTitleSearchRejectsAmbiguousRemakes() {
        val item = OnDemand("movie:1", "The Film", "Films")
        val result = MetadataAddon.enrich("https://addon.example/config/manifest.json", item) { url ->
            if (url.endsWith("/manifest.json")) """{"catalogs":[{"type":"movie","id":"search","extra":[{"name":"search"}]}]}"""
            else """{"metas":[{"id":"tt1234567","name":"The Film","releaseInfo":"1990"},{"id":"tt9876543","name":"The Film","releaseInfo":"2025"}]}"""
        }
        assertEquals(item, result)
    }
    @Test fun addonSeriesArtworkFallsBackFromProviderIdToExactCatalogSearch() {
        val item = OnDemand("series:1", "US | The Show (2025) FHD", "Shows", "https://provider.example/secret", kind = "series", year = "0", externalId = "tmdb:12")
        val requests = mutableListOf<String>()
        val result = MetadataAddon.enrich("https://addon.example/config/manifest.json", item) { url ->
            requests.add(url)
            when {
                url.endsWith("/manifest.json") -> """{"catalogs":[{"type":"series","id":"search","extra":[{"name":"search"}]}]}"""
                url.contains("/catalog/") -> """{"metas":[{"id":"tvdb:123","name":"The Show","releaseInfo":"2025","poster":"https://images.example/show.jpg","background":"https://images.example/show-wide.jpg"}]}"""
                else -> throw IllegalStateException("No detail metadata")
            }
        }
        assertEquals("https://images.example/show.jpg", result.poster); assertEquals("https://images.example/show-wide.jpg", result.backdrop)
        assertEquals(item.url, result.url); assertEquals("tvdb:123", result.externalId)
        assertTrue(requests.any { it.endsWith("/meta/series/tmdb%3A12.json") }); assertFalse(requests.any { it.contains("provider.example") || it.contains("secret") })
    }

}

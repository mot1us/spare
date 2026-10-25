package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RefinementTest {
    private val now = 1800000000000L
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
        assertEquals(listOf("live"), all.map { it.eventId }); assertTrue(all.single().broadcasts.isEmpty())
        val followed = SportsSchedule.games(feed, Guide.EMPTY, "mlb:NYY", emptySet(), now)
        assertEquals("next", followed.single().eventId); assertFalse(followed.single().live(now))
        assertEquals(feed, SportsSchedule.from(SportsSchedule.json(feed)))
        assertTrue(SportsSchedule.games(feed.copy(checked = now - 120001), Guide.EMPTY, null, emptySet(), now).isEmpty())
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
}

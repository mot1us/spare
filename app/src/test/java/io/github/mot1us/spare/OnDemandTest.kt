package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnDemandTest {
    private val source = Source(name = "Fixture", kind = "xtream", url = "https://example.com/provider/", username = "a/b", password = "p ?&")
    private val now = 1800000000000L
    @Test fun moviesAndSeriesUseDistinctValidatedProviderRoutes() {
        val movies = VodCatalog.parse(JSONArray("""[{"stream_id":42,"name":"A movie","category_id":"4","container_extension":"mkv"},{"stream_id":"../bad"}]"""), source, "movie", mapOf("4" to "Films"))
        assertEquals(1, movies.size); assertEquals("Films", movies.single().category)
        assertEquals("https://example.com/provider/movie/a%2Fb/p%20%3F%26/42.mkv", movies.single().url)
        val series = VodCatalog.parse(JSONArray("""[{"series_id":8,"name":"A series"}]"""), source, "series", emptyMap()).single()
        assertEquals("series:8", series.id); assertTrue(series.url.isEmpty())
        assertEquals(movies.single(), OnDemand.from(movies.single().json()))
    }
    @Test fun seriesEpisodesAreOrderedBySeasonAndEpisodeAndKeepSeriesIdentity() {
        val series = OnDemand("series:8", "A series", "Drama", kind = "series")
        val root = JSONObject("""{"episodes":{"2":[{"id":4,"episode_num":2,"title":"Later","container_extension":"mkv"}],"1":[{"id":3,"episode_num":1,"title":"Pilot","container_extension":"../../bad"},{"id":"no"}]}}""")
        val entries = VodCatalog.parseEpisodes(root, source, series)
        assertEquals(listOf("Pilot", "Later"), entries.map { it.title }); assertEquals(listOf(1, 2), entries.map { it.season })
        assertTrue(entries.all { it.seriesId == "series:8" }); assertTrue(entries.first().url.endsWith("/series/a%2Fb/p%20%3F%26/3.mp4"))
        assertTrue(VodCatalog.parseEpisodes(JSONObject("{\"episodes\":[]}"), source, series).isEmpty())
    }
    @Test fun archiveMetadataSurvivesLegacyCacheMigration() {
        val channels = Importers.xtreamChannels(JSONArray("""[{"stream_id":5,"name":"Archive","tv_archive":1,"tv_archive_duration":7},{"stream_id":6,"tv_archive":0,"tv_archive_duration":7}]"""), source, emptyMap())
        assertTrue(Catchup.supported(channels.first())); assertFalse(Catchup.supported(channels.last()))
        assertEquals(channels.first(), Channel.from(channels.first().json()))
        assertFalse(Catchup.supported(Channel.from(JSONObject("""{"id":"old","name":"Old","group":"Group","url":"https://example.com/live"}"""))))
    }
    @Test fun xtreamArchiveUsesEncodedCredentialsUtcAndMinuteDuration() {
        val channel = Channel("xc:42", "Archive", "TV", "https://example.com/provider/live/a%2Fb/p%20%3F%26/42.ts", catchupDays = 7, catchupMode = "xc")
        val programme = Programme(channel.id, "Yesterday", now - 7200000, now - 3599000)
        val url = Catchup.url(channel, programme, false, now)!!
        assertTrue(url.startsWith("https://example.com/provider/timeshift/a%2Fb/p%20%3F%26/61/")); assertTrue(url.endsWith("/42.ts"))
        assertEquals(url, Catchup.url(channel.copy(url = channel.url.replace("/live/", "/")), programme, false, now))
        assertNull(Catchup.url(channel, programme.copy(end = now + 1), false, now))
        assertNull(Catchup.url(channel, programme.copy(start = now - 8 * 86400000), false, now))
        assertNull(Catchup.url(channel.copy(catchupDays = 0), programme, false, now))
    }
    @Test fun m3uTemplatesAreBoundedAndRespectHttpConsent() {
        val m3u = """#EXTM3U
#EXTINF:-1 catchup="append" catchup-days="3" catchup-source="?utc={utc}&duration={duration:60}",Archive
https://example.com/archive.ts
"""
        val channel = Importers.m3u(m3u, false).channels.single()
        val programme = Programme(channel.id, "Past", now - 3600000, now - 1800000)
        assertEquals("https://example.com/archive.ts?utc=${programme.start / 1000}&duration=30", Catchup.url(channel, programme, false, now))
        assertNull(Catchup.url(channel.copy(catchupTemplate = "?x={unsupported}"), programme, false, now))
        assertNull(Catchup.url(channel.copy(catchupMode = "default", catchupTemplate = "http://example.com/?utc={utc}"), programme, false, now))
        assertNotNull(Catchup.url(channel.copy(catchupMode = "default", catchupTemplate = "http://example.com/?utc={utc}"), programme, true, now))
        assertNull(Catchup.url(channel.copy(catchupTemplate = "?x={duration:0}"), programme, false, now))
    }
    @Test fun xmltvHistoryIsRetainedOnlyForChannelsAdvertisingArchives() {
        val format = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(java.time.ZoneOffset.UTC)
        val start = format.format(java.time.Instant.ofEpochMilli(now - 2 * 86400000L)); val end = format.format(java.time.Instant.ofEpochMilli(now - 2 * 86400000L + 3600000))
        val xml = "<tv><programme channel=\"archive\" start=\"$start\" stop=\"$end\"><title>Yesterday</title></programme></tv>".toByteArray()
        assertTrue(Importers.xmltv(xml, setOf("archive"), now).isEmpty())
        assertEquals(1, Importers.xmltv(xml, setOf("archive"), now, mapOf("archive" to 3)).size)
        assertTrue(Importers.xmltv(xml, setOf("archive"), now, mapOf("archive" to 1)).isEmpty())
    }
    @Test fun archiveGuideRetainsOnlyAdvertisedWindowAndLiveGuideStaysShort() {
        val entries = JSONObject().put("epg_listings", JSONArray().put(JSONObject().put("start_timestamp", (now - 86400000) / 1000).put("stop_timestamp", (now - 82800000) / 1000).put("title", "Yesterday"))).toString()
        assertTrue(XtreamEpg.parse(entries, "xc:1", now).isEmpty())
        assertEquals("Yesterday", XtreamEpg.parse(entries, "xc:1", now, pastDays = 2).single().title)
        assertTrue(XtreamEpg.parse(entries, "xc:1", now + 3 * 86400000L, pastDays = 2).isEmpty())
    }
}

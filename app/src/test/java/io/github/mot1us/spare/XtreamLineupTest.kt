package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XtreamLineupTest {
    private val source = Source(name = "Test", kind = "xtream", url = "https://provider.example:8443/panel/", username = "test", password = "test-secret")
    private fun stream(id: Int) = JSONObject().put("stream_id", id).put("name", "Channel $id").put("category_id", "news")

    @Test fun importsMoreThanTheOldTwentyThousandLimitInProviderOrder() {
        val streams = JSONArray()
        for (id in 1..25001) streams.put(stream(id))
        val channels = Importers.xtreamChannels(streams, source, mapOf("news" to "News"))
        assertEquals(25001, channels.size)
        assertEquals("xc:1", channels.first().id)
        assertEquals("xc:25001", channels.last().id)
        assertEquals("News", channels.last().group)
        assertEquals("https://provider.example:8443/panel/live/test/test-secret/25001.ts", channels.last().url)
    }
    @Test fun oversizedLineupsHaveAnExplicitLimitError() {
        val streams = JSONArray()
        val entry = stream(1)
        repeat(Importers.MAX_CHANNELS + 1) { streams.put(entry) }
        val failure = assertThrows(SourceFailure::class.java) { Importers.xtreamChannels(streams, source, emptyMap()) }
        assertEquals(SourceProblem.CHANNEL_LIMIT, failure.problem)
        assertTrue(SourceErrors.message(failure).contains("100,000"))
    }
    @Test fun skipsMalformedEntriesWithoutDiscardingUsableChannels() {
        val streams = JSONArray().put(JSONObject.NULL).put("broken entry").put(stream(1)).put(JSONObject().put("stream_id", "bad")).put(stream(2))
        assertEquals(listOf("xc:1", "xc:2"), Importers.xtreamChannels(streams, source, emptyMap()).map { it.id })
    }
    @Test fun trailingForwardSlashDoesNotChangeTheRequestEndpoint() {
        val without = source.copy(url = source.url.removeSuffix("/"))
        assertEquals(Importers.endpoint(source, "player_api.php", "get_live_streams"), Importers.endpoint(without, "player_api.php", "get_live_streams"))
        assertFalse(Network.allowed("https://provider.example/\\", true))
    }
    @Test fun streamSpecificGuidesDoNotCollapseSharedXmltvIds() {
        val streams = JSONArray().put(stream(1).put("epg_channel_id", "shared")).put(stream(2).put("epg_channel_id", "shared"))
        assertEquals(listOf("xc:1", "xc:2"), Importers.xtreamChannels(streams, source, emptyMap()).map { it.guideId })
        assertEquals(listOf("shared", "shared"), Importers.xtreamChannels(streams, source.copy(epgUrl = "https://example.com/epg"), emptyMap()).map { it.guideId })
    }
    @Test fun migratesUniqueOldGuideKeysButDiscardsAmbiguousSharedListings() {
        val one = Channel("xc:1", "One", "News", "https://example.com/1", guideId = "shared")
        val two = one.copy(id = "xc:2")
        val programme = Programme("shared", "News", 1, 2)
        val unique = Guide(listOf(one), mapOf("shared" to listOf(programme))).streamGuide()
        assertEquals("xc:1", unique.channels.single().guideId)
        assertEquals("xc:1", unique.programmes["xc:1"]!!.single().channel)
        assertTrue(Guide(listOf(one, two), mapOf("shared" to listOf(programme))).streamGuide().programmes.isEmpty())
        val current = Guide(listOf(one.copy(guideId = one.id)), unique.programmes)
        assertEquals(current, current.streamGuide())
    }

}

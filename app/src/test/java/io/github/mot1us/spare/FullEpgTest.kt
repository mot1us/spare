package io.github.mot1us.spare

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class FullEpgTest {
    private val source = Source(name = "Full EPG", kind = "xtream", url = "https://example.test", username = "test", password = "test")

    @Test fun preservesXmltvAliasesWithoutChangingStreamIdentitiesOrMergingDifferentProviders() {
        val channels = Importers.xtreamChannels(JSONArray("""[{"stream_id":1,"name":"HD","epg_channel_id":"shared"},{"stream_id":2,"name":"4K","epg_channel_id":"shared"},{"stream_id":3,"name":"No guide"}]"""), source, emptyMap())
        assertEquals(listOf("xc:1", "xc:2", "xc:3"), channels.map { it.guideId })
        assertEquals("shared", Channel.from(channels[0].json()).xmltvId)
        val programme = Programme("shared", "News", 1000, 2000)
        val result = FullEpg.map(channels, source, mapOf("shared" to listOf(programme), "unrelated" to listOf(programme)))
        assertEquals(setOf("xc:1", "xc:2"), result.keys)
        assertEquals("xc:2", result.getValue("xc:2").single().channel)
        val other = source.copy(id = "other")
        val catalog = PlaylistCatalog(source.id).apply { configure(listOf(source, other)) }
        val scoped = catalog.view(other, Guide(channels, result))
        assertTrue(scoped.programmes.keys.all { it.startsWith("playlist:other:") })
        assertEquals("shared", scoped.channels.first().native().xmltvId)
    }

    @Test fun fullGuideKeepsMoreThanFiveThousandChannelsAndRespectsExplicitXmltvMapping() {
        val channel = Channel("xc:1", "One", "News", "https://example.test/1", guideId = "xc:1", xmltvId = "shared")
        val channels = (1..6001).map { channel.copy(id = "xc:$it", guideId = "xc:$it") }
        val programme = Programme("shared", "News", 1000, 2000)
        assertEquals(6001, FullEpg.map(channels, source, mapOf("shared" to listOf(programme))).size)
        val custom = source.copy(epgUrl = "https://example.test/guide")
        val result = FullEpg.map(listOf(channel.copy(guideId = "custom")), custom, mapOf("custom" to listOf(programme.copy(channel = "custom"))))
        assertEquals(setOf("custom"), result.keys)
    }
}

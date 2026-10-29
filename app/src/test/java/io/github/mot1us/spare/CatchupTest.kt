package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class CatchupTest {
    private val source = Source(name = "Fixture", kind = "xtream", url = "https://example.com", username = "user", password = "pass")
    private val channel = Channel("xc:1", "Archive", "Other", "https://example.com/live/user/pass/1.ts", catchupDays = 2, catchupMode = "xc")
    @Test fun inventoryUsesEveryAdvertisedChannelAndSurvivesPlaylistCaching() {
        val channels = listOf(channel, channel.copy(id = "xc:2", group = "Hidden category"), channel.copy(id = "xc:3", catchupDays = 0), channel.copy(id = "xc:4", media = true))
        val restored = Guide.from(Guide(channels, emptyMap()).json())
        assertEquals(listOf("xc:1", "xc:2"), Catchup.channels(restored).map { it.id })
    }
    @Test fun emptyModernArchiveFallsBackToTheLegacyEndpointWithinOneDeadline() {
        val now = System.currentTimeMillis(); val actions = mutableListOf<String>()
        val entries = """{"epg_listings":{"123":{"title":"Yesterday","start_timestamp":${(now - 86400000) / 1000},"stop_timestamp":${(now - 82800000) / 1000},"has_archive":1}}}"""
        val found = Catchup.load(source, channel, 1200) { url, timeout ->
            assertTrue(timeout in 1..1200); actions.add(url.substringAfter("&action=").substringBefore('&'))
            if (actions.size == 1) "{\"epg_listings\":[]}".toByteArray() else entries.toByteArray()
        }
        assertEquals(listOf("get_simple_data_table", "get_simple_date_table"), actions)
        assertEquals("Yesterday", found.single().title)
        assertTrue(Catchup.available(channel, found.single(), now))
    }
    @Test fun workingModernArchiveDoesNotMakeAnExtraRequest() {
        val now = System.currentTimeMillis(); var calls = 0
        val found = Catchup.load(source, channel, 1200) { _, _ ->
            calls++; """{"epg_listings":[{"title":"Past","start_timestamp":${(now - 7200000) / 1000},"stop_timestamp":${(now - 3600000) / 1000}}]}""".toByteArray()
        }
        assertEquals(1, calls); assertEquals("Past", found.single().title)
    }
}

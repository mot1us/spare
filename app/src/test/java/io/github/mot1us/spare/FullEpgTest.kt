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
    @Test fun aLargeAliasedPlaylistKeepsEveryStationsCurrentGuideWithinTheSameRecordBudget() {
        val now = System.currentTimeMillis()
        val channels = (1..26000).map { Channel("xc:$it", "Channel $it", "News", "https://example.test/$it", guideId = "xc:$it", xmltvId = "shared") }
        val programmes = (0..11).map { Programme("shared", "Slot $it", now - 1000 + it * 3600000L, now + (it + 1) * 3600000L) }
        val result = FullEpg.map(channels, source, mapOf("shared" to programmes))
        assertEquals(26000, result.size); assertTrue(result.values.sumOf { it.size } <= ProgrammeBudget.MAX)
        assertEquals("Slot 0", result.getValue("xc:26000").first().title)
        assertEquals("xc:26000", result.getValue("xc:26000").first().channel)
    }

    @Test fun aGuideMadeForTheProviderMatchesItsStreamNumbersOnlyWhenTheNamesAgree() {
        val custom = source.copy(epgUrl = "https://guide.example.test/epg.xml.gz")
        fun channel(id: Int, name: String) = Channel("xc:$id", name, "Sports", "https://example.test/$id", guideId = "xc:$id")
        // ESPN+ 19's lineup name is from yesterday; the guide names today's game on the same slot.
        val slot = channel(150130, "ESPN+ 19: Yesterday's Game @ Nov 21 7:00PM ET")
        val news = channel(369840, "Channel 4"); val clash = channel(500, "Local Weather"); val listed = channel(7, "BBC One").copy(guideId = "bbcone.uk")
        val programme = Programme("x", "Programme", 1000, 2000)
        val entries = listOf("150130", "369840", "500", "bbcone.uk").associateWith { listOf(programme.copy(channel = it)) }
        val names = mapOf("150130" to listOf("ESPN+ 19: SV Wehen Wiesbaden vs. FC Saarbrucken @ Nov 22 10:30AM ET"), "369840" to listOf("Channel 4"), "500" to listOf("Al Jazeera"),
            "bbcone.uk" to listOf("BBC One"))
        val chosen = FullEpg.choose(listOf(slot, news, clash, listed), custom, names, numbered = true)
        assertEquals(mapOf("xc:150130" to "150130", "xc:369840" to "369840"), chosen)
        val result = FullEpg.map(listOf(slot, news, clash, listed), custom, entries, chosen)
        assertEquals(setOf("xc:150130", "xc:369840", "bbcone.uk"), result.keys)
        assertEquals("Mapped listings carry the channel's own guide key", "xc:150130", result.getValue("xc:150130").single().channel)
        assertTrue("Without the guide's names a number is never trusted", FullEpg.map(listOf(slot, news), custom, entries).isEmpty())
        assertTrue(FullEpg.sameChannel("espn+ 19 : Another Game", slot))
        assertFalse(FullEpg.sameChannel("ESPN+ 18: SV Wehen Wiesbaden vs. FC Saarbrucken", slot))
        assertFalse(FullEpg.sameChannel(null, news))
    }

    @Test fun aChannelTheGuideListsUnderAnotherIdMatchesByANameOnlyOneGuideChannelHas() {
        fun channel(id: Int, name: String, xmltv: String = "") = Channel("xc:$id", name, "TV", "https://example.test/$id", guideId = "xc:$id", xmltvId = xmltv)
        val espn = channel(1, "US: ESPN HD"); val plus = channel(2, "ESPN+"); val bbc = channel(3, "BBC One"); val own = channel(4, "Sky Sports", "sky.uk")
        val accented = channel(5, "CA | Télé-Québec FHD"); val short = channel(6, "Go")
        val listed = mapOf("espn.us" to listOf("ESPN"), "bbcone.uk" to listOf("BBC One"), "bbcone.nl" to listOf("BBC One HD"), "sky.uk" to listOf("Sky Sports Main"),
            "skysports.ie" to listOf("Sky Sports"), "telequebec.ca" to listOf("Tele-Quebec"), "go.us" to listOf("GO"))
        val chosen = FullEpg.choose(listOf(espn, plus, bbc, own, accented, short), source, listed, numbered = false)
        assertEquals("Only one guide channel per name; ESPN+ is not ESPN; a channel listed by its own ID keeps it; very short names never match",
            mapOf("xc:1" to "espn.us", "xc:5" to "telequebec.ca"), chosen)
        assertEquals("espn", FullEpg.nameKey("US: ESPN HD")); assertEquals("espn+", FullEpg.nameKey("ESPN+"))
        assertEquals("skysportsmainevent", FullEpg.nameKey("UK | Sky Sports Main Event FHD"))
        val programme = Programme("x", "Programme", 1000, 2000)
        val entries = listOf("espn.us", "telequebec.ca", "sky.uk").associateWith { listOf(programme.copy(channel = it)) }
        assertEquals(setOf("xc:1", "xc:4", "xc:5"), FullEpg.map(listOf(espn, plus, bbc, own, accented, short), source, entries, chosen).keys)
    }

    @Test fun aSecondGuideThatFillsGapsKeepsWithinTheProgrammesLeft() {
        val now = System.currentTimeMillis()
        val channels = (1..10).map { Channel("xc:$it", "Channel $it", "News", "https://example.test/$it", guideId = "xc:$it", xmltvId = "id$it") }
        val entries = channels.associate { "id${it.id.removePrefix("xc:")}" to (0..99).map { slot -> Programme("x", "Slot $slot", now + slot * 3600000L, now + (slot + 1) * 3600000L) } }
        val kept = FullEpg.map(channels, source, entries, budget = 200)
        assertEquals(10, kept.size); assertTrue(kept.values.sumOf { it.size } <= 200)
        assertEquals("The current programme is kept first", "Slot 0", kept.getValue("xc:1").first().title)
    }
}

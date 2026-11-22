package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

class ImportersTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
    private fun xml(body: String) = "<?xml version=\"1.0\"?><tv>$body</tv>".toByteArray()
    private fun programme(title: String = "News &amp; Weather", channel: String = "one") =
        "<programme channel=\"$channel\" start=\"20261024110000 +0000\" stop=\"20261024130000 +0000\"><title>$title</title><desc>Details</desc></programme>"
    private fun reject(task: () -> Unit) { try { task(); fail("Unsafe input accepted") } catch (_: IllegalArgumentException) { } }

    private fun playlist(text: String, http: Boolean): Lineup {
        val streamed = text.toByteArray(Charsets.UTF_8).inputStream().bufferedReader(Charsets.UTF_8).use { Importers.m3u(it, http) }
        assertEquals(Importers.m3u(text, http), streamed)
        return streamed
    }

    @Test fun streamingPlaylistHandlesCrLfAcrossBufferBoundaryAndBareCr() {
        val prefix = " \uFEFF\r\n#EXTM3U\r\n"
        val comment = "#".padEnd(8191 - prefix.length, 'x')
        val lineup = playlist(prefix + comment + "\r\n#EXTINF:-1,Canal Éclair\rhttps://example.com/stream", false)
        assertEquals("Canal Éclair", lineup.channels.single().name)
    }

    @Test fun m3uPreservesQuotedCommasAndProviderOrder() {
        val result = playlist("""
            #EXTM3U x-tvg-url="https://guide.example/epg.xml"
            #EXTINF:-1 tvg-id="one" tvg-name="A, B" group-title="News, World" tvg-logo="https://images.example/logo.png",Channel, One
            https://stream.example/one.m3u8
            #EXTINF:-1 tvg-id="two",Channel Two
            https://stream.example/two.ts
        """.trimIndent(), false)
        assertEquals(listOf("Channel, One", "Channel Two"), result.channels.map { it.name })
        assertEquals("News, World", result.channels[0].group)
        assertEquals("https://guide.example/epg.xml", result.epgUrl)
        assertEquals("https://images.example/logo.png", result.channels[0].logo)
    }
    @Test fun m3uRequiresExplicitHttpConsent() {
        val text = "#EXTM3U\n#EXTINF:-1,One\nhttp://example.com/1\n"
        reject { playlist(text, false) }
        assertEquals(1, playlist(text, true).channels.size)
    }
    @Test fun rejectsLocalAndExecutableSchemes() {
        listOf("file:///private/source", "content://x", "javascript:alert(1)", "data:text/plain,x", "intent://x", "ftp://example.com/a").forEach {
            assertFalse(it, Network.allowed(it, true))
        }
    }
    @Test fun rejectsCredentialsEmbeddedInAuthority() {
        assertFalse(Network.allowed("https://user:password@example.com/a", true))
        assertFalse(Network.allowed("https:///missing-host", true))
    }
    @Test fun acceptsHttpsAndOnlyOptedInHttp() {
        assertTrue(Network.allowed("HTTPS://example.com/a?token=secret", false))
        assertFalse(Network.allowed("http://example.com/a", false))
        assertTrue(Network.allowed("http://192.168.1.3/playlist", true))
    }
    @Test fun stripsUnsafeLogoAndEpgAddresses() {
        val result = playlist("#EXTM3U url-tvg=\"http://example.com/guide\"\n#EXTINF:-1 tvg-logo=\"file:///private/data\",One\nhttps://example.com/stream", false)
        assertEquals("", result.epgUrl); assertEquals("", result.channels.single().logo)
    }
    @Test fun acceptsBomAndSingleQuotedAttributes() {
        val result = playlist("\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id='one' group-title='News',One\nhttps://example.com/one", false)
        assertEquals("one", result.channels.single().guideId)
        assertEquals("News", result.channels.single().group)
    }
    @Test fun rejectsHtmlInsteadOfPlaylist() { reject { playlist("<html>Login required</html>", false) } }
    @Test fun deduplicatesRepeatedChannelEntries() {
        val channel = "#EXTINF:-1 tvg-id=\"one\",One\nhttps://example.com/one\n"
        assertEquals(1, playlist("#EXTM3U\n$channel$channel", false).channels.size)
    }
    @Test fun preservesChannelIdentityAcrossTokenRefreshAndDistinguishesQueryChannels() {
        fun channel(query: String) = playlist("#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/watch?$query", false).channels.single()
        assertEquals(channel("id=1&token=old").id, channel("token=new&id=1").id)
        assertNotEquals(channel("id=1").id, channel("id=2").id)
    }
    @Test fun blocksOversizedPlaylistLines() { reject { playlist("#EXTM3U\n#" + "x".repeat(32769), false) } }
    @Test fun encodesXtreamCredentialsWithoutQueryInjection() {
        val source = Source(name = "Test", kind = "xtream", url = "https://example.com", username = "a&action=other", password = "p/#? ")
        assertEquals("https://example.com/player_api.php?username=a%26action%3Dother&password=p%2F%23%3F%20&action=get_live_streams", Importers.endpoint(source, "player_api.php", "get_live_streams"))
    }
    @Test fun rejectsXtreamBaseQuery() {
        reject { Importers.endpoint(Source(name = "Test", kind = "xtream", url = "https://example.com/?x=1"), "player_api.php") }
    }
    @Test fun parsesXmltvTimesAndBuiltInEntities() {
        val result = Importers.xmltv(xml(programme()), setOf("one"), now)
        assertEquals("News & Weather", result["one"]!!.single().title)
        assertEquals(now - 3600000, result["one"]!!.single().start)
        assertEquals("Details", result["one"]!!.single().description)
    }
    @Test fun ignoresUnknownChannelsAndStaleProgrammes() {
        val old = "<programme channel=\"one\" start=\"20260101110000 +0000\" stop=\"20260101120000 +0000\"><title>Old</title></programme>"
        assertTrue(Importers.xmltv(xml(programme(channel = "other") + old), setOf("one"), now).isEmpty())
    }
    @Test fun rejectsDtdAndExternalEntities() {
        val payload = "<!DOCTYPE tv [<!ENTITY x SYSTEM \"file:///private/data\">]><tv>${programme("&x;")}</tv>"
        reject { Importers.xmltv(payload.toByteArray(), setOf("one"), now) }
    }
    @Test fun rejectsEntityExpansionBomb() {
        reject { Importers.xmltv("<!DOCTYPE tv [<!ENTITY a 'xx'><!ENTITY b '&a;&a;'>]><tv>${programme("&b;")}</tv>".toByteArray(), setOf("one"), now) }
    }
    @Test fun rejectsDeeplyNestedXml() {
        reject { Importers.xmltv(xml("<x>".repeat(35) + "</x>".repeat(35)), setOf("one"), now) }
    }
    @Test fun boundsPlainAndDecompressedDownloads() {
        reject { Network.readLimited(ByteArray(101).inputStream(), 100) }
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(10000)) } }.toByteArray()
        reject { Network.xmlPayload(zipped, 100) }
    }
    @Test fun supportsGzippedXmltv() {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml(programme())) } }.toByteArray()
        assertEquals(1, Importers.xmltv(zipped, setOf("one"), now)["one"]!!.size)
    }
    @Test fun rejectsInvalidDatesAndHonorsOffsets() {
        assertEquals(0, Importers.timestamp("20260230120000 +0000"))
        assertEquals(now, Importers.timestamp("20261024140000 +0200"))
        assertEquals(0, Importers.timestamp("garbage"))
    }
    @Test fun roundTripsEncryptedCachePayloadShapeWithoutDroppingProgrammeFields() {
        val channel = Channel("one", "One", "News", "https://example.com/one", guideId = "one")
        val guide = Guide(listOf(channel), Importers.xmltv(xml(programme()), setOf("one"), now))
        val restored = Guide.from(guide.json())
        assertEquals(guide, restored)
    }

    @Test fun aGuideNamesItsWantedChannelsWhenAsked() {
        val now = System.currentTimeMillis()
        fun stamp(at: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC))
        val xml = """<tv><channel id="150130"><display-name>ESPN+ 19: Game</display-name><display-name>Second name</display-name></channel>
            <channel id="9"><display-name>Not wanted</display-name></channel>
            <programme channel="150130" start="${stamp(now - 60000)}" stop="${stamp(now + 3600000)}"><title>Game</title></programme></tv>"""
        val names = HashMap<String, String>()
        val result = Importers.xmltv(xml.toByteArray().inputStream(), setOf("150130"), now, names = names)
        assertEquals(mapOf("150130" to "ESPN+ 19: Game"), names)
        assertEquals("Game", result.getValue("150130").single().title)
        assertEquals("The guide is larger than Spare reads (GUIDE_SIZE). Use a smaller guide link, or leave the link empty for the provider's guide.",
            SourceErrors.message(IllegalArgumentException(Importers.GUIDE_TOO_LARGE)))
        assertTrue(Importers.XMLTV_MAX_BYTES >= 600L * 1024 * 1024 && Importers.XMLTV_MAX_ELEMENTS >= 4_000_000)
    }

    @Test fun anOverlongGuideFieldIsCutInsteadOfDiscardingTheWholeGuide() {
        val now = System.currentTimeMillis()
        fun stamp(at: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC))
        val long = "x".repeat(40_000)
        val xml = """<tv><programme channel="a" start="${stamp(now - 60000)}" stop="${stamp(now + 3600000)}"><title>Long &amp; $long</title><desc>$long</desc></programme>
            <programme channel="b" start="${stamp(now - 60000)}" stop="${stamp(now + 3600000)}"><title>Next</title></programme></tv>"""
        val result = Importers.xmltv(xml.toByteArray().inputStream(), setOf("a", "b"), now)
        assertTrue(result.getValue("a").single().title.startsWith("Long & xxx"))
        assertEquals("Next", result.getValue("b").single().title)
    }
}

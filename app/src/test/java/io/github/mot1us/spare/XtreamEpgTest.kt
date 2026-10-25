package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class XtreamEpgTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
    @Test fun decodesBase64AndMapsMissingProviderEpgIdsToTheStream() {
        val body = """{"epg_listings":[{"title":"TUxCIExpdmU=","description":"TGl2ZSBiYXNlYmFsbA==","start_timestamp":"${now / 1000}","stop_timestamp":${(now + 3600000) / 1000}}]}"""
        val entry = XtreamEpg.parse(body, "xc:42", now).single()
        assertEquals("MLB Live", entry.title); assertEquals("Live baseball", entry.description); assertEquals("xc:42", entry.channel)
        val channel = Channel("xc:42", "One", "Sports", "https://example.com/42.ts")
        assertEquals(entry, Guide(listOf(channel), mapOf("xc:42" to listOf(entry))).at(channel, now + 1000))
    }
    @Test fun acceptsPlainTextAndMillisecondEpochsButDoesNotGuessOffsetFreeDates() {
        val body = """{"epg_listings":[{"title":"World News","start_timestamp":$now,"end_timestamp":${now + 60000}},{"title":"No timezone","start":"2026-10-24 12:00:00","end":"2026-10-24 13:00:00"}]}"""
        assertEquals("World News", XtreamEpg.parse(body, "one", now).single().title)
        assertTrue(XtreamEpg.parse("""{"epg_listings":[]}""", "one", now).isEmpty())
        assertThrows(SourceFailure::class.java) { XtreamEpg.parse("{}", "one", now) }
    }
    @Test fun compatibleXmltvTimestampVariantsRemainStrictAboutDates() {
        listOf("20261024120000Z", "20261024120000 UTC", "202610241200 +0000", "20261024140000+0200").forEach { assertEquals(it, now, Importers.timestamp(it)) }
        assertEquals(0, Importers.timestamp("20260230120000 UTC"))
    }
}

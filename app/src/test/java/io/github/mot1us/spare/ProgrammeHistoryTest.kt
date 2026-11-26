package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class ProgrammeHistoryTest {
    private val source = Source(name = "Fixture", kind = "xtream", url = "https://example.com", username = "user", password = "pass")
    private val channel = Channel("xc:1", "Archive", "Other", "https://example.com/live/user/pass/1.ts", catchupDays = 2, catchupMode = "xc")
    private val hour = 3600000L
    private val now = 1_800_000_000_000L
    private fun programme(title: String, start: Long, end: Long) = Programme("xc:1", title, start, end)

    @Test fun aChannelsListRunsOldestFirstAndSaysWhatEachProgrammeOffers() {
        val archived = programme("Archived", now - 3 * hour, now - 2 * hour)
        val missing = programme("Not archived", now - 2 * hour, now - hour)
        val on = programme("On now", now - hour / 2, now + hour / 2)
        val later = programme("Later", now + hour / 2, now + 3 * hour / 2)
        val entries = ProgrammeHistory.entries(channel, listOf(later, on, missing, archived, archived), setOf(archived.start, on.start), http = false, now = now, zone = ZoneOffset.UTC)
        assertEquals(listOf("Archived", "Not archived", "On now", "Later"), entries.map { it.programme.title })
        assertEquals(listOf(ProgrammeHistory.State.CATCHUP, ProgrammeHistory.State.PAST, ProgrammeHistory.State.NOW, ProgrammeHistory.State.LATER), entries.map { it.state })
        assertTrue("A catch-up programme carries its replay address", entries[0].replay!!.contains("/timeshift/user/pass/60/"))
        assertNull("The programme on now plays live, not from the archive", entries[2].replay)
        assertEquals("The list starts on the programme on now", "On now", ProgrammeHistory.focus(entries)?.programme?.title)
        assertTrue(ProgrammeHistory.detail(entries[0]).endsWith("· Catch-up")); assertTrue(ProgrammeHistory.detail(entries[1]).endsWith("· No catch-up"))
        assertTrue(ProgrammeHistory.detail(entries[2]).startsWith("Now · "))
        assertEquals(setOf("Today"), entries.map { it.day }.toSet())
    }
    @Test fun beforeTheProviderAnswersTheArchiveWindowDecidesAndAChannelWithoutCatchUpOffersNone() {
        val yesterday = programme("Yesterday", now - 26 * hour, now - 25 * hour)
        val tooOld = programme("Too old", now - 75 * hour, now - 74 * hour)
        val entries = ProgrammeHistory.entries(channel, listOf(yesterday, tooOld), archived = null, http = false, now = now, zone = ZoneOffset.UTC)
        assertEquals(listOf(ProgrammeHistory.State.PAST, ProgrammeHistory.State.CATCHUP), entries.map { it.state })
        assertEquals("Yesterday", entries[1].day)
        assertEquals("With nothing on now the list starts on the latest programme that has ended", "Yesterday", ProgrammeHistory.focus(entries)?.programme?.title)
        val plain = ProgrammeHistory.entries(channel.copy(catchupDays = 0, catchupMode = ""), listOf(yesterday), null, false, now, ZoneOffset.UTC)
        assertEquals(ProgrammeHistory.State.PAST, plain.single().state)
        assertEquals("At least three days back, at most a week", 3, ProgrammeHistory.days(channel)); assertEquals(7, ProgrammeHistory.days(channel.copy(catchupDays = 14)))
    }
    @Test fun theProvidersListKeepsProgrammesItDoesNotArchiveAndSaysWhichItDoes() {
        val time = System.currentTimeMillis()
        fun listing(title: String, start: Long, archive: Int?) = """{"title":"${java.util.Base64.getEncoder().encodeToString(title.toByteArray())}","start_timestamp":${start / 1000},"stop_timestamp":${(start + hour) / 1000}${archive?.let { ",\"has_archive\":$it" }.orEmpty()}}"""
        val body = """{"epg_listings":[${listing("Kept", time - 5 * hour, 1)},${listing("Not kept", time - 3 * hour, 0)},${listing("Unflagged", time - 2 * hour, null)}]}"""
        val (entries, archived) = Catchup.history(source, channel, 3, 1200) { url, _ -> assertTrue(url.contains("get_simple_data_table")); body.toByteArray() }
        assertEquals(listOf("Kept", "Not kept", "Unflagged"), entries.map { it.title })
        assertEquals(setOf(entries[0].start, entries[2].start), archived)
        // The catch-up list itself still leaves out what the provider does not archive.
        assertEquals(listOf("Kept", "Unflagged"), Catchup.load(source, channel, 1200) { _, _ -> body.toByteArray() }.map { it.title })
    }
}

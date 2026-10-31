package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaybackJournalTest {
    @Test fun reportIsBoundedStructuredAndClearable() {
        val journal = PlaybackJournal()
        repeat(200) { journal.record(PlaybackJournal.Event.SAMPLE, it * 1000L, 5000, 100000000, 256000000, 4) }
        val report = journal.report("0.24.0")
        assertEquals(64, report.lines().count { it.startsWith("+") })
        assertFalse(report.contains("http://")); assertFalse(report.contains("https://"))
        assertTrue(report.contains("4 stream(s)")); assertTrue(report.contains("Java heap"))
        journal.clear(); assertTrue(journal.report("0.24.0").contains("No playback events"))
    }
}

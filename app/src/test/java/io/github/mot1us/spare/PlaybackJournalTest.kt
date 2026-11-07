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
    @Test fun recoveryDiagnosticsContainOnlyCodesAndTiming() {
        val journal = PlaybackJournal()
        journal.record(PlaybackJournal.Event.LOAD_ERROR, 1000, 6000, 12000000, 256000000, 1, code = 404, liveOffsetMs = 15000, loading = true)
        journal.record(PlaybackJournal.Event.DISCONTINUITY, 2000, 0, 12000000, 256000000, 1, code = 0, liveOffsetMs = Long.MIN_VALUE)
        val report = journal.report("test")
        assertTrue(report.contains("code 404")); assertTrue(report.contains("live offset 15s")); assertTrue(report.contains("loading true"))
        assertFalse(report.contains(Long.MIN_VALUE.toString())); assertFalse(report.contains("https://"))
    }
}

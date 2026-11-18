package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySavedAgeTest {
    private val day = 86400000L
    private val now = 1_800_000_000_000L

    @Test fun rowsSayHowOldTheSavedCatalogIs() {
        assertEquals("Saved today", LibraryController.savedAge(now - 3_600_000L, now))
        assertEquals("Saved yesterday", LibraryController.savedAge(now - day - 1, now))
        assertEquals("Saved 2 days ago · Refresh for new titles", LibraryController.savedAge(now - 2 * day, now))
        assertEquals("Saved 9 days ago · Refresh for new titles", LibraryController.savedAge(now - 9 * day - 5, now))
    }

    @Test fun aBackgroundRefreshSaysItIsCheckingInsteadOfAskingForRefresh() {
        assertEquals("Saved 3 days ago · Checking for new titles…", LibraryController.savedAge(now - 3 * day, now, checking = true))
        assertEquals("Saved today · Checking for new titles…", LibraryController.savedAge(now - 7 * 3_600_000L, now, checking = true))
        assertEquals("Saved on this TV · Checking for new titles…", LibraryController.savedAge(0, now, checking = true))
    }

    @Test fun aSavedCatalogIsRefreshedAfterSixHoursOrWhenItsFormatIsOlder() {
        fun saved(updated: Long, schema: Int = LibraryCatalog.SCHEMA) = LibraryCatalog.Snapshot(updated, emptyList(), 10, emptyMap(), schema = schema)
        assertFalse(LibraryCatalog.due(saved(now - 5 * 3_600_000L), now))
        assertTrue(LibraryCatalog.due(saved(now - 6 * 3_600_000L), now))
        assertTrue("A stamp from the future is not trusted", LibraryCatalog.due(saved(now + 60_000L), now))
        assertTrue(LibraryCatalog.due(saved(now - 60_000L, schema = LibraryCatalog.SCHEMA - 1), now))
    }

    @Test fun anUnknownOrFutureStampKeepsThePlainLabel() {
        assertEquals("Saved on this TV", LibraryController.savedAge(0, now))
        assertEquals("Saved on this TV", LibraryController.savedAge(now + day, now))
    }
}

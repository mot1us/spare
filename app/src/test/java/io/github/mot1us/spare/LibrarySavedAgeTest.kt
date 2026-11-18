package io.github.mot1us.spare

import org.junit.Assert.assertEquals
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

    @Test fun anUnknownOrFutureStampKeepsThePlainLabel() {
        assertEquals("Saved on this TV", LibraryController.savedAge(0, now))
        assertEquals("Saved on this TV", LibraryController.savedAge(now + day, now))
    }
}

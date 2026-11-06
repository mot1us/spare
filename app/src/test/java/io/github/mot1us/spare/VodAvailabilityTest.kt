package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class VodAvailabilityTest {
    @Test fun absentFromCompleteCatalogStopsImmediatelyEvenDuringRefreshOrFailure() {
        val state = VodAvailability()
        val result = state.update("movie", 100, false, true, true, true)
        assertFalse(result.checking)
        assertEquals("Not available in your VOD library", result.message)
    }

    @Test fun unfinishedCatalogGetsABoundedSpinnerWithoutClaimingTheTitleIsAbsent() {
        val state = VodAvailability()
        assertTrue(state.update("movie", 100, false, false, true, false).checking)
        assertTrue(state.update("movie", 8099, false, false, true, false).checking)
        val expired = state.update("movie", 8100, false, false, true, false)
        assertFalse(expired.checking); assertTrue(expired.message.contains("still updating"))
        assertFalse(state.update("movie", 20000, false, false, false, true).checking)
        assertFalse("Reopening the same programme must not restart the wait", state.update("movie", 30000, false, false, true, false).checking)
        assertTrue("A different programme gets its own check", state.update("another movie", 30000, false, false, true, false).checking)
    }

    @Test fun lateMatchesAndCompletedEmptyIndexesReplaceTheExpiredState() {
        val state = VodAvailability()
        state.update("show", 0, false, false, true, false)
        assertFalse(state.update("show", 9000, false, false, true, false).checking)
        val found = state.update("show", 10000, true, false, true, true)
        assertFalse(found.checking); assertEquals("", found.message)
        assertEquals("Not available in your VOD library", state.update("show", 11000, false, true, false, false).message)
    }

    @Test fun FailedImportsAreNotReportedAsMissingTitlesOrRestartedByUnrelatedUpdates() {
        val state = VodAvailability()
        val failed = state.update("episode", 0, false, false, false, true)
        assertFalse(failed.checking); assertTrue(failed.message.contains("unavailable")); assertFalse(failed.message.contains("Not available"))
        assertTrue(state.update("episode", 5000, false, false, true, false).checking)
        assertFalse(state.update("episode", 13000, false, false, true, false).checking)
    }
}

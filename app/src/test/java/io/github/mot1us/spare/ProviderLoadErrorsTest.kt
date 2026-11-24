package io.github.mot1us.spare

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderLoadErrorsTest {
    @Test fun transientNetworkFailuresKeepTheTransportRetryBudgetBeforeFullReconnect() {
        val policy = ProviderLoadErrors()
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }
    @Test fun briefMissingLiveSegmentsRetryBeforeDiscardingBufferedVideo() {
        val policy = ProviderLoadErrors("https://provider.test/live.m3u8?token=synthetic")
        assertEquals(750L, policy.missingSegmentDelay(404, "https://cdn.test/segment.ts", 1))
        assertEquals(1500L, policy.missingSegmentDelay(410, "https://cdn.test/variant.m3u8", 2))
        assertNull(policy.missingSegmentDelay(404, "https://cdn.test/segment.ts", 3))
        assertNull("A missing main endpoint keeps startup/alternate handling", policy.missingSegmentDelay(404, "https://provider.test/live.m3u8?token=synthetic", 1))
        listOf(401, 403, 408, 429, 500, null).forEach { status -> assertNull(policy.missingSegmentDelay(status, "https://cdn.test/segment.ts", 1)) }
        assertNull(policy.missingSegmentDelay(404, "file:///segment.ts", 1))
        assertNull("VOD and generic sources retain their existing missing-file handling", ProviderLoadErrors().missingSegmentDelay(404, "https://cdn.test/movie.ts", 1))
    }

    @Test fun aPlayingMovieRetriesADroppedConnectionWhileItsBufferPlays() {
        val policy = ProviderLoadErrors(demand = { true }, demandPlaying = { true })
        assertEquals(ProviderLoadErrors.DEMAND_RETRIES, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        // A connection limit or a dropped socket while the film plays: retried, the buffer kept.
        listOf(403, 429, 458, 503, null).forEach { status -> assertEquals("$status", 1000L, policy.demandDelay(status, 1, demand = true, playing = true)) }
        assertEquals(3000L, policy.demandDelay(403, 7, demand = true, playing = true))
        assertEquals(C.TIME_UNSET, policy.demandDelay(null, ProviderLoadErrors.DEMAND_RETRIES + 1, demand = true, playing = true))
        // Answers that cannot change still fail at once.
        listOf(302, 401, 404, 410).forEach { status -> assertEquals("$status", C.TIME_UNSET, policy.demandDelay(status, 1, demand = true, playing = true)) }
        // Before the first frame a movie keeps Media3's budget, so a broken film still fails quickly.
        assertNull(policy.demandDelay(403, 1, demand = true, playing = false))
        assertEquals(C.TIME_UNSET, policy.demandDelay(null, ProviderLoadErrors.START_RETRIES + 1, demand = true, playing = false))
        // Live channels are unchanged.
        assertNull(policy.demandDelay(403, 1, demand = false, playing = true))
        assertEquals(3, ProviderLoadErrors().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}

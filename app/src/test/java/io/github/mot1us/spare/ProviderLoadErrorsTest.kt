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
}

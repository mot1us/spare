package io.github.mot1us.spare

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderLoadErrorsTest {
    @Test fun transientNetworkFailuresKeepTheTransportRetryBudgetBeforeFullReconnect() {
        val policy = ProviderLoadErrors()
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(6, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
    }
}

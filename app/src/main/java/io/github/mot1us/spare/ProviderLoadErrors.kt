package io.github.mot1us.spare

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@UnstableApi
class ProviderLoadErrors : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = PlaybackErrors.httpFailure(loadErrorInfo.exception)?.responseCode
        if (status != null && status in 400..499 && status != 408) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

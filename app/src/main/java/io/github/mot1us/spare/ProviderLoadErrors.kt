package io.github.mot1us.spare

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@UnstableApi
class ProviderLoadErrors(private val liveHlsEndpoint: String? = null) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = PlaybackErrors.httpFailure(loadErrorInfo.exception)?.responseCode
        missingSegmentDelay(status, loadErrorInfo.loadEventInfo.dataSpec.uri.toString(), loadErrorInfo.errorCount)?.let { return it }
        if (status != null && status in 400..499 && status != 408) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** Retry a briefly unavailable live segment without throwing away queued video. */
    internal fun missingSegmentDelay(status: Int?, failedUrl: String, attempt: Int): Long? {
        val endpoint = liveHlsEndpoint ?: return null
        if (status !in setOf(404, 410) || attempt !in 1..2) return null
        val child = runCatching { java.net.URI(failedUrl).normalize() }.getOrNull() ?: return null
        val root = runCatching { java.net.URI(endpoint).normalize() }.getOrNull() ?: return null
        if (child.host == null || child.scheme !in setOf("http", "https") || child == root) return null
        return attempt * 750L
    }
}

package io.github.mot1us.spare

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@UnstableApi
class ProviderLoadErrors(private val liveHlsEndpoint: String? = null, private val demand: () -> Boolean = { false },
    private val demandPlaying: () -> Boolean = { false }) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = PlaybackErrors.httpFailure(loadErrorInfo.exception)?.responseCode
        missingSegmentDelay(status, loadErrorInfo.loadEventInfo.dataSpec.uri.toString(), loadErrorInfo.errorCount)?.let { return it }
        demandDelay(status, loadErrorInfo.errorCount, demand(), demandPlaying())?.let { return it }
        // A redirect that reaches the player is one Spare will not follow (to plain HTTP without the playlist's
        // consent); retrying it only wasted about five seconds per stream before the next choice.
        if (final(status)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    // A movie or episode loads as one long request, so its retry budget is set when it starts.
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (demand()) DEMAND_RETRIES else super.getMinimumLoadableRetryCount(dataType)

    /**
     * A movie that is already playing keeps its buffer when its connection drops: some providers end a movie's connection
     * every few minutes and answer the reconnect with an error (such as too many connections) for a moment. Spare gave up
     * at once, stopped the player and started the movie again, a few seconds of buffering every 2 to 5 minutes on a 4K film
     * that TiviMate played smoothly. Now it retries every one to three seconds for about half a minute while the buffer
     * plays. Answers that cannot change (gone, unauthorized, a refused redirect) still fail at once, and before the first
     * frame a movie fails as quickly as before.
     */
    internal fun demandDelay(status: Int?, attempt: Int, demand: Boolean, playing: Boolean): Long? {
        if (!demand) return null
        if (!playing) return if (attempt > START_RETRIES) C.TIME_UNSET else null
        if (status != null && (status in 300..399 || status in setOf(401, 404, 410))) return C.TIME_UNSET
        if (attempt > DEMAND_RETRIES) return C.TIME_UNSET
        return minOf(attempt * 1000L, 3000L)
    }

    /** Responses that retrying cannot change: a redirect Spare will not follow, or a client error other than a timeout. */
    internal fun final(status: Int?) = status != null && (status in 300..399 || status in 400..499 && status != 408)

    /** Retry a briefly unavailable live segment without throwing away queued video. */
    internal fun missingSegmentDelay(status: Int?, failedUrl: String, attempt: Int): Long? {
        val endpoint = liveHlsEndpoint ?: return null
        if (status !in setOf(404, 410) || attempt !in 1..2) return null
        val child = runCatching { java.net.URI(failedUrl).normalize() }.getOrNull() ?: return null
        val root = runCatching { java.net.URI(endpoint).normalize() }.getOrNull() ?: return null
        if (child.host == null || child.scheme !in setOf("http", "https") || child == root) return null
        return attempt * 750L
    }

    companion object {
        /** Retries for a playing movie: about 30 seconds at one to three seconds apart. */
        const val DEMAND_RETRIES = 12
        /** Media3's own budget, kept for a movie that has not shown a frame. */
        const val START_RETRIES = 3
    }
}

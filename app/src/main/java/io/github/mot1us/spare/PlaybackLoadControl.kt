package io.github.mot1us.spare

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/** One allocator/player, with a steadier startup cushion for on-demand files. */
@UnstableApi
class PlaybackLoadControl(private val base: LoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(60000, 90000, 1500, 10000)
    .setTargetBufferBytes(96 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build()) : LoadControl {
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    // Explicitly forward Media3's default interface methods as well as abstract ones.
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) = base.onTracksSelected(parameters, groups, selections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId) = base.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters) = base.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long) = base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val timeline = parameters.timeline
        val index = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index < 0 || timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window)
                .mediaItem.localConfiguration?.tag != VOD) return base.shouldStartPlayback(parameters)
        // ExoPlayer handles fully loaded short files/the end of a file separately.
        // Keep the existing 60–90 second read-ahead and allocator limits.
        val cushionUs = if (parameters.rebuffering) 12000000L else 5000000L
        return Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed) >= cushionUs
    }

    companion object { const val VOD = "spare-vod" }
}

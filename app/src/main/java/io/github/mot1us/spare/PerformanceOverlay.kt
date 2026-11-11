package io.github.mot1us.spare

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale

/** Installed only while the developer switch is on and the activity is started. */
@UnstableApi
internal class PerformanceOverlay(
    private val activity: Activity,
    private val player: ExoPlayer,
    private val videoSurface: View,
    private val reading: () -> VideoReading,
    private val journal: PlaybackJournal,
) : AnalyticsListener {
    data class VideoReading(val mediaId: String, val requested: Float, val info: VideoInfo?, val firstFrame: Boolean, val dropped: Long)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("spare-frame-metrics").apply { start() }
    private val background = Handler(worker.looper)
    private val frames = PerformanceFrames()
    private val drops = PerformanceDrops()
    private val settle = DisplaySettle()
    val sports = SportsClickTraces(SystemClock::elapsedRealtime, journal::sports)
    private var codec = ""
    @Volatile private var hardware = emptyMap<String, Boolean>()
    @Volatile private var fallbackDeadlineNs = 16_666_667L
    private var lastViewAt = -1L
    private var lastJournalAt = -1L
    private val view = TextView(activity).apply {
        tag = "performance-overlay"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        typeface = Typeface.MONOSPACE
        textSize = 10f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xe0181a1f.toInt())
        setPadding(dp(9), dp(6), dp(9), dp(6))
    }

    private val metrics = Window.OnFrameMetricsAvailableListener { _, data, reportsDropped ->
        val reportedDeadline = if (Build.VERSION.SDK_INT >= 31) data.getMetric(FrameMetrics.DEADLINE) else -1L
        frames.record(SystemClock.elapsedRealtime(), data.getMetric(FrameMetrics.TOTAL_DURATION),
            reportedDeadline.takeIf { it > 0 } ?: fallbackDeadlineNs, reportsDropped)
    }

    private val sample = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val video = reading()
            val mode = videoSurface.display?.mode
            val hz = mode?.refreshRate ?: 0f
            if (hz > 0f) fallbackDeadlineNs = (1_000_000_000.0 / hz).toLong()
            val info = video.info
            val elapsed = settle.observe(now, mode?.modeId ?: 0, hz, video.requested, info?.fps ?: 0f, video.firstFrame)
            handler.postDelayed(this, 250)
            if (lastViewAt >= 0 && now - lastViewAt < 1_000) return
            lastViewAt = now
            val buffer = player.totalBufferedDuration.coerceAtLeast(0)
            val decoderType = when (hardware[codec]) { true -> "hardware"; false -> "software"; null -> "acceleration unknown" }
            val rateLine = "Display ${rate(hz)} Hz · Request ${rate(video.requested)} fps · " +
                (elapsed?.let { "Settled ${it}ms from tune" } ?: if (video.requested > 0f && hz > 0f && !DisplaySettle.compatible(hz, video.requested)) "Mode unmatched" else "Settle pending")
            val sourceLine = "Source ${rate(info?.fps ?: 0f)} fps · ${info?.evidence?.ifBlank { "unknown" } ?: "unknown"} · " +
                when (info?.interlaced) { true -> "interlaced"; false -> "progressive"; null -> "scan unknown" }
            val decoderLine = if (codec.isEmpty()) "Decoder unknown · retune to sample" else "Decoder $codec · $decoderType"
            val dropLine = "Dropped (10s / attempt) ${drops.count(now)} / ${video.dropped} · Buffer ${buffer}ms"
            val uiLine = frames.summary(now)?.let {
                String.format(Locale.ROOT, "UI 10s · p50 %.2f / p95 %.2f / p99 %.2f ms · %.1f%% jank · %d frames · %d missed reports",
                    it.p50 / 1e6, it.p95 / 1e6, it.p99 / 1e6, it.jankyPercent, it.count, it.missedReports)
            } ?: "UI 10s · no app frames"
            val report = listOf(rateLine, sourceLine, decoderLine, dropLine, uiLine, sports.report()).joinToString("\n")
            if (view.text.toString() != report) view.text = report
            if (lastJournalAt < 0 || now - lastJournalAt >= 5_000) {
                journal.performance(now, report)
                lastJournalAt = now
            }
        }
    }

    init {
        activity.addContentView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(10); marginEnd = dp(12)
        })
        background.post {
            if (Build.VERSION.SDK_INT >= 29) hardware = runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associate { it.name to it.isHardwareAccelerated }
            }.getOrDefault(emptyMap())
        }
        player.addAnalyticsListener(this)
        activity.window.addOnFrameMetricsAvailableListener(metrics, background)
        handler.post(sample)
    }

    fun tune() = settle.tune(SystemClock.elapsedRealtime())
    fun attempt() { drops.clear(); settle.resetObservation(); lastViewAt = -1L; lastJournalAt = -1L }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        if (belongs(eventTime)) codec = decoderName
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (belongs(eventTime)) drops.record(eventTime.realtimeMs, droppedFrames)
    }

    private fun belongs(eventTime: AnalyticsListener.EventTime): Boolean {
        val expected = reading().mediaId
        return expected.isNotEmpty() && eventTime.windowIndex in 0 until eventTime.timeline.windowCount &&
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == expected
    }
    private fun rate(value: Float) = if (value.isFinite() && value > 0f) String.format(Locale.ROOT, "%.3f", value) else "—"
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun close() {
        sports.close()
        handler.removeCallbacksAndMessages(null)
        activity.window.removeOnFrameMetricsAvailableListener(metrics)
        player.removeAnalyticsListener(this)
        background.removeCallbacksAndMessages(null)
        worker.quitSafely()
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

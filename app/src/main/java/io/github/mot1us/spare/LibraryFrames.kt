package io.github.mot1us.spare

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/** How many frames were drawn, how many missed their deadline, and roughly how many screen refreshes that cost. */
internal class FrameTally {
    private var frames = 0
    private var slow = 0
    private var dropped = 0L
    private var worstNs = 0L

    /** [deadlineNs] is the time the system allowed for the frame; [periodNs] is one screen refresh. */
    @Synchronized fun record(durationNs: Long, deadlineNs: Long, periodNs: Long) {
        if (durationNs <= 0 || deadlineNs <= 0 || periodNs <= 0) return
        frames++
        if (durationNs > worstNs) worstNs = durationNs
        if (durationNs > deadlineNs) { slow++; dropped += (durationNs - deadlineNs + periodNs - 1) / periodNs }
    }
    @Synchronized fun reset() { frames = 0; slow = 0; dropped = 0; worstNs = 0 }
    @Synchronized fun summary(): String? = if (frames == 0) null else String.format(Locale.ROOT,
        "%d drawn · %d slow (%.1f%%) · about %d dropped · worst %d ms", frames, slow, slow * 100.0 / frames, dropped, (worstNs + 500_000) / 1_000_000)
}

/**
 * Frame times while Library is on screen, for Settings › Playlists › Refresh settings. Android only reports
 * frames the app actually drew, so an idle screen adds nothing: the counts describe scrolling and opening rows.
 * Each Library visit starts a new count; the last one stays readable after Library closes. Kept in memory only.
 */
internal object LibraryFrames {
    private val tally = FrameTally()
    private var window: Window? = null
    private var thread: HandlerThread? = null
    @Volatile private var periodNs = 16_666_667L
    private val listener = Window.OnFrameMetricsAvailableListener { _, data, _ ->
        // The first frame of a new layout includes inflating it; it is not a scrolling hitch.
        if (data.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) {
            val deadline = if (Build.VERSION.SDK_INT >= 31) data.getMetric(FrameMetrics.DEADLINE) else -1L
            tally.record(data.getMetric(FrameMetrics.TOTAL_DURATION), deadline.takeIf { it > 0 } ?: periodNs, periodNs)
        }
    }

    /** Main thread only. */
    fun start(target: Window, refreshRate: Float) {
        if (window === target) return
        stop(); tally.reset()
        if (refreshRate.isFinite() && refreshRate >= 1f) periodNs = (1_000_000_000.0 / refreshRate).toLong()
        val worker = HandlerThread("spare-library-frames").apply { start() }
        runCatching { target.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
            .onSuccess { window = target; thread = worker }.onFailure { worker.quitSafely() }
    }
    fun stop() {
        window?.let { runCatching { it.removeOnFrameMetricsAvailableListener(listener) } }; window = null
        thread?.quitSafely(); thread = null
    }
    fun summary() = tally.summary()
}

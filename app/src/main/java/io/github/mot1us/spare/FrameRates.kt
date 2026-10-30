package io.github.mot1us.spare

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.round

class FrameRates(private val activity: Activity, private val view: SurfaceView) : SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private val target = FrameRateTarget()
    @Volatile private var rate = 0f
    @Volatile var enabled = true
        set(value) { field = value; if (!value) clear() }
    init { view.holder.addCallback(this) }
    /** Apply a known rate before prepare, while the opening overlay is still visible. */
    fun begin(value: Float) {
        target.reset(value)
        if (value.isFinite() && value in 10f..120f) request(value)
    }
    fun observe(value: Float, reliable: Boolean) {
        if (!enabled) return
        val settled = target.observe(value, android.os.SystemClock.elapsedRealtime(), reliable) ?: return
        if (abs(settled - rate) < .1f) return
        rate = settled; handler.post { apply() }
    }
    fun request(value: Float) {
        if (!enabled || !value.isFinite() || value !in 10f..120f || abs(value - rate) < .1f) return
        target.reset(value); rate = value
        handler.post { apply() }
    }
    private fun apply() {
        if (!enabled || rate <= 0f) return
        runCatching {
            val surface = view.holder.surface
            if (!surface.isValid) return
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else if (rate > 0f) {
                @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay
                val current = display.mode
                val match = display.supportedModes.filter {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                        abs(it.refreshRate / rate - round(it.refreshRate / rate)) < .025f
                }.minByOrNull { it.refreshRate }
                if (match != null) activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = match.modeId }
            }
        }
    }
    fun clear() {
        target.reset(); rate = 0f
        handler.removeCallbacksAndMessages(null)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30 && view.holder.surface.isValid)
                view.holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { apply() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { apply() }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}

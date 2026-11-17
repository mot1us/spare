package io.github.mot1us.spare

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ProgressBar

/** An optional CRT sweep for indeterminate foreground waits. Determinate progress still uses the normal bar. */
internal class TuningBar(context: Context) : ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    private val standard = indeterminateDrawable
    fun useNostalgia(enabled: Boolean, accent: Int) {
        val current = indeterminateDrawable
        val sweep = current as? Sweep
        if (enabled && sweep != null) {
            if (sweep.accent != accent) { sweep.accent = accent; sweep.invalidateSelf() }
            return
        }
        if (!enabled && sweep == null) return
        val replacement = if (enabled) Sweep(accent) else standard
        (current as? Animatable)?.stop()
        replacement.bounds = current.bounds
        indeterminateDrawable = replacement
        if (isIndeterminate && isShown && windowVisibility == VISIBLE) (replacement as? Animatable)?.start()
    }

    private class Sweep(var accent: Int) : Drawable(), Animatable {
        private val paint = Paint()
        private var running = false
        private var startedAt = 0L
        private var opacity = 255
        private val frame = object : Runnable {
            override fun run() {
                if (!running) return
                invalidateSelf()
                scheduleSelf(this, SystemClock.uptimeMillis() + 50)
            }
        }
        override fun start() {
            if (running) return
            running = true
            startedAt = SystemClock.uptimeMillis()
            frame.run()
        }
        override fun stop() { running = false; unscheduleSelf(frame) }
        override fun isRunning() = running

        override fun draw(canvas: Canvas) {
            val bar = bounds
            val width = bar.width() / 25f
            if (width <= 0f || bar.height() <= 0) return
            // A dim row of phosphor ticks with a soft-edged sweep that keeps moving until the request ends.
            val phase = ((SystemClock.uptimeMillis() - startedAt) % 2000L) / 50
            val centre = if (phase <= 20) phase + 2 else 42 - phase
            paint.color = accent
            for (index in 0 until 25) {
                val distance = kotlin.math.abs(centre - index)
                paint.alpha = (if (distance < 4) 188 - 38 * distance.toInt() else 42) * opacity / 255
                val left = bar.left + index * width
                canvas.drawRect(left, bar.top.toFloat(), left + width * .68f, bar.bottom.toFloat(), paint)
            }
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

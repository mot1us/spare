package io.github.mot1us.spare

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * A Sports card's stream line. While its game is still being looked up, it shows a soft bar with a band of light sweeping
 * across it, as Library does while it loads, instead of text that changed several times ("Select to find stream",
 * "Finding stream · 3 / 10", "Checking event channels…"). The result fades in once. The text stays set underneath, so
 * the card still reads aloud and its tag still names what it found.
 */
internal class CardStreamLine(context: Context) : TextView(context) {
    var looking = false
        private set
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val sweep = Matrix()
    private var shineWidth = 0
    private var shineColor = 0

    fun show(value: String, lookingUp: Boolean) {
        val wasLooking = looking
        looking = lookingUp
        if (text.toString() != value) text = value
        if (wasLooking && !lookingUp && Motion.enter > 0 && isShown) {
            alpha = 0f; animate().alpha(1f).setDuration(RESULT_FADE_MS).start()
        } else if (lookingUp || !wasLooking) { animate().cancel(); alpha = 1f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!looking) { super.onDraw(canvas); return }
        val density = resources.displayMetrics.density
        val height = 6 * density; val top = (this.height - height) / 2f
        bar.set(paddingLeft.toFloat(), top, paddingLeft + (width - paddingLeft - paddingRight) * .58f, top + height)
        // The line's own text colour, faint: it follows the card's focus ink.
        val ink = currentTextColor
        fill.color = ColorUtils.setAlphaComponent(ink, 46)
        canvas.drawRoundRect(bar, height / 2, height / 2, fill)
        if (Motion.enter <= 0) return
        if (shineWidth != width || shineColor != ink) {
            shineWidth = width; shineColor = ink
            shine.shader = LinearGradient(0f, 0f, width * .35f, 0f, intArrayOf(ColorUtils.setAlphaComponent(ink, 0), ColorUtils.setAlphaComponent(ink, 72), ColorUtils.setAlphaComponent(ink, 0)), null, Shader.TileMode.CLAMP)
        }
        // Every card shares one clock, so the sweeps move together across the grid.
        val phase = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        sweep.setTranslate(-width * .35f + phase * width * 1.35f, 0f); shine.shader?.setLocalMatrix(sweep)
        canvas.drawRoundRect(bar, height / 2, height / 2, shine)
        if (isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val SWEEP_MS = 1600L
        const val RESULT_FADE_MS = 220L
    }
}

package io.github.mot1us.spare

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible

/**
 * What Library shows while it gets a catalog ready: three steps that tick off in turn (categories, titles, rows) above
 * placeholder rows shaped like the rows that follow. No counts change while it works, so nothing on screen flickers; the
 * rows fade in when they are ready and their posters fade in as they arrive.
 */
internal class LibraryLoading(context: Context) : LinearLayout(context) {
    enum class Step(val label: String) { CATEGORIES("Categories"), TITLES("Titles"), ROWS("Rows") }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val headline = TextView(context).apply { textSize = 16f * GuideTheme.textScale; setTextColor(Color.WHITE); typeface = AppFont.face(true); tag = "library-loading-headline" }
    private val note = TextView(context).apply { textSize = 11f * GuideTheme.textScale; setTextColor(0xFFB8C0CC.toInt()); typeface = AppFont.face() }
    private val steps = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val marks = Step.entries.associateWith { StepMark(context) }
    private val labels = Step.entries.associateWith { step -> TextView(context).apply { text = step.label; textSize = 12f * GuideTheme.textScale; typeface = AppFont.face(true) } }
    private val skeleton = Skeleton(context)
    private var current = Step.CATEGORIES
    /** Drives the shimmer and the pulse of the current step; it runs only while this view is on screen. */
    private val motion = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { val phase = it.animatedValue as Float; skeleton.phase = phase; marks[current]?.pulse = phase }
    }

    init {
        orientation = VERTICAL; tag = "library-loading-view"; visibility = GONE
        setPadding(dp(10), dp(6), dp(10), 0)
        // The remote rests here while Library works, with no highlight: Up reaches the tabs and Left the menu, as from the rows.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true; isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false; descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        Step.entries.forEachIndexed { index, step ->
            if (index > 0) steps.addView(View(context).apply { setBackgroundColor(0x40FFFFFF) }, LayoutParams(dp(28), dp(1)).apply { marginStart = dp(10); marginEnd = dp(10) })
            steps.addView(marks.getValue(step), LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(7) })
            steps.addView(labels.getValue(step), LayoutParams(-2, -2))
        }
        addView(headline, LayoutParams(-1, -2))
        addView(steps, LayoutParams(-2, -2).apply { topMargin = dp(10) })
        addView(note, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(skeleton, LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })
    }

    /** [downloading]: the titles come from the provider this time (the first visit), not from this TV. */
    fun show(kind: String, downloading: Boolean) {
        val what = if (kind == "series") "series" else "movies"
        headline.text = if (downloading) "Saving your $what to this TV" else "Opening your $what"
        note.text = if (downloading) "The first visit can take a minute or two on a large playlist. After that Library opens at once." else ""
        note.isVisible = downloading
        step(Step.CATEGORIES)
        if (!isVisible) { alpha = 1f; isVisible = true }
        syncMotion()
    }

    fun step(next: Step) {
        current = next
        Step.entries.forEach { step ->
            val state = when { step.ordinal < next.ordinal -> StepMark.DONE; step == next -> StepMark.ACTIVE; else -> StepMark.WAITING }
            marks.getValue(step).state = state
            labels.getValue(step).setTextColor(if (state == StepMark.WAITING) 0x80FFFFFF.toInt() else Color.WHITE)
        }
        contentDescription = "${headline.text}. ${next.label}"
    }

    fun hide() {
        if (isGone) return
        isGone = true; syncMotion()
    }

    val showing get() = isVisible

    private fun syncMotion() {
        val run = isAttachedToWindow && isVisible && Motion.enter > 0
        if (run && !motion.isStarted) motion.start() else if (!run && motion.isStarted) motion.cancel()
        if (!run) { skeleton.phase = -1f; marks.values.forEach { it.pulse = -1f } }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncMotion() }
    override fun onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (changedView !== this) syncMotion() }

    /** A step's dot: hollow while waiting, pulsing while it runs, filled with a check once done. */
    private class StepMark(context: Context) : View(context) {
        var state = WAITING; set(value) { field = value; invalidate() }
        var pulse = -1f; set(value) { field = value; if (state == ACTIVE) invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tick = android.graphics.Path()
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f; val accent = GuideTheme.current.accent
            when (state) {
                DONE -> {
                    paint.style = Paint.Style.FILL; paint.color = accent; canvas.drawCircle(r, r, r, paint)
                    // A light accent (white in Monochrome) gets a dark check, so the check never disappears into its dot.
                    val check = if (ColorUtils.calculateLuminance(accent) > .55) 0xFF10141A.toInt() else Color.WHITE
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = width / 7f; paint.color = check; paint.strokeCap = Paint.Cap.ROUND
                    tick.reset(); tick.moveTo(width * .28f, height * .52f); tick.lineTo(width * .44f, height * .68f); tick.lineTo(width * .73f, height * .35f)
                    canvas.drawPath(tick, paint)
                }
                ACTIVE -> {
                    // A slow breath, not a blink: between 45% and 100% opacity.
                    val level = if (pulse < 0f) 1f else .45f + .55f * (0.5f - 0.5f * kotlin.math.cos(pulse * 2 * Math.PI).toFloat())
                    paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(accent, (255 * level).toInt()); canvas.drawCircle(r, r, r * .8f, paint)
                }
                else -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = width / 8f; paint.color = 0x66FFFFFF; canvas.drawCircle(r, r, r - paint.strokeWidth, paint) }
            }
        }
        companion object { const val WAITING = 0; const val ACTIVE = 1; const val DONE = 2 }
    }

    /** Placeholder rows: a row title and a line of 2:3 posters, with a soft band of light sweeping across them. */
    private class Skeleton(context: Context) : View(context) {
        var phase = -1f; set(value) { field = value; invalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val sweep = Matrix()
        private var gradientWidth = 0
        private fun dp(n: Int) = n * resources.displayMetrics.density
        override fun onDraw(canvas: Canvas) {
            val base = GuideTheme.current.background or Color.BLACK
            fill.color = ColorUtils.blendARGB(base, Color.WHITE, .09f)
            val gap = dp(8); val columns = CatalogShelves.COLUMNS
            val tile = (width - gap * (columns - 1)) / columns; val poster = tile * 1.5f
            val radius = dp(GuideTheme.cornerRadiusDp.toInt().coerceAtLeast(2))
            if (phase >= 0f && gradientWidth != width) {
                gradientWidth = width
                shine.shader = LinearGradient(0f, 0f, width * .35f, 0f, intArrayOf(0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            }
            sweep.setTranslate(-width * .35f + phase * width * 1.35f, 0f); shine.shader?.setLocalMatrix(sweep)
            var top = 0f
            while (top + dp(22) + poster <= height + poster * .45f) {
                box.set(0f, top, dp(110), top + dp(11)); draw(canvas, radius / 2)
                val row = top + dp(22)
                for (column in 0 until columns) { box.set(column * (tile + gap), row, column * (tile + gap) + tile, row + poster); draw(canvas, radius) }
                top = row + poster + dp(20)
            }
        }
        private fun draw(canvas: Canvas, radius: Float) {
            canvas.drawRoundRect(box, radius, radius, fill)
            if (phase >= 0f) canvas.drawRoundRect(box, radius, radius, shine)
        }
    }
}

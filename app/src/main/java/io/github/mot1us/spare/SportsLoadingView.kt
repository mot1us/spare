package io.github.mot1us.spare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Fixed card footprints while the first complete scoreboard is loading. No moving focus targets. */
class SportsLoadingView(context: Context) : View(context) {
    var hero = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        if (hero) {
            paint.color = Color.argb(35, 200, 210, 222)
            fun bar(x: Float, y: Float, w: Float, h: Float) = canvas.drawRoundRect(x, y, x + w, y + h, 3 * density, 3 * density, paint)
            bar(20 * density, 31 * density, 80 * density, 8 * density)
            bar(20 * density, 58 * density, width * .52f, 21 * density)
            bar(20 * density, 100 * density, width * .35f, 10 * density)
            bar(20 * density, 124 * density, width * .42f, 8 * density)
            canvas.drawCircle(width - 150 * density, height / 2f, 36 * density, paint)
            canvas.drawCircle(width - 62 * density, height / 2f, 36 * density, paint)
            return
        }
        val gap = 8 * density; val cardWidth = (width - gap * 3) / 3f; val cardHeight = 156 * density
        repeat(6) { i ->
            val x = i % 3 * (cardWidth + gap); val y = i / 3 * (cardHeight + gap)
            paint.color = Color.argb(45, 160, 173, 188); canvas.drawRoundRect(x, y, x + cardWidth, y + cardHeight, 6 * density, 6 * density, paint)
            paint.color = Color.argb(32, 200, 210, 222)
            canvas.drawRoundRect(x + gap, y + gap, x + cardWidth - gap, y + 82 * density, 4 * density, 4 * density, paint)
            canvas.drawRect(x + gap, y + 99 * density, x + cardWidth * .7f, y + 109 * density, paint)
            canvas.drawRect(x + gap, y + 124 * density, x + cardWidth * .5f, y + 131 * density, paint)
        }
    }
}

package io.github.mot1us.spare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class TimeRuler(context: Context, private val start: () -> Long) : View(context) {
    constructor(context: Context) : this(context, { System.currentTimeMillis() })
    private val scale = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 10f * scale }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = GuideAdapter.CHANNEL_WIDTH_DP * scale
        canvas.drawText("Channel", 8 * scale, 19 * scale, paint)
        for (i in 0..5) {
            val x = left + (width - left) * i / 6f + 8 * scale
            canvas.drawText(GuideAdapter.time(start() + i * 1800000L), x, 19 * scale, paint)
        }
    }
}

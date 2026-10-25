package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Compact floating channel card; shares the existing player and never takes remote focus. */
@SuppressLint("SetTextI18n")
class NowPlayingBanner(context: Context) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(color); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val channel = label(12f, Color.LTGRAY)
    private val programme = label(17f, Color.WHITE).apply { typeface = AppFont.face(true); maxLines = 2 }
    private val timing = label(11f, Color.LTGRAY)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
    init {
        orientation = VERTICAL; tag = "channel-banner"; visibility = GONE; setPadding(dp(18), dp(13), dp(18), dp(13))
        elevation = dp(12).toFloat(); clipToOutline = true
        addView(channel); addView(programme, LayoutParams(-1, -2).apply { topMargin = dp(3) }); addView(timing, LayoutParams(-1, -2).apply { topMargin = dp(5) })
        addView(progress, LayoutParams(-1, dp(2)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        addView(label(10f, 0xFFB8C0CC.toInt()).apply { text = "OK Details    ← Previous    Back Guide" })
    }
    fun update(current: Channel, show: Programme?, quality: VideoInfo?, now: Long = System.currentTimeMillis()) {
        background = GuideTheme.glass(resources.displayMetrics.density)
        channel.text = current.name
        programme.text = show?.title ?: "Live TV"
        timing.text = listOfNotNull(show?.let { "${GuideClock.time(it.start)} – ${GuideClock.time(it.end)}" }, quality?.label()).joinToString("  ·  ")
        timing.visibility = if (timing.text.isEmpty()) GONE else VISIBLE
        progress.visibility = if (show == null) GONE else VISIBLE
        progress.progress = show?.let { ((now - it.start).toDouble() / (it.end - it.start).coerceAtLeast(1) * 1000).toInt().coerceIn(0, 1000) } ?: 0
        contentDescription = "${current.name}. ${show?.title ?: "Live TV"}. ${timing.text}"
    }
    fun reveal() { animate().cancel(); if (visibility != VISIBLE) { alpha = 0f; visibility = VISIBLE }; animate().alpha(1f).setDuration(140).start() }
    fun hide(immediate: Boolean = false) {
        animate().cancel()
        if (immediate) { visibility = GONE; alpha = 1f }
        else animate().alpha(0f).setDuration(140).withEndAction { visibility = GONE; alpha = 1f }.start()
    }
}

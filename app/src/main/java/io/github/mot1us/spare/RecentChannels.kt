package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withScale

/** A bounded local history, with separate Switch and Add-to-multiview actions. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class RecentChannels(context: Context, private val button: (String, () -> Unit) -> Button, private val watch: (Channel) -> Unit, private val add: (Channel) -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val images = LogoLoader(128, 1024 * 1024)
    private val entries = LinearLayout(context)
    private var shown = emptyList<Channel>()
    private var http = false
    init {
        orientation = VERTICAL; tag = "recent-channels"; visibility = GONE
        addView(TextView(context).apply { text = "RECENT CHANNELS"; textSize = 10f; setTextColor(Color.LTGRAY); typeface = AppFont.face(); setPadding(0, dp(10), 0, dp(6)) })
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(entries) }, LayoutParams(-1, dp(102)))
        images.pause()
    }
    fun bind(channels: List<Channel>, allowHttp: Boolean) {
        if (shown == channels && http == allowHttp && entries.isNotEmpty()) return
        shown = channels.take(12); http = allowHttp; entries.removeAllViews(); visibility = if (shown.isEmpty()) GONE else VISIBLE
        shown.forEach { channel ->
            val group = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(0, 0, dp(10), 0) }
            val tile = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(4), dp(7), dp(4)); isFocusableInTouchMode = true; isClickable = true; tag = "recent-watch:${channel.id}"; contentDescription = "Watch ${channel.name}"; background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B) }
            val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            val name = TextView(context).apply { text = channel.name; textSize = 10f; typeface = AppFont.face(); setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
            tile.addView(logo, LayoutParams(-1, dp(31))); tile.addView(name, LayoutParams(-1, dp(18)))
            tile.setOnFocusChangeListener { _, active -> name.setTextColor(if (active) GuideTheme.focusText else Color.WHITE) }; tile.setOnClickListener { watch(channel) }
            val plus = button("Add screen") { add(channel) }.apply {
                textSize = 11f; tag = "recent-add:${channel.id}"; contentDescription = "Add ${channel.name} to multiview"
                setPadding(dp(10), 0, dp(10), 0); compoundDrawablePadding = dp(6)
                val icon = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ScreenGrid(resources.displayMetrics.density, GuideTheme.focusText))
                    addState(intArrayOf(), ScreenGrid(resources.displayMetrics.density, Color.WHITE))
                }
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            }
            group.addView(tile, LayoutParams(dp(138), dp(60)))
            group.addView(plus, LayoutParams(dp(138), dp(34)).apply { topMargin = dp(6) })
            entries.addView(group, LayoutParams(-2, -1))
            fun draw() { if (channel in shown) logo.setImageBitmap(images.get(channel.logo, http) {}) }
            logo.setImageBitmap(images.get(channel.logo, http, ::draw))
        }
    }
    fun resume() { images.resume(); val previous = shown; shown = emptyList(); bind(previous, http) }
    fun pause() { images.pause() }
    fun close() { images.close() }
    fun appearance() { val focused = findFocus()?.tag; val previous = shown; shown = emptyList(); bind(previous, http); AppFont.apply(this); if (focused != null) findViewWithTag<View>(focused)?.requestFocus() }
    private class ScreenGrid(private val density: Float, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        override fun draw(canvas: Canvas) {
            canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
                withScale(bounds.width() / 20f, bounds.height() / 20f) {
                    for (y in 0..1) for (x in 0..1) drawRoundRect(1.5f + x * 10, 1.5f + y * 10, 8.5f + x * 10, 8.5f + y * 10, 1f, 1f, paint)
                }
            }
        }
        override fun getIntrinsicWidth() = (18 * density).toInt()
        override fun getIntrinsicHeight() = (18 * density).toInt()
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

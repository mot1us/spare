package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty

/** A bounded local history, with separate Switch and Add-to-multiview actions. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class RecentChannels(context: Context, private val watch: (Channel) -> Unit, private val add: (Channel) -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val images = LogoLoader(128, 1024 * 1024)
    private val entries = LinearLayout(context)
    private var shown = emptyList<Channel>()
    private var http = false
    init {
        orientation = VERTICAL; tag = "recent-channels"; visibility = GONE
        addView(TextView(context).apply { text = "RECENT CHANNELS · OK Watch · ＋ Add view"; textSize = 10f; setTextColor(Color.LTGRAY); typeface = AppFont.face(); setPadding(0, dp(10), 0, dp(5)) })
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(entries) }, LayoutParams(-1, dp(65)))
        images.pause()
    }
    fun bind(channels: List<Channel>, allowHttp: Boolean) {
        if (shown == channels && http == allowHttp && entries.isNotEmpty()) return
        shown = channels.take(12); http = allowHttp; entries.removeAllViews(); visibility = if (shown.isEmpty()) GONE else VISIBLE
        shown.forEach { channel ->
            val group = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(8), 0) }
            val tile = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(4), dp(7), dp(4)); isFocusableInTouchMode = true; isClickable = true; tag = "recent-watch:${channel.id}"; contentDescription = "Watch ${channel.name}"; background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B) }
            val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            val name = TextView(context).apply { text = channel.name; textSize = 10f; typeface = AppFont.face(); setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
            tile.addView(logo, LayoutParams(-1, dp(31))); tile.addView(name, LayoutParams(-1, dp(18)))
            tile.setOnFocusChangeListener { _, active -> name.setTextColor(if (active) Color.BLACK else Color.WHITE) }; tile.setOnClickListener { watch(channel) }
            val plus = Button(context).apply { text = "+ View"; textSize = 10f; tag = "recent-add:${channel.id}"; contentDescription = "Add ${channel.name} to multiview"; isFocusableInTouchMode = true; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0); stateListAnimator = null; background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B); setTextColor(GuideTheme.ink()); setOnClickListener { add(channel) } }
            group.addView(tile, LayoutParams(dp(114), -1)); group.addView(plus, LayoutParams(dp(60), -1).apply { marginStart = dp(3) }); entries.addView(group, LayoutParams(-2, -1))
            fun draw() { if (channel in shown) logo.setImageBitmap(images.get(channel.logo, http) {}) }
            logo.setImageBitmap(images.get(channel.logo, http, ::draw))
        }
    }
    fun resume() { images.resume(); val previous = shown; shown = emptyList(); bind(previous, http) }
    fun pause() { images.pause() }
    fun close() { images.close() }
}

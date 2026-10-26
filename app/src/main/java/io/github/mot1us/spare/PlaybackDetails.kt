package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor") // Constructed with callbacks in code; never inflated from XML.
class PlaybackDetails(
    context: Context,
    makeButton: (String, () -> Unit) -> Button,
    onGuide: () -> Unit,
    onPrevious: () -> Unit,
    onFavorite: () -> Unit,
    onSubtitles: () -> Unit,
    onCatchup: () -> Unit,
    onMultiview: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int = Color.WHITE) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(color) }
    private val channel = label(12f, Color.LTGRAY)
    private val title = label(20f).apply { typeface = AppFont.face(true); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val description = label(12f, Color.LTGRAY).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val next = label(11f, Color.LTGRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val guideButton = makeButton("Guide", onGuide)
    private val previous = makeButton("Previous", onPrevious).apply { tag = "previous-channel" }
    private val favorite = makeButton("Favorite", onFavorite)
    private val catchup = makeButton("Catch-up", onCatchup).apply { tag = "channel-catchup" }
    init {
        orientation = VERTICAL; tag = "playback-details"; visibility = View.GONE
        setPadding(dp(22), dp(16), dp(22), dp(16))
        background = GuideTheme.glass(resources.displayMetrics.density); elevation = dp(10).toFloat(); clipToOutline = true
        addView(channel); addView(title, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(description, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(next, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(LinearLayout(context).apply { addView(guideButton); addView(previous); addView(favorite); addView(makeButton("Subtitles", onSubtitles).apply { tag = "channel-subtitles" }); addView(catchup); addView(makeButton("Watch together", onMultiview).apply { tag = "channel-multiview" }) }, LayoutParams(-1, dp(36)).apply { topMargin = dp(14) })
    }
    @SuppressLint("SetTextI18n") // Match the app's existing English UI; programme/channel names come from the provider.
    fun update(current: Channel, programme: Programme?, upcoming: Programme?, quality: String, isFavorite: Boolean, canRecall: Boolean, canCatchup: Boolean) {
        channel.text = "${current.name}  ·  $quality"
        title.text = programme?.title ?: current.name
        description.text = programme?.let { "${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}  ·  ${it.description}" } ?: "No programme information"
        next.text = upcoming?.let { "Next · ${GuideAdapter.time(it.start)}  ${it.title}" }.orEmpty()
        next.visibility = if (upcoming == null) View.GONE else View.VISIBLE
        previous.isEnabled = canRecall; previous.alpha = if (canRecall) 1f else 0.4f
        favorite.text = if (isFavorite) "Favorite ✓" else "Favorite"
        catchup.visibility = if (canCatchup) View.VISIBLE else View.GONE
    }
}

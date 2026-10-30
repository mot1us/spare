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
import android.widget.ProgressBar

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
    onRecent: (Channel) -> Unit,
    onAddView: (Channel) -> Unit,
    onReconnect: () -> Unit,
    onGameStreams: () -> Unit,
    onVodStart: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int = Color.WHITE) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(color) }
    private val recent = RecentChannels(context, makeButton, onRecent, onAddView)
    private val channel = label(12f, Color.LTGRAY)
    private val title = label(20f).apply { typeface = AppFont.face(true); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val description = label(12f, Color.LTGRAY).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val next = label(11f, Color.LTGRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val gameStreamsButton = makeButton("Other streams for this game", onGameStreams).apply { tag = "channel-game-streams"; visibility = View.GONE }
    val guideButton = makeButton("Guide", onGuide)
    private val previous = makeButton("Previous", onPrevious).apply { tag = "previous-channel" }
    private val favorite = makeButton("Favorite channel", onFavorite)
    private val catchup = makeButton("Channel catch-up", onCatchup).apply { tag = "channel-catchup" }
    private val vodStart = makeButton("Watch from beginning · VOD", onVodStart).apply { tag = "channel-vod-start"; visibility = View.GONE }
    private val vodStatus = label(12f, Color.LTGRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val vodProgress = ProgressBar(context).apply { isIndeterminate = true; tag = "vod-match-progress"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val vodChecking = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL; tag = "vod-match-status"; visibility = View.GONE }
    init {
        orientation = VERTICAL; tag = "playback-details"; visibility = View.GONE
        setPadding(dp(22), dp(16), dp(22), dp(16))
        background = GuideTheme.glass(resources.displayMetrics.density); elevation = dp(10).toFloat(); clipToOutline = true
        addView(gameStreamsButton, LayoutParams(-2, dp(36)).apply { bottomMargin = dp(10) })
        addView(channel); addView(title, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(description, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(recent, LayoutParams(-1, -2))
        addView(next, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        vodChecking.addView(vodProgress, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
        vodChecking.addView(vodStatus, LayoutParams(0, -1, 1f))
        addView(vodChecking, LayoutParams(-1, dp(36)).apply { topMargin = dp(8) })
        addView(vodStart, LayoutParams(-2, dp(36)).apply { topMargin = dp(8) })
        addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(LinearLayout(context).apply { addView(guideButton); addView(previous); addView(favorite); addView(makeButton("Subtitles", onSubtitles).apply { tag = "channel-subtitles" }); addView(catchup); addView(makeButton("Multiview", onMultiview).apply { tag = "channel-multiview" }); addView(makeButton("Reconnect", onReconnect).apply { tag = "channel-reconnect" }) }) }, LayoutParams(-1, dp(38)).apply { topMargin = dp(14) })
    }
    fun resumeImages() = recent.resume()
    fun pauseImages() = recent.pause()
    fun closeImages() = recent.close()
    fun appearance() { background = GuideTheme.glass(resources.displayMetrics.density); vodProgress.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent); recent.appearance(); AppFont.apply(this) }
    @SuppressLint("SetTextI18n") // Match the app's existing English UI; programme/channel names come from the provider.
    fun update(current: Channel, programme: Programme?, upcoming: Programme?, quality: String, isFavorite: Boolean, canRecall: Boolean, canCatchup: Boolean, recentChannels: List<Channel> = emptyList(), allowHttp: Boolean = false, gameTitle: String? = null, canVodStart: Boolean = false, vodTarget: String = "", checkingVod: Boolean = false, vodMessage: String = "") {
        vodChecking.visibility = if (!canVodStart && vodMessage.isNotBlank()) View.VISIBLE else View.GONE
        vodProgress.visibility = if (checkingVod) View.VISIBLE else View.GONE
        vodStatus.text = vodMessage; vodChecking.contentDescription = vodMessage
        vodStart.visibility = if (canVodStart) View.VISIBLE else View.GONE
        vodStart.text = if (vodTarget.isBlank()) "Watch from beginning · VOD" else "Watch from beginning · $vodTarget"
        vodStart.contentDescription = "Watch ${vodTarget.ifBlank { "this programme" }} from the beginning on demand"
        vodStart.maxLines = 2; vodStart.ellipsize = TextUtils.TruncateAt.END
        gameStreamsButton.visibility = if (gameTitle == null) View.GONE else View.VISIBLE
        gameStreamsButton.contentDescription = "Other streams for ${gameTitle.orEmpty()}"
        recent.bind(recentChannels, allowHttp)
        channel.text = "${current.name}  ·  $quality"
        title.text = programme?.title ?: current.name
        description.text = programme?.let { "${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}  ·  ${it.description}" } ?: "No programme information"
        next.text = upcoming?.let { "Next · ${GuideAdapter.time(it.start)}  ${it.title}" }.orEmpty()
        next.visibility = if (upcoming == null) View.GONE else View.VISIBLE
        previous.isEnabled = canRecall; previous.alpha = if (canRecall) 1f else 0.4f
        favorite.text = if (isFavorite) "★ Favorite channel" else "☆ Favorite channel"
        favorite.contentDescription = if (isFavorite) "Remove ${current.name} from favorite channels" else "Add ${current.name} to favorite channels"
        catchup.visibility = if (canCatchup) View.VISIBLE else View.GONE
    }
}

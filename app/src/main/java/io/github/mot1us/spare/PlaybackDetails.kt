package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible

@SuppressLint("ViewConstructor", "SetTextI18n")
class PlaybackDetails(
    context: Context,
    private val makeButton: (String, () -> Unit) -> Button,
    onGuide: () -> Unit,
    onPrevious: () -> Unit,
    onFavorite: () -> Unit,
    onSubtitles: () -> Unit,
    onCatchup: () -> Unit,
    onMultiview: () -> Unit,
    onRecent: (Channel) -> Unit,
    onAddView: (Channel) -> Unit,
    onGameStreams: () -> Unit,
    onVodStart: () -> Unit,
    onSportsOverlay: () -> Unit = {},
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float, color: Int = Color.WHITE) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(color) }
    private fun action(text: String, key: String, click: () -> Unit) = makeButton(text, click).apply {
        tag = key; id = View.generateViewId(); gravity = Gravity.CENTER; textSize = 10f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false; setPadding(dp(5), dp(4), dp(5), dp(4)); compoundDrawablePadding = dp(4)
        layoutParams = LayoutParams(dp(90), dp(52)).apply { marginEnd = dp(6) }
    }
    private val recent = RecentChannels(context, onRecent, onAddView)
    private val channel = label(12f, Color.LTGRAY)
    private val title = label(20f).apply { typeface = AppFont.face(true); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val description = label(12f, Color.LTGRAY).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val next = label(11f, Color.LTGRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val overlayButton = action("Sports overlay", "channel-scores", onSportsOverlay)
    val guideButton = action("Guide", "channel-guide-action", onGuide)
    val gameStreamsButton = action("Other streams", "channel-game-streams", onGameStreams).apply { visibility = GONE }
    private val previous = action("Previous", "previous-channel", onPrevious)
    private val favorite = action("Favorite", "channel-favorite", onFavorite)
    private val subtitles = action("Subtitles", "channel-subtitles", onSubtitles)
    private val catchup = action("Catch-up", "channel-catchup", onCatchup)
    private val multiview = action("Multiview", "channel-multiview", onMultiview)
    private val vodStart = action("Start VOD", "channel-vod-start", onVodStart).apply { visibility = GONE }
    private val controls = listOf(overlayButton, guideButton, gameStreamsButton, previous, favorite, subtitles, catchup, multiview, vodStart)
    private val icons = listOf(R.drawable.ic_action_overlay, R.drawable.ic_guide, R.drawable.ic_action_streams, R.drawable.ic_action_previous,
        R.drawable.ic_action_favorite, R.drawable.ic_action_subtitles, R.drawable.ic_action_catchup, R.drawable.ic_action_multiview, R.drawable.ic_action_vod)
    private val actions = LinearLayout(context).apply { tag = "playback-actions"; gravity = Gravity.CENTER_VERTICAL }
    private val actionScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(actions) }
    private val vodStatus = label(11f, Color.LTGRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val vodProgress = ProgressBar(context).apply { isIndeterminate = true; tag = "vod-match-progress"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val vodChecking = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "vod-match-status"; visibility = GONE }
    init {
        orientation = VERTICAL; tag = "playback-details"; visibility = GONE
        setPadding(dp(22), dp(16), dp(22), dp(16)); elevation = dp(10).toFloat(); clipToOutline = true
        addView(channel); addView(title, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(description, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(next, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(recent, LayoutParams(-1, -2))
        vodChecking.addView(vodProgress, LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
        vodChecking.addView(vodStatus, LayoutParams(0, -1, 1f))
        addView(vodChecking, LayoutParams(-1, dp(26)).apply { topMargin = dp(6) })
        controls.forEach { control ->
            actions.addView(control)
            control.setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val available = controls.filter { it.isVisible && it.isEnabled }; val at = available.indexOf(control)
                            available.getOrNull((at + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(0, available.lastIndex))?.requestFocus()
                        }; true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) recent.focusFirst(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
        }
        addView(actionScroll, LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        appearance()
    }
    private fun refreshIcons() {
        controls.forEachIndexed { index, control ->
            fun glyph(color: Int) = context.getDrawable(icons[index])!!.mutate().apply { setTint(color) }
            val drawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glyph(GuideTheme.focusText))
                addState(intArrayOf(), glyph(if (GuideTheme.style == "Nostalgia") GuideTheme.current.accent else Color.WHITE))
                setBounds(0, 0, dp(18), dp(18))
            }
            control.setCompoundDrawables(null, drawable, null, null)
        }
    }
    fun focusActions() { actionScroll.scrollTo(0, 0); overlayButton.requestFocus() }
    val checkingVod get() = vodChecking.isVisible && vodProgress.isVisible
    fun resumeImages() = recent.resume()
    fun pauseImages() = recent.pause()
    fun closeImages() = recent.close()
    fun appearance() { background = GuideTheme.glass(resources.displayMetrics.density); vodProgress.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent); recent.appearance(); AppFont.apply(this); refreshIcons() }
    fun update(current: Channel, programme: Programme?, upcoming: Programme?, quality: String, isFavorite: Boolean, canRecall: Boolean, canCatchup: Boolean, recentChannels: List<Channel> = emptyList(), allowHttp: Boolean = false, gameTitle: String? = null, canVodStart: Boolean = false, vodTarget: String = "", checkingVod: Boolean = false, vodMessage: String = "") {
        vodChecking.visibility = if (canVodStart || vodMessage.isNotBlank()) VISIBLE else GONE
        vodProgress.visibility = if (checkingVod) VISIBLE else GONE
        vodStatus.text = if (canVodStart) "Start ${vodTarget.ifBlank { "this programme" }} from the beginning on demand" else vodMessage
        vodChecking.contentDescription = vodStatus.text
        vodStart.visibility = if (canVodStart) VISIBLE else GONE
        vodStart.contentDescription = "Watch ${vodTarget.ifBlank { "this programme" }} from the beginning on demand"
        gameStreamsButton.visibility = if (gameTitle == null) GONE else VISIBLE
        gameStreamsButton.contentDescription = "Other streams for ${gameTitle.orEmpty()}"
        recent.bind(recentChannels, allowHttp)
        channel.text = "${current.name}  ·  $quality"; title.text = programme?.title ?: current.name
        description.text = programme?.let { "${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}  ·  ${it.description}" } ?: "No programme information"
        next.text = upcoming?.let { "Next · ${GuideAdapter.time(it.start)}  ${it.title}" }.orEmpty(); next.visibility = if (upcoming == null) GONE else VISIBLE
        previous.isEnabled = canRecall; previous.alpha = if (canRecall) 1f else .4f
        favorite.text = if (isFavorite) "Favorited" else "Favorite"
        favorite.contentDescription = if (isFavorite) "Remove ${current.name} from favorite channels" else "Add ${current.name} to favorite channels"
        catchup.visibility = if (canCatchup) VISIBLE else GONE
    }
}

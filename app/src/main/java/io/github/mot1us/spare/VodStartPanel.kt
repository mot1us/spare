package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** A focused live-to-VOD handoff, separate from playlist-wide search. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class VodStartPanel(context: Context, private val button: (String, () -> Unit) -> Button,
    cancel: () -> Unit, browse: () -> Unit, series: () -> Unit, retry: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(GuideTheme.ink()); typeface = AppFont.face() }
    private val title = label(22f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val status = label(13f).apply { setTextColor(Color.LTGRAY); maxLines = 3 }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
    private val choices = LinearLayout(context).apply { orientation = VERTICAL; tag = "vod-start-options" }
    private val findSeries = button("Find series instead", series)
    private val retryButton = button("Retry", retry)
    private val cancelButton = button("Cancel", cancel).apply { tag = "vod-start-cancel" }
    private val browseButton = button("Browse Library", browse)
    private val card = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(22)); background = GuideTheme.glass(resources.displayMetrics.density) }
    private var identity = emptyList<String>()
    var backgroundContent: ViewGroup? = null
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; visibility = GONE; tag = "vod-start-panel"
        background = GuideTheme.guideBackground(60)
        card.addView(label(11f).apply { text = "WATCH FROM BEGINNING" }, LayoutParams(-1, dp(24)))
        card.addView(title, LayoutParams(-1, -2))
        card.addView(status, LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        card.addView(progress, LayoutParams(-1, dp(3)).apply { bottomMargin = dp(12) })
        card.addView(choices, LayoutParams(-1, -2))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(cancelButton, findSeries, retryButton, browseButton).forEach { action ->
            actions.addView(action, LayoutParams(-2, dp(40)).apply { marginEnd = dp(12) })
        }
        card.addView(actions, LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        addView(card, LayoutParams(dp(680), -2))
    }
    override fun focusSearch(focused: View, direction: Int): View? = FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    fun open(request: VodStart.Request) {
        title.text = request.query + request.year.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        identity = emptyList(); choices.removeAllViews(); status.text = "Finding your on-demand version…"
        findSeries.visibility = GONE; retryButton.visibility = GONE; browseButton.visibility = GONE; progress.visibility = VISIBLE
        backgroundContent?.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        visibility = VISIBLE; cancelButton.requestFocus()
    }
    fun results(values: List<OnDemand>, busy: Boolean, warning: String, mode: String, select: (OnDemand) -> Unit) {
        browseButton.visibility = if (busy) GONE else VISIBLE
        progress.visibility = if (busy) VISIBLE else INVISIBLE
        findSeries.visibility = if (!busy && values.isEmpty() && mode == "movie") VISIBLE else GONE
        retryButton.visibility = if (!busy && warning.isNotBlank()) VISIBLE else GONE
        status.text = when {
            values.isNotEmpty() -> "More than one version matches. Choose the release you want."
            busy -> if (mode == "series") "Finding the series in your saved titles and provider catalog…" else "Finding the movie in your saved titles and provider catalog…"
            warning.isNotBlank() -> warning
            else -> "No matching ${if (mode == "series") "series" else "movie"} was found. You can browse your Library."
        }
        val keys = values.map { it.id }
        if (keys == identity) return
        val focusedKey = choices.findFocus()?.tag
        identity = keys; choices.removeAllViews()
        values.forEach { item ->
            choices.addView(button(item.title + item.year.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()) { select(item) }.apply { tag = "vod-start-choice:${item.id}" },
                LayoutParams(-1, dp(38)).apply { bottomMargin = dp(6) })
        }
        (choices.findViewWithTag<View>(focusedKey) ?: choices.getChildAt(0))?.requestFocus()
    }
    fun openingEpisode(season: Int?, episode: Int?) {
        identity = emptyList(); choices.removeAllViews(); cancelButton.requestFocus()
        status.text = if (season != null && episode != null) "Opening season $season, episode $episode…" else "Finding the matching episode…"
        progress.visibility = VISIBLE; browseButton.visibility = GONE; findSeries.visibility = GONE; retryButton.visibility = GONE
    }
    fun appearance() { background = GuideTheme.guideBackground(60); card.background = GuideTheme.glass(resources.displayMetrics.density); progress.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent); AppFont.apply(this) }
    fun dismiss() { visibility = GONE; backgroundContent?.descendantFocusability = FOCUS_AFTER_DESCENDANTS }
}

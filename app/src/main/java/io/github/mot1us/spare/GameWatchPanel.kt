package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Keep the current programme visible while a reminder resolves its game channel. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class GameWatchPanel(context: Context, button: (String, () -> Unit) -> Button, cancel: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private val title = TextView(context).apply { textSize = 20f; setTextColor(GuideTheme.ink()); typeface = AppFont.face(true); maxLines = 2 }
    private val status = TextView(context).apply { textSize = 12f; setTextColor(GuideTheme.ink()); maxLines = 2; tag = "game-watch-status" }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val cancelButton = button("Cancel", cancel).apply { tag = "game-watch-cancel" }
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; visibility = GONE; tag = "game-watch-panel"
        background = GuideTheme.guideBackground(60)
        addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(22)); background = GuideTheme.glass(resources.displayMetrics.density)
            addView(title, LayoutParams(-1, -2))
            addView(status, LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            addView(progress, LayoutParams(-1, dp(3)).apply { bottomMargin = dp(16) })
            addView(cancelButton, LayoutParams(-2, dp(40)))
        }, LayoutParams(dp(520), -2))
    }
    override fun focusSearch(focused: View, direction: Int): View? = FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    fun open(game: SportsGame) { title.text = game.title; update("Finding a matching game stream…"); visibility = VISIBLE; cancelButton.requestFocus() }
    fun update(message: String, checked: Int = 0, total: Int = 0) { status.text = message; progress.isIndeterminate = total <= 0; if (total > 0) { progress.max = total; progress.progress = checked } }
    fun dismiss() { visibility = GONE }
}

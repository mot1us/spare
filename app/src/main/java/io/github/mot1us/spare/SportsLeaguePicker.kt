package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A session-only scoreboard filter; saved leagues remain in Sports settings. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SportsLeaguePicker(context: Context, private val button: (String, () -> Unit) -> Button,
    private val choose: (String?) -> Unit, private val cancel: () -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
    private val title = TextView(context).apply { text = "Filter leagues"; textSize = 22f; setTextColor(Color.WHITE) }
    private val hint = TextView(context).apply { text = "Browse your enabled leagues · Back to cancel"; textSize = 12f; setTextColor(Color.LTGRAY) }
    private val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = false; isFocusable = false; addView(choices) }

    init {
        tag = "sports-league-picker"; visibility = GONE; isClickable = true
        panel.addView(title); panel.addView(hint, LinearLayout.LayoutParams(-1, dp(36)))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(panel, LayoutParams(dp(420), -1, Gravity.CENTER).apply { topMargin = dp(40); bottomMargin = dp(40) })
    }

    fun open(enabled: Set<String>, selected: String?) {
        background = GuideTheme.guideBackground(96)
        panel.background = GuideTheme.glass(resources.displayMetrics.density)
        title.typeface = AppFont.face(true); hint.typeface = AppFont.face()
        choices.removeAllViews()
        val leagues = listOf<String?>(null) + SportsLeagues.all.filter { it.id in enabled }.map { it.id }
        val controls = leagues.map { league ->
            button(league?.let(SportsLeagues::label) ?: "All leagues") { choose(league) }.apply {
                tag = "sports-league:${league ?: "all"}"; isSelected = selected == league
                contentDescription = text.toString() + if (isSelected) ", selected" else ""
                choices.addView(this, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
            }
        }
        controls.forEachIndexed { index, control -> control.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) controls[(index + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(controls.indices)].requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancel(); true }
                else -> false
            }
        } }
        visibility = View.VISIBLE
        controls[leagues.indexOf(selected).coerceAtLeast(0)].requestFocus()
    }
}

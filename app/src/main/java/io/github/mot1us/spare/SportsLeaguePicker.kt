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

/** First Sports visit: choose a small personal schedule before requesting scoreboards. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SportsLeaguePicker(context: Context, private val button: (String, () -> Unit) -> Button,
    private val choose: (Set<String>) -> Unit, private val cancel: () -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
    private val title = TextView(context).apply { text = "Choose your leagues"; textSize = 24f; setTextColor(Color.WHITE) }
    private val hint = TextView(context).apply { text = "Pick the sports you watch. Browse one league at a time, and change your choices later in Sports settings."; textSize = 14f; setTextColor(Color.LTGRAY) }
    private val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = false; isFocusable = false; addView(choices) }
    private val selected = linkedSetOf<String>()
    private val continueButton = button("Continue") { if (selected.isNotEmpty()) choose(selected.toSet()) }.apply { tag = "sports-league-continue" }

    init {
        tag = "sports-league-picker"; visibility = GONE; isClickable = true
        panel.addView(title); panel.addView(hint, LinearLayout.LayoutParams(-1, dp(54)))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(continueButton, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        addView(panel, LayoutParams(-1, -1).apply { setMargins(dp(36), dp(24), dp(36), dp(24)) })
    }

    fun open(enabled: Set<String> = emptySet()) {
        background = GuideTheme.guideBackground(96)
        panel.background = GuideTheme.glass(resources.displayMetrics.density)
        title.typeface = AppFont.face(true); hint.typeface = AppFont.face()
        choices.removeAllViews()
        selected.clear(); selected.addAll(enabled)
        continueButton.isEnabled = selected.isNotEmpty()
        val leagues = SportsLeagues.all
        val controls = mutableListOf<Button>()
        fun paint() { controls.forEachIndexed { index, control ->
            val league = leagues[index]; control.isSelected = league.id in selected
            control.text = (if (control.isSelected) "✓  " else "    ") + league.name
            control.contentDescription = league.name + if (control.isSelected) ", selected" else ", not selected"
        }; continueButton.isEnabled = selected.isNotEmpty() }
        leagues.forEach { league ->
            controls.add(button(league.name) {
                if (!selected.add(league.id)) selected.remove(league.id)
                paint()
            }.apply {
                tag = "sports-setup:${league.id}"
                choices.addView(this, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
            })
        }
        paint()
        controls.forEachIndexed { index, control -> control.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (key == KeyEvent.KEYCODE_DPAD_DOWN && index == controls.lastIndex && continueButton.isEnabled) continueButton.requestFocus()
                        else controls[(index + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(controls.indices)].requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && continueButton.isEnabled) continueButton.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancel(); true }
                else -> false
            }
        } }
        continueButton.setOnKeyListener { _, key, event ->
            if (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)) { if (event.action == KeyEvent.ACTION_DOWN) controls.first().requestFocus(); true }
            else key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT
        }
        visibility = View.VISIBLE
        controls.first().requestFocus()
    }
}

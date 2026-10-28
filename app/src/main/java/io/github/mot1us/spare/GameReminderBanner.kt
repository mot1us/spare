package io.github.mot1us.spare

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor", "SetTextI18n") // Created in code with the app's shared TV button styling.
class GameReminderBanner(context: Context, button: (String, () -> Unit) -> Button, watch: () -> Unit, dismiss: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val title = TextView(context).apply { textSize = 17f; typeface = AppFont.face(true); setTextColor(Color.WHITE); maxLines = 2 }
    private val action = button("Watch game", watch).apply { tag = "reminder-watch" }
    init {
        orientation = VERTICAL; visibility = GONE; tag = "game-reminder"; elevation = dp(18).toFloat(); clipToOutline = true; setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(TextView(context).apply { text = "GAME STARTED"; textSize = 10f; setTextColor(GuideTheme.current.accent) }); addView(title)
        addView(TextView(context).apply { text = "Find the highest-resolution matching stream"; textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(8)) })
        addView(LinearLayout(context).apply { addView(action, LayoutParams(0, dp(36), 1f)); addView(button("Dismiss", dismiss).apply { tag = "reminder-dismiss" }, LayoutParams(dp(90), dp(36)).apply { marginStart = dp(10) }) })
    }
    fun show(game: SportsGame) { title.text = game.title; background = GuideTheme.glass(resources.displayMetrics.density); visibility = VISIBLE; action.requestFocus() }
}

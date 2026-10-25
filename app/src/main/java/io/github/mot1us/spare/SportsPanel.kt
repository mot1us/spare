package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SuppressLint("SetTextI18n", "ViewConstructor") // Constructed in Kotlin with activity callbacks; never inflated from XML.
class SportsPanel(context: Context, button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    toggle: () -> Unit, refresh: () -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(Color.LTGRAY) }
    val teamsButton = button("Teams", chooseTeams).apply { tag = "sports-teams" }
    private val scope = button("All games", toggle).apply { tag = "sports-scope" }
    private val summary = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = label(10f).apply { tag = "sports-status"; maxLines = 2; setPadding(0, dp(4), 0, dp(4)) }
    private val empty = label(12f).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; tag = "sports-games" }
    private var games = emptyList<SportsGame>()
    private var remembered: String? = null
    private var focusEpoch = 0
    private val cards = object : RecyclerView.Adapter<Card>() {
        override fun getItemCount() = games.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val box = object : LinearLayout(context) {
                override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                    val at = games.indexOfFirst { it.key == tag }
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { leave(); return true }
                        KeyEvent.KEYCODE_DPAD_UP -> { if (at <= 0) teamsButton.requestFocus() else focusAt(at - 1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { focusAt((at + 1).coerceAtMost(games.lastIndex)); return true }
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }.apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusableInTouchMode = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) }
            }
            val title = label(13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val detail = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(title); box.addView(detail)
            return Card(box, title, detail)
        }
        override fun onBindViewHolder(holder: Card, position: Int) {
            val game = games[position]; holder.box.tag = game.key
            holder.title.text = game.title
            val live = game.live(System.currentTimeMillis())
            holder.detail.text = "${if (live) "LIVE" else time(game.start)} · ${game.teams.first().league} · ${game.broadcasts.size} ${if (game.broadcasts.size == 1) "channel" else "channels"} · ${game.broadcasts.first().channel.name}"
            holder.box.contentDescription = "${holder.title.text}. ${holder.detail.text}"
            holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x222D3542)
            fun ink(active: Boolean) { holder.title.setTextColor(if (active) Color.BLACK else Color.WHITE); holder.detail.setTextColor(if (active) Color.BLACK else Color.LTGRAY) }
            ink(holder.box.hasFocus())
            holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { remembered = game.key; focused(game) } }
            holder.box.setOnClickListener { select(game) }
        }
    }
    init {
        orientation = VERTICAL; tag = "sports-panel"; visibility = GONE
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val refreshButton = button("Refresh", refresh).apply { tag = "sports-refresh" }
        toolbar.addView(teamsButton); toolbar.addView(scope); toolbar.addView(refreshButton)
        toolbar.addView(summary, LayoutParams(0, -2, 1f))
        listOf(teamsButton, scope, refreshButton).forEach { control -> control.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) leave(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (control === teamsButton) { if (event.action == KeyEvent.ACTION_DOWN) leave(); true } else false
                else -> false
            }
        } }
        addView(toolbar, LayoutParams(-1, dp(38))); addView(progress, LayoutParams(-1, -2)); addView(empty, LayoutParams(-1, -2))
        rows.adapter = cards; addView(rows, LayoutParams(-1, 0, 1f))
    }
    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<SportsGame>, teamSummary: String, message: String, following: Boolean, hasTeams: Boolean, filtered: Boolean) {
        summary.text = teamSummary; scope.isEnabled = hasTeams; scope.text = if (following && hasTeams) "My teams" else "All games"
        progress.text = message
        empty.text = if (following && hasTeams) "No matching games in the next 48 hours. Try All games, or check back after the provider updates its guide."
            else "No MLB/NFL games found in the next 48 hours. Games need team matchups in your provider’s guide."
        if (filtered) empty.append(" Your category and verified-quality filters also apply.")
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        if (games != next) {
            val hadFocus = rows.hasFocus() || (games.isEmpty() && hasFocus()); games = next; cards.notifyDataSetChanged()
            if (hadFocus) post { focusGames() }
        }
    }
    fun focusGames() { if (games.isEmpty()) teamsButton.requestFocus() else focusAt(games.indexOfFirst { it.key == remembered }.coerceAtLeast(0)) }
    private fun focusAt(position: Int) {
        if (position !in games.indices) return
        val epoch = ++focusEpoch; rows.scrollToPosition(position)
        fun attempt(left: Int) {
            if (!isShown || epoch != focusEpoch) return
            val item = rows.findViewHolderForAdapterPosition(position)?.itemView
            if (item != null) item.requestFocus() else if (left > 0) rows.postOnAnimation { attempt(left - 1) }
        }
        attempt(8)
    }
    fun refreshTheme() { cards.notifyItemRangeChanged(0, cards.itemCount) }
    private class Card(val box: LinearLayout, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(box)
    companion object {
        fun time(epoch: Long): String = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
    }
}

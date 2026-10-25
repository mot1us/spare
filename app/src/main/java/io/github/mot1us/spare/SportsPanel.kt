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

@SuppressLint("SetTextI18n", "ViewConstructor") // Constructed in Kotlin with activity callbacks; never inflated from XML.
class SportsPanel(context: Context, button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    toggle: () -> Unit, refresh: () -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, private val selectChannel: (Channel) -> Unit, private val focusChannel: (Channel) -> Unit, filters: () -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(Color.LTGRAY) }
    val teamsButton = button("Teams", chooseTeams).apply { tag = "sports-teams" }
    private val scope = button("All games", toggle).apply { tag = "sports-scope" }
    private val summary = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val progress = label(10f).apply { tag = "sports-status"; maxLines = 2; setPadding(0, dp(4), 0, dp(4)) }
    private val empty = label(12f).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; tag = "sports-games" }
    private data class Entry(val game: SportsGame? = null, val channel: Channel? = null) {
        val key get() = game?.key ?: "team:${channel!!.id}"
    }
    private var games = emptyList<Entry>()
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
            val entry = games[position]; val game = entry.game; holder.box.tag = entry.key
            holder.title.text = game?.title ?: entry.channel!!.name
            holder.detail.text = if (game != null) "${if (game.live(System.currentTimeMillis())) "LIVE" else time(game.start)} · ${game.teams.first().league} · ${game.broadcasts.size} ${if (game.broadcasts.size == 1) "channel" else "channels"} · ${game.broadcasts.first().channel.name}"
                else "Team channel · matchup unavailable · ${entry.channel!!.group}"
            holder.box.contentDescription = "${holder.title.text}. ${holder.detail.text}"
            holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x222D3542)
            fun ink(active: Boolean) { holder.title.setTextColor(if (active) Color.BLACK else Color.WHITE); holder.detail.setTextColor(if (active) Color.BLACK else Color.LTGRAY) }
            ink(holder.box.hasFocus())
            holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { remembered = entry.key; if (game != null) focused(game) else focusChannel(entry.channel!!) } }
            holder.box.setOnClickListener { if (game != null) select(game) else selectChannel(entry.channel!!) }
        }
    }
    init {
        orientation = VERTICAL; tag = "sports-panel"; visibility = GONE
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val refreshButton = button("Refresh", refresh).apply { tag = "sports-refresh" }
        val filtersButton = button("Filters", filters).apply { tag = "sports-filters" }
        toolbar.addView(teamsButton); toolbar.addView(scope); toolbar.addView(refreshButton); toolbar.addView(filtersButton)
        toolbar.addView(summary, LayoutParams(0, -2, 1f))
        listOf(teamsButton, scope, refreshButton, filtersButton).forEach { control -> control.setOnKeyListener { _, key, event ->
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
    fun submit(next: List<SportsGame>, channels: List<Channel>, teamSummary: String, message: String, following: Boolean, hasTeams: Boolean, hiddenGames: Int, guideChannels: Int) {
        summary.text = teamSummary; scope.isEnabled = hasTeams; scope.text = if (following && hasTeams) "My teams" else "All games"
        progress.text = message
        empty.text = if (following && hasTeams) "No matching games in the next 48 hours. Try All games, or check back after the provider updates its guide."
            else "No MLB/NFL games found in the next 48 hours. Games need team matchups in your provider’s guide."
        if (hiddenGames > 0) empty.text = "$hiddenGames matching ${if (hiddenGames == 1) "game is" else "games are"} hidden by your category, verified-quality, or unavailable-channel filters. Open Filters to adjust."
        else if (guideChannels == 0) empty.text = "No usable sports guide data yet. Refresh checks likely game networks; listings need team names to identify a matchup."
        if (channels.isNotEmpty()) empty.append(" Showing your provider’s team channels below; these are not confirmed game listings.")
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        val entries = next.map { Entry(game = it) } + channels.map { Entry(channel = it) }
        if (games != entries) {
            val hadFocus = rows.hasFocus() || (games.isEmpty() && hasFocus()); games = entries; cards.notifyDataSetChanged()
            if (hadFocus) post { focusGames() }
        }
    }
    fun focusGames() { if (games.isEmpty()) teamsButton.requestFocus() else focusAt(games.indexOfFirst { it.key == remembered }.coerceAtLeast(0)) }
    private fun focusAt(position: Int) {
        if (position !in games.indices) return
        val epoch = ++focusEpoch; if (rows.findViewHolderForAdapterPosition(position) == null) rows.scrollToPosition(position)
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
        fun time(epoch: Long): String = GuideClock.dayTime(epoch)
    }
}

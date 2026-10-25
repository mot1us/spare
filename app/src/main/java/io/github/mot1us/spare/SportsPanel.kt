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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("SetTextI18n", "ViewConstructor")
class SportsPanel(context: Context, private val button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    private val chooseTeam: (String?) -> Unit, refresh: () -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, filters: () -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.LTGRAY) }
    val teamsButton = button("Teams", chooseTeams).apply { tag = "sports-teams" }
    private val teamBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val teamScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(teamBar) }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; tag = "sports-loading"; visibility = GONE }
    private val progress = label(10f).apply { tag = "sports-status"; maxLines = 2; setPadding(0, dp(5), 0, dp(5)) }
    private val empty = label(12f).apply { tag = "sports-empty"; setPadding(dp(8), dp(12), dp(8), dp(12)) }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-games" }
    private var games = emptyList<SportsGame>()
    private var remembered: String? = null
    private var focusEpoch = 0
    private var pendingEnter = false
    private var teamSignature: Set<String>? = null
    private val cards = object : RecyclerView.Adapter<Card>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = Channel.stableId(games[position].key).take(16).toULong(16).toLong()
        override fun getItemCount() = games.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val box = object : LinearLayout(context) {
                override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                    val at = games.indexOfFirst { it.key == tag }
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { leave(); return true }
                        KeyEvent.KEYCODE_DPAD_UP -> { if (at <= 0) { if (event.repeatCount == 0) teamsButton.requestFocus() } else focusAt(at - 1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { if (at < games.lastIndex) focusAt(at + 1); return true }
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }.apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusableInTouchMode = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) }
            }
            val title = label(13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
            val detail = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(title); box.addView(detail); return Card(box, title, detail)
        }
        override fun onBindViewHolder(holder: Card, position: Int) {
            val game = games[position]; holder.box.tag = game.key; holder.title.text = game.title
            holder.detail.text = "LIVE · ${game.teams.first().league} · ${game.broadcasts.size} ${if (game.broadcasts.size == 1) "channel" else "channels"} · ${game.broadcasts.first().channel.name}"
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
        val filtersButton = button("Filters", filters).apply { tag = "sports-filters" }
        toolbar.addView(teamsButton); toolbar.addView(refreshButton); toolbar.addView(filtersButton)
        listOf(teamsButton, refreshButton, filtersButton).forEach(::linkControl)
        addView(toolbar, LayoutParams(-1, dp(38))); addView(teamScroll, LayoutParams(-1, dp(38)))
        addView(progressBar, LayoutParams(-1, dp(3))); addView(progress, LayoutParams(-1, -2)); addView(empty, LayoutParams(-1, -2))
        rows.adapter = cards; addView(rows, LayoutParams(-1, 0, 1f))
    }
    private fun linkControl(control: View) { control.setOnKeyListener { _, key, event -> when (key) {
        KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
        KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leave(); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> if (control === teamsButton) { if (event.action == KeyEvent.ACTION_DOWN) leave(); true } else false
        else -> false
    } } }
    fun loading(message: String) { progressBar.visibility = VISIBLE; progress.text = message; if (games.isEmpty()) { empty.text = "Finding live games… Results appear as channels are checked."; empty.visibility = VISIBLE } }
    fun submit(next: List<SportsGame>, followed: Set<String>, selected: String?, message: String, scanning: Boolean, hiddenGames: Int, guideChannels: Int) {
        val signature = followed
        if (teamSignature != signature) {
            teamSignature = signature; teamBar.removeAllViews()
            val teams = listOf<String?>(null) + followed.sortedBy { SportsCatalog.byId[it]?.name }
            teams.forEach { team ->
                val chip = button(team?.let { SportsCatalog.byId[it]!!.name } ?: "All live") { pendingEnter = false; remembered = null; chooseTeam(team) }
                chip.tag = team?.let { "sports-team:$it" } ?: "sports-scope"; chip.isSelected = team == selected
                chip.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) teamsButton.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
                    else -> false
                } }; teamBar.addView(chip)
            }
        }
        for (index in 0 until teamBar.childCount) {
            val chip = teamBar.getChildAt(index)
            chip.isSelected = chip.tag == (selected?.let { "sports-team:$it" } ?: "sports-scope")
        }
        progressBar.visibility = if (scanning) VISIBLE else GONE; progress.text = message
        empty.text = when {
            message.contains("status unavailable") -> "Live game status could not be verified. Unverified matchups stay hidden; choose Refresh to retry."
            scanning -> "Finding live games… Results appear as channels are checked."
            hiddenGames > 0 -> "$hiddenGames live ${if (hiddenGames == 1) "game is" else "games are"} hidden by your guide filters or unavailable channels. Open Filters to adjust."
            guideChannels == 0 -> "No current sports guide data. Refresh checks likely game networks; your provider must name the teams in its live listings."
            selected != null -> "No live game found for ${SportsCatalog.byId[selected]?.name}. Choose All live or another team."
            else -> "No live MLB/NFL matchups found in your provider’s current guide."
        }
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        if (games != next) {
            val old = games; val hadFocus = rows.hasFocus()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
            })
            games = next; diff.dispatchUpdatesTo(cards)
            if (hadFocus || (pendingEnter && games.isNotEmpty())) post { focusGames() }
        }
    }
    fun focusGames() { if (games.isEmpty()) { pendingEnter = true; teamsButton.requestFocus() } else { pendingEnter = false; focusAt(games.indexOfFirst { it.key == remembered }.coerceAtLeast(0)) } }
    private fun focusAt(position: Int) {
        if (position !in games.indices) return
        val epoch = ++focusEpoch; if (rows.findViewHolderForAdapterPosition(position) == null) rows.scrollToPosition(position)
        fun attempt(left: Int) {
            if (!isShown || epoch != focusEpoch) return
            val item = rows.findViewHolderForAdapterPosition(position)?.itemView
            if (item != null) item.requestFocus() else if (left > 0) rows.postOnAnimation { attempt(left - 1) }
        }; attempt(15)
    }
    fun refreshTheme() { cards.notifyItemRangeChanged(0, cards.itemCount); teamSignature = null }
    private class Card(val box: LinearLayout, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(box)
    companion object { fun time(epoch: Long): String = GuideClock.dayTime(epoch) }
}

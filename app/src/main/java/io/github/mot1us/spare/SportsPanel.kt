package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
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
    var watchChannel: (Channel) -> Unit = {}
    var secondChannel: (Channel) -> Unit = {}
    private var watchMode = false
    private var matchingKey: String? = null
    private var lastSelected: String? = null
    private val teamBar = LinearLayout(context).apply { orientation = VERTICAL }
    private val teamScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(teamBar) }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; tag = "sports-loading"; visibility = GONE }
    private val progress = label(10f).apply { tag = "sports-status"; maxLines = 2; setPadding(0, dp(5), 0, dp(5)) }
    private val empty = label(12f).apply { tag = "sports-empty"; setPadding(dp(8), dp(12), dp(8), dp(12)) }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-games" }
    private val logos = LogoLoader(256)
    private val streams = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-streams"; visibility = GONE }
    private var streamChannels = emptyList<Channel>()
    private var streamLabels: (Channel) -> String = { it.name }
    private lateinit var scoresTab: Button
    private lateinit var watchTab: Button
    private val streamAdapter = object : RecyclerView.Adapter<StreamCard>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = Channel.stableId(streamChannels[position].id).take(16).toULong(16).toLong()
        override fun getItemCount() = streamChannels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamCard {
            val text = label(12f).apply { isFocusableInTouchMode = true; isClickable = true; isLongClickable = true; maxLines = 2; setPadding(dp(12), dp(6), dp(12), dp(6)); layoutParams = RecyclerView.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(4) } }
            return StreamCard(text)
        }
        override fun onBindViewHolder(holder: StreamCard, position: Int) {
            val channel = streamChannels[position]; holder.text.text = streamLabels(channel); holder.text.tag = "sports-stream:${channel.id}"
            holder.text.background = GuideTheme.background(resources.displayMetrics.density, 0x332D3542)
            holder.text.setTextColor(GuideTheme.ink())
            holder.text.setOnClickListener { watchChannel(channel) }; holder.text.setOnLongClickListener { secondChannel(channel); true }
            holder.text.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) teamBar.getChildAt(teamIndex)?.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) {
                    val at = holder.bindingAdapterPosition
                    val next = at + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next < 0 && event.repeatCount == 0) watchTab.requestFocus() else if (next in streamChannels.indices) focusStream(next)
                }; true }
                else -> false
            } }
        }
    }
    private var games = emptyList<SportsGame>()
    private var remembered: String? = null
    private var focusEpoch = 0
    private var pendingEnter = false
    private var teamSignature: Set<String>? = null
    private val toolbarControls = mutableListOf<View>()
    private var teamIndex = 0
    private val cards = object : RecyclerView.Adapter<Card>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = Channel.stableId(games[position].key).take(16).toULong(16).toLong()
        override fun getItemCount() = games.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val box = object : LinearLayout(context) {
                override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                    val at = games.indexOfFirst { it.key == tag }
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { teamBar.getChildAt(teamIndex)?.requestFocus(); return true }
                        KeyEvent.KEYCODE_DPAD_UP -> { if (at <= 0) { if (event.repeatCount == 0) teamBar.getChildAt(teamIndex)?.requestFocus() } else focusAt(at - 1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { if (at < games.lastIndex) focusAt(at + 1); return true }
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }.apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
                isFocusableInTouchMode = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(5) }
            }
            val title = label(13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
            val detail = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val first = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }; val second = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            box.addView(first, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }); box.addView(second, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            box.addView(LinearLayout(context).apply { orientation = VERTICAL; addView(title); addView(detail) }, LayoutParams(0, -2, 1f)); return Card(box, title, detail, first, second)
        }
        override fun onBindViewHolder(holder: Card, position: Int) {
            val game = games[position]; holder.box.tag = game.key; holder.title.text = game.scoreLine
            holder.detail.text = "${game.statusLine(System.currentTimeMillis())} · ${game.teams.first().league} · ${game.networks.joinToString(" / ").ifBlank { "Broadcast TBA" }}"
            listOf(holder.first, holder.second).forEachIndexed { index, view ->
                val url = game.logos[game.teams[index].id].orEmpty()
                fun redraw() { if (holder.box.tag == game.key) view.setImageBitmap(logos.get(url, false) {}) }
                view.setImageBitmap(logos.get(url, false, ::redraw))
                view.contentDescription = game.teams[index].name
            }
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
        scoresTab = button("Scores") { watchMode = false; matchingKey = null; showGames(); focusGames() }.apply { tag = "sports-scores"; isSelected = true }
        watchTab = button("Watch") { watchMode = true; scoresTab.isSelected = false; watchTab.isSelected = true; games.firstOrNull { it.key == remembered }?.let(select) ?: games.firstOrNull()?.let(select) }.apply { tag = "sports-watch" }
        listOf(scoresTab, watchTab, teamsButton, refreshButton, filtersButton).forEach { toolbar.addView(it); toolbarControls.add(it) }
        toolbarControls.forEach(::linkControl)
        addView(toolbar, LayoutParams(-1, dp(38)))
        addView(progressBar, LayoutParams(-1, dp(3))); addView(progress, LayoutParams(-1, -2)); addView(empty, LayoutParams(-1, -2))
        rows.adapter = cards; streams.adapter = streamAdapter
        val results = LinearLayout(context).apply { orientation = VERTICAL; addView(rows, LayoutParams(-1, 0, 1f)); addView(streams, LayoutParams(-1, 0, 1f)) }
        val body = LinearLayout(context).apply { addView(teamScroll, LayoutParams(dp(145), -1).apply { marginEnd = dp(8) }); addView(results, LayoutParams(0, -1, 1f)) }
        addView(body, LayoutParams(-1, 0, 1f))
    }
    private fun linkControl(control: View) { control.setOnKeyListener { _, key, event -> when (key) {
        KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) teamBar.getChildAt(teamIndex)?.requestFocus(); true }
        KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leave(); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) {
            val next = toolbarControls.indexOf(control) + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            if (next < 0) leave() else toolbarControls.getOrNull(next)?.requestFocus()
        }; true }
        else -> false
    } } }
    fun loading(message: String) { progressBar.visibility = VISIBLE; progress.text = message; if (games.isEmpty()) { empty.text = "Loading schedules… Saved results appear immediately when available."; empty.visibility = VISIBLE } }
    fun submit(next: List<SportsGame>, followed: Set<String>, selected: String?, message: String, scanning: Boolean, hiddenGames: Int, guideChannels: Int) {
        if (lastSelected != selected) { matchingKey = null; showGames(); lastSelected = selected }
        val signature = followed
        if (teamSignature != signature) {
            teamSignature = signature; teamBar.removeAllViews()
            val teams = listOf(null, "league:NFL", "league:MLB") + followed.sortedBy { SportsCatalog.byId[it]?.name } + SportsCatalog.teams.filter { it.id !in followed }.sortedWith(compareBy({ it.league }, { it.name })).map { it.id }
            teams.forEachIndexed { index, team ->
                val chip = button(when (team) { null -> "All games"; "league:NFL" -> "NFL"; "league:MLB" -> "MLB"; else -> SportsCatalog.byId[team]!!.name }) { pendingEnter = false; remembered = null; matchingKey = null; showGames(); chooseTeam(team) }
                chip.layoutParams = LayoutParams(-1, dp(34)).apply { bottomMargin = dp(4) }; chip.maxLines = 1; chip.ellipsize = android.text.TextUtils.TruncateAt.END; chip.textSize = 11f
                chip.setOnFocusChangeListener { _, active -> if (active) teamIndex = index }
                chip.tag = team?.let { "sports-team:$it" } ?: "sports-scope"; chip.isSelected = team == selected
                chip.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { if (index == 0 && event.repeatCount == 0) scoresTab.requestFocus() else teamBar.getChildAt(index - 1)?.requestFocus() }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) teamBar.getChildAt(index + 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) leave(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
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
            message.contains("Schedule unavailable") -> "Scores could not be refreshed. Saved results may be shown; choose Refresh to retry."
            scanning -> "Loading schedules… Saved results appear immediately when available."
            hiddenGames > 0 -> "$hiddenGames live ${if (hiddenGames == 1) "game is" else "games are"} hidden by your guide filters or unavailable channels. Open Filters to adjust."
            selected != null -> "No upcoming game has been announced for ${SportsCatalog.byId[selected]?.name ?: selected.substringAfter(':')}. Try Refresh or choose another team."
            else -> "No games in this scoreboard yet. Choose Refresh to retry."
        }
        empty.visibility = if (next.isEmpty() && !streams.isVisible) VISIBLE else GONE
        if (watchMode && matchingKey == null && next.isNotEmpty()) { matchingKey = next.first().key; post { select(next.first()) } }
        if (games != next) {
            val old = games; val hadFocus = rows.hasFocus()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
            })
            games = next; diff.dispatchUpdatesTo(cards)
            if ((pendingEnter && games.isNotEmpty()) || hadFocus && rows.findFocus() == null) post { focusGames() }
        }
    }
    fun matching(game: SportsGame) {
        watchMode = true; matchingKey = game.key; scoresTab.isSelected = false; watchTab.isSelected = true
        progressBar.visibility = VISIBLE; progress.text = "Finding streams for ${game.title}…"
    }
    @SuppressLint("NotifyDataSetChanged")
    fun showStreams(game: SportsGame, channels: List<Channel>, label: (Channel) -> String) {
        if (!watchMode || matchingKey != game.key) return
        streamChannels = channels; streamLabels = label; streamAdapter.notifyDataSetChanged()
        rows.visibility = GONE; streams.visibility = VISIBLE; progressBar.visibility = GONE
        progress.text = "${channels.size} streams · OK Preview · Hold OK Two streams · Hold Back Full screen"
        empty.text = if (game.state == "post") "This game has ended. Choose Scores for the final result." else "No verified channel matched your filters. Try Filters, or refresh the playlist guide. Broadcasters: ${game.networks.joinToString(" / ").ifBlank { "TBA" }}"
        empty.visibility = if (channels.isEmpty()) VISIBLE else GONE
        if (channels.isNotEmpty()) focusStream(0) else watchTab.requestFocus()
    }
    private fun showGames() { streams.visibility = GONE; rows.visibility = VISIBLE; scoresTab.isSelected = !watchMode; watchTab.isSelected = watchMode }
    private fun focusStream(at: Int) {
        val token = ++focusEpoch
        if (streams.findViewHolderForAdapterPosition(at) == null) streams.scrollToPosition(at)
        fun attempt(left: Int) { if (isShown && token == focusEpoch) { val view = streams.findViewHolderForAdapterPosition(at)?.itemView; if (view != null) { view.requestFocus(); TvFocus.center(streams, view) } else if (left > 0) streams.postOnAnimation { attempt(left - 1) } } }; attempt(15)
    }
    fun focusGames() { if (streams.isVisible && streamChannels.isNotEmpty()) { focusStream(0); return }; if (games.isEmpty()) { pendingEnter = true; teamsButton.requestFocus() } else { pendingEnter = false; focusAt(games.indexOfFirst { it.key == remembered }.coerceAtLeast(0)) } }
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
    fun pauseImages() { logos.pause() }
    fun resumeImages() { logos.resume(); cards.notifyItemRangeChanged(0, cards.itemCount) }
    fun closeImages() { logos.close() }
    private class StreamCard(val text: TextView) : RecyclerView.ViewHolder(text)
    private class Card(val box: LinearLayout, val title: TextView, val detail: TextView, val first: ImageView, val second: ImageView) : RecyclerView.ViewHolder(box)
    companion object { fun time(epoch: Long): String = GuideClock.dayTime(epoch) }
}

package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A small score surface; it owns remote keys only after the viewer explicitly opens it. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SportsScoreStrip(context: Context, button: (String, () -> Unit) -> Button,
    private val choose: (SportsGame) -> Unit,
    private val leave: () -> Unit, close: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val logos = LogoLoader(72, 1024 * 1024, ArtworkFiles(context))
    private val navigationHint = TextView(context).apply { text = "↑ Browse"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
    private val hide = button("×", close).apply { tag = "score-strip-close"; contentDescription = "Close sports overlay"; textSize = 18f; minWidth = 0; setPadding(0, 0, 0, 0) }
    private val rows = RecyclerView(context).apply { tag = "score-strip-games"; layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; preserveFocusAfterLayout = true }
    private val empty = TextView(context).apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; tag = "score-strip-status" }
    private var games = emptyList<SportsGame>()
    private var current: String? = null
    private var busy: String? = null
    private var message = ""
    private var selected: String? = null
    private var hideFinalScores = false
    var scorePresentation: (SportsGame) -> SportsGame = { it }
    private var focusEpoch = 0
    private val checking = ProgressBar(context).apply { isIndeterminate = true; tag = "sports-overlay-loading"; visibility = INVISIBLE }
    var interactive = false; private set
    private val idle = Runnable { if (busy == null) releaseFocus() }
    private fun armIdle() { removeCallbacks(idle); if (interactive && busy == null) postDelayed(idle, 8000) }
    init {
        tag = "sports-score-strip"; orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS; setPadding(dp(8), dp(5), dp(8), dp(5)); elevation = dp(10).toFloat(); clipToOutline = true
        addView(LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(TextView(context).apply { text = "SPORTS"; textSize = 8f; setTextColor(Color.LTGRAY) }, LayoutParams(-2, -1))
                addView(checking, LayoutParams(dp(10), dp(10)).apply { marginStart = dp(4) })
            }, LayoutParams(-1, dp(15)))
            addView(navigationHint, LayoutParams(-1, dp(29)))
        }, LayoutParams(dp(72), -1).apply { marginEnd = dp(6) })
        addView(rows, LayoutParams(0, -1, 1f)); addView(empty, LayoutParams(0, -1, 1f)); addView(hide, LayoutParams(dp(30), -1).apply { marginStart = dp(6) })
        rows.adapter = object : RecyclerView.Adapter<Tile>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = games.size
            override fun getItemId(position: Int) = Channel.stableLongId(games[position].key)
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Tile {
                val box = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; isFocusableInTouchMode = true; isClickable = true; setPadding(dp(8), dp(3), dp(8), dp(3)); layoutParams = RecyclerView.LayoutParams(dp(176), -1).apply { marginEnd = dp(6) } }
                val sides = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                val icons = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }
                val scores = List(2) { TextView(context).apply { textSize = 12f; typeface = AppFont.face(true); maxLines = 1; gravity = Gravity.CENTER_VERTICAL } }
                repeat(2) { i -> sides.addView(icons[i], LayoutParams(dp(19), dp(19))); sides.addView(scores[i], LayoutParams(0, dp(23), 1f).apply { marginStart = dp(3) }) }
                val status = TextView(context).apply { textSize = 9f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                box.addView(sides, LayoutParams(-1, dp(24))); box.addView(status, LayoutParams(-1, dp(14)))
                return Tile(box, icons, scores, status)
            }
            override fun onBindViewHolder(holder: Tile, position: Int) {
                val game = games[position]; holder.box.tag = "score-game:${game.key}"
                val focused = holder.box.hasFocus(); holder.box.background = GuideTheme.background(resources.displayMetrics.density, if (game.key == current) 0x993A5964.toInt() else 0x77303943)
                fun ink(active: Boolean) { holder.scores.forEach { it.setTextColor(if (active) GuideTheme.focusText else Color.WHITE) }; holder.status.setTextColor(if (active) GuideTheme.focusText else 0xFFC8D4D8.toInt()) }
                game.teams.forEachIndexed { i, team ->
                    logos.bind(holder.icons[i], game.logos[team.id].orEmpty().ifBlank { team.logo })
                }
                renderScore(holder, game)
                ink(focused); holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { selected = game.key; armIdle() } }
                holder.box.setOnClickListener { choose(game) }
                holder.box.setOnKeyListener { _, key, event ->
                    if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false else {
                        if (event.action == KeyEvent.ACTION_DOWN) when (key) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> releaseFocus()
                            else -> {
                                val at = holder.bindingAdapterPosition
                                if (at in games.indices) {
                                    val next = at + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                    when { next < 0 -> Unit; next >= games.size -> { ++focusEpoch; hide.requestFocus() }; else -> focusTile(next) }
                                }
                            }
                        }; true
                    }
                }
            }
        }
        hide.setOnFocusChangeListener { _, active -> if (active) armIdle() }
        hide.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) releaseFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT && games.isNotEmpty()) focusTile(games.lastIndex); true }
                else -> false
            }
        }
        appearance()
    }
    private class Tile(val box: LinearLayout, val icons: List<ImageView>, val scores: List<TextView>, val status: TextView) : RecyclerView.ViewHolder(box)
    private fun renderScore(holder: Tile, game: SportsGame) {
        val display = scorePresentation(game); val hidden = display.state == "post" && hideFinalScores
        display.teams.forEachIndexed { index, team -> holder.scores[index].text = "${team.id.substringAfter(':').take(4).uppercase(java.util.Locale.ROOT)} ${if (hidden) "–" else display.scores[team.id] ?: "–"}" }
        holder.status.text = if (game.key == busy) message.ifBlank { "Finding stream…" } else if (display.status == SportsScoreDelay.WAITING) "Score delayed · syncing…" else when (display.state) {
            "post" -> "FINAL · ${SportsLeagues.label(game.leagueId)}" + if (hidden) " · Scores hidden" else ""
            "hold" -> "${SportsLeagues.label(game.leagueId)} · Checking live status"
            else -> "● ${SportsLeagues.label(game.leagueId)} · ${display.status.ifBlank { "Live" }}"
        }
        holder.box.contentDescription = "${game.title}. ${if (hidden) "Scores hidden" else display.scoreLine}. ${holder.status.text}"
    }
    fun refreshScores() { for (i in 0 until rows.childCount) { val holder = rows.getChildViewHolder(rows.getChildAt(i)) as? Tile ?: continue; games.getOrNull(holder.bindingAdapterPosition)?.let { renderScore(holder, it) } } }
    override fun focusSearch(focused: View, direction: Int): View? = FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) armIdle(); return super.dispatchKeyEvent(event) }
    private fun focusTile(position: Int) {
        val key = games.getOrNull(position)?.key ?: return; val token = ++focusEpoch
        rows.scrollToPosition(position)
        rows.doOnLayout { if (interactive && isShown && token == focusEpoch && games.getOrNull(position)?.key == key) rows.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
    }
    fun enter() { interactive = true; navigationHint.text = "← → Browse"; descendantFocusability = FOCUS_AFTER_DESCENDANTS; if (games.isEmpty()) hide.requestFocus() else focusTile(games.indexOfFirst { it.key == (selected ?: current) }.coerceAtLeast(0)); armIdle() }
    fun releaseFocus() { ++focusEpoch; removeCallbacks(idle); interactive = false; descendantFocusability = FOCUS_BLOCK_DESCENDANTS; clearFocus(); leave() }
    fun present() { navigationHint.text = if (interactive) "← → Browse" else "↑ Browse"; if (visibility != VISIBLE) { visibility = VISIBLE; logos.resume(); rows.adapter?.notifyItemRangeChanged(0, games.size) } }
    fun suspend() { ++focusEpoch; removeCallbacks(idle); interactive = false; descendantFocusability = FOCUS_BLOCK_DESCENDANTS; clearFocus(); visibility = GONE; logos.pause() }
    fun submit(next: List<SportsGame>, currentGame: String?, checking: Boolean, hideCompletedScores: Boolean = false) {
        this.checking.visibility = if (checking) VISIBLE else INVISIBLE
        val before = games; val currentChanged = current != currentGame || hideFinalScores != hideCompletedScores; current = currentGame; hideFinalScores = hideCompletedScores
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = before.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = before[a].key == next[b].key
            override fun areContentsTheSame(a: Int, b: Int) = before[a] == next[b] && !currentChanged
        })
        val hadFocus = rows.hasFocus(); games = next; diff.dispatchUpdatesTo(rows.adapter!!)
        rows.visibility = if (next.isEmpty()) GONE else VISIBLE; empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        empty.text = if (checking) "Checking today’s games…" else "No games today in your enabled leagues"
        if (interactive && hadFocus && next.none { it.key == selected }) { selected = next.firstOrNull()?.key; rows.post { if (interactive) { if (next.isEmpty()) hide.requestFocus() else focusTile(0) } } }
    }
    fun tuning(key: String?, text: String = "") { val previous = busy; busy = key; message = text; games.forEachIndexed { i, game -> if (game.key == key || game.key == previous) rows.adapter?.notifyItemChanged(i) }; armIdle() }
    fun appearance() { background = GuideTheme.glass(resources.displayMetrics.density); AppFont.apply(this); rows.adapter?.notifyItemRangeChanged(0, games.size) }
    fun trimImages() = logos.trim()
    fun closeImages() { removeCallbacks(idle); logos.close() }
    companion object {
        fun todayGames(snapshot: LiveSportsSnapshot, leagues: Set<String>, followed: Set<String>, now: Long): List<SportsGame> =
            SportsSchedule.games(snapshot, Guide.EMPTY, "today", emptySet(), now, matchChannels = false)
                .filter { it.leagueId in leagues && it.state in setOf("in", "post", "hold") }
                .sortedWith(compareBy<SportsGame> { if (it.state == "in") 0 else if (it.state == "hold") 1 else 2 }
                    .thenByDescending { it.teams.any { team -> team.id in followed } }.thenBy { it.start }.thenBy { it.key }).take(160)
    }
}

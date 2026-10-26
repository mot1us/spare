package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** League navigation, a focused event hero, and a stable game rail. No stream opens on focus. */
@SuppressLint("SetTextI18n", "ViewConstructor")
class SportsPanel(context: Context, private val button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    private val chooseTeam: (String?) -> Unit, refresh: () -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, filters: () -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE) }
    private val leagueList = LinearLayout(context).apply { orientation = VERTICAL; tag = "sports-leagues" }
    private val leagueScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(leagueList) }
    val teamsButton = button("Manage teams", chooseTeams).apply { tag = "sports-teams" }
    private val refreshButton = button("Refresh", refresh).apply { tag = "sports-refresh" }
    private val filtersButton = button("Stream filters", filters).apply { tag = "sports-filters"; visibility = GONE }
    private val scoresButton = button("Back to games") { showGames(); focusGames() }.apply { tag = "sports-scores"; visibility = GONE }
    private val hero = FrameLayout(context).apply { tag = "sports-hero"; clipToOutline = true }
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .65f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val heroBadge = label(11f).apply { tag = "sports-hero-badge"; typeface = AppFont.face(true) }
    private val heroTitle = label(25f).apply { tag = "sports-hero-title"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
    private val heroScore = label(25f).apply { tag = "sports-hero-score"; typeface = AppFont.face(true) }
    private val heroDetail = label(11f).apply { tag = "sports-hero-detail"; maxLines = 2; setTextColor(Color.LTGRAY) }
    private val credit = label(8f).apply { maxLines = 1; gravity = Gravity.END; setTextColor(Color.LTGRAY) }
    private val heroContent = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(210), dp(12)) }
    private val crests = LinearLayout(context).apply { gravity = Gravity.CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val crestViews = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; crests.addView(this, LayoutParams(dp(88), dp(100))) } }
    private val findStreams = button("Find streams") { featured?.let(select) }.apply { tag = "sports-watch" }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; tag = "sports-loading"; visibility = GONE }
    private val status = label(10f).apply { tag = "sports-status"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.LTGRAY) }
    private val empty = label(13f).apply { tag = "sports-empty"; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false); itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-games" }
    private val streams = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-streams"; visibility = GONE }
    private val logos = LogoLoader(512, 6 * 1024 * 1024)
    private val pictures = LogoLoader(1280, 8 * 1024 * 1024)
    private val artwork = SportsArtwork { key, url, attribution -> if (featured?.key == key) showArtwork(key, url, attribution) }
    private var games = emptyList<SportsGame>()
    private var featured: SportsGame? = null
    private var remembered: String? = null
    private var followed = emptySet<String>()
    private var signature: Set<String>? = null
    private var scope: String? = null
    private var focusEpoch = 0
    private var leagueIndex = 0
    private var pendingEnter = false
    private var matchingKey: String? = null
    private var preview = false
    private var streamsList = emptyList<Channel>()
    private var streamLabels: (Channel) -> String = { it.name }
    private var watchMode = false
    val watching get() = watchMode
    var watchChannel: (Channel) -> Unit = {}
    var secondChannel: (Channel) -> Unit = {}
    var presentationChanged: (() -> Unit)? = null
    private var streamActions: View? = null
    private val cards = object : RecyclerView.Adapter<GameCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = games.size
        override fun getItemId(position: Int) = Channel.stableId(games[position].key).take(16).toULong(16).toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameCard {
            val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)); isFocusableInTouchMode = true; isClickable = true; layoutParams = RecyclerView.LayoutParams(dp(216), -1).apply { marginEnd = dp(9) } }
            val badge = label(10f).apply { typeface = AppFont.face(true) }; val title = label(12f).apply { maxLines = 2; typeface = AppFont.face(true); ellipsize = android.text.TextUtils.TruncateAt.END }; val detail = label(10f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(badge); box.addView(title, LayoutParams(-1, 0, 1f)); box.addView(detail)
            return GameCard(box, badge, title, detail)
        }
        override fun onBindViewHolder(holder: GameCard, position: Int) {
            val game = games[position]; holder.box.tag = game.key; holder.badge.text = badge(game); holder.title.text = game.scoreLine
            holder.detail.text = SportsLeagues.label(game.leagueId) + " · " + game.statusLine(System.currentTimeMillis())
            holder.box.contentDescription = holder.title.text.toString() + ". " + holder.detail.text
            holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x88303943.toInt())
            fun ink(active: Boolean) { val color = if (active) Color.BLACK else Color.WHITE; holder.title.setTextColor(color); holder.detail.setTextColor(if (active) Color.BLACK else Color.LTGRAY); holder.badge.setTextColor(if (active) Color.BLACK else if (game.live(System.currentTimeMillis())) 0xFF8CE5C1.toInt() else GuideTheme.current.accent) }
            ink(holder.box.hasFocus()); holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { remembered = game.key; showHero(game); focused(game) } }
            holder.box.setOnClickListener { select(game) }
            holder.box.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) { val at = holder.bindingAdapterPosition; if (at != RecyclerView.NO_POSITION) { val next = at + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1; if (next < 0) focusTeams() else if (next in games.indices) focusAt(next) } }; true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) findStreams.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            } }
        }
    }
    private val streamAdapter = object : RecyclerView.Adapter<StreamCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = streamsList.size
        override fun getItemId(position: Int) = Channel.stableId(streamsList[position].id).take(16).toULong(16).toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamCard {
            val box = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = RecyclerView.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) } }
            val play = button("") {}.apply { maxLines = 2; textSize = 11f; isLongClickable = true }
            val together = button("＋") {}.apply { contentDescription = "Watch together"; tag = "sports-add-view" }
            box.addView(play, LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) }); box.addView(together, LayoutParams(dp(54), -1)); return StreamCard(box, play, together)
        }
        override fun onBindViewHolder(holder: StreamCard, position: Int) {
            val channel = streamsList[position]; holder.play.text = streamLabels(channel); holder.play.tag = "sports-stream:${channel.id}"; holder.together.contentDescription = "Watch together · " + channel.name
            holder.play.setOnClickListener { watchChannel(channel) }; holder.play.setOnLongClickListener { secondChannel(channel); true }; holder.together.setOnClickListener { secondChannel(channel) }
            listOf(holder.play, holder.together).forEach { view -> view.setOnFocusChangeListener { _, active -> if (active) streamActions = view }; view.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (view === holder.together) holder.play.requestFocus() else focusTeams() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) holder.together.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { val at = holder.bindingAdapterPosition; if (at != RecyclerView.NO_POSITION) { val next = at + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1; if (next < 0) scoresButton.requestFocus() else if (next in streamsList.indices) focusStream(next) } }; true }
                else -> false
            } } }
        }
    }
    init {
        orientation = HORIZONTAL; tag = "sports-panel"; visibility = GONE
        val sidebar = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, 0, dp(12), 0) }
        sidebar.addView(label(19f).apply { text = "Sports"; typeface = AppFont.face(true); setPadding(dp(8), 0, 0, dp(12)) })
        sidebar.addView(leagueScroll, LayoutParams(-1, 0, 1f)); listOf(teamsButton, refreshButton).forEach { sidebar.addView(it, LayoutParams(-1, dp(34)).apply { topMargin = dp(6) }); linkAction(it) }
        addView(sidebar, LayoutParams(dp(155), -1))
        hero.background = GuideTheme.glass(resources.displayMetrics.density); hero.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5091019.toInt(), 0x99091019.toInt(), 0x20091019)) }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(crests, FrameLayout.LayoutParams(dp(192), -1, Gravity.END))
        heroContent.addView(heroBadge); heroContent.addView(heroTitle); heroContent.addView(heroScore); heroContent.addView(heroDetail)
        heroContent.addView(findStreams, LayoutParams(dp(145), dp(34)).apply { topMargin = dp(10) }); linkAction(findStreams)
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1)); hero.addView(credit, FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM).apply { marginEnd = dp(8) })
        val main = LinearLayout(context).apply { orientation = VERTICAL; addView(hero, LayoutParams(-1, 0, 1f)); addView(progress, LayoutParams(-1, dp(3))); addView(status, LayoutParams(-1, dp(24))); addView(empty, LayoutParams(-1, -2)) }
        val actions = LinearLayout(context).apply { addView(scoresButton); addView(filtersButton, LayoutParams(-2, -2).apply { marginStart = dp(8) }) }
        main.addView(actions); listOf(scoresButton, filtersButton).forEach(::linkAction)
        rows.adapter = cards; streams.adapter = streamAdapter; main.addView(rows, LayoutParams(-1, dp(126))); main.addView(streams, LayoutParams(-1, 0, 1f))
        addView(main, LayoutParams(0, -1, 1f)); heroTitle.text = "Your game, one click away"; heroDetail.text = "Browse leagues or follow your teams."; findStreams.isEnabled = false
        submit(emptyList(), emptySet(), null, "", false, 0, 0)
    }
    private fun badge(game: SportsGame) = when { game.live(System.currentTimeMillis()) -> "● LIVE"; game.state == "post" -> "FINAL"; game.state == "pre" -> "UPCOMING"; else -> "SAVED · REFRESH" }
    private fun linkAction(view: View) { view.setOnKeyListener { _, key, event -> when (key) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) focusTeams(); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
        KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focusTeams(); true }
        else -> false
    } } }
    private fun showHero(game: SportsGame) {
        val changed = featured?.key != game.key; featured = game
        heroBadge.text = SportsLeagues.label(game.leagueId).uppercase() + "  ·  " + badge(game); heroBadge.setTextColor(if (game.live(System.currentTimeMillis())) 0xFF8CE5C1.toInt() else GuideTheme.current.accent)
        heroTitle.text = game.title; heroScore.text = if (game.state == "pre") GuideClock.dayTime(game.start) else if (game.scores.isEmpty()) "Score unavailable" else game.teams.joinToString("   :   ") { game.scores[it.id] ?: "–" }
        heroDetail.text = (if (game.live(System.currentTimeMillis())) game.status.ifBlank { "In progress" } else if (game.state == "pre") "Upcoming" else game.statusLine(System.currentTimeMillis())) + "\n" + game.networks.joinToString(" · ").ifBlank { "Broadcast to be announced" }
        findStreams.isEnabled = game.state != "post"; findStreams.text = if (game.state == "pre") "Find coverage" else "Find streams"
        if (changed) { backdrop.setImageDrawable(null); credit.text = ""; crests.isVisible = !preview }
        game.teams.forEachIndexed { i, team -> val url = game.logos[team.id].orEmpty().ifBlank { team.logo }; fun draw() { if (featured?.key == game.key) crestViews[i].setImageBitmap(logos.get(url, false) {}) }; crestViews[i].setImageBitmap(logos.get(url, false, ::draw)) }
        if (changed) artwork.request(game)
    }
    private fun showArtwork(key: String, url: String, attribution: String) {
        if (url.isBlank() || preview) return
        fun draw() { if (featured?.key == key && !preview) { val image = pictures.get(url, false) {}; backdrop.setImageBitmap(image); crests.isVisible = image == null; credit.text = if (image != null && attribution.isNotBlank()) "Photo: $attribution" else "" } }
        val image = pictures.get(url, false, ::draw); backdrop.setImageBitmap(image); crests.isVisible = image == null; credit.text = if (image != null && attribution.isNotBlank()) "Photo: $attribution" else ""
    }
    fun previewMode(enabled: Boolean) {
        preview = enabled; heroContent.setPadding(dp(20), dp(12), dp(if (enabled) 330 else 210), dp(12)); crests.isVisible = !enabled && backdrop.drawable == null
        if (enabled) backdrop.setImageDrawable(null)
        hero.layoutParams = LayoutParams(-1, if (watchMode) dp(200) else 0, if (watchMode) 0f else 1f)
        if (!enabled) featured?.let { artwork.request(it) }
    }
    fun loading(message: String) { progress.isVisible = true; status.text = message; if (games.isEmpty()) { empty.text = "Loading scores and schedules…"; empty.isVisible = true } }
    fun submit(next: List<SportsGame>, followed: Set<String>, selected: String?, message: String, scanning: Boolean, hiddenGames: Int, guideChannels: Int) {
        this.followed = followed
        if (scope != selected) { scope = selected; remembered = null; showGames() }
        if (signature != followed) {
            signature = followed; val hadFocus = leagueList.hasFocus(); leagueList.removeAllViews()
            val values = listOf<String?>(null, "followed") + SportsLeagues.all.map { "league:${it.id}" } + followed.sortedBy { SportsCatalog.byId[it]?.name }
            values.forEachIndexed { index, value ->
                val title = when { value == null -> "All games"; value == "followed" -> "My teams"; value.startsWith("league:") -> SportsLeagues.label(value.substringAfter(':')); else -> SportsCatalog.byId[value]?.name ?: value }
                val chip = button(title) { pendingEnter = false; chooseTeam(value) }.apply { textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; isSelected = value == selected; tag = value?.let { "sports-team:$it" } ?: "sports-scope"; layoutParams = LayoutParams(-1, dp(34)).apply { bottomMargin = dp(5) } }
                chip.setOnFocusChangeListener { _, active -> if (active) leagueIndex = index }
                chip.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { pendingEnter = false; if (index == 0) leave() else leagueList.getChildAt(index - 1)?.requestFocus() }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { pendingEnter = false; leagueList.getChildAt(index + 1)?.requestFocus() ?: teamsButton.requestFocus() }; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) leave(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
                    else -> false
                } }; leagueList.addView(chip)
            }
            leagueIndex = values.indexOf(selected).coerceAtLeast(0); if (hadFocus) leagueList.getChildAt(leagueIndex)?.requestFocus()
        }
        for (index in 0 until leagueList.childCount) {
            val chip = leagueList.getChildAt(index); chip.isSelected = chip.tag == (selected?.let { "sports-team:$it" } ?: "sports-scope")
        }
        progress.isVisible = scanning; status.text = message
        empty.text = when { scanning -> "Loading scores and schedules…"; selected == "followed" && followed.isEmpty() -> "Choose Manage teams to follow your clubs."; message.contains("unavailable", true) -> "Scores are unavailable. Choose Refresh to try again."; else -> "No games scheduled in this view. Choose another league or Refresh." }
        empty.isVisible = next.isEmpty() && !streams.isVisible
        if (games != next) {
            val old = games; val hadFocus = rows.hasFocus()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() { override fun getOldListSize() = old.size; override fun getNewListSize() = next.size; override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key; override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b] })
            games = next; diff.dispatchUpdatesTo(cards)
            val chosen = next.firstOrNull { it.key == remembered } ?: featured(next, followed, System.currentTimeMillis())
            if (chosen != null) showHero(chosen) else { featured = null; artwork.pause(); backdrop.setImageDrawable(null); crestViews.forEach { it.setImageDrawable(null) }; heroTitle.text = "Your next game"; heroScore.text = ""; heroBadge.text = selected?.removePrefix("league:")?.let(SportsLeagues::label) ?: "SPORTS"; heroDetail.text = "Schedules appear independently of playlist channels."; findStreams.isEnabled = false }
            if ((pendingEnter && games.isNotEmpty()) || (hadFocus && rows.findFocus() == null)) post { focusGames() }
        } else featured?.let { current -> next.firstOrNull { it.key == current.key }?.let(::showHero) }
    }
    @SuppressLint("NotifyDataSetChanged")
    fun matching(game: SportsGame) { streamsList = emptyList(); streamAdapter.notifyDataSetChanged(); empty.isVisible = false; showHero(game); watchMode = true; matchingKey = game.key; rows.isVisible = false; streams.isVisible = true; scoresButton.isVisible = true; filtersButton.isVisible = true; progress.isVisible = true; status.text = "Finding playlist streams…"; presentationChanged?.invoke() }
    @SuppressLint("NotifyDataSetChanged")
    fun showStreams(game: SportsGame, channels: List<Channel>, label: (Channel) -> String) {
        if (!watchMode || matchingKey != game.key) return
        val same = streamsList == channels; val hadFocus = streams.hasFocus(); streamsList = channels; streamLabels = label
        if (!same) streamAdapter.notifyDataSetChanged(); progress.isVisible = false; status.text = "${channels.size} streams · OK Preview · ＋ Watch together · Hold Back Full screen"
        empty.text = if (game.state == "post") "This game has ended." else "No channel matched these filters yet. Try Stream filters or refresh your guide."
        empty.isVisible = channels.isEmpty(); if (!hadFocus) { if (channels.isNotEmpty()) focusStream(0) else filtersButton.requestFocus() }
    }
    private fun showGames() { watchMode = false; matchingKey = null; streams.isVisible = false; rows.isVisible = true; scoresButton.isVisible = false; filtersButton.isVisible = false; presentationChanged?.invoke() }
    fun back(): Boolean { if (!watchMode) return false; showGames(); focusGames(); return true }
    fun focusTeams() { leagueList.getChildAt(leagueIndex)?.requestFocus() ?: teamsButton.requestFocus() }
    fun focusGames() { if (watchMode && streamsList.isNotEmpty()) focusStream(0) else if (games.isNotEmpty()) { pendingEnter = false; focusAt(games.indexOfFirst { it.key == (remembered ?: featured?.key) }.coerceAtLeast(0)) } else { pendingEnter = true; focusTeams() } }
    private fun focusAt(position: Int) { focusRow(rows, position) }
    private fun focusStream(position: Int) { focusRow(streams, position) }
    private fun focusRow(list: RecyclerView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0)) return
        val token = ++focusEpoch; if (list.findViewHolderForAdapterPosition(position) == null) list.scrollToPosition(position)
        fun attempt(left: Int) { if (isShown && token == focusEpoch) { val item = list.findViewHolderForAdapterPosition(position)?.itemView; if (item != null) { if (list === streams) item.findViewWithTag<View>("sports-stream:${streamsList[position].id}").requestFocus() else item.requestFocus(); if (list === streams) TvFocus.center(list, item) } else if (left > 0) list.postOnAnimation { attempt(left - 1) } } }; attempt(15)
    }
    @SuppressLint("NotifyDataSetChanged") fun refreshTheme() { hero.background = GuideTheme.glass(resources.displayMetrics.density); cards.notifyDataSetChanged(); streamAdapter.notifyDataSetChanged(); signature = null; featured?.let(::showHero) }
    fun pauseImages() { artwork.pause(); logos.pause(); pictures.pause() }
    fun resumeImages() { artwork.resume(); logos.resume(); pictures.resume(); cards.notifyItemRangeChanged(0, cards.itemCount); featured?.let { showHero(it); artwork.request(it) } }
    fun closeImages() { artwork.close(); logos.close(); pictures.close() }
    private class GameCard(val box: LinearLayout, val badge: TextView, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(box)
    private class StreamCard(val box: LinearLayout, val play: Button, val together: Button) : RecyclerView.ViewHolder(box)
    companion object {
        fun time(epoch: Long): String = GuideClock.dayTime(epoch)
        fun featured(games: List<SportsGame>, followed: Set<String>, now: Long): SportsGame? = games.minWithOrNull(compareBy<SportsGame> { game ->
            val mine = game.teams.any { it.id in followed }; when { mine && game.live(now) -> 0; mine && game.state == "pre" && game.start >= now -> 1; game.live(now) -> 2; game.state == "pre" && game.start >= now -> 3; else -> 4 }
        }.thenBy { if (it.state == "post") -it.start else it.start })
    }
}

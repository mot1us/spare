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
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Fixed-size Sports home. Asset/score updates never rebuild navigation or open streams on focus. */
@SuppressLint("SetTextI18n", "ViewConstructor")
class SportsPanel(context: Context, private val button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    private val chooseTeam: (String?) -> Unit, refresh: () -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, filters: () -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE) }
    val teamsButton = button("Sports settings", chooseTeams).apply { tag = "sports-teams" }
    private val refreshButton = button("Refresh", refresh).apply { tag = "sports-refresh" }
    private val dateButton = button("Today") { chooseDate() }.apply { tag = "sports-date" }
    private val todayButton = button("Home") { chooseTeam(null) }.apply { tag = "sports-today" }
    private val filtersButton = button("Stream filters", filters).apply { tag = "sports-filters"; visibility = GONE }
    private val scoresButton = button("Back to games") { showGames(); focusGames() }.apply { tag = "sports-scores"; visibility = GONE }
    private val actionRow = LinearLayout(context).apply { tag = "sports-actions"; visibility = GONE }
    private val hero = FrameLayout(context).apply { tag = "sports-hero"; clipToOutline = true }
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .65f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val heroBadge = label(11f).apply { tag = "sports-hero-badge"; typeface = AppFont.face(true) }
    private val heroTitle = label(25f).apply { tag = "sports-hero-title"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
    private val heroScore = label(25f).apply { tag = "sports-hero-score"; typeface = AppFont.face(true) }
    private val heroDetail = label(11f).apply { tag = "sports-hero-detail"; maxLines = 2; setTextColor(Color.LTGRAY) }
    private val credit = label(8f).apply { maxLines = 1; gravity = Gravity.END; setTextColor(Color.LTGRAY) }
    private val heroContent = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(210), dp(12)) }
    private val crests = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x002E4060, 0x8845688F.toInt(), 0xAA122136.toInt())) }
    private val crestViews = List(2) { index -> ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; alpha = .88f; rotation = if (index == 0) -8f else 8f
        crests.addView(this, FrameLayout.LayoutParams(dp(190), dp(190), if (index == 0) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.END).apply { topMargin = dp(if (index == 0) -12 else 0); bottomMargin = dp(if (index == 1) -12 else 0) })
    } }
    private val findStreams = button("Find streams") { if (remembered == REDZONE) findRedZone() else featured?.let { if (it.live(System.currentTimeMillis())) watchBest(it) else select(it) } }.apply { tag = "sports-watch" }
    private val reminder = button("Remind me") { featured?.takeIf { it.state == "pre" }?.let { toggleReminder(it); updateReminder(it) } }.apply { tag = "sports-reminder"; isEnabled = false }
    var findRedZone: () -> Unit = {}
    private val chooseStreams = button("Choose stream") { featured?.let(select) }.apply { tag = "sports-choose-stream" }
    private var actionsOpen = false
    var hasReminder: (SportsGame) -> Boolean = { false }
    var toggleReminder: (SportsGame) -> Unit = {}
    var watchBest: (SportsGame) -> Unit = {}
    private fun updateReminder(game: SportsGame) {
        reminder.isVisible = actionsOpen && game.state == "pre"
        reminder.isEnabled = game.state == "pre"
        reminder.text = if (hasReminder(game)) "Reminder set ✓" else "Remind me"
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; tag = "sports-loading"; visibility = INVISIBLE }
    private val status = label(10f).apply { tag = "sports-status"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.LTGRAY) }
    private val empty = label(13f).apply { tag = "sports-empty"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    private val rows = RecyclerView(context).apply { layoutManager = object : GridLayoutManager(context, COLUMNS) { override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false }; itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-games"; setItemViewCacheSize(8); isFocusableInTouchMode = true }
    private val streams = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-streams"; visibility = GONE }
    private val artworkFiles = ArtworkFiles(context)
    private val logos = LogoLoader(512, 6 * 1024 * 1024, artworkFiles)
    private val cardLogos = LogoLoader(160, 3 * 1024 * 1024, artworkFiles)
    private val thumbnails = LogoLoader(384, 5 * 1024 * 1024, artworkFiles)
    private val pictures = LogoLoader(1280, 8 * 1024 * 1024, artworkFiles)
    private val artwork = SportsArtwork(context) { key, url, attribution -> showArtwork(key, url, attribution) }
    private val gameArt = linkedMapOf<String, String>()
    var archiveWindowDays = 0
    private var hideScores = false
    fun scoresHidden(game: SportsGame) = hideScores && game.state == "post"
    fun scoreText(game: SportsGame) = SportsText.score(game, scoresHidden(game))
    fun statusText(game: SportsGame) = SportsText.status(game, scoresHidden(game), System.currentTimeMillis())
    fun setSpoilers(hidden: Boolean) {
        if (hideScores == hidden) return
        hideScores = hidden; redrawVisiblePosters(); featured?.let { showHero(it); if (!scoresHidden(it)) artwork.request(it) }
    }
    private var replayMode = false
    private var replays = emptyList<SportsReplay>()
    var watchReplay: (SportsReplay) -> Unit = {}
    private var games = emptyList<SportsGame>()
    private var featured: SportsGame? = null
    private var remembered: String? = null
    private var followed = emptySet<String>()
    private var enabledLeagues = SportsLeagues.byId.keys
    private val loadHeroArt = Runnable { featured?.takeUnless(::scoresHidden)?.let(artwork::request) }
    fun setLeagues(leagues: Set<String>) { enabledLeagues = leagues }
    private var displayedRedZone = false
    private var hasSchedule = false
    private var scope: String? = null
    private var focusEpoch = 0
    private var pendingEnter = false
    private var pendingInput = 0
    private var loadingGames = true
    private var inputEpoch = 0
    private var streamEntry = false
    private var streamInput = 0
    var chooseDate: () -> Unit = {}
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) ++inputEpoch; return super.dispatchKeyEvent(event) }
    private var matchingKey: String? = null
    private var preview = false
    private var streamsList = emptyList<Channel>()
    private var streamLabels: (Channel) -> String = { it.name }
    private var watchMode = false
    val watching get() = watchMode
    var watchChannel: (Channel) -> Unit = {}
    var secondChannel: (Channel) -> Unit = {}
    var presentationChanged: (() -> Unit)? = null
    private val cards = object : RecyclerView.Adapter<GameCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = games.size + if (displayedRedZone) 1 else 0
        override fun getItemId(position: Int) = Channel.stableId(tileKey(position)).take(16).toULong(16).toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameCard {
            val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)); isFocusableInTouchMode = true; isClickable = true; layoutParams = RecyclerView.LayoutParams(-1, dp(164)).apply { marginEnd = dp(8); bottomMargin = dp(8) } }
            val badge = label(10f).apply { typeface = AppFont.face(true) }; val title = label(12f).apply { maxLines = 2; typeface = AppFont.face(true); ellipsize = android.text.TextUtils.TruncateAt.END }; val detail = label(10f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val poster = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF172B40.toInt(), 0xFF344258.toInt())); clipChildren = true }
            val crests = LinearLayout(context).apply { gravity = Gravity.CENTER }
            val logos = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(7), dp(3), dp(7), dp(3)); crests.addView(this, LayoutParams(0, -1, 1f)) } }
            poster.addView(crests, FrameLayout.LayoutParams(-1, -1))
            val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE }; poster.addView(photo, FrameLayout.LayoutParams(-1, -1))
            box.addView(poster, LayoutParams(-1, dp(74)).apply { bottomMargin = dp(5) }); box.addView(badge); box.addView(title, LayoutParams(-1, 0, 1f)); box.addView(detail)
            val special = label(24f).apply { text = "REDZONE"; typeface = AppFont.face(true); gravity = Gravity.CENTER; visibility = GONE; setTextColor(0xFFF2D9D9.toInt()) }; poster.addView(special, FrameLayout.LayoutParams(-1, -1))
            return GameCard(box, badge, title, detail, logos, photo, special)
        }
        override fun onBindViewHolder(holder: GameCard, position: Int) {
            val game = games.getOrNull(position)
            if (game == null) { bindRedZone(holder); return }
            holder.special.visibility = GONE; holder.box.tag = game.key; holder.badge.text = badge(game); holder.title.text = scoreText(game)
            drawCard(holder, game)
            holder.detail.text = SportsLeagues.label(game.leagueId) + " · " + GuideClock.sportsDate(game.start) + if (game.state != "pre") "\n" + statusText(game) else ""
            holder.box.contentDescription = holder.title.text.toString() + ". " + holder.detail.text
            holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x88303943.toInt())
            fun ink(active: Boolean) { val color = if (active) Color.BLACK else Color.WHITE; holder.title.setTextColor(color); holder.detail.setTextColor(if (active) Color.BLACK else Color.LTGRAY); holder.badge.setTextColor(if (active) Color.BLACK else if (game.live(System.currentTimeMillis())) 0xFF8CE5C1.toInt() else GuideTheme.current.accent) }
            ink(holder.box.hasFocus()); holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { remembered = game.key; showHero(game); focused(game); artwork.prefetch(games.drop(position + 1).filterNot(::scoresHidden).take(2)) } }
            holder.box.setOnClickListener { openGame(game) }
            cardKeys(holder)

        }
    }
    private val streamAdapter = object : RecyclerView.Adapter<StreamCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = streamsList.size
        override fun getItemId(position: Int) = Channel.stableId(streamsList[position].id).take(16).toULong(16).toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamCard {
            val box = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = RecyclerView.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) } }
            val play = button("") {}.apply { maxLines = 2; textSize = 11f; isLongClickable = true }
            val together = button("＋ View") {}.apply { contentDescription = "Add to multiview"; tag = "sports-add-view" }
            box.addView(play, LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) }); box.addView(together, LayoutParams(dp(82), -1)); return StreamCard(box, play, together)
        }
        override fun onBindViewHolder(holder: StreamCard, position: Int) {
            val channel = streamsList[position]; holder.play.text = streamLabels(channel); holder.play.tag = "sports-stream:${channel.id}"; holder.together.contentDescription = "Add to multiview · " + channel.name
            holder.together.isVisible = !replayMode
            holder.play.setOnClickListener { if (replayMode) replays.firstOrNull { it.channel.id == channel.id }?.let(watchReplay) else watchChannel(channel) }; holder.play.setOnLongClickListener { if (replayMode) false else { secondChannel(channel); true } }; holder.together.setOnClickListener { secondChannel(channel) }
            listOf(holder.play, holder.together).forEach { view -> view.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (view === holder.together) holder.play.requestFocus() else scoresButton.requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && holder.together.isShown) holder.together.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { val at = holder.bindingAdapterPosition; if (at != RecyclerView.NO_POSITION) { val next = at + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1; if (next < 0) scoresButton.requestFocus() else if (next in streamsList.indices) focusStream(next) } }; true }
                else -> false
            } } }
        }
    }
    init {
        orientation = VERTICAL; tag = "sports-panel"; visibility = GONE
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(22f).apply { text = "Sports"; typeface = AppFont.face(true) }, LayoutParams(0, -1, 1f))
        listOf(dateButton, todayButton, refreshButton, teamsButton).forEach { header.addView(it, LayoutParams(dp(if (it === teamsButton) 140 else 100), dp(34)).apply { marginStart = dp(8) }) }
        addView(header, LayoutParams(-1, dp(42)))
        val headerButtons = listOf(dateButton, todayButton, refreshButton, teamsButton)
        headerButtons.forEachIndexed { index, view -> view.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (index == 0) leave() else headerButtons[index - 1].requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && index < headerButtons.lastIndex) headerButtons[index + 1].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusGames(); true }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        } }
        hero.background = GuideTheme.glass(resources.displayMetrics.density); hero.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        hero.addView(crests, FrameLayout.LayoutParams(dp(310), -1, Gravity.END))
        hero.addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5091019.toInt(), 0xB0091019.toInt(), 0x00091019)) }, FrameLayout.LayoutParams(-1, -1))
        heroContent.addView(heroBadge); heroContent.addView(heroTitle); heroContent.addView(heroScore); heroContent.addView(heroDetail)
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1)); hero.addView(credit, FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM).apply { marginEnd = dp(8) })
        addView(hero, LayoutParams(-1, dp(180)))
        addView(progress, LayoutParams(-1, dp(3))); addView(status, LayoutParams(-1, dp(23)))
        listOf(findStreams, chooseStreams, reminder).forEach { actionRow.addView(it, LayoutParams(-2, dp(36)).apply { marginEnd = dp(10) }) }
        addView(actionRow, LayoutParams(-1, dp(44)))
        val actions = listOf(findStreams, chooseStreams, reminder)
        actions.forEach { view -> view.setOnKeyListener { _, key, event ->
            val visible = actions.filter { it.isShown && it.isEnabled }; val index = visible.indexOf(view)
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN && index > 0) visible[index - 1].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && index < visible.lastIndex) visible[index + 1].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) dateButton.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        } }
        val resultsBar = LinearLayout(context).apply { addView(scoresButton, LayoutParams(dp(140), dp(34))); addView(filtersButton, LayoutParams(dp(140), dp(34)).apply { marginStart = dp(8) }) }
        addView(resultsBar); listOf(scoresButton, filtersButton).forEach { view -> view.setOnKeyListener { _, key, event -> when(key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN && streamsList.isNotEmpty()) focusStream(0); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) dateButton.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN && view === filtersButton) scoresButton.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && filtersButton.isShown) filtersButton.requestFocus(); true }
            else -> false
        } } }
        addView(empty, LayoutParams(-1, dp(32))); empty.visibility = INVISIBLE
        rows.adapter = cards; streams.adapter = streamAdapter
        addView(rows, LayoutParams(-1, 0, 1f)); addView(streams, LayoutParams(-1, 0, 1f))
        heroTitle.text = "Your sports"; heroDetail.text = "Live games, final scores and upcoming matchups."; findStreams.isEnabled = false
    }
    private fun tileKey(position: Int) = games.getOrNull(position)?.key ?: REDZONE
    private fun cardKeys(holder: GameCard) { holder.box.setOnKeyListener { _, key, event ->
        if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false else {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) when(key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (at % COLUMNS == 0) leave() else focusAt(at - 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (at % COLUMNS < COLUMNS - 1 && at + 1 < cards.itemCount) focusAt(at + 1)
                    KeyEvent.KEYCODE_DPAD_UP -> if (at < COLUMNS) dateButton.requestFocus() else focusAt(at - COLUMNS)
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at + COLUMNS < cards.itemCount) focusAt(at + COLUMNS)
                }
            }; true
        }
    } }
    private fun openGame(game: SportsGame) {
        remembered = game.key; actionsOpen = true; showHero(game); focused(game)
        rows.isVisible = false; actionRow.isVisible = true
        status.text = if (game.state == "post" && !findStreams.isEnabled) "Replay unavailable in this playlist’s catch-up window." else "Back returns to games"
        if (!findStreams.requestFocus()) dateButton.requestFocus()
    }
    private fun bindRedZone(holder: GameCard) {
        holder.special.isVisible = true; holder.box.contentDescription = "NFL RedZone. Find channels in your playlist"; holder.box.tag = REDZONE; holder.badge.text = "NFL"; holder.title.text = "NFL RedZone"; holder.detail.text = "Every touchdown, one destination"
        holder.logos.forEach { cardLogos.bind(it, "") }; thumbnails.bind(holder.photo, ""); holder.photo.visibility = GONE
        holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x88303943.toInt())
        fun ink(active: Boolean) { holder.title.setTextColor(if(active) Color.BLACK else Color.WHITE); holder.detail.setTextColor(if(active) Color.BLACK else Color.LTGRAY); holder.badge.setTextColor(if(active) Color.BLACK else GuideTheme.current.accent) }
        ink(holder.box.hasFocus()); holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) { remembered = REDZONE; showRedZoneHero() } }
        holder.box.setOnClickListener { remembered = REDZONE; actionsOpen = true; showRedZoneHero(); rows.isVisible = false; actionRow.isVisible = true; findStreams.requestFocus() }
        cardKeys(holder)
    }
    private fun showRedZoneHero() {
        featured = null; removeCallbacks(loadHeroArt); pictures.bind(backdrop, ""); credit.text = ""; crestViews.forEach { logos.bind(it, "") }; crests.isVisible = false
        heroBadge.text = "NFL COVERAGE"; heroTitle.text = "NFL RedZone"; heroScore.text = "Every touchdown"; heroDetail.text = "Find RedZone in your playlist. Availability follows the provider’s schedule."
        findStreams.text = "Find RedZone channels"; findStreams.isEnabled = true; chooseStreams.isVisible = false; reminder.visibility = GONE
    }
    private fun badge(game: SportsGame) = when { game.live(System.currentTimeMillis()) -> "● LIVE"; game.state == "post" -> "FINAL"; game.state == "pre" -> "UPCOMING"; else -> "SAVED · REFRESH" }
    private fun showHero(game: SportsGame) {
        val changed = featured?.key != game.key; featured = game; artwork.resume()
        heroBadge.text = SportsLeagues.label(game.leagueId).uppercase() + "  ·  " + badge(game); heroBadge.setTextColor(if (game.live(System.currentTimeMillis())) 0xFF8CE5C1.toInt() else GuideTheme.current.accent)
        heroTitle.text = game.title; heroScore.text = if (scoresHidden(game)) "Score hidden" else if (game.state == "pre") GuideClock.time(game.start) else if (game.scores.isEmpty()) "Score unavailable" else game.teams.joinToString("   :   ") { game.scores[it.id] ?: "–" }
        heroDetail.text = GuideClock.sportsDate(game.start) + "\n" + statusText(game) + " · " + game.networks.joinToString(" · ").ifBlank { "Broadcast to be announced" }

        findStreams.isEnabled = game.state != "post" || SportsReplays.eligible(game, archiveWindowDays, System.currentTimeMillis()); findStreams.text = when (game.state) { "pre" -> "Find coverage"; "post" -> "Find replay"; else -> "Watch best live channel" }
        chooseStreams.isVisible = actionsOpen && game.live(System.currentTimeMillis())
        updateReminder(game)
        if (changed || scoresHidden(game)) { pictures.bind(backdrop, ""); credit.text = ""; crests.isVisible = !preview }
        game.teams.forEachIndexed { i, team -> logos.bind(crestViews[i], game.logos[team.id].orEmpty().ifBlank { team.logo }) }
        if (changed || scoresHidden(game)) removeCallbacks(loadHeroArt); if (changed && !scoresHidden(game)) postDelayed(loadHeroArt, 150)
    }
    private fun showArtwork(key: String, url: String, attribution: String) {
        if (url.isBlank()) return
        gameArt[key] = url; while (gameArt.size > 64) gameArt.remove(gameArt.keys.first())
        redrawVisiblePosters()
        if (preview || featured?.key != key || featured?.let(::scoresHidden) == true) return
        pictures.bind(backdrop, url) { image ->
            if (featured?.key == key && !preview) {
                crests.isVisible = image == null
                credit.text = if (image != null && attribution.isNotBlank()) "Photo: $attribution" else ""
            }
        }
    }
    fun previewMode(enabled: Boolean) {
        if (preview == enabled) return
        preview = enabled; heroContent.setPadding(dp(20), dp(12), dp(if (enabled) 330 else 210), dp(12)); crests.isVisible = !enabled && backdrop.drawable == null
        if (enabled) pictures.bind(backdrop, "")
        if (!enabled) featured?.takeUnless(::scoresHidden)?.let { artwork.request(it) }
    }
    fun loading(message: String) { loadingGames = games.isEmpty(); progress.isVisible = true; status.text = message; if (games.isEmpty()) { empty.text = "Loading scores and schedules…"; empty.isVisible = true } }
    fun submit(next: List<SportsGame>, followed: Set<String>, selected: String?, message: String, scanning: Boolean) {
        this.followed = followed; loadingGames = scanning && next.isEmpty(); hasSchedule = hasSchedule || next.isNotEmpty() || !scanning
        val showRedZone = hasSchedule && "NFL" in enabledLeagues
        if (scope != selected) { scope = selected; remembered = null; showGames() }
        dateButton.text = SportsSchedule.selectedDate(selected)?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: "Choose date"
        if (!watchMode && !actionsOpen) { progress.visibility = if(scanning) VISIBLE else INVISIBLE; status.text = message }
        empty.text = if (scanning) "Loading scores and schedules…" else "No games on this date. Choose another date or update Sports settings."
        empty.visibility = if(next.isEmpty() && !watchMode && !actionsOpen) VISIBLE else INVISIBLE
        if (games != next || displayedRedZone != showRedZone) {
            val old = games; val hadFocus = rows.hasFocus(); val input = inputEpoch; val anchor = remembered
            val oldKeys = old.map { it.key } + if (displayedRedZone) listOf(REDZONE) else emptyList()
            val newKeys = next.map { it.key } + if (showRedZone) listOf(REDZONE) else emptyList()
            if (hadFocus && anchor !in newKeys) { rows.isFocusableInTouchMode = true; rows.requestFocus() }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldKeys.size
                override fun getNewListSize() = newKeys.size
                override fun areItemsTheSame(a: Int, b: Int) = oldKeys[a] == newKeys[b]
                override fun areContentsTheSame(a: Int, b: Int) = old.getOrNull(a) == next.getOrNull(b)
            })
            games = next; displayedRedZone = showRedZone; diff.dispatchUpdatesTo(cards)
            val chosen = next.firstOrNull { it.key == remembered } ?: featured(next, followed, System.currentTimeMillis())
            if (remembered == REDZONE && "NFL" in enabledLeagues || chosen == null && "NFL" in enabledLeagues) showRedZoneHero()
            else if (chosen != null) showHero(chosen)
            else { featured = null; pictures.bind(backdrop, ""); crestViews.forEach { logos.bind(it, "") }; heroTitle.text = "Your sports"; heroScore.text = ""; heroBadge.text = "SCORES & SCHEDULES"; heroDetail.text = "Choose your leagues and teams in Sports settings."; findStreams.isEnabled = false }
            if (hadFocus && cards.itemCount > 0) rows.postOnAnimation { if (input == inputEpoch && !watchMode && !actionsOpen && !rows.hasFocus()) focusAt(newKeys.indexOf(anchor).coerceAtLeast(0)) }
        } else if (remembered != REDZONE) featured?.let { current -> next.firstOrNull { it.key == current.key }?.let(::showHero) }
        if (pendingEnter && cards.itemCount > 0 && !watchMode && !actionsOpen) { pendingEnter = false; val input = pendingInput; post { if (input == inputEpoch && isShown) focusGames() } }
    }
    @SuppressLint("NotifyDataSetChanged")
    fun matching(game: SportsGame) { actionsOpen = false; actionRow.visibility = GONE; replayMode = game.state == "post"; replays = emptyList(); streamEntry = true; streamInput = inputEpoch; streamsList = emptyList(); streamAdapter.notifyDataSetChanged(); empty.visibility = INVISIBLE; showHero(game); watchMode = true; matchingKey = game.key; progress.isIndeterminate = true; rows.isVisible = false; streams.isVisible = true; scoresButton.isVisible = true; filtersButton.isVisible = !replayMode; progress.isVisible = true; status.text = if (replayMode) "Finding provider replays…" else "Finding playlist streams…"; presentationChanged?.invoke() }
    fun matchingProgress(game: SportsGame, message: String, checked: Int = 0, total: Int = 0) {
        if (!watchMode || matchingKey != game.key) return
        progress.isVisible = true; progress.isIndeterminate = total <= 0
        if (total > 0) { progress.max = total; progress.progress = checked }
        status.text = message
    }
    fun finishTuning() { progress.visibility = INVISIBLE }
    @SuppressLint("NotifyDataSetChanged")
    fun showStreams(game: SportsGame, channels: List<Channel>, label: (Channel) -> String) {
        if (!watchMode || matchingKey != game.key) return
        val same = streamsList == channels; val hadFocus = streams.hasFocus(); streamsList = channels; streamLabels = label
        if (!same) streamAdapter.notifyDataSetChanged(); progress.visibility = INVISIBLE; status.text = "${channels.size} streams · ${if (game.coverageComplete) "OK Preview" else "Partial guide results · Retry to check more"} · ＋ Add to multiview · Hold Back Full screen"
        empty.text = if (game.state == "post") "This game has ended." else "No channel matched these filters yet. Try Stream filters or refresh your guide."
        empty.visibility = if(channels.isEmpty()) VISIBLE else INVISIBLE; val enter = streamEntry && streamInput == inputEpoch; streamEntry = false; if (enter && !hadFocus) { if (channels.isNotEmpty()) focusStream(0) else filtersButton.requestFocus() }
    }
    @SuppressLint("NotifyDataSetChanged")
    fun showReplays(game: SportsGame, choices: List<SportsReplay>) {
        if (!watchMode || matchingKey != game.key) return
        replays = choices; streamsList = choices.map { it.channel }
        streamLabels = { channel -> choices.first { it.channel.id == channel.id }.let { "${channel.name} · Watch replay\nExpires ${GuideClock.dayTime(it.expires)}" } }
        streamAdapter.notifyDataSetChanged(); progress.visibility = INVISIBLE
        status.text = "${choices.size} replays · Quality may differ from live · Back returns to games"
        empty.text = "No matching provider archive found. It may have expired or the provider may not archive this broadcast."
        empty.visibility = if(choices.isEmpty()) VISIBLE else INVISIBLE
        val enter = streamEntry && streamInput == inputEpoch; streamEntry = false
        if (enter && !streams.hasFocus()) { if (choices.isNotEmpty()) focusStream(0) else scoresButton.requestFocus() }
    }
    private fun showGames() { actionsOpen = false; actionRow.visibility = GONE; progress.visibility = INVISIBLE; empty.visibility = INVISIBLE; replayMode = false; replays = emptyList(); watchMode = false; matchingKey = null; streams.isVisible = false; rows.isVisible = true; scoresButton.isVisible = false; filtersButton.isVisible = false; presentationChanged?.invoke() }
    fun home() { remembered = null; showGames(); pendingEnter = true; pendingInput = inputEpoch }
    fun back(): Boolean { if (!watchMode && !actionsOpen) return false; showGames(); focusGames(); return true }
    fun focusTeams() { ++focusEpoch; teamsButton.requestFocus() }
    fun focusHero() = focusGames()
    fun focusGames() {
        when {
            loadingGames && games.isEmpty() && !watchMode -> { pendingEnter = true; pendingInput = inputEpoch; dateButton.requestFocus() }
            watchMode -> { pendingEnter = false; if (streamsList.isNotEmpty()) focusStream(0) else scoresButton.requestFocus() }
            actionsOpen -> findStreams.takeIf { it.isEnabled }?.requestFocus() ?: dateButton.requestFocus()
            cards.itemCount > 0 -> { pendingEnter = false; val key = remembered ?: featured?.key ?: REDZONE; focusAt((0 until cards.itemCount).firstOrNull { tileKey(it) == key } ?: 0) }
            else -> { pendingEnter = true; pendingInput = inputEpoch; dateButton.requestFocus() }
        }
    }
    fun matchingRedZone() {
        remembered = REDZONE; actionsOpen = false; actionRow.visibility = GONE; replayMode = false; replays = emptyList(); watchMode = true; matchingKey = REDZONE
        showRedZoneHero(); rows.isVisible = false; streams.isVisible = true; val oldCount = streamsList.size; streamsList = emptyList(); streamAdapter.notifyItemRangeRemoved(0, oldCount)
        scoresButton.isVisible = true; filtersButton.isVisible = false; empty.visibility = INVISIBLE; progress.isIndeterminate = true; progress.isVisible = true
        status.text = "Finding RedZone in your playlist…"; presentationChanged?.invoke(); scoresButton.requestFocus()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun showRedZoneStreams(channels: List<Channel>, label: (Channel) -> String) {
        if (!watchMode || matchingKey != REDZONE) return
        showRedZoneHero(); streamsList = channels; streamLabels = label; streamAdapter.notifyDataSetChanged()
        rows.isVisible = false; streams.isVisible = true; scoresButton.isVisible = true; filtersButton.isVisible = true; progress.visibility = INVISIBLE
        status.text = "${channels.size} RedZone channels · Availability follows your provider"
        empty.text = "No RedZone channel matches your playlist and stream filters."; empty.visibility = if(channels.isEmpty()) VISIBLE else INVISIBLE
        presentationChanged?.invoke(); if (channels.isEmpty()) scoresButton.requestFocus() else focusStream(0)
    }
    private fun focusAt(position: Int) { focusRow(rows, position) }
    private fun focusStream(position: Int) { focusRow(streams, position) }
    private fun focusRow(list: RecyclerView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0)) return
        val token = ++focusEpoch
        if (list.findViewHolderForAdapterPosition(position) == null) { list.isFocusableInTouchMode = true; list.descendantFocusability = FOCUS_BEFORE_DESCENDANTS; list.requestFocus(); list.descendantFocusability = FOCUS_AFTER_DESCENDANTS; list.scrollToPosition(position) }
        fun attempt(left: Int) { if (isShown && token == focusEpoch) { val item = list.findViewHolderForAdapterPosition(position)?.itemView; if (item != null) { if (list === streams) item.findViewWithTag<View>("sports-stream:${streamsList[position].id}").requestFocus() else item.requestFocus(); TvFocus.center(list, item) } else if (left > 0) list.postOnAnimation { attempt(left - 1) } } }; attempt(15)
    }
    @SuppressLint("NotifyDataSetChanged") fun refreshTheme() { hero.background = GuideTheme.glass(resources.displayMetrics.density); cards.notifyDataSetChanged(); streamAdapter.notifyDataSetChanged(); featured?.let(::showHero) }
    fun pauseImages() { removeCallbacks(loadHeroArt); artwork.pause(); logos.pause(); pictures.pause(); cardLogos.pause(); thumbnails.pause() }
    fun resumeImages() { artwork.resume(); logos.resume(); pictures.resume(); cardLogos.resume(); thumbnails.resume(); redrawVisiblePosters(); featured?.let { showHero(it); if (!scoresHidden(it)) artwork.request(it) } }
    fun closeImages() { removeCallbacks(loadHeroArt); artwork.close(); logos.close(); pictures.close(); cardLogos.close(); thumbnails.close() }
    private fun drawCard(holder: GameCard, game: SportsGame) {
        if (holder.box.tag != game.key) return
        game.teams.forEachIndexed { index, team -> cardLogos.bind(holder.logos[index], game.logos[team.id].orEmpty().ifBlank { team.logo }) }
        thumbnails.bind(holder.photo, if (scoresHidden(game)) "" else gameArt[game.key].orEmpty()) { holder.photo.isVisible = it != null }
    }
    private fun redrawVisiblePosters() {
        for (index in 0 until rows.childCount) {
            val holder = rows.getChildViewHolder(rows.getChildAt(index)) as? GameCard ?: continue
            games.getOrNull(holder.bindingAdapterPosition)?.let { drawCard(holder, it) }
        }
    }
    private class GameCard(val box: LinearLayout, val badge: TextView, val title: TextView, val detail: TextView, val logos: List<ImageView>, val photo: ImageView, val special: TextView) : RecyclerView.ViewHolder(box)
    private class StreamCard(val box: LinearLayout, val play: Button, val together: Button) : RecyclerView.ViewHolder(box)
    companion object {
        private const val COLUMNS = 3
        private const val REDZONE = "sports-redzone"
        fun time(epoch: Long): String = GuideClock.dayTime(epoch)
        fun ordered(games: List<SportsGame>, followed: Set<String>, now: Long): List<SportsGame> = games.sortedWith(priority(followed, now, false))
        private fun priority(followed: Set<String>, now: Long, todayFirst: Boolean) = compareBy<SportsGame> { game ->
            val mine = game.teams.any { it.id in followed }
            if (todayFirst) when { mine && game.live(now) -> 0; game.live(now) -> 1; game.state == "pre" && game.start >= now && SportsSchedule.onDay(game.start, now) -> 2; mine && game.state == "pre" && game.start >= now -> 3; game.state == "pre" && game.start >= now -> 4; else -> 5 }
            else when { mine && game.live(now) -> 0; mine && game.state == "pre" && game.start >= now -> 1; game.live(now) -> 2; game.state == "pre" && game.start >= now -> 3; else -> 4 }
        }.thenBy { if (it.state == "post") -it.start else it.start }
        fun featured(games: List<SportsGame>, followed: Set<String>, now: Long, todayFirst: Boolean = false): SportsGame? = games.minWithOrNull(priority(followed, now, todayFirst))
    }
}

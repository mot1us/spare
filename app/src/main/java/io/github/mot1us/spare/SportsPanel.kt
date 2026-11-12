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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Fixed-size Sports home. Asset/score updates never rebuild navigation or open streams on focus. */
@SuppressLint("SetTextI18n", "ViewConstructor")
class SportsPanel(context: Context, private val button: (String, () -> Unit) -> Button, chooseTeams: () -> Unit,
    private val chooseTeam: (String?) -> Unit, private val select: (SportsGame) -> Unit,
    private val focused: (SportsGame) -> Unit, filters: () -> Unit, private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE) }
    val teamsButton = button("Leagues and teams", chooseTeams).apply { tag = "sports-teams"; contentDescription = "Leagues and teams settings" }
    private val liveButton = button("Live now") { selectSchedule("live") }.apply { tag = "sports-live" }
    private val todayButton = button("Today") { selectSchedule("today") }.apply { tag = "sports-today"; isSelected = true }
    private val dateButton = button("Calendar") { chooseDate() }.apply { tag = "sports-date" }
    private val scheduleOptions = listOf(liveButton, todayButton, dateButton)
    /** The single filter row: when (schedule), which league, then settings. Left/Right walk it in order. */
    private val filterBar = LinearLayout(context).apply { tag = "sports-filter-bar"; gravity = Gravity.CENTER_VERTICAL }
    private val leagueChoices = LinearLayout(context).apply { tag = "sports-league-rail"; gravity = Gravity.CENTER_VERTICAL }
    private val leagueScroll = object : android.widget.HorizontalScrollView(context) {
        // Keep a focused league clear of the edge cues so its neighbour peeks in, showing there is more.
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
            val room = android.graphics.Rect(rect); room.inset(-dp(44), 0); return super.computeScrollDeltaToGetChildRectOnScreen(room)
        }
    }.apply {
        isFocusable = false; isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(leagueChoices)
        setOnScrollChangeListener { _, _, _, _, _ -> updateLeagueEdges() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLeagueEdges() }
    }
    /** Edge cues that appear only when more leagues are hidden off that side of the row. */
    private fun leagueEdge(start: Boolean) = TextView(context).apply {
        tag = if (start) "sports-league-more-left" else "sports-league-more-right"
        text = if (start) "‹" else "›"; textSize = 26f; typeface = AppFont.face(true); setTextColor(Color.WHITE); includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or if (start) Gravity.START else Gravity.END
        setPadding(dp(if (start) 2 else 0), 0, dp(if (start) 0 else 2), dp(2)); isFocusable = false; visibility = INVISIBLE
        contentDescription = if (start) "More leagues to the left" else "More leagues to the right"
        background = GradientDrawable(if (start) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0xFF0E1622.toInt(), 0xE60E1622.toInt(), 0x000E1622))
    }
    private val leagueMoreLeft = leagueEdge(true)
    private val leagueMoreRight = leagueEdge(false)
    private val leagueArea = FrameLayout(context).apply {
        addView(leagueScroll, FrameLayout.LayoutParams(-1, -1))
        addView(leagueMoreLeft, FrameLayout.LayoutParams(dp(36), -1, Gravity.START))
        addView(leagueMoreRight, FrameLayout.LayoutParams(dp(36), -1, Gravity.END))
    }
    private fun updateLeagueEdges() {
        leagueMoreLeft.visibility = if (leagueScroll.canScrollHorizontally(-1)) VISIBLE else INVISIBLE
        leagueMoreRight.visibility = if (leagueScroll.canScrollHorizontally(1)) VISIBLE else INVISIBLE
    }
    private val leagueControls = linkedMapOf<String, Button>()
    private var availableLeagues = emptySet<String>()
    private var selectedLeague: String? = null
    private val leaguePositions = mutableMapOf<String, Pair<String?, android.os.Parcelable?>>()
    private var restoreLeagueLayout: android.os.Parcelable? = null
    private var restoringLeague = false
    var leagueSelected: (String) -> Unit = {}
    private val filtersButton = button("Stream quality", filters).apply { tag = "sports-filters"; visibility = GONE }
    private val actionRow = LinearLayout(context).apply { tag = "sports-actions"; visibility = GONE }
    private val hero = FrameLayout(context).apply { tag = "sports-hero"; clipToOutline = true }
    private val heroSkeleton = SportsLoadingView(context).apply { hero = true; visibility = GONE }
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .65f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val heroBadge = label(9f).apply { tag = "sports-hero-badge"; typeface = AppFont.face(true); includeFontPadding = false }
    private val heroTitle = label(17f).apply { tag = "sports-hero-title"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true); includeFontPadding = false }
    private val heroRecords = label(11f).apply { tag = "sports-hero-records"; maxLines = 1; visibility = GONE }
    private val heroScore = label(18f).apply { tag = "sports-hero-score"; typeface = AppFont.face(true); includeFontPadding = false }
    private val heroDetail = label(10f).apply { tag = "sports-hero-detail"; maxLines = 2; setTextColor(Color.LTGRAY); includeFontPadding = false; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val credit = label(8f).apply { maxLines = 1; gravity = Gravity.END; setTextColor(Color.LTGRAY) }
    private val heroContent = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(5), dp(182), dp(5)) }
    private val crests = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x002E4060, 0x8845688F.toInt(), 0xAA122136.toInt())) }
    private val crestViews = List(2) { index -> ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; alpha = .88f; rotation = if (index == 0) -8f else 8f
        crests.addView(this, FrameLayout.LayoutParams(dp(125), dp(125), if (index == 0) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.END).apply { topMargin = dp(if (index == 0) -12 else 0); bottomMargin = dp(if (index == 1) -12 else 0) })
    } }
    private val findStreams = button("Find streams") { if (remembered == REDZONE) watchRedZone() else featured?.let { if (it.live(System.currentTimeMillis())) watchBest(it) else select(it) } }.apply { tag = "sports-watch" }
    private val reminder = button("Remind me") { featured?.takeIf { it.state == "pre" }?.let { toggleReminder(it); updateReminder(it) } }.apply { tag = "sports-reminder"; isEnabled = false }
    var findRedZone: () -> Unit = {}
    var watchRedZone: () -> Unit = { findRedZone() }
    val redZoneResults get() = watchMode && matchingKey == REDZONE
    val redZoneFocused get() = remembered == REDZONE
    private val chooseStreams = button("Choose stream") { if (remembered == REDZONE) findRedZone() else featured?.let(select) }.apply { tag = "sports-choose-stream" }
    private var actionsOpen = false
    var hasReminder: (SportsGame) -> Boolean = { false }
    var toggleReminder: (SportsGame) -> Unit = {}
    var watchBest: (SportsGame) -> Unit = {}
    var playOnSelection: () -> Boolean = { false }
    private var streamSummaries = emptyMap<String, String>()
    private var cardReplays = emptyMap<String, SportsReplay>()
    private var replayChecking: String? = null
    private var replayChecked = emptySet<String>()
    private fun cardStream(game: SportsGame): String = if (scorePresentation(game).state != "post") streamSummaries[game.key].orEmpty()
        else if (hasReplay(game)) cardReplays.getValue(game.key).let { "▶ Play replay · ${it.channel.name}\nCatch-up confirmed" }
        else if (replayChecking == game.key) "Checking catch-up…"
        else if (game.key in replayChecked) "Replay not found"
        else if (SportsReplays.eligible(game, archiveWindowDays, System.currentTimeMillis())) "Catch-up check pending"
        else "No catch-up available"
    fun updateReplayDiscovery(checkingKey: String?, checked: Set<String>) {
        if (replayChecking == checkingKey && replayChecked == checked) return
        replayChecking = checkingKey; replayChecked = checked.toSet()
        refreshCardStreams(); featured?.let(::refreshHeroBadge)
    }
    fun updateCardReplays(replays: Map<String, SportsReplay>) {
        if (cardReplays == replays) return
        cardReplays = replays.toMap(); refreshCardStreams(); featured?.let(::refreshHeroBadge)
    }
    fun confirmReplay(game: SportsGame, replay: SportsReplay) = updateCardReplays(cardReplays + (game.key to replay))
    private fun refreshCardStreams() {
        for (i in 0 until rows.childCount) {
            val holder = rows.getChildViewHolder(rows.getChildAt(i)) as? GameCard ?: continue
            val game = games.getOrNull(holder.bindingAdapterPosition) ?: continue
            val next = cardStream(game)
            if (holder.stream.text.toString() != next) holder.stream.text = next
            styleBadge(holder.badge, game, holder.box.hasFocus())
            describeCard(holder, game)
        }
    }
    private fun updateReminder(game: SportsGame) {
        reminder.isVisible = actionsOpen && game.state == "pre"
        reminder.isEnabled = game.state == "pre"
        reminder.text = if (hasReminder(game)) "Reminder set ✓" else "Remind me"
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; tag = "sports-loading"; visibility = INVISIBLE }
    private val headerTitle = label(13f).apply { typeface = AppFont.face(true); text = "Sports" }
    private val contextLine = label(10f).apply { tag = "sports-context"; setTextColor(Color.LTGRAY); maxLines = 1 }
    private val streamsHeader = LinearLayout(context).apply { tag = "sports-stream-header"; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
    private val status = label(10f).apply { tag = "sports-status"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.LTGRAY) }
    private val matchNotice = label(13f).apply { tag = "sports-match-notice"; visibility = GONE; maxLines = 2; setPadding(dp(10), dp(6), dp(10), dp(6)); background = GuideTheme.glass(resources.displayMetrics.density) }
    private val empty = label(13f).apply { tag = "sports-empty"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    private var scrolling = false
    private val rows = RecyclerView(context).apply { layoutManager = object : GridLayoutManager(context, COLUMNS) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            // Pre-build half a screen of cards only while scrolling. When a league or date
            // replaces every card, laying out the extra rows doubled the work of that frame.
            val extra = if (scrolling) height / 2 else 0
            extraLayoutSpace[0] = extra; extraLayoutSpace[1] = extra
        }
    }; itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-games"; setItemViewCacheSize(8); isFocusableInTouchMode = true
        setHasFixedSize(true)
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { scrolling = newState != RecyclerView.SCROLL_STATE_IDLE }
        })
    }
    private val gridFocus = SportsGridFocus(rows)
    private val gridArea = FrameLayout(context)
    private val skeleton = SportsLoadingView(context).apply { tag = "sports-skeleton"; visibility = GONE }
    private val scrollHint = label(10f).apply { tag = "sports-scroll-hint"; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; visibility = INVISIBLE }
    private fun updateScrollHint() {
        val layout = rows.layoutManager as GridLayoutManager
        val up = rows.canScrollVertically(-1); val down = rows.canScrollVertically(1)
        scrollHint.visibility = if (!watchMode && !initialReveal && rows.isVisible && (up || down)) VISIBLE else INVISIBLE
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0) + 1
        val last = (layout.findLastVisibleItemPosition() + 1).coerceAtLeast(first).coerceAtMost(cards.itemCount)
        scrollHint.text = listOfNotNull(if (up) "↑ Scroll up" else null, "$first–$last of ${cards.itemCount}", if (down) "Scroll down ↓" else null).joinToString("    ·    ")
    }
    private val streams = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; preserveFocusAfterLayout = false; overScrollMode = OVER_SCROLL_NEVER; tag = "sports-streams"; visibility = GONE }
    /** Calm stand-in rows while channels are searched, instead of an empty list under a jumping counter. */
    private val streamsSkeleton = SportsLoadingView(context).apply { streamRows = true; tag = "sports-streams-skeleton"; visibility = GONE }
    private val streamsArea = FrameLayout(context).apply { visibility = GONE }
    private var searchProgress = 0
    private fun setSearching(on: Boolean) { streamsSkeleton.isVisible = on; if (!on) searchProgress = 0 }
    private val artworkFiles = ArtworkFiles(context)
    private val logos = LogoLoader(512, 6 * 1024 * 1024, artworkFiles)
    private val cardLogos = LogoLoader(384, 6 * 1024 * 1024, artworkFiles)
    private val pictures = LogoLoader(1280, 8 * 1024 * 1024, artworkFiles)
    private val artwork = SportsArtwork(context) { key, url, attribution -> showArtwork(key, url, attribution) }
    var archiveWindowDays = 0
        set(value) { if (field != value) { field = value; refreshCardStreams() } }
    private var hideScores = false
    var scorePresentation: (SportsGame) -> SportsGame = { it }
    private fun artworkHidden(game: SportsGame) = hideScores && game.state == "post"
    fun scoresHidden(game: SportsGame) = hideScores && scorePresentation(game).state == "post"
    fun scoreText(game: SportsGame) = if (scoresHidden(game)) game.title else SportsText.score(scorePresentation(game), false)
    fun statusText(game: SportsGame) = if (scoresHidden(game)) "Completed" else SportsText.status(scorePresentation(game), false, System.currentTimeMillis())
    fun refreshScores() {
        for (i in 0 until rows.childCount) {
            val holder = rows.getChildViewHolder(rows.getChildAt(i)) as? GameCard ?: continue
            val game = games.getOrNull(holder.bindingAdapterPosition) ?: continue
            holder.game = game; renderCardText(holder, game, holder.box.hasFocus())
        }
        featured?.let(::refreshHeroScores)
    }
    fun setSpoilers(hidden: Boolean) {
        if (hideScores == hidden) return
        hideScores = hidden; cards.notifyItemRangeChanged(0, cards.itemCount); featured?.let { showHero(it); if (!artworkHidden(it)) artwork.request(it) }
    }
    private var replayMode = false
    private var replays = emptyList<SportsReplay>()
    var watchReplay: (SportsReplay) -> Unit = {}
    private var games = emptyList<SportsGame>()
    private var gameKeys = emptyList<String>()
    private var gameIds = LongArray(0)
    private var gamePositions = emptyMap<String, Int>()
    private var cardsClosed = false
    @Volatile private var cardsRequest = 0
    var cardsReady: () -> Unit = {}
    private data class CardUpdate(val request: Int, val before: List<SportsGame>, val beforeKeys: List<String>, val next: List<SportsGame>, val redZone: Boolean, val first: Boolean, val hadFocus: Boolean, val input: Int, val focus: Int, val anchor: String?)
    private val cardDiffWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-card-diff") }
    private val cardUpdates by lazy { LatestTask<CardUpdate>(cardDiffWorker) { update ->
        if (update.request == cardsRequest) {
            val keys = update.next.map { it.key } + if (update.redZone) listOf(REDZONE) else emptyList()
            val ids = LongArray(keys.size) { Channel.stableLongId(keys[it]) }
            val positions = keys.withIndex().associate { it.value to it.index }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = update.beforeKeys.size
                override fun getNewListSize() = keys.size
                override fun areItemsTheSame(a: Int, b: Int) = update.beforeKeys[a] == keys[b]
                override fun areContentsTheSame(a: Int, b: Int) = update.before.getOrNull(a) == update.next.getOrNull(b)
                override fun getChangePayload(a: Int, b: Int): Any? {
                    val before = update.before.getOrNull(a); val next = update.next.getOrNull(b)
                    return if (before != null && next != null && before.teams == next.teams && before.logos == next.logos) SCORE_PAYLOAD else null
                }
            })
            post { if (!cardsClosed && update.request == cardsRequest) publishCards(update, keys, ids, positions, diff) }
        }
    } }
    private var featured: SportsGame? = null
    private var remembered: String? = null
    private var followed = emptySet<String>()
    private var enabledLeagues = SportsLeagues.byId.keys
    private val loadHeroArt = Runnable { featured?.takeUnless(::artworkHidden)?.let { game ->
        artwork.request(game)
        val at = gamePositions[game.key] ?: -1
        if (at >= 0) artwork.prefetch(games.asSequence().drop(at + 1).filterNot(::artworkHidden).take(2).toList())
    } }
    fun setLeagues(leagues: Set<String>) { enabledLeagues = leagues }
    fun setAvailableLeagues(leagues: Set<String>) {
        if (availableLeagues == leagues) return
        val focus = leagueChoices.findFocus()?.tag
        availableLeagues = leagues.toSet(); leagueChoices.removeAllViews(); leagueControls.clear()
        SportsLeagues.all.filter { it.id in leagues }.forEach { league ->
            val control = button(league.name) { leagueSelected(league.id) }.apply {
                tag = "sports-league:${league.id}"; textSize = 11f; isSelected = league.id == selectedLeague
                text = (if (isSelected) "✓  " else "") + league.name
                contentDescription = league.name + if (isSelected) ", selected" else ""
                leagueChoices.addView(this, LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
            }
            leagueControls[league.id] = control
        }
        leagueControls.values.forEach(::barKeys)
        if (focus != null) leagueChoices.findViewWithTag<View>(focus)?.requestFocus()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun setLeagueFilter(league: String?) {
        if (selectedLeague == league) return
        selectedLeague?.let { leaguePositions[it] = remembered to rows.layoutManager?.onSaveInstanceState() }
        val hadFocus = rows.hasFocus()
        gridFocus.cancel(); ++focusEpoch; ++cardsRequest; removeCallbacks(settleGameFocus); removeCallbacks(loadHeroArt)
        selectedLeague = league; remembered = leaguePositions[league]?.first; restoreLeagueLayout = leaguePositions[league]?.second; restoringLeague = true
        games = emptyList(); gameKeys = emptyList(); gameIds = LongArray(0); gamePositions = emptyMap(); displayedRedZone = false; cards.notifyDataSetChanged()
        featured = null; initialReveal = true; loadingGames = true; pendingEnter = false
        showGames(); loading("Loading ${league?.let(SportsLeagues::label).orEmpty()}…")
        leagueControls.forEach { (id, control) ->
            control.isSelected = id == league; control.text = (if (control.isSelected) "✓  " else "") + SportsLeagues.label(id)
            control.contentDescription = SportsLeagues.label(id) + if (control.isSelected) ", selected" else ""
        }
        if (hadFocus) focusLeagueFilter()
    }
    private fun focusSelectedLeague() {
        val target = leagueControls[selectedLeague] ?: leagueControls.values.firstOrNull() ?: teamsButton
        target.requestFocus(); leagueScroll.post { leagueScroll.requestChildRectangleOnScreen(target, android.graphics.Rect(0, 0, target.width, target.height), false) }
    }
    private fun barControls(): List<View> = scheduleOptions + leagueControls.values + teamsButton
    /** Bar: Left/Right move along the row (Left at the start leaves to app navigation), Down enters the games. */
    private fun barKeys(view: View) = view.setOnKeyListener { _, key, event ->
        if (key !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val row = barControls(); val index = row.indexOf(view)
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (index <= 0) leave() else row[index - 1].requestFocus()
                KeyEvent.KEYCODE_DPAD_RIGHT -> row.getOrNull(index + 1)?.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN -> if (watchMode && filtersButton.isShown) filtersButton.requestFocus() else focusGames()
            }
        }
        true
    }
    fun focusLeagueFilter() { gridFocus.cancel(); ++focusEpoch; focusSelectedLeague() }
    private fun selectSchedule(value: String) {
        showGames()
        chooseTeam(value)
    }
    fun resetLeagueBrowse() { leaguePositions.clear(); restoreLeagueLayout = null; remembered = null; pendingEnter = false; showGames(); focusLeagueFilter() }
    private var focusedGame: SportsGame? = null
    private val settleGameFocus = Runnable {
        val key = focusedGame?.key
        val current = gamePositions[key]?.let(games::getOrNull)
        current?.takeIf { rows.findFocus()?.tag == it.key && browsingGames && isShown }?.let {
            focusedGame = it; showHero(it); focused(it)
        }
    }
    private fun focusGame(game: SportsGame) {
        remembered = game.key; focusedGame = game
        removeCallbacks(settleGameFocus); removeCallbacks(loadHeroArt)
        // Highlight immediately; only refresh hero/metadata once remote repeats settle.
        postDelayed(settleGameFocus, 90)
    }
    fun visibleGameKeys(): Set<String> {
        val layout = rows.layoutManager as GridLayoutManager
        val first = layout.findFirstVisibleItemPosition(); val last = layout.findLastVisibleItemPosition()
        if (first < 0 || last < first) return games.take(COLUMNS).mapTo(hashSetOf()) { it.key }
        return (first..last.coerceAtMost(first + COLUMNS * 2 - 1)).mapNotNull { games.getOrNull(it)?.key }.toSet()
    }
    private var displayedRedZone = false
    private var redZoneSummary = "Finding RedZone…"
    fun updateRedZone(channels: List<Channel>, quality: Map<String, VideoInfo>) {
        val summary = channels.firstOrNull()?.let { it.name + "\n" + it.qualityLabel(quality[it.id]) } ?: "Select to check provider guide"
        if (summary == redZoneSummary) return
        redZoneSummary = summary
        for (i in 0 until rows.childCount) (rows.getChildViewHolder(rows.getChildAt(i)) as? GameCard)?.takeIf { it.box.tag == REDZONE }?.let { it.stream.text = summary }
        if (remembered == REDZONE) heroDetail.text = summary
    }
    private var initialReveal = true
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
    val browsingGames get() = !watchMode && !actionsOpen
    fun showingStreams(game: SportsGame) = watchMode && matchingKey == game.key
    var watchChannel: (Channel) -> Unit = {}
    var secondChannel: (Channel) -> Unit = {}
    var presentationChanged: (() -> Unit)? = null
    private val cards = object : RecyclerView.Adapter<GameCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = games.size + if (displayedRedZone) 1 else 0
        override fun getItemId(position: Int) = gameIds[position]
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameCard {
            val cardHeight = dp(CARD_HEIGHT_DP)
            val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); isFocusableInTouchMode = true; isClickable = true; layoutParams = RecyclerView.LayoutParams(-1, cardHeight).apply { marginEnd = dp(8); bottomMargin = dp(8) } }
            val badge = label(8f).apply { typeface = AppFont.face(true); gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine() }
            val title = LinearLayout(context).apply { orientation = VERTICAL }
            val teamNames = List(2) { label(12f).apply { setSingleLine(); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) } }
            val teamRecords = List(2) { label(9f).apply { setSingleLine(); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, 0) } }
            repeat(2) { index -> title.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(teamNames[index], LayoutParams(0, -1, 1f)); addView(teamRecords[index], LayoutParams(-2, -1))
            }, LayoutParams(-1, dp(17))) }
            val detail = label(9f).apply { setSingleLine(); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END }
            val date = label(9f).apply { setSingleLine(); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END }
            val stream = label(9f).apply { setSingleLine(); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END }
            val poster = FrameLayout(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF172B40.toInt(), 0xFF344258.toInt())); clipChildren = true }
            val scoreLine = LinearLayout(context).apply { gravity = Gravity.CENTER }
            val scoreValues = List(2) { label(12f).apply { gravity = Gravity.CENTER; setSingleLine(); includeFontPadding = false; typeface = AppFont.face(true) } }
            scoreLine.addView(scoreValues[0], LayoutParams(dp(28), -1)); scoreLine.addView(label(10f).apply { text = "–"; gravity = Gravity.CENTER; setTextColor(0xFFB6C6D6.toInt()) }, LayoutParams(dp(8), -1)); scoreLine.addView(scoreValues[1], LayoutParams(dp(28), -1))
            val scoreMessage = label(9f).apply { gravity = Gravity.CENTER; setSingleLine(); typeface = AppFont.face(true); visibility = GONE }
            // Scores keep a fixed slot; a message such as "Score unavailable" widens it instead of clipping.
            val scoreboard = FrameLayout(context).apply { minimumWidth = dp(64); addView(scoreLine, FrameLayout.LayoutParams(-1, -1)); addView(scoreMessage, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER).apply { marginStart = dp(4); marginEnd = dp(4) }) }
            val logos = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(7), dp(3), dp(7), dp(3)) } }
            val crests = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(logos[0], LayoutParams(0, -1, 1f)); addView(logos[1], LayoutParams(0, -1, 1f))
            }
            poster.addView(crests, FrameLayout.LayoutParams(-1, -1))
            val footer = LinearLayout(context).apply {
                tag = "sports-card-footer"; gravity = Gravity.CENTER_VERTICAL
                addView(badge, LayoutParams(dp(54), -1)); addView(scoreboard, LayoutParams(-2, -1)); addView(detail, LayoutParams(0, -1, 1f).apply { marginStart = dp(3) })
            }
            box.addView(poster, LayoutParams(-1, dp(54)).apply { bottomMargin = dp(2) })
            box.addView(title, LayoutParams(-1, dp(34)))
            box.addView(date, LayoutParams(-1, dp(11)))
            box.addView(stream, LayoutParams(-1, dp(14)))
            box.addView(footer, LayoutParams(-1, dp(15)))
            val special = label(24f).apply { text = "REDZONE"; typeface = AppFont.face(true); gravity = Gravity.CENTER; visibility = GONE; setTextColor(0xFFF2D9D9.toInt()) }; poster.addView(special, FrameLayout.LayoutParams(-1, -1))
            return GameCard(box, badge, title, teamNames, teamRecords, scoreValues, scoreLine, scoreMessage, detail, date, stream, logos, special, poster)
        }
        override fun onBindViewHolder(holder: GameCard, position: Int) {
            val game = games.getOrNull(position)
            holder.game = game; holder.textState = null; holder.dateStart = Long.MIN_VALUE; holder.palette = 0; holder.posterPalette = 0
            if (game == null) { bindRedZone(holder); return }
            holder.special.visibility = GONE; holder.box.tag = game.key; holder.badge.tag = "sports-badge:${game.key}"; holder.title.tag = "sports-card-teams:${game.key}"
            holder.poster.tag = "sports-card-banner:${game.key}"; holder.detail.tag = "sports-card-live-detail:${game.key}"; holder.scoreMessage.tag = "sports-card-score-message:${game.key}"; holder.date.tag = "sports-card-date:${game.key}"
            repeat(2) { index -> holder.teamNames[index].tag = "sports-card-team-name:${game.key}:$index"; holder.teamRecords[index].tag = "sports-card-team-record:${game.key}:$index"; holder.scoreValues[index].tag = "sports-card-score:${game.key}:$index" }
            drawCard(holder, game)
            holder.stream.tag = "sports-coverage:${game.key}"
            renderCardText(holder, game, holder.box.hasFocus())
            holder.box.setOnFocusChangeListener { box, active -> lift(box, active); currentCardGame(holder)?.let { current -> renderCardText(holder, current, active); if (active) focusGame(current) } }
            holder.box.setOnClickListener { currentCardGame(holder)?.let { openGame(it) } }; holder.box.setOnLongClickListener { currentCardGame(holder)?.let(::openGameActions); true }
            cardKeys(holder)
        }
        override fun onBindViewHolder(holder: GameCard, position: Int, payloads: MutableList<Any>) {
            val game = games.getOrNull(position)
            if (payloads.isEmpty() || game == null) onBindViewHolder(holder, position)
            else { holder.game = game; renderCardText(holder, game, holder.box.hasFocus()) }
        }
    }
    var streamSource: (Channel) -> Source? = { null }
    var streamFavorites: () -> Set<String> = { emptySet() }
    private val channelLogos = LogoLoader(96, 1024 * 1024, artworkFiles)
    // Use the Guide's actual row renderer so themes, spacing, logos and focus stay consistent.
    private val streamRows = GuideAdapter(context, channelLogos, { streamSource(it) }, { Guide.EMPTY }, { streamFavorites() },
        { GuideTimeline.live(System.currentTimeMillis()) },
        { channel -> if (replayMode) replays.firstOrNull { it.channel.id == channel.id }?.let(watchReplay) else watchChannel(channel) },
        { channel -> if (!replayMode) secondChannel(channel) }, {}, { _, _ -> }, ::focusLeagueFilter,
        { id, direction, _ -> val next = streamsList.indexOfFirst { it.id == id } + direction; if (next < 0) focusStreamsHeader() else if (next in streamsList.indices) focusStream(next) },
        { channel ->
            val title = replays.firstOrNull { replayMode && it.channel.id == channel.id }?.programme?.title ?: featured?.title ?: "NFL RedZone"
            title to streamLabels(channel).replace(channel.name, "").split('·', '\n').map { it.trim().removePrefix("★").trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        })
    private val streamAdapter = object : RecyclerView.Adapter<StreamCard>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = streamsList.size
        override fun getItemId(position: Int) = streamsList[position].rowId
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = StreamCard(streamRows.Row(context))
        override fun onBindViewHolder(holder: StreamCard, position: Int) {
            val channel = streamsList[position]
            holder.play.channel = channel; holder.play.tag = "sports-stream:${channel.id}"; holder.play.appearance()
        }
    }
    init {
        rows.clipChildren = false; rows.clipToPadding = false; gridArea.clipChildren = false
        orientation = VERTICAL; tag = "sports-panel"; visibility = GONE
        // Rail: Up/Down between leagues and Settings, stay at the ends, Left app navigation, Right content.
        // Schedule: only Down moves to content. Picker: Up/Down options, Left or Back closes, Right stays.
        // Cards: adjacent rows/columns, Up from the top row goes to Schedule, Left edge to selected league.
        // Hero actions: horizontal, Left edge returns to scope, Up to Schedule, Down stays.
        // Stream quality: Up Schedule, Down first stream, Left scope. Streams use vertical
        // rows, Up from the first goes to Quality or Schedule, Left scope, Right stays.
        scheduleOptions.forEach { filterBar.addView(it, LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }) }
        filterBar.addView(View(context).apply { setBackgroundColor(0x40FFFFFF) }, LayoutParams(dp(1), dp(22)).apply { marginStart = dp(4); marginEnd = dp(10) })
        filterBar.addView(leagueArea, LayoutParams(0, dp(36), 1f))
        filterBar.addView(teamsButton, LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
        scheduleOptions.forEach(::barKeys); barKeys(teamsButton)
        addView(filterBar, LayoutParams(-1, dp(40)))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerTitle, LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        header.addView(contextLine, LayoutParams(0, -2, 1f))
        addView(header, LayoutParams(-1, dp(20)))
        hero.background = GuideTheme.glass(resources.displayMetrics.density); hero.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        hero.addView(crests, FrameLayout.LayoutParams(dp(205), -1, Gravity.END))
        hero.addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF5091019.toInt(), 0xB0091019.toInt(), 0x00091019)) }, FrameLayout.LayoutParams(-1, -1))
        heroContent.addView(heroBadge); heroContent.addView(heroTitle); heroContent.addView(heroRecords); heroContent.addView(heroScore); heroContent.addView(heroDetail)
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -1)); hero.addView(credit, FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM).apply { marginEnd = dp(8) })
        hero.addView(heroSkeleton, FrameLayout.LayoutParams(-1, -1))
        addView(hero, LayoutParams(-1, dp(104)).apply { bottomMargin = dp(5) })
        listOf(findStreams, chooseStreams, reminder).forEach { actionRow.addView(it, LayoutParams(-2, dp(30)).apply { marginEnd = dp(8) }) }
        hero.addView(actionRow, FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM).apply {
            marginStart = dp(12); bottomMargin = dp(3)
        })
        val actions = listOf(findStreams, chooseStreams, reminder)
        actions.forEach { view -> view.setOnKeyListener { _, key, event ->
            val visible = actions.filter { it.isShown && it.isEnabled }; val index = visible.indexOf(view)
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (index > 0) visible[index - 1].requestFocus() else leave() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && index < visible.lastIndex) visible[index + 1].requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) focusLeagueFilter(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        } }
        streamsHeader.addView(LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(status, LayoutParams(-1, dp(22)))
            addView(progress, LayoutParams(-1, dp(3)))
        }, LayoutParams(0, -1, 1f))
        streamsHeader.addView(filtersButton, LayoutParams(dp(132), dp(32)).apply { marginStart = dp(8) })
        addView(streamsHeader, LayoutParams(-1, dp(36)))
        filtersButton.setOnKeyListener { _, key, event ->
            if (key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> focusLeagueFilter()
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (streamsList.isNotEmpty()) focusStream(0)
                    KeyEvent.KEYCODE_DPAD_LEFT -> leave()
                }
                true
            } else false
        }
        addView(matchNotice, LayoutParams(-1, -2))
        addView(empty, LayoutParams(-1, dp(32))); empty.visibility = GONE
        rows.adapter = cards; streams.adapter = streamAdapter
        // A league switch clears every card; keep enough spare cards that the next league
        // rebinds existing views instead of building ~15 new view trees in one frame.
        rows.recycledViewPool.setMaxRecycledViews(0, 24)
        gridArea.addView(rows, FrameLayout.LayoutParams(-1, -1)); gridArea.addView(skeleton, FrameLayout.LayoutParams(-1, -1)); addView(gridArea, LayoutParams(-1, 0, 1f)); streamsArea.addView(streams, FrameLayout.LayoutParams(-1, -1)); streamsArea.addView(streamsSkeleton, FrameLayout.LayoutParams(-1, -1)); addView(streamsArea, LayoutParams(-1, 0, 1f))
        addView(scrollHint, LayoutParams(-1, dp(16)))
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { updateScrollHint() } })
        rows.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollHint() }
        rows.setOnKeyListener { _, key, event ->
            if (rows.isFocused && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.action == KeyEvent.ACTION_DOWN) moveCard(gridFocus.targetPosition ?: gamePositions[remembered] ?: 0, key)
                true
            } else false
        }

        heroTitle.text = "Your sports"; heroDetail.text = "Live games, final scores and upcoming matchups."; findStreams.isEnabled = false
    }
    private fun focusStreamsHeader() {
        if (filtersButton.isShown) filtersButton.requestFocus() else focusLeagueFilter()
    }
    private fun heroActions(visible: Boolean) {
        actionRow.isVisible = visible
        heroContent.setPadding(dp(14), dp(5), dp(if (preview) 330 else 182), dp(if (visible) 34 else 5))
        heroDetail.maxLines = 1
        heroTitle.maxLines = if (visible) 1 else 2
    }
    private fun tileKey(position: Int) = gameKeys.getOrNull(position) ?: REDZONE
    private fun cardKeys(holder: GameCard) { holder.box.setOnKeyListener { _, key, event ->
        if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false else {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val at = gridFocus.targetPosition ?: holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) moveCard(at, key)
            }; true
        }
    } }
    private fun moveCard(at: Int, key: Int) {
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (at % COLUMNS == 0) { gridFocus.cancel(); leave() } else focusAt(at - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (at % COLUMNS < COLUMNS - 1 && at + 1 < cards.itemCount) focusAt(at + 1)
            KeyEvent.KEYCODE_DPAD_UP -> if (at < COLUMNS) { gridFocus.cancel(); focusLeagueFilter() } else focusAt(at - COLUMNS)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (at + COLUMNS < cards.itemCount) focusAt(at + COLUMNS)
        }
    }
    fun openGameActions(game: SportsGame) { showGames(); openGame(game, direct = false) }
    private fun openGame(game: SportsGame, direct: Boolean = true) {
        remembered = game.key
        if (direct && game.state == "post") {
            cardReplays[game.key]?.takeIf { Catchup.available(it.channel, it.programme, System.currentTimeMillis()) }?.let { focused(game); watchReplay(it); return }
            if (SportsReplays.eligible(game, archiveWindowDays, System.currentTimeMillis())) { focused(game); select(game); return }
        }
        if (direct && playOnSelection() && game.live(System.currentTimeMillis())) { focused(game); watchBest(game); return }
        actionsOpen = true; showHero(game); focused(game)
        heroActions(true)
        status.text = if (game.state == "post" && !findStreams.isEnabled) "No catch-up available for this game." else "Back returns to games"
        if (!findStreams.requestFocus()) focusLeagueFilter()
    }
    private fun bindRedZone(holder: GameCard) {
        holder.palette = 0; holder.posterPalette = 0
        holder.poster.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF472936.toInt(), 0xFF241C2B.toInt()))
        holder.stream.text = redZoneSummary; holder.stream.tag = "sports-redzone-coverage"; holder.title.tag = null; holder.badge.tag = null; holder.badge.background = null; holder.badge.setPadding(0, 0, 0, 0)
        holder.poster.tag = "sports-card-banner:$REDZONE"; holder.detail.tag = null; holder.scoreMessage.tag = null; holder.date.tag = null
        holder.special.isVisible = true; holder.box.contentDescription = "NFL RedZone. OK watches the best feed. Hold OK for stream options"; holder.box.tag = REDZONE; holder.badge.text = "NFL"; holder.detail.text = ""
        holder.scoreLine.visibility = INVISIBLE; holder.scoreMessage.visibility = GONE; holder.scoreMessage.text = ""
        repeat(2) { index ->
            holder.scoreValues[index].text = ""; holder.scoreValues[index].tag = null
            holder.teamNames[index].text = if (index == 0) "NFL RedZone" else "Every touchdown"
            holder.teamNames[index].tag = null; holder.teamRecords[index].text = ""; holder.teamRecords[index].tag = null; holder.teamRecords[index].visibility = GONE
        }
        holder.date.text = "One destination · Every game"
        holder.logos.forEach { cardLogos.bind(it, "") }
        holder.box.background = GuideTheme.background(resources.displayMetrics.density, 0x88303943.toInt())
        fun ink(active: Boolean) { holder.teamNames.forEach { it.setTextColor(if(active) GuideTheme.focusText else Color.WHITE) }; holder.date.setTextColor(if(active) GuideTheme.focusText else Color.LTGRAY); holder.stream.setTextColor(if(active) GuideTheme.focusText else Color.LTGRAY); holder.badge.setTextColor(if(active) GuideTheme.focusText else 0xFFF2D9D9.toInt()) }
        ink(holder.box.hasFocus()); holder.box.setOnFocusChangeListener { box, active -> lift(box, active); ink(active); if (active) { remembered = REDZONE; showRedZoneHero() } }
        holder.box.setOnClickListener { remembered = REDZONE; watchRedZone() }
        holder.box.setOnLongClickListener { remembered = REDZONE; actionsOpen = true; showRedZoneHero(); heroActions(true); chooseStreams.requestFocus(); true }
        cardKeys(holder)
    }
    private fun showRedZoneHero() {
        removeCallbacks(settleGameFocus)
        featured = null; removeCallbacks(loadHeroArt); pictures.bind(backdrop, ""); credit.text = ""; crestViews.forEach { logos.bind(it, "") }; crests.isVisible = false
        heroBadge.text = "NFL COVERAGE"; heroTitle.text = "NFL RedZone"; heroRecords.visibility = GONE; heroScore.text = "Every touchdown"; heroDetail.text = redZoneSummary
        findStreams.text = "Watch RedZone"; findStreams.isEnabled = true; chooseStreams.isVisible = true; reminder.visibility = GONE
    }
    private fun hasReplay(game: SportsGame) = game.state == "post" && cardReplays[game.key]?.let { Catchup.available(it.channel, it.programme, System.currentTimeMillis()) } == true
    private fun badge(game: SportsGame, display: SportsGame = scorePresentation(game)): String {
        return when { display.live(System.currentTimeMillis()) -> "● LIVE"; display.status == SportsScoreDelay.WAITING -> "SCORE DELAY"; display.state == "post" && hasReplay(game) -> "REPLAY"; display.state == "post" -> "FINAL"; display.state == "pre" -> "UPCOMING"; else -> "SAVED SCORE" }
    }
    private fun describeCard(holder: GameCard, game: SportsGame, display: SportsGame = scorePresentation(game)) {
        val names = holder.teamNames.indices.joinToString(". ") { index -> holder.teamNames[index].text.toString() + " " + holder.teamRecords[index].text }
        val scores = if (holder.scoreMessage.isVisible) holder.scoreMessage.text.toString() else holder.scoreValues.joinToString(" to ") { it.text.toString().replace("▸ ", "Possession ") }
        holder.box.contentDescription = badge(game, display) + ". " + names + ". " + scores + ". " + holder.detail.text + ". " + holder.date.text + ". " + holder.stream.text
    }
    private fun styleBadge(view: TextView, game: SportsGame, focused: Boolean, display: SportsGame = scorePresentation(game)) {
        val next = badge(game, display)
        val live = display.live(System.currentTimeMillis()); val replay = display.state == "post" && hasReplay(game)
        val color = when { live -> 0xFF07572E.toInt(); replay -> 0xFF123D68.toInt(); focused -> GuideTheme.focusText; display.status == SportsScoreDelay.WAITING -> 0xFFF1D39A.toInt(); else -> 0xFFC9D6E3.toInt() }
        if (view.text.toString() == next && view.currentTextColor == color) return
        view.text = next
        view.setPadding(if (live || replay) dp(6) else 0, 0, if (live || replay) dp(6) else 0, 0)
        view.background = if (live || replay) GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (live) 0xFFB6F3CE.toInt() else 0xFFC3E2FF.toInt()) } else null
        view.setTextColor(color)
    }
    private fun refreshHeroBadge(game: SportsGame) {
        val discovery = if (scorePresentation(game).state != "post" || hasReplay(game)) "" else when {
            replayChecking == game.key -> " · CHECKING CATCH-UP"
            game.key in replayChecked -> " · REPLAY NOT FOUND"
            else -> ""
        }
        heroBadge.text = SportsLeagues.label(game.leagueId).uppercase() + "  ·  " + badge(game) + discovery
        heroBadge.setTextColor(if (scorePresentation(game).live(System.currentTimeMillis())) 0xFF8CE5C1.toInt() else if (hasReplay(game)) 0xFFC3E2FF.toInt() else GuideTheme.current.accent)
    }
    private fun refreshHeroScores(game: SportsGame) {
        val display = scorePresentation(game)
        val hidden = hideScores && display.state == "post"
        refreshHeroBadge(game)
        val teams = SportsCardContent.teams(display, hidden)
        setTextChanged(heroTitle, styledTeams(teams, false, false, if (actionsOpen) "  vs  " else "\n"))
        setTextChanged(heroScore, if (hidden) "Score hidden" else if (display.state == "pre") GuideClock.time(game.start) else if (display.status == SportsScoreDelay.WAITING) "Score delayed" else if (display.scores.isEmpty()) "Score unavailable" else display.teams.joinToString("   :   ") { display.scores[it.id] ?: "–" })
        heroScore.setTextColor(if (!hidden && display.live(System.currentTimeMillis())) 0xFFB6F3CE.toInt() else Color.WHITE)
        heroRecords.visibility = GONE
        val situation = SportsCardContent.situation(display, hidden)
        val schedule = GuideClock.sportsDate(game.start) + " · " + SportsText.status(display, hidden, System.currentTimeMillis())
        val network = game.networks.joinToString(" · ").ifBlank { "Broadcast to be announced" }
        setTextChanged(heroDetail, "$schedule\n" + if (situation.isNotBlank()) "$network · $situation" else network)
    }
    private fun showHero(game: SportsGame) {
        val changed = featured?.key != game.key; featured = game
        refreshHeroScores(game)
        findStreams.isEnabled = game.state != "post" || SportsReplays.eligible(game, archiveWindowDays, System.currentTimeMillis()); findStreams.text = when { game.state == "pre" -> "Find coverage"; game.state == "post" -> "Find replay"; game.live(System.currentTimeMillis()) -> "Watch live"; else -> "Find streams" }
        chooseStreams.isVisible = actionsOpen && game.live(System.currentTimeMillis())
        updateReminder(game)
        if (changed || artworkHidden(game)) { pictures.bind(backdrop, ""); credit.text = ""; crests.isVisible = !preview }
        game.teams.forEachIndexed { i, team -> bindCrest(logos, crestViews[i], game.logos[team.id].orEmpty().ifBlank { team.logo }, .88f) }
        if (changed || artworkHidden(game)) removeCallbacks(loadHeroArt); if (changed && !scoresHidden(game)) postDelayed(loadHeroArt, 150)
    }
    private fun showArtwork(key: String, url: String, attribution: String) {
        if (url.isBlank()) return
        if (preview || featured?.key != key || featured?.let(::artworkHidden) == true) return
        pictures.bind(backdrop, url) { image ->
            if (featured?.key == key && !preview) {
                crests.isVisible = image == null
                if (image != null) fadeArtwork(backdrop, .65f)
                credit.text = if (image != null && attribution.isNotBlank()) "Photo: $attribution" else ""
            }
        }
    }
    fun previewMode(enabled: Boolean) {
        if (preview == enabled) return
        preview = enabled; heroActions(actionsOpen); crests.isVisible = !enabled && backdrop.drawable == null
        // The 320 dp preview sits over the top-right corner: keep every control out from under it.
        filterBar.setPadding(0, 0, dp(if (enabled) 340 else 0), 0)
        streamsHeader.setPadding(0, 0, dp(if (enabled) 340 else 0), 0)
        if (enabled) pictures.bind(backdrop, "")
        if (!enabled) featured?.takeUnless(::artworkHidden)?.let { artwork.request(it) }
    }
    /** Only show placeholders when loading is noticeable: a fast league switch goes straight to its cards. */
    private val revealSkeleton = Runnable { if (loadingGames) showSkeletonNow() }
    private fun showSkeleton() {
        if (skeleton.isVisible) return
        removeCallbacks(revealSkeleton); postDelayed(revealSkeleton, SKELETON_DELAY_MS)
    }
    private fun showSkeletonNow() {
        scrollHint.visibility = INVISIBLE
        skeleton.isVisible = true; heroSkeleton.isVisible = true; rows.visibility = INVISIBLE; gridArea.isVisible = true
        heroContent.visibility = INVISIBLE; crests.visibility = INVISIBLE; backdrop.visibility = INVISIBLE; credit.visibility = INVISIBLE
        empty.text = "Loading Sports…"; empty.isVisible = true
    }
    private fun revealContent() {
        removeCallbacks(revealSkeleton)
        if (skeleton.visibility != VISIBLE) return
        skeleton.visibility = GONE; heroSkeleton.visibility = GONE; rows.isVisible = !watchMode; heroContent.isVisible = true; backdrop.isVisible = true; credit.isVisible = true
        crests.isVisible = !preview && backdrop.drawable == null
        gridArea.alpha = 0f; heroContent.alpha = 0f
        gridArea.animate().alpha(1f).setDuration(180).start(); heroContent.animate().alpha(1f).setDuration(180).start()
    }
    fun loading(message: String) { contextLine.text = message; loadingGames = games.isEmpty(); progress.isVisible = true; status.text = message; if (games.isEmpty()) { empty.text = "Loading scores and schedules…"; empty.isVisible = true; if (initialReveal) showSkeleton() } }
    fun submit(next: List<SportsGame>, followed: Set<String>, selected: String?, message: String, scanning: Boolean) {
        if (cardsClosed) return
        val request = ++cardsRequest
        this.followed = followed
        val changedScope = scope != selected
        if (changedScope) {
            scope = selected
            // A date refresh can finish after the viewer has already moved to a card.
            // Keep that visible focus and its hero in agreement when it still exists.
            if (browsingGames) {
                if (!restoringLeague) remembered = if (rows.hasFocus()) rows.findFocus()?.tag as? String else null
                showGames()
            }
        }
        restoringLeague = false
        todayButton.isSelected = selected == "today" || selected == null
        liveButton.isSelected = selected == "live"
        dateButton.isSelected = selected?.startsWith("date:") == true
        val schedule = when (selected) {
            "live" -> "Live now"
            else -> SportsSchedule.selectedDate(selected)?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: "Today"
        }
        dateButton.text = if (dateButton.isSelected) "$schedule ▾" else "Calendar"
        scheduleOptions.forEach { it.contentDescription = it.text.toString() + if (it.isSelected) ", selected" else "" }
        setTextChanged(headerTitle, selectedLeague?.let(SportsLeagues::label) ?: "Sports")
        val now = System.currentTimeMillis()
        setTextChanged(contextLine, if (scanning && next.isEmpty()) "$schedule · Checking games…"
            else "$schedule · ${next.size} games · ${next.count { it.live(now) }} live")
        if (scanning && next.isEmpty() && (initialReveal || !changedScope)) {
            if (!watchMode && !actionsOpen) { progress.isIndeterminate = true; progress.isVisible = true; status.text = "Updating sports…" }
            if (initialReveal && browsingGames) { loadingGames = true; showSkeleton() }
            return
        }
        val first = initialReveal; initialReveal = false; loadingGames = false
        val showRedZone = "NFL" in enabledLeagues
        if (!watchMode && !actionsOpen) { progress.visibility = if(scanning) VISIBLE else INVISIBLE; status.text = message }
        empty.text = if (scanning) "Checking games…" else if (selected == "live") "No live games. Choose Today for upcoming games and results." else "No games on this date. Try another date or league."
        empty.visibility = if(next.isEmpty() && !showRedZone && !watchMode && !actionsOpen) VISIBLE else GONE
        if (games != next || displayedRedZone != showRedZone) {
            cardUpdates.submit(CardUpdate(request, games, gameKeys, next.toList(), showRedZone, first, rows.hasFocus(), inputEpoch, focusEpoch, remembered))
            return
        }
        if (first || skeleton.isVisible) revealContent()
        if (browsingGames && remembered != REDZONE) featured?.let { current -> gamePositions[current.key]?.let(games::getOrNull)?.let(::refreshHeroScores) }
        finishCardUpdate()
    }
    private fun publishCards(update: CardUpdate, keys: List<String>, ids: LongArray, positions: Map<String, Int>, diff: DiffUtil.DiffResult) {
        if (rows.isComputingLayout) { postOnAnimation { if (!cardsClosed && update.request == cardsRequest) publishCards(update, keys, ids, positions, diff) }; return }
        if (update.before !== games) return
        if (update.hadFocus && update.anchor !in positions && rows.hasFocus()) {
            val before = rows.descendantFocusability; rows.descendantFocusability = FOCUS_BEFORE_DESCENDANTS; rows.requestFocus(); rows.descendantFocusability = before
        }
        games = update.next; gameKeys = keys; gameIds = ids; gamePositions = positions; displayedRedZone = update.redZone
        diff.dispatchUpdatesTo(cards)
        restoreLeagueLayout?.let { rows.layoutManager?.onRestoreInstanceState(it); restoreLeagueLayout = null }
        if (update.first || skeleton.isVisible) revealContent()
        if (browsingGames) {
            val chosen = positions[remembered]?.let(games::getOrNull) ?: featured(games, followed, System.currentTimeMillis())
            if (update.redZone && (remembered == REDZONE || chosen == null)) showRedZoneHero()
            else if (chosen != null) showHero(chosen)
            else { featured = null; heroRecords.visibility = GONE; pictures.bind(backdrop, ""); crestViews.forEach { logos.bind(it, "") }; heroTitle.text = "Your sports"; heroScore.text = ""; heroBadge.text = "SCORES & SCHEDULES"; heroDetail.text = "Choose your leagues and teams in Sports settings."; findStreams.isEnabled = false }
        }
        if (update.hadFocus && cards.itemCount > 0) rows.postOnAnimation {
            if (isShown && update.input == inputEpoch && update.focus == focusEpoch && browsingGames && !rows.hasFocus()) focusAt(positions[remembered ?: update.anchor] ?: 0)
        }
        finishCardUpdate(); cardsReady()
    }
    private fun finishCardUpdate() {
        rows.post { updateScrollHint() }
        if (pendingEnter && cards.itemCount > 0 && !watchMode && !actionsOpen) { pendingEnter = false; val input = pendingInput; post { if (input == inputEpoch && isShown) focusGames() } }
    }
    @SuppressLint("NotifyDataSetChanged")
    fun matching(game: SportsGame) { scrollHint.visibility = INVISIBLE; matchNotice.visibility = GONE; actionsOpen = false; heroActions(false); replayMode = game.state == "post"; replays = emptyList(); streamEntry = true; streamInput = inputEpoch; streamsList = emptyList(); streamAdapter.notifyDataSetChanged(); empty.visibility = GONE; showHero(game); watchMode = true; matchingKey = game.key; rows.isVisible = false; gridArea.isVisible = false; streams.isVisible = true; streamsArea.isVisible = true; streamsHeader.isVisible = true; filtersButton.isVisible = !replayMode
        setSearching(true); progress.isIndeterminate = false; progress.max = PROGRESS_MAX; progress.progress = 0; progress.isVisible = true
        status.text = if (replayMode) CHECKING_ARCHIVES else SEARCHING; presentationChanged?.invoke() }
    fun matchingProgress(game: SportsGame, message: String, checked: Int = 0, total: Int = 0) {
        if (!watchMode || matchingKey != game.key) return
        status.text = message; progress.isVisible = true
        // One bar for the whole search: local scan fills the first part, provider checks the rest.
        // It only moves forward and animates, so phases never reset or jump back to empty.
        val span = when (message) { SEARCHING -> 0 to 600; CONFIRMING, CHECKING_SCHEDULES -> 600 to 1000; CHECKING_ARCHIVES -> 0 to 1000; else -> null }
        if (span == null) { progress.isIndeterminate = true; return }
        val fraction = if (total > 0) checked.coerceIn(0, total).toFloat() / total else 0f
        val next = (span.first + (span.second - span.first) * fraction).toInt().coerceAtLeast(searchProgress)
        if (progress.isIndeterminate) { progress.isIndeterminate = false; progress.max = PROGRESS_MAX }
        if (next != searchProgress) { searchProgress = next; progress.setProgress(next, true) }
    }
    fun finishTuning() { progress.visibility = INVISIBLE; setSearching(false) }
    fun refreshStreamLabels() {
        if (replayMode) return
        for (i in 0 until streams.childCount) {
            val holder = streams.getChildViewHolder(streams.getChildAt(i)) as? StreamCard ?: continue
            streamsList.getOrNull(holder.bindingAdapterPosition)?.let { holder.play.invalidate() }
        }
    }
    fun showMatchNotice(message: String) { matchNotice.text = message; matchNotice.isVisible = true; progress.visibility = INVISIBLE; setSearching(false) }
    fun updateStreamSummaries(summaries: Map<String, String>) {
        if (streamSummaries == summaries) return
        streamSummaries = summaries.toMap()
        refreshCardStreams()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun showStreams(game: SportsGame, channels: List<Channel>, message: String? = null, label: (Channel) -> String) {
        if (!watchMode || matchingKey != game.key) return
        matchNotice.isVisible = message != null; matchNotice.text = message.orEmpty(); setSearching(false)
        val same = streamsList == channels; val hadFocus = streams.hasFocus(); streamsList = channels; streamLabels = label
        if (!same) streamAdapter.notifyDataSetChanged() else refreshStreamLabels()
        progress.visibility = INVISIBLE; status.text = message ?: if (game.state == "pre") "${channels.size} scheduled EPG matches · OK previews the channel · Starts ${GuideClock.sportsDate(game.start)}" else "${channels.size} streams${if (game.coverageComplete) "" else " · More coverage may be available"} · OK Watch · Hold OK Add screen"
        empty.text = when (game.state) { "post" -> "This game has ended."; "pre" -> "No scheduled EPG match yet. The provider may not have listings this far ahead."; else -> "No matching streams. Try Stream quality." }
        empty.visibility = if(channels.isEmpty()) VISIBLE else GONE; val enter = streamEntry && streamInput == inputEpoch; streamEntry = false; if (enter && !hadFocus) { if (channels.isNotEmpty()) focusStream(0) else filtersButton.requestFocus() }
    }
    @SuppressLint("NotifyDataSetChanged")
    fun showReplays(game: SportsGame, choices: List<SportsReplay>) {
        if (!watchMode || matchingKey != game.key) return
        matchNotice.visibility = GONE; setSearching(false)
        replays = choices; streamsList = choices.map { it.channel }
        streamLabels = { channel -> choices.first { it.channel.id == channel.id }.let { "${channel.name} · Watch replay\nExpires ${GuideClock.dayTime(it.expires)}" } }
        streamAdapter.notifyDataSetChanged(); progress.visibility = INVISIBLE
        status.text = "${choices.size} replays · Back returns to games"
        empty.text = "No catch-up available. The provider may not archive this game."
        empty.visibility = if(choices.isEmpty()) VISIBLE else GONE
        val enter = streamEntry && streamInput == inputEpoch; streamEntry = false
        if (enter && !streams.hasFocus()) { if (choices.isNotEmpty()) focusStream(0) else focusStreamsHeader() }
    }
    private fun showGames() { removeCallbacks(settleGameFocus); setSearching(false); rows.post { updateScrollHint() }; matchNotice.visibility = GONE; gridArea.animate().cancel(); heroContent.animate().cancel(); gridArea.alpha = 1f; heroContent.alpha = 1f; actionsOpen = false; heroActions(false); progress.visibility = INVISIBLE; empty.visibility = GONE; replayMode = false; replays = emptyList(); watchMode = false; matchingKey = null; streams.isVisible = false; streamsArea.isVisible = false; rows.isVisible = true; gridArea.isVisible = true; streamsHeader.isVisible = false; filtersButton.isVisible = false; presentationChanged?.invoke() }
    fun home() { showGames(); pendingEnter = true; pendingInput = inputEpoch }
    fun back(): Boolean {
        if (watchMode || actionsOpen) { showGames(); focusGames(); return true }
        if (!filterBar.hasFocus()) {
            gridFocus.cancel(); ++focusEpoch; pendingEnter = false
            focusLeagueFilter()
            return true
        }
        return false
    }
    fun focusTeams() { gridFocus.cancel(); ++focusEpoch; teamsButton.requestFocus() }
    fun focusCalendar() { gridFocus.cancel(); ++focusEpoch; (scheduleOptions.firstOrNull { it.isSelected } ?: todayButton).requestFocus() }
    fun focusHero() = focusGames()
    /** The focused card grows slightly, matching Library posters, so the cursor is easy to find from the couch. */
    private fun lift(view: View, active: Boolean) {
        view.animate().cancel()
        view.animate().scaleX(if (active) 1.04f else 1f).scaleY(if (active) 1.04f else 1f).translationZ(if (active) dp(8).toFloat() else 0f).setDuration(120).start()
    }
    fun focusGames() {
        when {
            // Wait for the first game instead of parking the cursor on a league chip while loading.
            loadingGames && games.isEmpty() && !watchMode -> { pendingEnter = true; pendingInput = inputEpoch; if (!hasFocus()) return; focusLeagueFilter() }
            watchMode -> { pendingEnter = false; if (streamsList.isNotEmpty()) focusStream(0) else focusStreamsHeader() }
            actionsOpen -> findStreams.takeIf { it.isEnabled }?.requestFocus() ?: focusLeagueFilter()
            cards.itemCount > 0 -> { pendingEnter = false; val key = remembered ?: featured?.key ?: REDZONE; focusAt(gamePositions[key] ?: 0) }
            else -> { pendingEnter = true; pendingInput = inputEpoch; focusLeagueFilter() }
        }
    }
    fun tuningRedZone(message: String) { progress.isIndeterminate = true; progress.isVisible = true; status.text = message }
    fun matchingRedZone() {
        scrollHint.visibility = INVISIBLE
        streamEntry = true; streamInput = inputEpoch
        remembered = REDZONE; actionsOpen = false; heroActions(false); replayMode = false; replays = emptyList(); watchMode = true; matchingKey = REDZONE
        showRedZoneHero(); rows.isVisible = false; gridArea.isVisible = false; streams.isVisible = true; streamsArea.isVisible = true; val oldCount = streamsList.size; streamsList = emptyList(); streamAdapter.notifyItemRangeRemoved(0, oldCount)
        streamsHeader.isVisible = true; filtersButton.isVisible = false; empty.visibility = GONE; progress.isIndeterminate = true; progress.isVisible = true
        setSearching(true); status.text = "Finding RedZone in your playlist…"; presentationChanged?.invoke(); focusStreamsHeader()
    }
    fun showRedZoneStreams(channels: List<Channel>, checking: Boolean = false, label: (Channel) -> String) {
        if (!watchMode || matchingKey != REDZONE) return
        val previous = streamsList; val hadFocus = streams.hasFocus()
        val anchor = streams.findFocus()?.let(streams::findContainingViewHolder)?.bindingAdapterPosition?.let { previous.getOrNull(it)?.id }
        val input = inputEpoch
        showRedZoneHero(); streamsList = channels; streamLabels = label
        if (previous != channels) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = channels.size
                override fun areItemsTheSame(old: Int, new: Int) = previous[old].id == channels[new].id
                override fun areContentsTheSame(old: Int, new: Int) = previous[old] == channels[new]
            })
            diff.dispatchUpdatesTo(streamAdapter)
        }
        refreshStreamLabels(); setSearching(checking && channels.isEmpty())
        rows.isVisible = false; gridArea.isVisible = false; streams.isVisible = true; streamsArea.isVisible = true; streamsHeader.isVisible = true; filtersButton.isVisible = true; progress.visibility = if (checking) VISIBLE else INVISIBLE
        status.text = "${channels.size} RedZone channels · " + if (checking) "Checking provider guide for more…" else "Availability follows your provider"
        empty.text = "No RedZone channel was found in this playlist’s names or current guide."; empty.visibility = if(channels.isEmpty()) VISIBLE else GONE
        presentationChanged?.invoke()
        val enter = streamEntry && streamInput == inputEpoch; streamEntry = false
        if (enter && !hadFocus) { if (channels.isEmpty()) focusStreamsHeader() else focusStream(0) }
        else if (hadFocus && anchor != null) post { if (input == inputEpoch && !streams.hasFocus()) channels.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }?.let(::focusStream) }
    }
    private fun focusAt(position: Int) { ++focusEpoch; gridFocus.move(position) }
    private fun focusStream(position: Int) { focusRow(streams, position) }
    private fun focusRow(list: RecyclerView, position: Int) {
        val adapter = list.adapter ?: return
        if (position !in 0 until adapter.itemCount) return
        val token = ++focusEpoch; val input = inputEpoch; val targetId = adapter.getItemId(position)
        if (list.findViewHolderForAdapterPosition(position) == null) { list.isFocusableInTouchMode = true; list.descendantFocusability = FOCUS_BEFORE_DESCENDANTS; list.requestFocus(); list.descendantFocusability = FOCUS_AFTER_DESCENDANTS; list.scrollToPosition(position) }
        fun attempt(left: Int) {
            // A delayed layout must never reclaim focus after newer remote input or
            // target a different card when a score/stream refresh changes the list.
            if (!list.isShown || token != focusEpoch || input != inputEpoch || position !in 0 until adapter.itemCount || adapter.getItemId(position) != targetId) return
            val holder = list.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                val target = if (holder is StreamCard) holder.play else holder.itemView
                if (target.requestFocus()) TvFocus.center(list, holder.itemView)
            } else if (left > 0) list.postOnAnimation { attempt(left - 1) }
        }; attempt(15)
    }
    @SuppressLint("NotifyDataSetChanged") fun refreshTheme() { hero.background = GuideTheme.glass(resources.displayMetrics.density); cards.notifyDataSetChanged(); streamAdapter.notifyDataSetChanged(); featured?.let(::showHero) }
    fun pauseImages() { ++cardsRequest; removeCallbacks(settleGameFocus); gridFocus.cancel(); channelLogos.pause(); ++focusEpoch; removeCallbacks(loadHeroArt); artwork.pause(); logos.pause(); pictures.pause(); cardLogos.pause() }
    fun resumeImages() { channelLogos.resume(); refreshStreamLabels(); artwork.resume(); logos.resume(); pictures.resume(); cardLogos.resume(); redrawVisiblePosters(); featured?.let { showHero(it); if (!artworkHidden(it)) artwork.request(it) } }
    fun trimImages() {
        logos.trim()
        cardLogos.trim()
        pictures.trim()
        channelLogos.trim()
    }
    fun closeImages() { cardsClosed = true; ++cardsRequest; cardDiffWorker.shutdownNow(); removeCallbacks(settleGameFocus); gridFocus.cancel(); channelLogos.close(); removeCallbacks(loadHeroArt); artwork.close(); logos.close(); pictures.close(); cardLogos.close() }
    private fun fadeArtwork(view: ImageView, opacity: Float = 1f) { view.animate().cancel(); view.alpha = 0f; view.animate().alpha(opacity).setDuration(160).start() }
    private fun bindCrest(loader: LogoLoader, view: ImageView, url: String, opacity: Float = 1f) {
        val before = (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        loader.bind(view, url) { bitmap -> if (bitmap != null) {
            (view.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            if (bitmap !== before) fadeArtwork(view, opacity) else { view.animate().cancel(); view.alpha = opacity }
        } }
    }
    private fun drawCard(holder: GameCard, game: SportsGame) {
        if (holder.box.tag != game.key) return
        game.teams.forEachIndexed { index, team -> bindCrest(cardLogos, holder.logos[index], game.logos[team.id].orEmpty().ifBlank { team.logo }) }
    }
    private fun currentCardGame(holder: GameCard): SportsGame? =
        gamePositions[holder.box.tag as? String]?.let(games::getOrNull)?.also { holder.game = it }
    private fun setTextChanged(view: TextView, value: CharSequence) { if (view.text.toString() != value.toString()) view.text = value }
    private fun styledTeams(teams: List<SportsCardTeamLine>, active: Boolean, withScores: Boolean = true, separator: String = "\n"): CharSequence {
        val text = android.text.SpannableStringBuilder()
        teams.forEachIndexed { index, team ->
            if (index > 0) text.append(separator)
            val marker = text.length; text.append(if (team.possession) "▸ " else "")
            if (team.possession) text.setSpan(android.text.style.ForegroundColorSpan(if (active) GuideTheme.focusText else 0xFF8CE5C1.toInt()), marker, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val name = text.length; text.append(team.name)
            text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), name, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            team.record?.let { record ->
                val start = text.length; text.append(" ($record)")
                text.setSpan(android.text.style.RelativeSizeSpan(if (withScores) .82f else .60f), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.ForegroundColorSpan(if (active) GuideTheme.focusText else 0xFFB6C6D6.toInt()), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            team.score?.takeIf { withScores }?.let { score ->
                val start = text.length; text.append("   $score")
                text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.RelativeSizeSpan(1.12f), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(android.text.style.ForegroundColorSpan(if (active) GuideTheme.focusText else if (team.leading) 0xFFB6F3CE.toInt() else 0xFFE4EDF5.toInt()), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }
    private fun renderCardText(holder: GameCard, game: SportsGame, active: Boolean) {
        val display = scorePresentation(game); val hidden = hideScores && display.state == "post"
        val teams = SportsCardContent.teams(display, hidden)
        val textState = teams to active
        if (holder.textState != textState) {
            holder.textState = textState
            teams.forEachIndexed { index, team ->
                setTextChanged(holder.teamNames[index], team.name)
                setTextChanged(holder.teamRecords[index], team.record?.let { "($it)" }.orEmpty())
                holder.teamRecords[index].isVisible = team.record != null
            }
        }
        val color = if (active) GuideTheme.focusText else Color.WHITE
        holder.teamNames.forEach { it.setTextColor(color) }; holder.teamRecords.forEach { it.setTextColor(if (active) GuideTheme.focusText else 0xFFB6C6D6.toInt()) }
        holder.detail.setTextColor(if (active) GuideTheme.focusText else 0xFFDCE7F1.toInt()); holder.date.setTextColor(if (active) GuideTheme.focusText else 0xFFC3D1DF.toInt()); holder.stream.setTextColor(if (active) GuideTheme.focusText else 0xFFB5C5D3.toInt())
        if (holder.dateStart != game.start) { holder.dateStart = game.start; holder.dateText = SportsLeagues.label(game.leagueId) + " · " + GuideClock.sportsDate(game.start) }
        val situation = SportsCardContent.situation(display, hidden)
        val status = if (display.state != "pre") SportsText.status(display, hidden, System.currentTimeMillis()).removePrefix("LIVE · ") else game.networks.joinToString(" · ").ifBlank { "Broadcast to be announced" }
        setTextChanged(holder.detail, listOf(status, situation).filter { it.isNotBlank() }.joinToString(" · "))
        setTextChanged(holder.date, holder.dateText); setTextChanged(holder.stream, cardStream(game))
        val scoreboard = SportsCardContent.scoreboard(display, hidden, if (display.state == "pre") GuideClock.time(game.start) else "")
        holder.scoreLine.visibility = if (scoreboard.message == null) VISIBLE else INVISIBLE
        holder.scoreMessage.isVisible = scoreboard.message != null; setTextChanged(holder.scoreMessage, scoreboard.message.orEmpty())
        holder.scoreMessage.setTextColor(if (active) GuideTheme.focusText else if (display.status == SportsScoreDelay.WAITING) 0xFFF1D39A.toInt() else Color.WHITE)
        (holder.scoreLine.getChildAt(1) as TextView).setTextColor(if (active) GuideTheme.focusText else 0xFFB6C6D6.toInt())
        teams.forEachIndexed { index, team ->
            val value = scoreboard.values.getOrNull(index)
            setTextChanged(holder.scoreValues[index], value?.let { if (team.possession) "▸ $it" else it }.orEmpty())
            holder.scoreValues[index].setTextColor(if (active) GuideTheme.focusText else if (team.leading || team.possession) 0xFFB6F3CE.toInt() else 0xFFE4EDF5.toInt())
        }
        styleBadge(holder.badge, game, active, display); describeCard(holder, game, display)
        val palette = when (display.state) { "in" -> 0xD02A403C.toInt(); "pre" -> 0xD02A3548.toInt(); "post" -> 0xD02E3440.toInt(); else -> 0xC0303943.toInt() }
        if (holder.palette != palette) { holder.palette = palette; holder.box.background = GuideTheme.background(resources.displayMetrics.density, palette) }
        val poster = when (game.leagueId) { "NFL", "NCAAF" -> 0xFF244C68.toInt(); "NBA", "NCAAM", "NCAAW" -> 0xFF4B3F70.toInt(); "MLB", "NCAABSB" -> 0xFF28574E.toInt(); "NHL" -> 0xFF365A70.toInt(); else -> 0xFF365D54.toInt() }
        if (holder.posterPalette != poster) { holder.posterPalette = poster; holder.poster.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(poster, 0xFF172332.toInt())) }
    }
    private fun redrawVisiblePosters() {
        for (index in 0 until rows.childCount) {
            val holder = rows.getChildViewHolder(rows.getChildAt(index)) as? GameCard ?: continue
            games.getOrNull(holder.bindingAdapterPosition)?.let { drawCard(holder, it) }
        }
    }
    private class GameCard(val box: LinearLayout, val badge: TextView, val title: LinearLayout, val teamNames: List<TextView>, val teamRecords: List<TextView>, val scoreValues: List<TextView>, val scoreLine: LinearLayout, val scoreMessage: TextView, val detail: TextView, val date: TextView, val stream: TextView, val logos: List<ImageView>, val special: TextView, val poster: FrameLayout) : RecyclerView.ViewHolder(box) {
        var game: SportsGame? = null
        var textState: Pair<List<SportsCardTeamLine>, Boolean>? = null
        var palette = 0; var posterPalette = 0
        var dateStart = Long.MIN_VALUE; var dateText = ""
    }
    private class StreamCard(val play: GuideAdapter.Row) : RecyclerView.ViewHolder(play)
    companion object {
        private const val COLUMNS = 3
        private const val SKELETON_DELAY_MS = 150L
        private const val PROGRESS_MAX = 1000
        const val SEARCHING = "Searching your channels for this game…"
        const val CONFIRMING = "Confirming which channels are showing it…"
        const val CHECKING_SCHEDULES = "Checking channel schedules…"
        const val CHECKING_ARCHIVES = "Checking provider replays…"
        internal const val CARD_HEIGHT_DP = 140
        private const val REDZONE = "sports-redzone"
        private const val SCORE_PAYLOAD = "sports-card-score"
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

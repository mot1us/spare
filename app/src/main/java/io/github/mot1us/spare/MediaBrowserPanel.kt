package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Richer text for the spotlight, fetched once when a poster settles under the cursor. */
data class Spotlight(val meta: String, val plot: String, val backdrop: String)
data class BrowserRow(val key: String, val title: String, val detail: String = "", val poster: String = "", val description: String = "", val requestPoster: (((String) -> Unit) -> Unit)? = null, val longPress: (() -> Unit)? = null,
    val backdrop: String = "", val spotlight: (((Spotlight) -> Unit) -> Unit)? = null,
    /** [detail] is the viewer's own (a resume point, a Watchlist note): the banner keeps it ahead of the provider's facts. */
    val personal: Boolean = false, val select: () -> Unit) { val searchKey: String = ChannelSearch.key(title); val stableId = Channel.stableLongId(key) }

/**
 * Library, laid out like streaming apps: a tab line (Movies, Series, Watchlist…) over a large artwork banner that
 * describes the title under the cursor, then full-width poster rows or a grid. The main menu sits to the left.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class MediaBrowserPanel(context: Context, button: (String, () -> Unit) -> Button, private val mode: (String) -> Unit,
    refresh: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(Color.WHITE); typeface = AppFont.face() }
    var navigation: (() -> Unit)? = null
    var favoriteAction: (() -> Unit)? = null
    var markWatchedAction: (() -> Unit)? = null
    private var favoriteIds = emptySet<String>()
    private var favoriteLabels = emptyMap<String, String>()
    private val saveTitle = button("Add to Watchlist") { favoriteAction?.invoke() }.apply { tag = "library-favorite"; visibility = GONE }
    private val markWatched = button("Mark all watched") { markWatchedAction?.invoke() }.apply { tag = "library-mark-watched"; visibility = GONE }
    private var allCategoryTitle = "All"
    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE; tag = "library-series-art" }
    private var bannerUrl = ""
    private val heading = label(16f).apply { tag = "library-heading"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val message = label(11f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.LTGRAY); tag = "library-status" }
    // Spotlight: the focused title's artwork, name, details and plot above the grid, as streaming apps do.
    private val spotArt = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .6f; visibility = GONE; tag = "library-spot-art" }
    private val spotScrim = View(context).apply { visibility = GONE }
    private val spotTitle = label(28f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "library-spot-title" }
    private val spotMeta = label(13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xFFC9D1DB.toInt()); tag = "library-spot-meta" }
    private val spotPlot = label(13f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xFFE3E8EE.toInt()); setLineSpacing(0f, 1.1f); tag = "library-spot-plot" }
    private val spotlight = LinearLayout(context).apply { orientation = VERTICAL; tag = "library-spotlight"; visibility = GONE }
    private var spotKey: String? = null
    private val spotLater = Runnable { requestSpotDetails() }
    private var spotWaiting: BrowserRow? = null
    private var shelfFocus: BrowserRow? = null
    private val spotApply = Runnable { spotWaiting?.let { spotWaiting = null; showSpot(it) } }
    private var spotRow: BrowserRow? = null
    // Fixed heights: a wrap-content FrameLayout would stretch its full-bleed artwork to the whole screen.
    private val hero = FrameLayout(context)
    /** The page's name and state ("Fixture cinema · 80 titles") between the banner and the titles. */
    private val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "library-title-line" }
    private val loading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = GONE; tag = "library-loading" }
    var globalSearch: ((String) -> Unit)? = null
    // The search box only shows while it holds text, or as the archive filter; the tab line's search icon opens Search.
    private val search = EditText(context).apply { hint = "Search archived channels and programmes"; visibility = GONE; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true; tag = "library-search"; typeface = AppFont.face(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; showSoftInputOnFocus = false }
    // Leanback lists (Google's TV list engine) move the cursor and scroll; this panel only says where their edges lead.
    private val categories = VerticalGridView(context).apply {
        setNumColumns(1); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER
        tag = "library-categories"; visibility = GONE; isFocusableInTouchMode = true; isClickable = true
        setPadding(dp(8), dp(8), dp(8), dp(8)); elevation = dp(8).toFloat()
    }
    private val rows = VerticalGridView(context).apply { preserveFocusAfterLayout = false; setNumColumns(PosterGridLayout.COLUMNS); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-items"; recycledViewPool.setMaxRecycledViews(0, PosterGridLayout.COLUMNS * 4)
        // Leave room for the focused poster's lift instead of clipping it at the grid edge.
        clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private val empty = label(13f).apply { setPadding(dp(14), dp(16), dp(14), dp(16)); tag = "library-empty" }
    private var allRows = emptyList<BrowserRow>()
    private var shownRows = emptyList<BrowserRow>()
    private var categoryNames = emptyList<String>()
    private var categoryLabels = emptyMap<String, String>()
    private var catalogFocusRequested = false
    // When the tile under the cursor is replaced (a category was picked and its titles arrive), Android hands focus to the
    // first control it finds: the Movies/Series buttons. That is not the viewer navigating there, so the cursor is sent on
    // to the titles it was heading for. Any direction key the viewer presses afterwards ends this.
    private var contentWantedAt = 0L
    private var navKeyAt = 0L
    private fun drifted() = navKeyAt <= contentWantedAt && (catalogFocusRequested || pending === rows || android.os.SystemClock.uptimeMillis() - contentWantedAt < 1500)
    private fun wantContent() { contentWantedAt = android.os.SystemClock.uptimeMillis() }
    private val recoverFocus = Runnable { if (isShown && drifted() && !rows.hasFocus() && !shelves.hasFocus() && !detailPane.isVisible) focusRows() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) navKeyAt = android.os.SystemClock.uptimeMillis()
        return super.dispatchKeyEvent(event)
    }
    private var contentsAvailable = true
    private var rowGroups = emptyMap<String, String>()
    private var group: String? = null
    private var catalogQuery: ((String?, String) -> Unit)? = null
    private var catalogMore: (() -> Unit)? = null
    private var hasMore = false
    private var catalogContext = ""
    private val anchors = mutableMapOf<String, String?>()
    private val contexts = mutableMapOf<String, Pair<String?, String>>()
    private var changingSearch = false
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchLater = Runnable { filter() }
    private fun anchorKey() = "$catalogContext:${group.orEmpty()}:${ChannelSearch.key(search.text.toString())}"
    private fun rememberAnchor() { anchors[anchorKey()] = rememberedKey }

    private var pending: RecyclerView? = null
    private var focusEpoch = 0
    private var columns = PosterGridLayout.COLUMNS
    private var grid = true
    // Posters are kept encrypted on disk (provider URLs can carry account details) so a
    // Library visit after a restart draws saved artwork instead of downloading it again.
    // Tiles decode at the size they are drawn (about 213x320 px on a 1080p TV, ~135 KB in RGB_565), so 36 MB holds
    // some 270 posters; only the details page asks for the larger 480 px copy. TMDB artwork is fetched at w342.
    private val images = LogoLoader(480, 36 * 1024 * 1024, posterFiles(context), opaque = true, threads = 4, source = PosterUrls::sized, newestFirst = true)
    private val tileDecode = posterDecodeSize(resources.displayMetrics)
    private val backdrops = LogoLoader(960, 12 * 1024 * 1024, posterFiles(context), opaque = true)
    private val shelves = CatalogShelves(context, images).apply { setPadding(dp(8), dp(4), dp(8), dp(12)); decode = tileDecode }
    /** Set while Movies/Series browse by category rows: the category picker opens a category (or, for All, the rows). */
    private var shelfPicked: ((String?) -> Unit)? = null
    private var shelfSearch: (() -> Unit)? = null
    private var submittedList = ""
    private val shelvesShown get() = shelves.isVisible && body.isVisible
    private val detailPane = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE; tag = "library-details" }
    private var detailGeneration = 0
    private val body = LinearLayout(context)
    private val lists = LinearLayout(context).apply { orientation = VERTICAL }
    private var artworkHttp = false
    private var rememberedKey: String? = null
    /** Movies, Series, Watchlist, Up next and Continue watching, then this page's actions, on one line: Left/Right walk it. */
    private val tabStrip = LinearLayout(context).apply { tag = "library-section-rail"; orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val sectionControls = mutableListOf<Button>()
    private fun iconButton(icon: Int, name: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); contentDescription = name; scaleType = ImageView.ScaleType.CENTER; isFocusableInTouchMode = true
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { click() }
    }
    private val refreshButton = iconButton(R.drawable.ic_refresh, "Refresh", refresh).apply { tag = "library-refresh" }
    private val searchButton = iconButton(R.drawable.ic_search, "Search") {
        if (archive) { search.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
        else globalSearch?.invoke(search.text.toString())
    }.apply { tag = "library-search-button" }
    /** OK cycles the order of the Movies and Series grids; the choice is remembered. */
    var sortAction: (() -> String)? = null
    private val sortButton = button("Sort") { sortAction?.invoke()?.let(::sortLabel) }.apply { tag = "library-sort"; visibility = GONE; maxLines = 1 }
    fun sortLabel(value: String) { sortButton.text = "Sort · $value"; sortButton.contentDescription = "Sort order, $value. OK changes it." }
    private var selectedTab = "movie"
    private val tabs = listOf("Movies" to "movie", "Series" to "series", "Up next" to "upnext", "Watchlist" to "favorites", "Continue watching" to "continue")
    private val archiveHome = button("Channels") { mode("catchup") }.apply { tag = "archive-channels"; visibility = GONE }
    private var archive = false
    init {
        orientation = VERTICAL; tag = "library-panel"; visibility = GONE; setPadding(dp(24), dp(18), dp(24), dp(18)); background = GuideTheme.guideBackground()
        tabs.forEach { (title, key) ->
            sectionControls.add(button(title) { selectTab(key); hideKeyboard(); mode(key) }.apply { tag = "library-tab:$key" })
        }
        sectionControls.add(archiveHome)
        val actions = listOf<View>(sortButton, saveTitle, markWatched, searchButton, refreshButton)
        sectionControls.forEach { control -> control.textSize = 13f; tabStrip.addView(control, LayoutParams(-2, dp(34)).apply { marginEnd = dp(4) }) }
        tabStrip.addView(View(context), LayoutParams(0, 1, 1f))
        actions.forEach { control -> tabStrip.addView(control, LayoutParams(if (control is ImageButton) dp(36) else -2, dp(34)).apply { marginStart = dp(6) }) }
        (sectionControls + actions).forEach { control ->
            // Only the tabs can be where Android drops the cursor when the titles under it are replaced; actions never are.
            control.setOnFocusChangeListener { _, active ->
                if (active && control in sectionControls && drifted()) { searchHandler.removeCallbacks(recoverFocus); searchHandler.post(recoverFocus) }
                else if (active) { ++focusEpoch; pending = null; catalogFocusRequested = false }
            }
            control.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val visible = (sectionControls + actions).filter { it.isVisible }
                        val next = visible.indexOf(control) + if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        if (next < 0) navigation?.invoke() else visible.getOrNull(next)?.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        hideKeyboard()
                        when { detailPane.isVisible -> detailPane.findViewWithTag<View>("library-play")?.requestFocus(); search.isVisible -> search.requestFocus(); else -> focusRows() }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            } }
        }
        titleLine.addView(heading, LayoutParams(-2, -2))
        titleLine.addView(message, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        titleLine.addView(banner, LayoutParams(dp(18), dp(26)).apply { marginStart = dp(8) })
        spotlight.addView(spotTitle, LayoutParams(-1, -2))
        spotlight.addView(spotMeta, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        spotlight.addView(spotPlot, LayoutParams(dp(470), -2).apply { topMargin = dp(8) })
        hero.apply { tag = "library-hero"; clipChildren = true }
        // The artwork spans the whole banner; the scrim hides it under the text on the left and fades it in to the right.
        hero.addView(spotArt, FrameLayout.LayoutParams(-1, -1))
        hero.addView(spotScrim, FrameLayout.LayoutParams(-1, -1))
        hero.addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(0, 0, 0, dp(10))
            addView(tabStrip, LayoutParams(-1, dp(40)))
            addView(View(context), LayoutParams(-1, 0, 1f))
            addView(spotlight, LayoutParams(-1, -2).apply { marginStart = dp(6) })
        }, FrameLayout.LayoutParams(-1, -1))
        body.orientation = HORIZONTAL
        body.addView(categories, LayoutParams(dp(CATEGORY_WIDTH_DP), -1).apply { marginEnd = dp(8) })
        lists.addView(empty)
        lists.addView(rows, LayoutParams(-1, 0, 1f))
        lists.addView(shelves, LayoutParams(-1, 0, 1f))
        body.addView(lists, LayoutParams(0, -1, 1f))
        shelves.exitUp = { if (search.isVisible) search.requestFocus() else focusToolbar() }
        shelves.exitLeft = { navigation?.invoke() }
        shelves.focused = { row -> rememberedKey = row.key; ++focusEpoch; pending = null; shelfFocus = row; showSpot(row) }
        addView(hero, LayoutParams(-1, dp(HERO_COMPACT_DP)))
        addView(loading, LayoutParams(-1, dp(3)))
        addView(titleLine, LayoutParams(-1, dp(28)).apply { leftMargin = dp(6) })
        addView(search, LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
        addView(body, LayoutParams(-1, 0, 1f))
        addView(detailPane, LayoutParams(-1, 0, 1f))
        categories.adapter = object : RecyclerView.Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = (if (position == 0) "All" else categoryNames[position - 1]).hashCode().toLong()
            override fun getItemCount() = categoryNames.size + 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(cell(13f, 42))
            override fun onBindViewHolder(holder: Holder, position: Int) {
                holder.text.background = GuideTheme.background(resources.displayMetrics.density)
                holder.text.setTextColor(GuideTheme.ink()); holder.text.typeface = AppFont.face(); holder.text.textSize = 13f * GuideTheme.textScale
                val name = if (position == 0) null else categoryNames[position - 1]
                holder.text.text = name?.let { categoryLabels[it] ?: it } ?: allCategoryTitle; holder.text.isSelected = group == name
                holder.text.contentDescription = "Category · ${name ?: "All"}"
                holder.text.tag = "library-category:${name ?: "all"}"
                holder.text.setOnClickListener {
                    shelfPicked?.let { picked -> wantContent(); picked(name); return@setOnClickListener }
                    val before = if (group == null) 0 else categoryNames.indexOf(group).plus(1)
                    catalogFocusRequested = false; rememberAnchor(); group = name; rememberedKey = anchors[anchorKey()]
                    updateBrowseControls(); filter()
                    if (before in 0 until itemCount) notifyItemChanged(before); notifyItemChanged(position)
                    focusRows()
                }
                holder.text.setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null; catalogFocusRequested = false } }
                holder.text.setOnKeyListener { _, key, event ->
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusRows(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) navigation?.invoke(); true }
                        else -> false
                    }
                }
            }
        }
        rows.adapter = object : RecyclerView.Adapter<PosterHolder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = shownRows[position].stableId
            override fun getItemCount() = shownRows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): PosterHolder = PosterHolder(LibraryPosterTile(context))
            override fun onBindViewHolder(holder: PosterHolder, position: Int) {
                val item = shownRows[position]
                val favorite = item.key in favoriteIds
                holder.box.configure(if (grid) LibraryPosterTile.Layout.GRID else LibraryPosterTile.Layout.LIST)
                holder.box.appearance()
                holder.box.tag = "library-item:${item.key}"
                holder.box.contentDescription = "${item.title}. ${item.detail}" + if (favorite) ". In Watchlist" else ""
                holder.box.bind(item.key, LibraryPosterTile.Content(
                    (if (favorite) "✓ " else "") + item.title, favoriteLabels[item.key] ?: item.detail, item.poster,
                ), images, artworkHttp, item.requestPoster, tileDecode)
                // Decode the rows the remote is about to reach before they scroll into view.
                if (grid) shownRows.getOrNull(position + columns * 2)?.poster?.takeIf { it.isNotBlank() }?.let { images.prefetch(it, artworkHttp, tileDecode) }
                holder.box.setOnClickListener { if (contentsAvailable) { hideKeyboard(); shownRows.getOrNull(holder.bindingAdapterPosition)?.select?.invoke() } }
                holder.box.setOnLongClickListener { shownRows.getOrNull(holder.bindingAdapterPosition)?.longPress?.let { it(); true } ?: false }
                holder.box.setOnFocusChangeListener { _, active -> if (active) { rememberedKey = item.key; ++focusEpoch; pending = null; shownRows.getOrNull(holder.bindingAdapterPosition)?.let(::showSpot) } }
            }
        }
        categories.setOnKeyInterceptListener { event ->
            // Up from the first category leaves for the tab line.
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && categories.selectedPosition <= 0) { if (event.repeatCount == 0) focusToolbar(); true } else false
        }
        rows.setOnKeyInterceptListener { event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyInterceptListener false
            // While a key is held the grid is flying past: posters wait until it stops.
            if (event.repeatCount > 0) images.quiet()
            val position = rows.selectedPosition
            when (event.keyCode) {
                // Up from the top row leaves for the search box or the tab line; a held key stops at the top instead.
                KeyEvent.KEYCODE_DPAD_UP -> if (position < columns) { if (event.repeatCount == 0) { if (search.isVisible) search.requestFocus() else focusToolbar() }; true } else false
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!grid || position % columns == 0) { if (event.repeatCount == 0) focusBrowseRail(); true } else false
                // Page and Channel keys jump a screen of posters at a time through long catalogs.
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (shownRows.isNotEmpty()) {
                    val tile = rows.getChildAt(0)?.height?.takeIf { it > 0 } ?: rows.height
                    val page = (rows.height / tile).coerceAtLeast(1) * columns
                    val down = event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN || event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                    focusAt(rows, (position + if (down) page else -page).coerceIn(0, shownRows.lastIndex))
                }; true }
                else -> false
            }
        }
        rows.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ -> if (right - left != oldRight - oldLeft) rows.post(::sizeColumns) }
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(list: RecyclerView, dx: Int, dy: Int) {
                val last = (0 until list.childCount).maxOfOrNull { list.getChildAdapterPosition(list.getChildAt(it)) } ?: return
                if (hasMore && last >= shownRows.size - 16) { hasMore = false; catalogMore?.invoke() }
            }
        })
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showSearchBox(); if (!changingSearch) { searchHandler.removeCallbacks(searchLater); searchHandler.postDelayed(searchLater, 180) } }; override fun afterTextChanged(s: Editable?) {} })
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); focusAt(rows, 0); true }
        search.setOnClickListener { if (!archive) globalSearch?.invoke(search.text.toString()) else (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
        search.setOnKeyListener { _, key, event -> when (key) { KeyEvent.KEYCODE_DPAD_CENTER -> if (!archive && globalSearch != null) { if (event.action == KeyEvent.ACTION_UP) globalSearch?.invoke(search.text.toString()); true } else false; KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusRows() }; true }; KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusToolbar() }; true }; else -> false } }
    }
    private fun cell(size: Float, height: Int) = label(size).apply {
        isFocusableInTouchMode = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(5), dp(12), dp(5)); setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
        layoutParams = RecyclerView.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(5) }
        setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null } }
    }
    fun page(title: String, detail: String, busy: Boolean) { heading.text = title; message.text = detail; loading.visibility = if (busy) VISIBLE else GONE }
    fun confirmation(value: String) { message.text = value; message.announceForAccessibility(value) }
    @SuppressLint("NotifyDataSetChanged")
    fun submit(values: List<BrowserRow>, groups: Map<String, String> = emptyMap(), reset: Boolean = true, posters: Boolean = true, initialGroup: String? = null, allTitle: String = "All") {
        stopCatalog(); showGrid(); shelfPicked = null
        allCategoryTitle = allTitle
        browse()
        grid = posters; columns = if (grid) PosterGridLayout.COLUMNS else 1
        if (posters) seriesSpot = null
        sizeColumns(); spotMode()
        if (reset) { group = null; search.setText(""); rememberedKey = null }
        contentsAvailable = true; rows.alpha = 1f
        allRows = values; rowGroups = groups; categoryNames = groups.values.distinct(); categoryLabels = emptyMap()
        if (group !in categoryNames) group = null
        // A different list (another series) starts on its suggested group; the same list keeps the viewer's choice.
        val listKey = values.firstOrNull()?.key.orEmpty() + ":" + values.size
        if (initialGroup != null && initialGroup in categoryNames && (group == null && listKey != submittedList)) group = initialGroup
        submittedList = listKey
        updateBrowseControls()
        categories.adapter?.notifyDataSetChanged(); filter()
    }
    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
        if (shelvesShown) {
            // Typing on the rows home searches the whole catalog: hand the text to the grid that replaces the rows.
            val text = search.text.toString()
            if (text.isNotBlank()) shelfSearch?.let { go -> contexts[shelves.catalog] = null to text; go() }
            return
        }
        val query = ChannelSearch.key(search.text.toString())
        catalogQuery?.let { ++focusEpoch; pending = null; hasMore = false; it(group, query); return }
        // Filtering preserves provider order. A full Myers diff becomes quadratic when
        // a large catalog loses most entries; only visible holders need rebinding.
        ++focusEpoch; pending = null; rows.stopScroll()
        if (!detailPane.isVisible && seriesSpot == null) { spotKey = null; spotMode() }
        shownRows = allRows.filter { (group == null || rowGroups[it.key] == group) && (query.isEmpty() || it.searchKey.contains(query)) }
        rows.adapter?.notifyDataSetChanged()
        empty.visibility = if (shownRows.isEmpty()) VISIBLE else GONE; empty.text = if (allRows.isEmpty()) "No titles to show." else "No matching titles."
    }

    fun clearCategorySelection() {
        rememberAnchor(); group = null; rememberedKey = null
        contexts[catalogContext] = null to search.text.toString()
    }
    fun bindCatalog(key: String, groups: Map<String, String>, query: (String?, String) -> Unit, more: () -> Unit, allTitle: String = "All", initialGroup: String? = null, picked: ((String?) -> Unit)? = null) {
        allCategoryTitle = allTitle; showGrid(); shelfPicked = picked
        grid = true; seriesSpot = null; browse(); columns = PosterGridLayout.COLUMNS; sizeColumns()
        if (catalogContext != key || catalogQuery == null) {
            if (catalogQuery != null) { rememberAnchor(); contexts[catalogContext] = group to search.text.toString() }; catalogContext = key
            val saved = contexts[key]; group = saved?.first?.takeIf { it in groups }; changingSearch = true; search.setText(saved?.second.orEmpty()); search.setSelection(search.text.length); changingSearch = false
            rememberedKey = anchors[anchorKey()]
        }
        if (initialGroup != null && group != initialGroup) { rememberAnchor(); group = initialGroup; rememberedKey = anchors[anchorKey()]; categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1) }
        catalogQuery = query; catalogMore = more; catalogLabels(groups); filter()
    }
    private fun showGrid() { if (shelves.isVisible) { shelves.visibility = GONE; rows.visibility = VISIBLE } }
    /** Category rows for Movies or Series; rows keep their titles and positions when shown again for the same catalog. */
    @SuppressLint("NotifyDataSetChanged")
    internal fun showShelves(key: String, groups: Map<String, String>, load: (String) -> Unit, release: (String) -> Unit, open: (String) -> Unit, picked: (String?) -> Unit, focus: Boolean,
        initial: Map<String, CatalogShelves.Data> = emptyMap(), rail: Map<String, String> = groups, search: (() -> Unit)? = null) {
        stopCatalog(); browse()
        allCategoryTitle = "All"; group = null; shelfPicked = picked; shelfSearch = search
        grid = true; seriesSpot = null; contentsAvailable = true; contexts.remove(key)
        changingSearch = true; this.search.setText(""); changingSearch = false
        rows.visibility = GONE; empty.visibility = GONE
        // The hidden grid releases its tiles (and their bitmaps) while the rows are shown.
        if (shownRows.isNotEmpty() || allRows.isNotEmpty()) { ++focusEpoch; pending = null; shownRows = emptyList(); allRows = emptyList(); hasMore = false; rows.adapter?.notifyDataSetChanged() }
        shelves.load = load; shelves.release = release; shelves.open = open; shelves.http = artworkHttp
        shelves.favoriteIds = favoriteIds; shelves.favoriteLabels = favoriteLabels
        shelves.show(key, groups.map { (id, title) -> CatalogShelves.Shelf(id, title) }, initial)
        catalogLabels(rail); spotMode()
        if (focus || catalogFocusRequested) { catalogFocusRequested = false; shelves.post { if (shelvesShown) shelves.focusCurrent() } }
    }
    internal fun shelf(id: String, value: CatalogShelves.Data) = shelves.update(id, value)
    val shelfCatalog get() = shelves.catalog.takeIf { shelvesShown }
    @SuppressLint("NotifyDataSetChanged")
    fun catalogLabels(groups: Map<String, String>) {
        val names = groups.keys.toList()
        if (categoryNames != names) { categoryNames = names; categoryLabels = groups; categories.adapter?.notifyDataSetChanged() }
        else if (categoryLabels != groups) { categoryLabels = groups; categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1) }
        updateBrowseControls()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun catalogPage(values: List<BrowserRow>, append: Boolean, more: Boolean) {
        showGrid()
        val old = shownRows
        val samePrefix = values.size >= old.size && old.indices.all { old[it].key == values[it].key }
        val focus = rows.hasFocus(); val restore = rememberedKey
        contentsAvailable = true; rows.alpha = 1f
        shownRows = values; allRows = emptyList(); hasMore = more
        if (samePrefix) {
            for (index in old.indices) {
                val before = old[index]; val after = values[index]
                if (before.title != after.title || before.detail != after.detail || before.poster != after.poster || before.description != after.description) rows.adapter?.notifyItemChanged(index)
            }
            if (values.size > old.size) rows.adapter?.notifyItemRangeInserted(old.size, values.size - old.size)
        } else {
            ++focusEpoch; pending = null; rows.adapter?.notifyDataSetChanged()
            // New contents (another category or search): the spotlight must not keep describing a title that left the grid.
            if (!detailPane.isVisible && seriesSpot == null) { spotKey = null; spotMode() }
        }
        empty.visibility = if (values.isEmpty()) VISIBLE else GONE; empty.text = "No matching titles."
        if (!append && ((focus && !samePrefix) || catalogFocusRequested) && values.isNotEmpty()) { catalogFocusRequested = false; rememberedKey = restore; focusRows() }
    }
    fun catalogBusy(block: Boolean = false) { hasMore = false; loading.visibility = VISIBLE; if (block) { contentsAvailable = false; rows.alpha = .35f } }
    fun stopCatalog() { if (catalogQuery != null) { rememberAnchor(); contexts[catalogContext] = group to search.text.toString() }; catalogQuery = null; catalogMore = null; hasMore = false; searchHandler.removeCallbacks(searchLater) }
    fun selectTab(key: String) {
        selectedTab = key
        sectionControls.forEach { it.isSelected = it.tag == "library-tab:$key" }
    }
    private var savedNow = false
    private var detailWatch: Button? = null
    private fun watchLabel(saved: Boolean) = if (saved) "✓ In Watchlist" else "Add to Watchlist"
    fun favoriteTitle(item: OnDemand?, saved: Boolean, canMarkWatched: Boolean = false, allWatched: Boolean = false) {
        savedNow = saved; detailWatch?.text = watchLabel(saved)
        saveTitle.isVisible = item != null; saveTitle.text = watchLabel(saved)
        saveTitle.contentDescription = if (saved) "Remove ${item?.title} from Watchlist" else "Add ${item?.title} to Watchlist"
        markWatched.isVisible = item?.kind == "series" && saved && canMarkWatched
        markWatched.text = if (allWatched) "Mark unwatched" else "Mark all watched"
    }
    fun favorites(ids: Set<String>, labels: Map<String, String>) {
        if (favoriteIds == ids && favoriteLabels == labels) return
        favoriteIds = ids; favoriteLabels = labels; rows.adapter?.notifyItemRangeChanged(0, shownRows.size)
        shelves.favoriteIds = ids; shelves.favoriteLabels = labels; shelves.refreshTiles()
    }
    fun seriesArtwork(item: OnDemand?) {
        seriesSpotlight(item)
        bannerUrl = item?.poster.orEmpty(); banner.isVisible = bannerUrl.isNotBlank()
        val url = bannerUrl
        fun draw() { if (bannerUrl == url) banner.setImageBitmap(images.get(url, artworkHttp, tileDecode) {}) }
        banner.setImageBitmap(images.get(url, artworkHttp, tileDecode, ::draw))
    }
    fun refreshPosters() { rows.adapter?.notifyItemRangeChanged(0, shownRows.size) }
    fun archiveMode(value: Boolean) {
        archive = value; archiveHome.isVisible = value; spotMode()
        sectionControls.filter { it !== archiveHome }.forEach { it.isVisible = !value }
        showSearchBox()
    }
    fun focusToolbar() {
        ++focusEpoch; pending = null; catalogFocusRequested = false; contentWantedAt = 0; hideKeyboard()
        (if (archive) archiveHome else sectionControls.firstOrNull { it.tag == "library-tab:$selectedTab" })?.requestFocus()
    }
    /** Back from the titles goes to the tab line first; Back again leaves Library. */
    fun focusRailFromContent(): Boolean {
        if (archive || !hasFocus() || tabStrip.hasFocus()) return false
        focusToolbar()
        return tabStrip.hasFocus()
    }
    /** The search box shows as the archive filter, or while it holds text a search put there. */
    private fun showSearchBox() {
        val shown = !detailPane.isVisible && (archive || search.text.isNotEmpty())
        if (search.isVisible == shown) return
        if (!shown && search.hasFocus()) { search.visibility = GONE; focusRows() } else search.visibility = if (shown) VISIBLE else GONE
    }
    private fun updateBrowseControls() {
        categories.isVisible = body.isVisible && categoryNames.isNotEmpty()
        sortButton.isVisible = body.isVisible && catalogQuery != null
    }
    private fun focusBrowseRail() { if (categories.isVisible) focusCategories() else navigation?.invoke() }
    private fun focusCategories() {
        hideKeyboard(); ++focusEpoch; pending = null; catalogFocusRequested = false
        focusAt(categories, if (group == null) 0 else categoryNames.indexOf(group).plus(1).coerceAtLeast(0))
    }
    /** Entering Library from the main menu lands on the first poster as soon as one is shown, not on a menu or filter. */
    fun requestContentFocus() { wantContent(); if (shelvesShown) { catalogFocusRequested = false; shelves.post { if (shelvesShown) shelves.focusCurrent() }; return }; catalogFocusRequested = true; if (contentsAvailable && shownRows.isNotEmpty()) { catalogFocusRequested = false; focusRows() } }
    fun focusRows() { wantContent(); if (shelvesShown) { shelves.focusCurrent(); return }; if (catalogQuery != null && (!contentsAvailable || shownRows.isEmpty())) { catalogFocusRequested = true; return }; if (shownRows.isEmpty()) search.requestFocus() else focusAt(rows, shownRows.indexOfFirst { it.key == rememberedKey }.coerceAtLeast(0)) }
    /** Seven posters across (or one list row): each column gets an equal share of the grid's width. */
    private fun sizeColumns() {
        rows.setNumColumns(columns)
        val available = (if (rows.width > 0) rows.width else resources.displayMetrics.widthPixels - dp(LIBRARY_INSET_DP + (if (categories.isVisible) CATEGORY_WIDTH_DP + 8 else 0))) - rows.paddingLeft - rows.paddingRight
        rows.setColumnWidth((available / columns).coerceAtLeast(dp(72)))
    }
    /** Select a tile or category and give it the cursor as soon as the list has it on screen. */
    private fun focusAt(list: BaseGridView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0)) return
        val epoch = ++focusEpoch; pending = list
        list.setSelectedPosition(position) { holder ->
            if (epoch == focusEpoch) { pending = null; if (isShown && list.isShown) holder.itemView.requestFocus() }
        }
    }
    fun hideKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) }
    fun appearance(opacity: Int = -1) {
        background = GuideTheme.guideBackground(opacity); AppFont.apply(this)
        search.background = GuideTheme.background(resources.displayMetrics.density, GuideTheme.current.background or 0x66000000); search.setPadding(dp(12), 0, dp(12), 0); search.setTextColor(GuideTheme.ink())
        heading.textSize = 15f * GuideTheme.textScale; message.textSize = 11f * GuideTheme.textScale; search.textSize = 13f * GuideTheme.textScale
        spotTitle.textSize = 28f * GuideTheme.textScale; spotMeta.textSize = 13f * GuideTheme.textScale; spotPlot.textSize = 13f * GuideTheme.textScale
        spotTitle.typeface = AppFont.face(true); heading.typeface = AppFont.face(true)
        // Tabs read as text on the banner: a soft fill marks the open one, the accent marks the cursor.
        val density = resources.displayMetrics.density
        sectionControls.forEach { it.background = GuideTheme.background(density); it.setTextColor(GuideTheme.ink()); it.setPadding(dp(14), 0, dp(14), 0); it.minWidth = 0; it.minimumWidth = 0 }
        listOf(refreshButton, searchButton).forEach { it.background = GuideTheme.background(density, 0x33000000); it.imageTintList = GuideTheme.ink() }
        val base = (GuideTheme.current.background or 0xFF000000.toInt()) and 0xFFFFFF
        fun shade(alpha: Int) = base or (alpha shl 24)
        spotScrim.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(shade(0xFF), shade(0xF2), shade(0x8C), shade(0x10))),
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(shade(0xFF), shade(0x00), shade(0x00)))))
        loading.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1); rows.adapter?.notifyItemRangeChanged(0, shownRows.size); shelves.refreshTiles()
    }
    fun allowArtworkHttp(value: Boolean) { artworkHttp = value; shelves.http = value }
    fun browse() { ++detailGeneration; detailPane.visibility = GONE; body.visibility = VISIBLE; titleLine.visibility = VISIBLE; showSearchBox(); updateBrowseControls(); spotKey = null; spotMode() }
    /** Spotlight shows in poster grids and on a details page; plain lists (episodes, archive) keep the compact header. */
    private fun spotMode() {
        val on = !archive && (detailPane.isVisible || (body.isVisible && (grid || seriesSpot != null)))
        spotlight.isVisible = on
        // A series page gets a taller hero: its artwork is the page, not a strip above a poster grid.
        val height = dp(if (!on) HERO_COMPACT_DP else if (seriesSpot != null && !detailPane.isVisible) HERO_SERIES_DP else HERO_SPOTLIGHT_DP)
        if (hero.layoutParams != null && hero.layoutParams.height != height) hero.layoutParams = hero.layoutParams.apply { this.height = height }
        if (!on) { clearSpot(); return }
        spotPlot.isVisible = !detailPane.isVisible
        if (!detailPane.isVisible && seriesSpot != null) { showSeriesSpot(); return }
        if (spotKey == null) {
            clearSpot()
            // Keep the spotlight in step with a poster that already holds the cursor.
            // On the home rows the poster keeps the cursor when Library reopens, so no new focus event arrives to fill the banner.
            if (shelvesShown && shelves.hasFocus()) shelfFocus?.let(::showSpot)
            else rows.focusedChild?.let(rows::getChildAdapterPosition)?.let { shownRows.getOrNull(it) }?.let(::showSpot)
        }
    }
    private fun clearSpot() {
        searchHandler.removeCallbacks(spotLater); searchHandler.removeCallbacks(spotApply); spotKey = null; spotRow = null
        spotTitle.text = ""; spotMeta.text = ""; spotPlot.text = ""; backdrops.bind(spotArt, ""); spotArt.visibility = GONE; spotScrim.visibility = GONE
    }
    private var seriesSpot: Pair<OnDemand, Spotlight?>? = null
    /** An episode list keeps its series in the spotlight: backdrop, facts and plot. */
    fun seriesSpotlight(series: OnDemand?, spot: Spotlight? = null) {
        seriesSpot = series?.let { it to (spot ?: seriesSpot?.takeIf { old -> old.first.id == it.id }?.second) }
        spotKey = null; spotMode()
    }
    private fun showSeriesSpot() {
        val (series, spot) = seriesSpot ?: return
        spotKey = series.id; spotRow = null; searchHandler.removeCallbacks(spotLater); searchHandler.removeCallbacks(spotApply)
        spotTitle.text = series.title
        spotMeta.text = spot?.meta?.takeIf { it.isNotBlank() } ?: listOf(series.year, series.category).filter { it.isNotBlank() }.joinToString("  ·  ")
        spotPlot.text = spot?.plot.orEmpty().ifBlank { series.description }
        spotArtwork(spot?.backdrop.orEmpty().ifBlank { series.backdrop.ifBlank { series.poster } })
    }
    private fun showSpot(row: BrowserRow) {
        if (!spotlight.isVisible || detailPane.isVisible || seriesSpot != null) return
        // While a key is held the cursor is only passing through: the banner and its text change once it stops.
        val held = images.quietFor()
        if (held > 0) { spotWaiting = row; searchHandler.removeCallbacks(spotApply); searchHandler.postDelayed(spotApply, held + 20); return }
        spotWaiting = null
        if (spotKey == row.key) return
        spotKey = row.key; spotRow = row
        spotTitle.text = row.title; spotMeta.text = row.detail; spotPlot.text = row.description
        // The new title's words fade in rather than snapping, as the cursor moves along a row.
        spotlight.animate().cancel(); spotlight.alpha = .55f; spotlight.animate().alpha(1f).setDuration(150).start()
        spotArtwork(row.backdrop.ifBlank { row.poster })
        // Wait for the cursor to settle so fast scrolling never queues provider requests.
        searchHandler.removeCallbacks(spotLater); searchHandler.removeCallbacks(spotApply)
        if (row.spotlight != null) searchHandler.postDelayed(spotLater, 350)
    }
    private fun spotArtwork(url: String) {
        if (url.isBlank()) { backdrops.bind(spotArt, ""); spotArt.visibility = GONE; spotScrim.visibility = GONE; return }
        spotArt.visibility = VISIBLE; spotScrim.visibility = VISIBLE
        backdrops.bind(spotArt, url, artworkHttp) { bitmap -> if (bitmap == null && spotArt.drawable == null) { spotArt.visibility = GONE; spotScrim.visibility = GONE } }
    }
    private fun requestSpotDetails() {
        val row = spotRow ?: return; val key = row.key
        row.spotlight?.invoke { spot ->
            if (spotKey != key || detailPane.isVisible) return@invoke
            if (spot.meta.isNotBlank()) spotMeta.text = if (row.personal && row.detail.isNotBlank()) "${row.detail}  ·  ${spot.meta}" else spot.meta
            if (spot.plot.isNotBlank()) spotPlot.text = spot.plot
            if (spot.backdrop.isNotBlank()) spotArtwork(spot.backdrop)
        }
    }
    fun detail(item: OnDemand, makeButton: (String, () -> Unit) -> Button, play: () -> Unit, busy: Boolean) {
        val restore = detailPane.visibility != VISIBLE || detailPane.hasFocus()
        if (detailPane.visibility != VISIBLE) spotKey = null
        val focusedAction = detailPane.findFocus()?.tag
        val generation = ++detailGeneration
        hideKeyboard(); body.visibility = GONE; if (search.hasFocus()) focusToolbar(); search.visibility = GONE; titleLine.visibility = GONE; detailPane.visibility = VISIBLE; updateBrowseControls(); spotMode()
        // Keep focus on the toolbar while replacing focused detail controls.
        if (detailPane.hasFocus()) focusToolbar()
        detailPane.removeAllViews(); detailPane.background = null
        page(tabs.firstOrNull { it.second == selectedTab }?.first ?: "Library", if (busy) "Loading details…" else "", busy)
        // The details page uses the spotlight for its backdrop, title and facts; the pane holds poster, plot and actions.
        spotKey = item.id; spotRow = null; searchHandler.removeCallbacks(spotLater); searchHandler.removeCallbacks(spotApply)
        spotTitle.text = item.title
        spotMeta.text = LibraryFacts.meta(item)
        spotArtwork(item.backdrop.ifBlank { item.poster })
        val hero = LinearLayout(context)
        val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF202835.toInt()) }
        fun redraw() { images.get(item.poster, artworkHttp) {}?.let(cover::setImageBitmap) }
        // The tile's copy fills the cover at once; the sharper one replaces it a moment later.
        cover.setImageBitmap(images.get(item.poster, artworkHttp, redraw = ::redraw) ?: images.peek(item.poster, tileDecode)); hero.addView(cover, LayoutParams(dp(150), dp(225)).apply { marginEnd = dp(24) })
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        copy.addView(label(14f).apply { text = item.description.ifBlank { "No description supplied by the provider." }; maxLines = 8; setLineSpacing(0f, 1.15f) }, LayoutParams(-1, -2))
        LibraryFacts.cast(item.cast).takeIf { it.isNotBlank() }?.let { starring ->
            copy.addView(label(12f).apply { tag = "library-cast"; text = starring; maxLines = 2; setTextColor(Color.LTGRAY); setPadding(0, dp(14), 0, 0) })
        }
        // Actions lead the column beside the poster, so they stay in view above a long plot.
        val actions = LinearLayout(context)
        val start = makeButton("▶  Play / Resume", play).apply { tag = "library-play" }; actions.addView(start, LayoutParams(-2, dp(38)).apply { marginEnd = dp(10) }); 
        // The remote's Back button leaves this page, so the second action is the useful one: Watchlist.
        val watch = makeButton(watchLabel(savedNow)) { favoriteAction?.invoke() }.apply { tag = "library-detail-watchlist" }
        detailWatch = watch; actions.addView(watch, LayoutParams(-2, dp(38)))
        copy.addView(actions, 0, LayoutParams(-1, dp(42)).apply { bottomMargin = dp(14) })
        hero.addView(copy, LayoutParams(0, -2, 1f)); detailPane.addView(hero, LayoutParams(-1, 0, 1f))
        if (restore) (detailPane.findViewWithTag<View>(focusedAction) ?: start).requestFocus()
    }
    fun pauseImages() { rememberAnchor(); searchHandler.removeCallbacks(searchLater); searchHandler.removeCallbacks(spotLater); searchHandler.removeCallbacks(spotApply); ++focusEpoch; pending = null; spotKey = null; images.pause(); backdrops.pause() }
    fun warmPosters(urls: List<String>, http: Boolean) { urls.forEach { images.warm(it, http, tileDecode) } }
    /** Frame times are counted only while Library is actually on screen (see [LibraryFrames]). */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val window = (context as? android.app.Activity)?.window
        if (isVisible && window != null) LibraryFrames.start(window, display?.refreshRate ?: 0f) else LibraryFrames.stop()
    }
    fun resumeImages() { images.resume(); backdrops.resume() }
    fun trimImages() { images.trim(); backdrops.trim() }
    /** Closing also drops pending delayed work (search, spotlight), which must never run after its owner is gone. */
    fun closeImages() { searchHandler.removeCallbacksAndMessages(null); images.close(); backdrops.close() }
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private companion object {
        const val HERO_COMPACT_DP = 44; const val HERO_SPOTLIGHT_DP = 200; const val HERO_SERIES_DP = 250; const val CATEGORY_WIDTH_DP = 200
        /** The panel's own left and right padding beside the main menu, plus the grid's padding. */
        const val LIBRARY_INSET_DP = 84 + 24 + 32
    }
}

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
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Richer text for the spotlight, fetched once when a poster settles under the cursor. */
data class Spotlight(val meta: String, val plot: String, val backdrop: String)
data class BrowserRow(val key: String, val title: String, val detail: String = "", val poster: String = "", val description: String = "", val requestPoster: (((String) -> Unit) -> Unit)? = null, val longPress: (() -> Unit)? = null,
    val backdrop: String = "", val spotlight: (((Spotlight) -> Unit) -> Unit)? = null, val select: () -> Unit) { val searchKey: String = ChannelSearch.key(title); val stableId = Channel.stableLongId(key) }

/** Bounded native TV lists, including large provider catalogs. Search remains inside the page. */
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
    private val spotTitle = label(24f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "library-spot-title" }
    private val spotMeta = label(12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xFFC9D1DB.toInt()); tag = "library-spot-meta" }
    private val spotPlot = label(12f).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(0xFFE3E8EE.toInt()); setLineSpacing(0f, 1.1f); tag = "library-spot-plot" }
    private val spotlight = LinearLayout(context).apply { orientation = VERTICAL; tag = "library-spotlight"; visibility = GONE }
    private var spotKey: String? = null
    private val spotLater = Runnable { requestSpotDetails() }
    private var spotRow: BrowserRow? = null
    // Fixed heights: a wrap-content FrameLayout would stretch its full-bleed artwork to the whole screen.
    private val hero = FrameLayout(context)
    private val loading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = GONE; tag = "library-loading" }
    var globalSearch: ((String) -> Unit)? = null
    private val search = EditText(context).apply { hint = "Search channels, movies and series"; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true; tag = "library-search"; typeface = AppFont.face(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; showSoftInputOnFocus = false }
    private val categories = RecyclerView(context).apply {
        layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER
        tag = "library-categories"; visibility = GONE; isFocusableInTouchMode = true; isClickable = true
        setPadding(dp(8), dp(8), dp(8), dp(8)); elevation = dp(8).toFloat()
    }
    private val categoryButton = button("All categories ▾", ::openCategories).apply {
        tag = "library-category"; visibility = GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val rows = RecyclerView(context).apply { preserveFocusAfterLayout = false; layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-items"
        // Leave room for the focused poster's lift instead of clipping it at the grid edge.
        clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)) }
    private val empty = label(13f).apply { setPadding(dp(14), dp(16), dp(14), dp(16)); tag = "library-empty" }
    private var allRows = emptyList<BrowserRow>()
    private var shownRows = emptyList<BrowserRow>()
    private var categoryNames = emptyList<String>()
    private var categoryLabels = emptyMap<String, String>()
    private var catalogFocusRequested = false
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
    // A 1080p tile is ~300x450 px: 480 px keeps posters sharp; 28 MB of RGB_565 holds ~90 of them (several screens).
    private val images = LogoLoader(480, 28 * 1024 * 1024, posterFiles(context), opaque = true)
    private val backdrops = LogoLoader(960, 12 * 1024 * 1024, posterFiles(context), opaque = true)
    private val detailPane = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE; tag = "library-details" }
    private var detailGeneration = 0
    private val body = LinearLayout(context)
    private var artworkHttp = false
    private var rememberedKey: String? = null
    private val sectionRail = LinearLayout(context).apply {
        tag = "library-section-rail"; orientation = VERTICAL; setPadding(0, dp(4), dp(8), 0)
    }
    private val sectionControls = mutableListOf<Button>()
    private val refreshButton = button("Refresh", refresh).apply { tag = "library-refresh" }
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
        sectionControls.forEach { control ->
            control.textSize = 11f
            sectionRail.addView(control, LayoutParams(-1, dp(36)).apply { bottomMargin = dp(5) })
            control.setOnFocusChangeListener { _, active ->
                if (active) { ++focusEpoch; pending = null; catalogFocusRequested = false }
            }
            control.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val visible = sectionControls.filter { it.isVisible }
                        val step = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        val next = visible.indexOf(control) + step
                        if (next < 0) focusHeader() else visible.getOrNull(next)?.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) navigation?.invoke(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (detailPane.isVisible) detailPane.findViewWithTag<View>("library-play")?.requestFocus() else focusRows()
                    }
                    true
                }
                else -> false
            } }
        }
        val headerControls = listOf(categoryButton, refreshButton, saveTitle, markWatched)
        headerControls.forEach { control ->
            control.setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val visible = headerControls.filter { it.isVisible }
                            val step = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                            val next = visible.indexOf(control) + step
                            if (next < 0) focusToolbar() else visible.getOrNull(next)?.requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (detailPane.isVisible) detailPane.findViewWithTag<View>("library-play")?.requestFocus() else search.requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(context).apply { orientation = VERTICAL; addView(heading, LayoutParams(-1, dp(24))); addView(message, LayoutParams(-1, dp(18))) }, LayoutParams(0, -2, 1f))
        headerControls.forEach { control ->
            header.addView(control, LayoutParams(if (control === categoryButton) dp(156) else -2, dp(36)).apply { marginStart = dp(8) })
        }
        header.addView(banner, LayoutParams(dp(32), dp(48)).apply { marginStart = dp(12) })
        spotlight.addView(spotTitle, LayoutParams(-1, -2))
        spotlight.addView(spotMeta, LayoutParams(-1, -2).apply { topMargin = dp(2) })
        spotlight.addView(spotPlot, LayoutParams(dp(520), -2).apply { topMargin = dp(6) })
        hero.apply { tag = "library-hero"; clipChildren = true }
        hero.addView(spotArt, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(220) })
        hero.addView(spotScrim, FrameLayout.LayoutParams(-1, -1))
        hero.addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(0, 0, 0, dp(8))
            addView(header, LayoutParams(-1, dp(52)))
            addView(spotlight, LayoutParams(-1, dp(96)))
        }, FrameLayout.LayoutParams(-1, -2))
        body.orientation = VERTICAL
        body.addView(empty)
        body.addView(rows, LayoutParams(-1, 0, 1f))
        val content = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(4), 0, 0, 0)
            addView(hero, LayoutParams(-1, dp(HERO_COMPACT_DP)))
            addView(loading, LayoutParams(-1, dp(3)))
            addView(search, LayoutParams(-1, dp(36)).apply { bottomMargin = dp(4) })
            addView(body, LayoutParams(-1, 0, 1f))
            addView(detailPane, LayoutParams(-1, 0, 1f))
        }
        addView(LinearLayout(context).apply {
            addView(sectionRail, LayoutParams(dp(TvFocus.BROWSE_RAIL_WIDTH_DP), -1))
            addView(FrameLayout(context).apply {
                addView(content, FrameLayout.LayoutParams(-1, -1))
                addView(categories, FrameLayout.LayoutParams(dp(280), dp(310), Gravity.TOP or Gravity.END).apply { topMargin = dp(64) })
            }, LayoutParams(0, -1, 1f))
        }, LayoutParams(-1, 0, 1f))
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
                holder.text.setOnClickListener {
                    val before = if (group == null) 0 else categoryNames.indexOf(group).plus(1)
                    catalogFocusRequested = false; rememberAnchor(); group = name; rememberedKey = anchors[anchorKey()]
                    updateCategoryButton(); closeCategories(); filter()
                    if (before in 0 until itemCount) notifyItemChanged(before); notifyItemChanged(position)
                }
                holder.text.setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null; catalogFocusRequested = false } }
                holder.text.setOnKeyListener { _, key, event ->
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    TvFocus.pickerKey(key, event.action, { focusAt(categories, position + it) }) { closeCategories() }
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
                ), images, artworkHttp, item.requestPoster)
                // Decode the rows the remote is about to reach before they scroll into view.
                if (grid) shownRows.getOrNull(position + columns * 2)?.poster?.takeIf { it.isNotBlank() }?.let { images.prefetch(it, artworkHttp) }
                holder.box.setOnClickListener { if (contentsAvailable) { hideKeyboard(); shownRows.getOrNull(holder.bindingAdapterPosition)?.select?.invoke() } }
                holder.box.setOnLongClickListener { shownRows.getOrNull(holder.bindingAdapterPosition)?.longPress?.let { it(); true } ?: false }
                holder.box.setOnFocusChangeListener { _, active -> if (active) { rememberedKey = item.key; ++focusEpoch; pending = null; shownRows.getOrNull(holder.bindingAdapterPosition)?.let(::showSpot) } }
                holder.box.setOnKeyListener { _, key, event ->
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = if (grid) columns else 1
                        val next = position + if (key == KeyEvent.KEYCODE_DPAD_DOWN) step else -step
                        if (next < 0) { if (event.repeatCount == 0) search.requestFocus() } else focusAt(rows, next.coerceAtMost(shownRows.lastIndex))
                    }; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (!grid || position % columns == 0) focusToolbar() else focusAt(rows, position - 1) }; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && grid && position % columns < columns - 1) focusAt(rows, position + 1); true }
                    // Page and Channel keys jump a screen of posters at a time through long catalogs.
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (event.action == KeyEvent.ACTION_DOWN && shownRows.isNotEmpty()) {
                        val layout = rows.layoutManager as GridLayoutManager
                        val page = ((layout.findLastVisibleItemPosition() - layout.findFirstVisibleItemPosition()) / columns).coerceAtLeast(1) * columns
                        val down = key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_CHANNEL_DOWN
                        focusAt(rows, (position + if (down) page else -page).coerceIn(0, shownRows.lastIndex))
                    }; true }
                    else -> false
                } }
            }
        }
        rows.layoutManager = PosterGridLayout(context, columns)
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(list: RecyclerView, dx: Int, dy: Int) {
                if (hasMore && (list.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= shownRows.size - 16) { hasMore = false; catalogMore?.invoke() }
            }
        })
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!changingSearch) { searchHandler.removeCallbacks(searchLater); searchHandler.postDelayed(searchLater, 180) } }; override fun afterTextChanged(s: Editable?) {} })
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); focusAt(rows, 0); true }
        search.setOnClickListener { if (!archive) globalSearch?.invoke(search.text.toString()) else (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
        search.setOnKeyListener { _, key, event -> when (key) { KeyEvent.KEYCODE_DPAD_CENTER -> if (!archive && globalSearch != null) { if (event.action == KeyEvent.ACTION_UP) globalSearch?.invoke(search.text.toString()); true } else false; KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusRows() }; true }; KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusHeader() }; true }; else -> false } }
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
    fun submit(values: List<BrowserRow>, groups: Map<String, String> = emptyMap(), reset: Boolean = true, posters: Boolean = true) {
        stopCatalog()
        allCategoryTitle = "All"
        browse()
        grid = posters; columns = if (grid) PosterGridLayout.COLUMNS else 1
        if (posters) seriesSpot = null
        (rows.layoutManager as? GridLayoutManager)?.spanCount = columns; spotMode()
        if (reset) { group = null; search.setText(""); rememberedKey = null }
        contentsAvailable = true; rows.alpha = 1f
        allRows = values; rowGroups = groups; categoryNames = groups.values.distinct(); categoryLabels = emptyMap()
        if (group !in categoryNames) group = null
        updateCategoryButton()
        categories.adapter?.notifyDataSetChanged(); filter()
    }
    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
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

    fun bindCatalog(key: String, groups: Map<String, String>, query: (String?, String) -> Unit, more: () -> Unit, allTitle: String = "All") {
        allCategoryTitle = allTitle
        grid = true; seriesSpot = null; browse(); columns = PosterGridLayout.COLUMNS; (rows.layoutManager as GridLayoutManager).spanCount = columns
        if (catalogContext != key || catalogQuery == null) {
            if (catalogQuery != null) { rememberAnchor(); contexts[catalogContext] = group to search.text.toString() }; catalogContext = key
            val saved = contexts[key]; group = saved?.first?.takeIf { it in groups }; changingSearch = true; search.setText(saved?.second.orEmpty()); changingSearch = false
            rememberedKey = anchors[anchorKey()]
        }
        catalogQuery = query; catalogMore = more; catalogLabels(groups); filter()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun catalogLabels(groups: Map<String, String>) {
        val names = groups.keys.toList()
        if (categoryNames != names) { categoryNames = names; categoryLabels = groups; categories.adapter?.notifyDataSetChanged() }
        else if (categoryLabels != groups) { categoryLabels = groups; categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1) }
        updateCategoryButton()
    }
    @SuppressLint("NotifyDataSetChanged")
    fun catalogPage(values: List<BrowserRow>, append: Boolean, more: Boolean) {
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
        closeCategories(restoreFocus = false)
        selectedTab = key
        sectionControls.forEach { it.isSelected = it.tag == "library-tab:$key" }
    }
    fun favoriteTitle(item: OnDemand?, saved: Boolean, canMarkWatched: Boolean = false, allWatched: Boolean = false) {
        saveTitle.isVisible = item != null; saveTitle.text = if (saved) "✓ In Watchlist" else "Add to Watchlist"
        saveTitle.contentDescription = if (saved) "Remove ${item?.title} from Watchlist" else "Add ${item?.title} to Watchlist"
        markWatched.isVisible = item?.kind == "series" && saved && canMarkWatched
        markWatched.text = if (allWatched) "Mark unwatched" else "Mark all watched"
    }
    fun favorites(ids: Set<String>, labels: Map<String, String>) {
        if (favoriteIds == ids && favoriteLabels == labels) return
        favoriteIds = ids; favoriteLabels = labels; rows.adapter?.notifyItemRangeChanged(0, shownRows.size)
    }
    fun seriesArtwork(item: OnDemand?) {
        seriesSpotlight(item)
        bannerUrl = item?.poster.orEmpty(); banner.isVisible = bannerUrl.isNotBlank()
        val url = bannerUrl
        fun draw() { if (bannerUrl == url) banner.setImageBitmap(images.get(url, artworkHttp) {}) }
        banner.setImageBitmap(images.get(url, artworkHttp, ::draw))
    }
    fun refreshPosters() { rows.adapter?.notifyItemRangeChanged(0, shownRows.size) }
    fun archiveMode(value: Boolean) {
        archive = value; archiveHome.isVisible = value; spotMode()
        sectionControls.filter { it !== archiveHome }.forEach { it.isVisible = !value }
        search.hint = if (value) "Search archived channels and programmes" else "Search channels, movies and series"
    }
    fun focusToolbar() {
        closeCategories(restoreFocus = false)
        ++focusEpoch; pending = null; catalogFocusRequested = false; hideKeyboard()
        (if (archive) archiveHome else sectionControls.firstOrNull { it.tag == "library-tab:$selectedTab" })?.requestFocus()
    }
    fun focusRailFromContent(): Boolean {
        if (archive || !hasFocus() || sectionRail.hasFocus()) return false
        focusToolbar()
        return sectionRail.hasFocus()
    }
    private fun focusHeader() = (if (categoryButton.isVisible) categoryButton else refreshButton).requestFocus()
    private fun updateCategoryButton() {
        val label = group?.let { categoryLabels[it] ?: it } ?: allCategoryTitle.takeUnless { it == "All" } ?: "All categories"
        categoryButton.text = "$label ▾"
        categoryButton.contentDescription = "Category, $label"
        categoryButton.isVisible = body.isVisible && categoryNames.isNotEmpty()
        if (!categoryButton.isVisible) closeCategories(restoreFocus = false)
    }
    private fun openCategories() {
        if (categoryNames.isEmpty()) return
        hideKeyboard(); ++focusEpoch; pending = null; catalogFocusRequested = false
        categories.background = GuideTheme.guideBackground(100)
        TvFocus.openPicker(categories) {
            categories.requestFocus()
            focusAt(categories, if (group == null) 0 else categoryNames.indexOf(group).plus(1).coerceAtLeast(0))
        }
    }
    fun closeCategories(restoreFocus: Boolean = true): Boolean =
        TvFocus.closePicker(categories, categoryButton.takeIf { restoreFocus }) {
            ++focusEpoch
            pending = null
        }
    /** Entering Library from the main menu lands on the first poster as soon as one is shown, not on a menu or filter. */
    fun requestContentFocus() { catalogFocusRequested = true; if (contentsAvailable && shownRows.isNotEmpty()) { catalogFocusRequested = false; focusRows() } }
    fun focusRows() { if (catalogQuery != null && (!contentsAvailable || shownRows.isEmpty())) { catalogFocusRequested = true; return }; if (shownRows.isEmpty()) search.requestFocus() else focusAt(rows, shownRows.indexOfFirst { it.key == rememberedKey }.coerceAtLeast(0)) }
    private fun focusAt(list: RecyclerView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0) || pending != null) return
        val epoch = ++focusEpoch; val item = if (list.hasPendingAdapterUpdates() || list.isComputingLayout) null else list.findViewHolderForAdapterPosition(position)?.itemView
        if (item != null) { item.requestFocus(); TvFocus.center(list, item); return }
        pending = list; list.stopScroll(); (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, list.height / 2 - if (list === categories) dp(21) else if (grid) gridHeight(list.width / columns - dp(8), resources.displayMetrics.density) / 2 else dp(28))
        fun attempt(left: Int) {
            if (!isShown || epoch != focusEpoch) return
            val row = if (list.hasPendingAdapterUpdates() || list.isComputingLayout) null else list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) { pending = null; row.requestFocus(); TvFocus.center(list, row) } else if (left > 0) list.postOnAnimation { attempt(left - 1) } else pending = null
        }; list.postOnAnimation { attempt(25) }
    }
    fun hideKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) }
    fun appearance(opacity: Int = -1) {
        background = GuideTheme.guideBackground(opacity); AppFont.apply(this)
        search.background = GuideTheme.background(resources.displayMetrics.density, GuideTheme.current.background or 0x66000000); search.setPadding(dp(12), 0, dp(12), 0); search.setTextColor(GuideTheme.ink())
        heading.textSize = 16f * GuideTheme.textScale; message.textSize = 11f * GuideTheme.textScale; search.textSize = 13f * GuideTheme.textScale
        spotTitle.textSize = 24f * GuideTheme.textScale; spotMeta.textSize = 12f * GuideTheme.textScale; spotPlot.textSize = 12f * GuideTheme.textScale
        spotTitle.typeface = AppFont.face(true)
        val base = (GuideTheme.current.background or 0xFF000000.toInt()) and 0xFFFFFF
        fun shade(alpha: Int) = base or (alpha shl 24)
        spotScrim.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(shade(0xFF), shade(0xE6), shade(0x40))),
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(shade(0xFF), shade(0x00), shade(0x00)))))
        loading.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1); rows.adapter?.notifyItemRangeChanged(0, shownRows.size)
    }
    fun allowArtworkHttp(value: Boolean) { artworkHttp = value }
    fun browse() { ++detailGeneration; detailPane.visibility = GONE; body.visibility = VISIBLE; search.visibility = VISIBLE; updateCategoryButton(); spotKey = null; spotMode() }
    /** Spotlight shows in poster grids and on a details page; plain lists (episodes, archive) keep the compact header. */
    private fun spotMode() {
        val on = !archive && (detailPane.isVisible || (body.isVisible && (grid || seriesSpot != null)))
        spotlight.isVisible = on
        val height = dp(if (on) HERO_SPOTLIGHT_DP else HERO_COMPACT_DP)
        if (hero.layoutParams != null && hero.layoutParams.height != height) hero.layoutParams = hero.layoutParams.apply { this.height = height }
        if (!on) { clearSpot(); return }
        spotPlot.isVisible = !detailPane.isVisible
        if (!detailPane.isVisible && seriesSpot != null) { showSeriesSpot(); return }
        if (spotKey == null) {
            clearSpot()
            // Keep the spotlight in step with a poster that already holds the cursor.
            rows.focusedChild?.let(rows::getChildAdapterPosition)?.let { shownRows.getOrNull(it) }?.let(::showSpot)
        }
    }
    private fun clearSpot() {
        searchHandler.removeCallbacks(spotLater); spotKey = null; spotRow = null
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
        spotKey = series.id; spotRow = null; searchHandler.removeCallbacks(spotLater)
        spotTitle.text = series.title
        spotMeta.text = spot?.meta?.takeIf { it.isNotBlank() } ?: listOf(series.year, series.category).filter { it.isNotBlank() }.joinToString("  ·  ")
        spotPlot.text = spot?.plot.orEmpty().ifBlank { series.description }
        spotArtwork(spot?.backdrop.orEmpty().ifBlank { series.backdrop.ifBlank { series.poster } })
    }
    private fun showSpot(row: BrowserRow) {
        if (!spotlight.isVisible || detailPane.isVisible || seriesSpot != null) return
        if (spotKey == row.key) return
        spotKey = row.key; spotRow = row
        spotTitle.text = row.title; spotMeta.text = row.detail; spotPlot.text = row.description
        spotArtwork(row.backdrop.ifBlank { row.poster })
        // Wait for the cursor to settle so fast scrolling never queues provider requests.
        searchHandler.removeCallbacks(spotLater)
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
            if (spot.meta.isNotBlank()) spotMeta.text = spot.meta
            if (spot.plot.isNotBlank()) spotPlot.text = spot.plot
            if (spot.backdrop.isNotBlank()) spotArtwork(spot.backdrop)
        }
    }
    fun detail(item: OnDemand, makeButton: (String, () -> Unit) -> Button, play: () -> Unit, back: () -> Unit, busy: Boolean) {
        val restore = detailPane.visibility != VISIBLE || detailPane.hasFocus()
        if (detailPane.visibility != VISIBLE) spotKey = null
        val focusedAction = detailPane.findFocus()?.tag
        val generation = ++detailGeneration
        hideKeyboard(); closeCategories(restoreFocus = false); body.visibility = GONE; search.visibility = GONE; detailPane.visibility = VISIBLE; updateCategoryButton(); spotMode()
        // Keep focus on the toolbar while replacing focused detail controls.
        if (detailPane.hasFocus()) focusToolbar()
        detailPane.removeAllViews(); detailPane.background = null
        page(tabs.firstOrNull { it.second == selectedTab }?.first ?: "Library", if (busy) "Loading details…" else "", busy)
        // The details page uses the spotlight for its backdrop, title and facts; the pane holds poster, plot and actions.
        spotKey = item.id; spotRow = null; searchHandler.removeCallbacks(spotLater)
        spotTitle.text = item.title
        spotMeta.text = listOf(item.year, item.genre.substringBefore(',').trim(), item.runtime, item.category).filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
        spotArtwork(item.backdrop.ifBlank { item.poster })
        val hero = LinearLayout(context)
        val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF202835.toInt()) }
        fun redraw() { cover.setImageBitmap(images.get(item.poster, artworkHttp) {}) }
        cover.setImageBitmap(images.get(item.poster, artworkHttp, ::redraw)); hero.addView(cover, LayoutParams(dp(150), dp(225)).apply { marginEnd = dp(24) })
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        copy.addView(label(14f).apply { text = item.description.ifBlank { "No description supplied by the provider." }; maxLines = 8; setLineSpacing(0f, 1.15f) }, LayoutParams(-1, -2))
        if (item.cast.isNotBlank()) copy.addView(label(11f).apply { text = "Cast · ${item.cast}"; maxLines = 3; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, 0) })
        // Actions lead the column beside the poster, so they stay in view above a long plot.
        val actions = LinearLayout(context)
        val start = makeButton("▶  Play / Resume", play).apply { tag = "library-play" }; actions.addView(start, LayoutParams(-2, dp(38)).apply { marginEnd = dp(10) }); actions.addView(makeButton("Back to catalog", back).apply { tag = "library-back" }, LayoutParams(-2, dp(38)))
        copy.addView(actions, 0, LayoutParams(-1, dp(42)).apply { bottomMargin = dp(14) })
        hero.addView(copy, LayoutParams(0, -2, 1f)); detailPane.addView(hero, LayoutParams(-1, 0, 1f))
        if (restore) (detailPane.findViewWithTag<View>(focusedAction) ?: start).requestFocus()
    }
    fun pauseImages() { closeCategories(restoreFocus = false); rememberAnchor(); searchHandler.removeCallbacks(searchLater); searchHandler.removeCallbacks(spotLater); ++focusEpoch; pending = null; spotKey = null; images.pause(); backdrops.pause() }
    fun resumeImages() { images.resume(); backdrops.resume() }
    fun trimImages() { images.trim(); backdrops.trim() }
    fun closeImages() { images.close(); backdrops.close() }
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private companion object { const val HERO_COMPACT_DP = 60; const val HERO_SPOTLIGHT_DP = 156 }
}

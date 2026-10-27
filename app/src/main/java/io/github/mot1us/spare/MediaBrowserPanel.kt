package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class BrowserRow(val key: String, val title: String, val detail: String = "", val poster: String = "", val description: String = "", val requestPoster: (((String) -> Unit) -> Unit)? = null, val select: () -> Unit) { val searchKey: String = ChannelSearch.key(title); val stableId = Channel.stableLongId(key) }

/** Bounded native TV lists, including large provider catalogs. Search remains inside the page. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class MediaBrowserPanel(context: Context, button: (String, () -> Unit) -> Button, private val mode: (String) -> Unit,
    refresh: () -> Unit, private val close: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(Color.WHITE); typeface = AppFont.face() }
    var navigation: (() -> Unit)? = null
    private var allCategoryTitle = "All"
    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE; tag = "library-series-art" }
    private var bannerUrl = ""
    private val heading = label(22f).apply { tag = "library-heading" }
    private val message = label(11f).apply { maxLines = 2; setTextColor(Color.LTGRAY); tag = "library-status" }
    private val loading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = GONE; tag = "library-loading" }
    private val search = EditText(context).apply { hint = "Search this list"; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true; tag = "library-search"; typeface = AppFont.face(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    private val categories = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-categories" }
    private val rows = RecyclerView(context).apply { preserveFocusAfterLayout = false; layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-items" }
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
    private var columns = 4
    private var grid = true
    private val images = LogoLoader(512, 12 * 1024 * 1024)
    private val detailPane = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE; tag = "library-details" }
    private var detailGeneration = 0
    private val body = LinearLayout(context)
    private var artworkHttp = false
    private var rememberedKey: String? = null
    private val toolbarControls = mutableListOf<Button>()
    private var selectedTab = "movie"
    private val tabs = listOf("Movies" to "movie", "Series" to "series", "Continue" to "continue", "Catch-up" to "catchup")
    init {
        orientation = VERTICAL; tag = "library-panel"; visibility = GONE; setPadding(dp(24), dp(18), dp(24), dp(18)); setBackgroundColor(0xEE0B1018.toInt())
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbarControls.add(button("Live TV", close).apply { tag = "library-live" })
        tabs.forEach { (title, key) -> toolbarControls.add(button(title) { selectedTab = key; hideKeyboard(); mode(key) }.apply { tag = "library-tab:$key" }) }
        toolbarControls.add(button("Refresh", refresh).apply { tag = "library-refresh" })
        toolbarControls.forEachIndexed { index, control ->
            toolbar.addView(control)
            control.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) { val next = index + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1; if (next < 0) navigation?.invoke() else toolbarControls.getOrNull(next)?.requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { if (detailPane.isVisible) detailPane.findViewWithTag<View>("library-play")?.requestFocus() else search.requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            } }
        }
        addView(toolbar, LayoutParams(-1, dp(42)))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(context).apply { orientation = VERTICAL; addView(heading, LayoutParams(-1, dp(36))); addView(message, LayoutParams(-1, dp(32))) }, LayoutParams(0, -2, 1f))
        header.addView(banner, LayoutParams(dp(48), dp(68)).apply { marginStart = dp(12) }); addView(header, LayoutParams(-1, dp(68))); addView(loading, LayoutParams(-1, dp(3)))
        addView(search, LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        body.orientation = HORIZONTAL
        body.addView(categories, LayoutParams(dp(190), -1).apply { marginEnd = dp(16) })
        body.addView(LinearLayout(context).apply { orientation = VERTICAL; addView(empty); addView(rows, LayoutParams(-1, 0, 1f)) }, LayoutParams(0, -1, 1f))
        addView(body, LayoutParams(-1, 0, 1f)); addView(detailPane, LayoutParams(-1, 0, 1f))
        categories.adapter = object : RecyclerView.Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = (if (position == 0) "All" else categoryNames[position - 1]).hashCode().toLong()
            override fun getItemCount() = categoryNames.size + 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(cell(13f, 42))
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val name = if (position == 0) null else categoryNames[position - 1]
                holder.text.text = name?.let { categoryLabels[it] ?: it } ?: allCategoryTitle; holder.text.isSelected = group == name
                holder.text.contentDescription = "Category · ${name ?: "All"}"
                holder.text.setOnClickListener {
                    val before = if (group == null) 0 else categoryNames.indexOf(group).plus(1)
                    catalogFocusRequested = false; rememberAnchor(); group = name; rememberedKey = anchors[anchorKey()]; filter()
                    if (before in 0 until itemCount) notifyItemChanged(before); notifyItemChanged(position)
                    if (catalogQuery == null) focusRows()
                }
                holder.text.setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null; catalogFocusRequested = false } }
                holder.text.setOnKeyListener { _, key, event ->
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { if (position == 0 && key == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) search.requestFocus() else focusAt(categories, position + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1) }; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusRows(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) navigation?.invoke(); true }
                    else -> false
                } }
            }
        }
        rows.adapter = object : RecyclerView.Adapter<PosterHolder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = shownRows[position].stableId
            override fun getItemCount() = shownRows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): PosterHolder {
                val box = LinearLayout(context).apply { orientation = VERTICAL; isFocusableInTouchMode = true; isClickable = true; setPadding(dp(7), dp(7), dp(7), dp(7)); background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B) }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF202835.toInt()) }
                val text = label(12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), dp(5), dp(4), 0) }
                box.addView(image, LayoutParams(-1, 0, 1f)); box.addView(text, LayoutParams(-1, dp(42)))
                return PosterHolder(box, image, text)
            }
            override fun onBindViewHolder(holder: PosterHolder, position: Int) {
                val item = shownRows[position]
                holder.box.layoutParams = RecyclerView.LayoutParams(-1, dp(if (grid) 210 else 55)).apply { marginEnd = dp(7); bottomMargin = dp(7) }
                holder.box.orientation = if (grid) VERTICAL else HORIZONTAL
                holder.image.visibility = if (grid || item.poster.isNotBlank()) VISIBLE else GONE
                holder.image.layoutParams = if (grid) LayoutParams(-1, 0, 1f) else LayoutParams(dp(70), -1).apply { marginEnd = dp(8) }
                holder.text.text = item.title + if (item.detail.isBlank()) "" else "\n${item.detail}"
                holder.text.layoutParams = if (grid) LayoutParams(-1, dp(45)) else LayoutParams(0, -1, 1f)
                holder.text.setTextColor(if (holder.box.hasFocus()) Color.BLACK else Color.WHITE)
                holder.box.tag = "library-item:${item.key}"; holder.box.contentDescription = "${item.title}. ${item.detail}"
                holder.box.setOnClickListener { if (contentsAvailable) { hideKeyboard(); item.select() } }
                holder.box.setOnFocusChangeListener { _, active -> holder.text.setTextColor(if (active) Color.BLACK else Color.WHITE); if (active) { rememberedKey = item.key; ++focusEpoch; pending = null; if (item.description.isNotBlank()) message.text = item.description } }
                var poster = item.poster; var requested = false
                fun draw() {
                    if (holder.box.tag != "library-item:${item.key}") return
                    holder.image.setImageBitmap(images.get(poster, artworkHttp) {})
                    if (!requested && (poster.isBlank() || images.unavailable(poster))) {
                        requested = true
                        item.requestPoster?.invoke { fallback ->
                            if (fallback.isNotBlank() && fallback != poster && holder.box.tag == "library-item:${item.key}") {
                                poster = fallback
                                fun redraw() { if (holder.box.tag == "library-item:${item.key}") holder.image.setImageBitmap(images.get(poster, artworkHttp) {}) }
                                holder.image.setImageBitmap(images.get(poster, artworkHttp, ::redraw))
                            }
                        }
                    }
                }
                holder.image.setImageBitmap(images.get(poster, artworkHttp, ::draw)); draw()
                holder.box.setOnKeyListener { _, key, event ->
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = if (grid) columns else 1
                        val next = position + if (key == KeyEvent.KEYCODE_DPAD_DOWN) step else -step
                        if (next < 0) { if (event.repeatCount == 0) search.requestFocus() } else focusAt(rows, next.coerceAtMost(shownRows.lastIndex))
                    }; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) { if (!grid || position % columns == 0) focusAt(categories, if (group == null) 0 else categoryNames.indexOf(group).plus(1).coerceAtLeast(0)) else focusAt(rows, position - 1) }; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && grid && position % columns < columns - 1) focusAt(rows, position + 1); true }
                    else -> false
                } }
            }
        }
        rows.layoutManager = object : GridLayoutManager(context, columns) {
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
        }
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(list: RecyclerView, dx: Int, dy: Int) {
                if (hasMore && (list.layoutManager as GridLayoutManager).findLastVisibleItemPosition() >= shownRows.size - 16) { hasMore = false; catalogMore?.invoke() }
            }
        })
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!changingSearch) { searchHandler.removeCallbacks(searchLater); searchHandler.postDelayed(searchLater, 180) } }; override fun afterTextChanged(s: Editable?) {} })
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); focusAt(rows, 0); true }
        search.setOnKeyListener { _, key, event -> when (key) { KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusRows() }; true }; KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusToolbar() }; true }; else -> false } }
    }
    private fun cell(size: Float, height: Int) = label(size).apply {
        isFocusableInTouchMode = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(5), dp(12), dp(5)); setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
        layoutParams = RecyclerView.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(5) }
        setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null } }
    }
    fun page(title: String, detail: String, busy: Boolean) { heading.text = title; message.text = detail; loading.visibility = if (busy) VISIBLE else GONE }
    @SuppressLint("NotifyDataSetChanged")
    fun submit(values: List<BrowserRow>, groups: Map<String, String> = emptyMap(), reset: Boolean = true, posters: Boolean = true) {
        stopCatalog()
        browse()
        grid = posters; columns = if (grid) 4 else 1
        (rows.layoutManager as? GridLayoutManager)?.spanCount = columns
        if (reset) { group = null; search.setText(""); rememberedKey = null }
        contentsAvailable = true; rows.alpha = 1f
        allRows = values; rowGroups = groups; categoryNames = groups.values.distinct(); categoryLabels = emptyMap()
        if (group !in categoryNames) group = null
        categories.adapter?.notifyDataSetChanged(); filter()
    }
    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
        val query = ChannelSearch.key(search.text.toString())
        catalogQuery?.let { ++focusEpoch; pending = null; hasMore = false; it(group, query); return }
        // Filtering preserves provider order. A full Myers diff becomes quadratic when
        // a large catalog loses most entries; only visible holders need rebinding.
        ++focusEpoch; pending = null; rows.stopScroll()
        shownRows = allRows.filter { (group == null || rowGroups[it.key] == group) && (query.isEmpty() || it.searchKey.contains(query)) }
        rows.adapter?.notifyDataSetChanged()
        empty.visibility = if (shownRows.isEmpty()) VISIBLE else GONE; empty.text = if (allRows.isEmpty()) "No titles to show." else "No matching titles."
    }

    fun bindCatalog(key: String, groups: Map<String, String>, query: (String?, String) -> Unit, more: () -> Unit, allTitle: String = "All") {
        allCategoryTitle = allTitle
        browse(); grid = true; columns = 4; (rows.layoutManager as GridLayoutManager).spanCount = 4
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
    }
    @SuppressLint("NotifyDataSetChanged")
    fun catalogPage(values: List<BrowserRow>, append: Boolean, more: Boolean) {
        val old = shownRows
        val samePrefix = values.size >= old.size && old.indices.all { old[it].key == values[it].key }
        val focus = rows.hasFocus(); val restore = rememberedKey
        contentsAvailable = true; rows.alpha = 1f
        shownRows = values; allRows = emptyList(); hasMore = more
        if (samePrefix) {
            if (values.size > old.size) rows.adapter?.notifyItemRangeInserted(old.size, values.size - old.size)
        } else {
            ++focusEpoch; pending = null; rows.adapter?.notifyDataSetChanged()
        }
        empty.visibility = if (values.isEmpty()) VISIBLE else GONE; empty.text = "No matching titles."
        if (!append && ((focus && !samePrefix) || catalogFocusRequested) && values.isNotEmpty()) { catalogFocusRequested = false; rememberedKey = restore; focusRows() }
    }
    fun catalogBusy(block: Boolean = false) { hasMore = false; loading.visibility = VISIBLE; if (block) { contentsAvailable = false; rows.alpha = .35f } }
    fun stopCatalog() { if (catalogQuery != null) { rememberAnchor(); contexts[catalogContext] = group to search.text.toString() }; catalogQuery = null; catalogMore = null; hasMore = false; searchHandler.removeCallbacks(searchLater) }
    fun selectTab(key: String) { selectedTab = key }
    fun seriesArtwork(item: OnDemand?) {
        bannerUrl = item?.poster.orEmpty(); banner.isVisible = bannerUrl.isNotBlank()
        val url = bannerUrl
        fun draw() { if (bannerUrl == url) banner.setImageBitmap(images.get(url, artworkHttp) {}) }
        banner.setImageBitmap(images.get(url, artworkHttp, ::draw))
    }
    fun refreshPosters() { rows.adapter?.notifyItemRangeChanged(0, shownRows.size) }
    fun focusToolbar() { ++focusEpoch; pending = null; catalogFocusRequested = false; hideKeyboard(); toolbarControls.firstOrNull { it.tag == "library-tab:$selectedTab" }?.requestFocus() }
    fun focusCategories() { focusAt(categories, if (group == null) 0 else categoryNames.indexOf(group).plus(1).coerceAtLeast(0)) }
    fun focusRows() { if (catalogQuery != null && (!contentsAvailable || shownRows.isEmpty())) { catalogFocusRequested = true; return }; if (shownRows.isEmpty()) search.requestFocus() else focusAt(rows, shownRows.indexOfFirst { it.key == rememberedKey }.coerceAtLeast(0)) }
    private fun focusAt(list: RecyclerView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0) || pending != null) return
        val epoch = ++focusEpoch; val item = if (list.hasPendingAdapterUpdates() || list.isComputingLayout) null else list.findViewHolderForAdapterPosition(position)?.itemView
        if (item != null) { item.requestFocus(); TvFocus.center(list, item); return }
        pending = list; list.stopScroll(); (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, list.height / 2 - dp(if (list === categories) 21 else if (grid) 105 else 28))
        fun attempt(left: Int) {
            if (!isShown || epoch != focusEpoch) return
            val row = if (list.hasPendingAdapterUpdates() || list.isComputingLayout) null else list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) { pending = null; row.requestFocus(); TvFocus.center(list, row) } else if (left > 0) list.postOnAnimation { attempt(left - 1) } else pending = null
        }; list.postOnAnimation { attempt(25) }
    }
    fun hideKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) }
    fun appearance() { AppFont.apply(this); categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1); rows.adapter?.notifyItemRangeChanged(0, shownRows.size) }
    fun allowArtworkHttp(value: Boolean) { artworkHttp = value }
    fun browse() { ++detailGeneration; detailPane.visibility = GONE; body.visibility = VISIBLE; search.visibility = VISIBLE }
    fun detail(item: OnDemand, makeButton: (String, () -> Unit) -> Button, play: () -> Unit, back: () -> Unit, busy: Boolean) {
        val restore = detailPane.visibility != VISIBLE || detailPane.hasFocus()
        val focusedAction = detailPane.findFocus()?.tag
        val generation = ++detailGeneration
        hideKeyboard(); body.visibility = GONE; search.visibility = GONE; detailPane.visibility = VISIBLE
        // Keep focus on the toolbar while replacing focused detail controls.
        if (detailPane.hasFocus()) findViewWithTag<View>("library-tab:movie")?.requestFocus()
        detailPane.removeAllViews(); detailPane.background = null; page(item.title, listOf(item.year, item.genre, item.runtime).filter { it.isNotBlank() }.joinToString(" · "), busy)
        if (item.backdrop.isNotBlank()) {
            fun background() { if (generation == detailGeneration && detailPane.isVisible) images.get(item.backdrop, artworkHttp) {}?.let { bitmap -> detailPane.background = bitmap.toDrawable(resources).apply { alpha = 40 } } }
            images.get(item.backdrop, artworkHttp, ::background)?.let { bitmap -> detailPane.background = bitmap.toDrawable(resources).apply { alpha = 40 } }
        }
        val hero = LinearLayout(context)
        val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF202835.toInt()) }
        fun redraw() { cover.setImageBitmap(images.get(item.poster, artworkHttp) {}) }
        cover.setImageBitmap(images.get(item.poster, artworkHttp, ::redraw)); hero.addView(cover, LayoutParams(dp(150), dp(225)).apply { marginEnd = dp(24) })
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        copy.addView(label(14f).apply { text = item.description.ifBlank { "No description supplied by the provider." }; maxLines = 9 }, LayoutParams(-1, -2))
        if (item.cast.isNotBlank()) copy.addView(label(11f).apply { text = "Cast · ${item.cast}"; maxLines = 3; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, 0) })
        hero.addView(copy, LayoutParams(0, -2, 1f)); detailPane.addView(hero, LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context)
        val start = makeButton("Play / Resume", play).apply { tag = "library-play" }; actions.addView(start); actions.addView(makeButton("Back to catalog", back).apply { tag = "library-back" }); detailPane.addView(actions, LayoutParams(-1, dp(40)))
        if (restore) (detailPane.findViewWithTag<View>(focusedAction) ?: start).requestFocus()
    }
    fun pauseImages() { rememberAnchor(); searchHandler.removeCallbacks(searchLater); ++focusEpoch; pending = null; images.pause() }
    fun resumeImages() { images.resume() }
    fun closeImages() { images.close() }
    private class PosterHolder(val box: LinearLayout, val image: ImageView, val text: TextView) : RecyclerView.ViewHolder(box)
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}

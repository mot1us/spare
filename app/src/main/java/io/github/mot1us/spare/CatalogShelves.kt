package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isEmpty
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView

/**
 * Movies and Series home: one row per provider category, like streaming apps. Each row asks for its own
 * category only while it is on screen, shows the first titles, and ends with a "See all" tile that opens
 * the full grid. Up/Down moves between categories (each row remembers its position), Left/Right within one.
 *
 * Both directions are Leanback grids (Google's TV list engine): they move the cursor, keep it on screen, scroll
 * while a key is held and put it back after a row's titles change. This class only says where the edges lead.
 */
@SuppressLint("ViewConstructor", "NotifyDataSetChanged", "SetTextI18n", "RestrictedApi")
internal class CatalogShelves(context: Context, private val images: LogoLoader) : VerticalGridView(context) {
    data class Shelf(val id: String, val title: String)
    data class Data(val rows: List<BrowserRow>, val total: Int, val busy: Boolean, val failed: Boolean = false, val more: Boolean = true)

    var load: ((String) -> Unit)? = null
    var release: ((String) -> Unit)? = null
    var open: ((String) -> Unit)? = null
    var exitUp: (() -> Unit)? = null
    var exitLeft: (() -> Unit)? = null
    var focused: ((BrowserRow) -> Unit)? = null
    var http = false
    /** Decode size for tile artwork; the panel sets the size its grid uses so both share decoded posters. */
    var decode = 0
    var favoriteIds = emptySet<String>()
    var favoriteLabels = emptyMap<String, String>()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private var key = ""
    private var shelves = emptyList<Shelf>()
    private val data = mutableMapOf<String, Data>()
    private val positions = mutableMapOf<String, Int>()
    private var current: String? = null
    private var focusEpoch = 0
    /** Every row draws from one stock of poster tiles, so a row scrolling in reuses the tiles of the row that left instead of building new ones. */
    private val tilePool = RecycledViewPool().apply { setMaxRecycledViews(0, COLUMNS * 10); setMaxRecycledViews(1, 8) }

    init {
        tag = "library-shelves"; visibility = GONE; itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER
        setNumColumns(1); setHasFixedSize(true)
        // The focused category row sits at the top of the list, with the next row peeking below it.
        windowAlignment = WINDOW_ALIGN_LOW_EDGE; windowAlignmentOffset = 0; windowAlignmentOffsetPercent = WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        itemAlignmentOffset = 0; itemAlignmentOffsetPercent = ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        setWindowAlignmentPreferKeyLineOverHighEdge(true)
        // Keep the row below the screen laid out so Down does not have to build it. Leanback marks this setter as
        // library-internal in this version although it is public and stable; the version is pinned in the lockfile.
        extraLayoutSpace = dp(140); verticalSpacing = dp(4)
        clipChildren = false; clipToPadding = false
        setOnKeyInterceptListener { event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyInterceptListener false
            // While a key is held the rows are flying past: posters wait until it stops.
            if (event.repeatCount > 0) images.quiet()
            // Up from the first row leaves for the search box; a held key stops at the top instead.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && selectedPosition <= 0) { if (event.repeatCount == 0) exitUp?.invoke(); true } else false
        }
        adapter = Rows()
    }

    /** Keeps rows, loaded titles and positions when the same catalog is shown again (Back from details or a grid). */
    fun show(catalog: String, values: List<Shelf>, initial: Map<String, Data> = emptyMap()) {
        visibility = VISIBLE
        if (catalog == key && values == shelves) {
            // Unchanged rows are left alone: touching a row's tiles here would disturb the position it is about to be focused at.
            initial.forEach { (id, value) -> val before = data[id]; if (before == null || before.total != value.total || before.more != value.more || before.rows.size != value.rows.size || before.rows.indices.any { before.rows[it].key != value.rows[it].key || before.rows[it].detail != value.rows[it].detail }) update(id, value) }
            return
        }
        if (catalog != key) { data.clear(); positions.clear(); current = null; selectedPosition = 0 }
        key = catalog; shelves = values; data.putAll(initial)
        adapter?.notifyDataSetChanged()
    }
    fun clear() { data.clear(); adapter?.notifyDataSetChanged() }
    val currentShelf get() = current
    val catalog get() = key

    fun update(id: String, value: Data) {
        val before = data[id]
        data[id] = value
        val index = shelves.indexOfFirst { it.id == id }
        val holder = findViewHolderForAdapterPosition(index) as? ShelfHolder ?: return
        if (holder.id == id) holder.apply(before, value)
    }
    fun refreshTiles() { for (i in 0 until childCount) (getChildViewHolder(getChildAt(i)) as? ShelfHolder)?.refresh() }

    /** Focus the remembered row and tile; Back lands where the viewer left off. */
    fun focusCurrent(): Boolean {
        if (shelves.isEmpty()) return false
        val index = shelves.indexOfFirst { it.id == current }.coerceAtLeast(0)
        val epoch = ++focusEpoch
        current = shelves[index].id
        // The grid brings the row on screen; the row then hands the cursor to the tile it remembers.
        fun attempt(left: Int) {
            if (epoch != focusEpoch || !isShown) return
            if (selectedPosition != index) selectedPosition = index
            val holder = if (isComputingLayout) null else findViewHolderForAdapterPosition(index) as? ShelfHolder
            if (holder != null && holder.id == shelves[index].id && holder.focus()) return
            if (left > 0) postOnAnimation { attempt(left - 1) }
        }
        attempt(25); return true
    }

    private inner class Rows : Adapter<ShelfHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = Channel.stableLongId("shelf:" + shelves[position].id)
        override fun getItemCount() = shelves.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = ShelfHolder(LinearLayout(context))
        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {
            val shelf = shelves[position]
            holder.bind(shelf, data[shelf.id])
            // Unloaded, interrupted (still marked busy) or failed-empty rows ask again; a running download is not restarted.
            data[shelf.id].let { if (it == null || it.busy || it.failed && it.rows.isEmpty()) load?.invoke(shelf.id) }
            // Down is the next key press: have the row after this one decoded before it scrolls in.
            shelves.getOrNull(position + 1)?.let { next -> data[next.id]?.rows?.take(COLUMNS)?.forEach { row -> if (row.poster.isNotBlank()) images.prefetch(row.poster, http, decode) } }
        }
        override fun onViewRecycled(holder: ShelfHolder) { holder.id?.let { id -> if (data[id]?.busy != false) release?.invoke(id) } }
    }

    private fun tileWidth(): Int {
        val available = (if (width > 0) width else resources.displayMetrics.widthPixels - dp(TvFocus.BROWSE_RAIL_WIDTH_DP + 56)) - paddingLeft - paddingRight
        return (available / COLUMNS).coerceAtLeast(dp(72))
    }

    private inner class ShelfHolder(val root: LinearLayout) : ViewHolder(root) {
        var id: String? = null
        private var items = emptyList<BrowserRow>()
        private var more: Data? = null
        private var total = 0
        private val heading = TextView(context).apply {
            textSize = 13f * GuideTheme.textScale; setTextColor(Color.WHITE); typeface = AppFont.face(true); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0); gravity = Gravity.CENTER_VERTICAL
        }
        val list = HorizontalGridView(context).apply {
            setNumRows(1); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; horizontalSpacing = dp(2)
            // The first tiles sit at the left edge; further along, the cursor stays mid-row and the tiles move under it.
            windowAlignment = WINDOW_ALIGN_BOTH_EDGE; windowAlignmentOffsetPercent = 50f
            setRecycledViewPool(tilePool); setHasFixedSize(true)
            clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnKeyInterceptListener { event ->
                // Left from a row's first tile leaves for the Movies/Series buttons.
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && selectedPosition <= 0) { if (event.repeatCount == 0) exitLeft?.invoke(); true } else false
            }
        }
        init {
            root.orientation = LinearLayout.VERTICAL; root.clipChildren = false; root.clipToPadding = false
            root.addView(heading, LinearLayout.LayoutParams(-1, dp(22)))
            root.addView(list, LinearLayout.LayoutParams(-1, -2))
            root.layoutParams = LayoutParams(-1, -2)
            list.adapter = Tiles()
        }
        fun bind(shelf: Shelf, value: Data?) {
            id = shelf.id; root.tag = "library-shelf:${shelf.id}"
            heading.typeface = AppFont.face(true); heading.textSize = 13f * GuideTheme.textScale
            heading.text = shelf.title + (value?.total?.takeIf { it > 0 }?.let { "  ·  $it" } ?: "")
            items = value?.rows.orEmpty(); more = value; total = value?.total ?: 0
            // A fixed height keeps a row's own updates from re-measuring every other row.
            val tile = posterHeight(tileWidth()); val height = tile + dp(16)
            list.setRowHeight(tile); list.extraLayoutSpace = tileWidth() * 2
            if (list.layoutParams.height != height) list.layoutParams = list.layoutParams.apply { this.height = height }
            list.adapter?.notifyDataSetChanged()
            list.selectedPosition = (positions[shelf.id] ?: 0).coerceIn(0, maxOf(0, count() - 1))
        }
        fun apply(before: Data?, value: Data) {
            val focusedAt = list.focusedChild?.let(list::getChildAdapterPosition)
            val shelf = shelves.firstOrNull { it.id == id } ?: return
            heading.text = shelf.title + (value.total.takeIf { it > 0 }?.let { "  ·  $it" } ?: "")
            val old = items
            items = value.rows; more = value; total = value.total
            // Growing rows keep the tile under the cursor; a loading placeholder that becomes posters hands focus to the first one.
            if (old.isNotEmpty() && value.rows.size >= old.size && old.indices.all { old[it].key == value.rows[it].key }) {
                if (value.rows.size > old.size) list.adapter?.notifyItemRangeInserted(old.size, value.rows.size - old.size)
                list.adapter?.notifyItemChanged(count() - 1)
            } else {
                list.adapter?.notifyDataSetChanged()
                if (focusedAt != null) { positions[shelf.id] = 0; list.selectedPosition = 0; list.post { if (!list.hasFocus() && this@CatalogShelves.hasFocus()) focus() } }
            }
        }
        fun refresh() { list.adapter?.notifyItemRangeChanged(0, count()) }
        fun count() = items.size + if (more?.more == false && items.isNotEmpty()) 0 else 1
        /** Hand the cursor to this row's remembered tile; false while the row has no tile laid out yet. */
        fun focus(): Boolean {
            if (list.isComputingLayout || list.isEmpty()) return false
            val target = (positions[id] ?: 0).coerceIn(0, count() - 1)
            if (list.selectedPosition != target) list.selectedPosition = target
            return list.requestFocus() && list.hasFocus()
        }

        private inner class Tiles : Adapter<ViewHolder>() {
            override fun getItemCount() = count()
            override fun getItemViewType(position: Int) = if (position < items.size) 0 else 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): ViewHolder =
                if (type == 0) PosterHolder(LibraryPosterTile(context)) else object : ViewHolder(TextView(context).apply {
                    gravity = Gravity.CENTER; isFocusableInTouchMode = true; isClickable = true; maxLines = 3
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }) {}
            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val width = tileWidth(); val height = posterHeight(width)
                val view = holder.itemView
                if (holder is PosterHolder) holder.box.configure(LibraryPosterTile.Layout.ROW)
                val params = view.layoutParams
                if (params !is RecyclerView.LayoutParams || params.width != width || params.height != height || params.marginEnd != 0 || params.bottomMargin != 0)
                    view.layoutParams = RecyclerView.LayoutParams(width, height)
                if (holder is PosterHolder) {
                    val item = items[position]; val favorite = item.key in favoriteIds
                    holder.box.appearance()
                    holder.box.tag = "library-item:${item.key}"
                    holder.box.contentDescription = "${item.title}. ${item.detail}" + if (favorite) ". In Watchlist" else ""
                    holder.box.bind(item.key, LibraryPosterTile.Content((if (favorite) "✓ " else "") + item.title, favoriteLabels[item.key] ?: item.detail, item.poster), images, http, item.requestPoster, decode)
                    // Right is the next key press: keep the tile just past the screen edge decoded.
                    items.getOrNull(position + COLUMNS)?.poster?.takeIf { it.isNotBlank() }?.let { images.prefetch(it, http, decode) }
                    view.setOnClickListener { items.getOrNull(holder.bindingAdapterPosition)?.select?.invoke() }
                    view.setOnLongClickListener { items.getOrNull(holder.bindingAdapterPosition)?.longPress?.let { it(); true } ?: false }
                    view.setOnFocusChangeListener { _, active -> if (active) { val at = holder.bindingAdapterPosition; id?.let { positions[it] = at; current = it }; items.getOrNull(at)?.let { focused?.invoke(it) } } }
                } else {
                    val text = view as TextView
                    val state = more
                    text.tag = "library-shelf-more:$id"
                    text.background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
                    text.setTextColor(GuideTheme.ink()); text.typeface = AppFont.face(true); text.textSize = 12f * GuideTheme.textScale
                    text.text = when {
                        items.isNotEmpty() -> "See all\n${if (total > 0) counted(total, "title") else ""}".trim()
                        state == null || state.busy -> "Loading…"
                        state.failed -> "Couldn’t load\nOK to open"
                        else -> "No titles"
                    }
                    text.contentDescription = if (items.isNotEmpty()) "See all ${counted(total, "title")}" else text.text
                    view.setOnClickListener { id?.let { open?.invoke(it) } }
                    view.setOnLongClickListener(null)
                    view.setOnFocusChangeListener { _, active -> if (active) id?.let { positions[it] = holder.bindingAdapterPosition; current = it } }
                }
            }
        }
    }

    private fun posterHeight(width: Int): Int { val padding = dp(5) * 2; return ((width - padding).coerceAtLeast(0) * 3) / 2 + padding }

    companion object { const val COLUMNS = 8 }
}

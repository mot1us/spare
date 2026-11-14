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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Movies and Series home: one row per provider category, like streaming apps. Each row asks for its own
 * category only while it is on screen, shows the first titles, and ends with a "See all" tile that opens
 * the full grid. Up/Down moves between categories (each row remembers its position), Left/Right within one.
 */
@SuppressLint("ViewConstructor", "NotifyDataSetChanged", "SetTextI18n")
internal class CatalogShelves(context: Context, private val images: LogoLoader) : RecyclerView(context) {
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
        layoutManager = object : LinearLayoutManager(context) {
            override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
            override fun calculateExtraLayoutSpace(state: State, extra: IntArray) { extra[0] = height / 3; extra[1] = height / 3 }
        }
        clipChildren = false; clipToPadding = false
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
        if (catalog != key) { data.clear(); positions.clear(); current = null; scrollToPosition(0) }
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

    /** Focus the remembered row and tile; Up/Down and Back land where the viewer left off. */
    fun focusCurrent(): Boolean {
        if (shelves.isEmpty()) return false
        val index = shelves.indexOfFirst { it.id == current }.coerceAtLeast(0)
        moveTo(index); return true
    }

    private fun moveTo(index: Int) {
        val shelf = shelves.getOrNull(index) ?: return
        val epoch = ++focusEpoch
        current = shelf.id
        fun attempt(left: Int) {
            if (epoch != focusEpoch || !isShown) return
            val holder = if (isComputingLayout || hasPendingAdapterUpdates()) null else findViewHolderForAdapterPosition(index) as? ShelfHolder
            if (holder != null && holder.id == shelf.id) {
                align(holder.itemView)
                holder.focus(positions[shelf.id] ?: 0)
            } else if (left > 0) postOnAnimation { attempt(left - 1) }
        }
        val visible = findViewHolderForAdapterPosition(index)
        if (visible == null) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0)
        attempt(25)
    }
    /** The focused category row sits at the top of the list, with the next row peeking below it. */
    private fun align(row: View) { val delta = row.top - paddingTop; if (delta != 0) smoothScrollBy(0, delta) }

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
        val list = RecyclerView(context).apply {
            // Two tiles past each edge are kept ready; a whole extra screen each side tripled the work of showing a row.
            layoutManager = object : LinearLayoutManager(context, HORIZONTAL, false) {
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
                override fun calculateExtraLayoutSpace(state: State, extra: IntArray) { extra[0] = width / 4; extra[1] = width / 4 }
            }.apply { initialPrefetchItemCount = COLUMNS + 2 }
            setRecycledViewPool(tilePool); setHasFixedSize(true)
            itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(8), dp(2), dp(8)); isFocusable = false
        }
        init {
            root.orientation = LinearLayout.VERTICAL; root.clipChildren = false; root.clipToPadding = false
            root.addView(heading, LinearLayout.LayoutParams(-1, dp(22)))
            root.addView(list, LinearLayout.LayoutParams(-1, -2))
            root.layoutParams = LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            list.adapter = Tiles()
        }
        fun bind(shelf: Shelf, value: Data?) {
            id = shelf.id; root.tag = "library-shelf:${shelf.id}"
            heading.typeface = AppFont.face(true); heading.textSize = 13f * GuideTheme.textScale
            heading.text = shelf.title + (value?.total?.takeIf { it > 0 }?.let { "  ·  $it" } ?: "")
            items = value?.rows.orEmpty(); more = value; total = value?.total ?: 0
            // A fixed height keeps a row's own updates from re-measuring every other row.
            val height = posterHeight(tileWidth()) + dp(16)
            if (list.layoutParams.height != height) list.layoutParams = list.layoutParams.apply { this.height = height }
            list.adapter?.notifyDataSetChanged()
            list.scrollToPosition((positions[shelf.id] ?: 0).coerceAtMost(maxOf(0, count() - 1)))
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
                if (focusedAt != null) { positions[shelf.id] = 0; list.post { focus(0) } }
            }
        }
        fun refresh() { list.adapter?.notifyItemRangeChanged(0, count()) }
        fun count() = items.size + if (more?.more == false && items.isNotEmpty()) 0 else 1
        private var tileEpoch = 0
        fun focus(position: Int) {
            val target = position.coerceIn(0, count() - 1); val epoch = ++tileEpoch
            val manager = list.layoutManager as LinearLayoutManager
            fun tile() = if (list.isComputingLayout || list.hasPendingAdapterUpdates()) null else list.findViewHolderForAdapterPosition(target)?.itemView
            fun take(view: View) { view.requestFocus(); TvFocus.center(list, view) }
            tile()?.let { take(it); return }
            // The remembered tile is off-screen. Scroll by distance: unlike a position jump this lays the tiles out
            // right now, and it still works when the row has just been shown again or had a tile refreshed.
            fun reach() {
                val first = manager.findFirstVisibleItemPosition(); val child = manager.findViewByPosition(first) ?: return
                list.scrollBy((target - first) * (child.width + dp(2)), 0)
            }
            if (!list.isComputingLayout) { reach(); tile()?.let { take(it); return } }
            fun attempt(left: Int) {
                if (epoch != tileEpoch || !list.isShown) return
                val view = tile()
                when {
                    view != null -> take(view)
                    left > 0 -> { if (!list.isComputingLayout) reach(); list.postOnAnimation { attempt(left - 1) } }
                    // Never leave the cursor stranded outside the row: land on a tile that is there.
                    else -> manager.findViewByPosition(manager.findFirstCompletelyVisibleItemPosition())?.let(::take)
                }
            }
            list.postOnAnimation { attempt(12) }
        }
        private fun key(view: View, position: Int, code: Int, event: KeyEvent): Boolean {
            val shelf = id ?: return false
            val index = shelves.indexOfFirst { it.id == shelf }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) images.quiet()
            if (event.action != KeyEvent.ACTION_DOWN) return code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (index <= 0) { if (event.repeatCount == 0) exitUp?.invoke() } else moveTo(index - 1) }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (index < shelves.lastIndex) moveTo(index + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (position <= 0) exitLeft?.invoke() else focus(position - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (position < count() - 1) focus(position + 1)
                else -> return false
            }
            return true
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
                if (params !is RecyclerView.LayoutParams || params.width != width || params.height != height || params.marginEnd != dp(2) || params.bottomMargin != 0)
                    view.layoutParams = RecyclerView.LayoutParams(width, height).apply { marginEnd = dp(2) }
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
                view.setOnKeyListener { v, code, event -> key(v, holder.bindingAdapterPosition, code, event) }
            }
        }
    }

    private fun posterHeight(width: Int): Int { val padding = dp(5) * 2; return ((width - padding).coerceAtLeast(0) * 3) / 2 + padding }

    companion object { const val COLUMNS = 8 }
}

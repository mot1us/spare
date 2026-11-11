package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

internal class PlaylistSearchPoster(
    val key: String, val kind: String, val content: LibraryPosterTile.Content, val http: Boolean, val select: () -> Unit,
) {
    val id = Channel.stableLongId(key)
    val tag = "search-result:$key"
    val description = listOf(content.title, content.badge, content.subtitle).filter(String::isNotBlank).joinToString(". ")
    fun same(other: PlaylistSearchPoster) = content == other.content && http == other.http
}

internal class PlaylistSearchChannel(
    val channel: Channel, val playlist: String, val programme: String, val status: String, val progress: Int,
    val http: Boolean, val select: () -> Unit,
) {
    val tag = "search-result:live:${channel.id}"
    val description = listOf(channel.name, playlist, programme, if (progress >= 0) "Live, $status" else status)
        .filter(String::isNotBlank).joinToString(". ")
    fun same(other: PlaylistSearchChannel) = channel == other.channel && playlist == other.playlist &&
        programme == other.programme && status == other.status && progress == other.progress && http == other.http
}

/** Search owns focus while open. Only the left edge hands it back to the app rail. */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class PlaylistSearchPanel(context: Context, private val field: GuideSearchBar, button: (String, () -> Unit) -> Button,
    private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        setTextColor(if (bold) Color.WHITE else Color.LTGRAY)
        textSize = size * GuideTheme.textScale
        typeface = AppFont.face(bold)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val artwork = GuideLogoFiles(context)
    private val posters = LogoLoader(512, 8 * 1024 * 1024, artwork)
    private val logos = LogoLoader(96, 2 * 1024 * 1024, artwork)
    private val filters = LinearLayout(context)
    private val message = label(11f)
    private val titleHeader = label(13f, true).apply { tag = "search-library-heading" }
    private val liveHeader = label(13f, true).apply { tag = "search-live-heading" }
    private val grid = list("search-poster-grid", PosterGridLayout(context))
    private val live = list("playlist-search-results", TvFocus.Layout(context))
    private val gridAdapter = PosterAdapter(grid, LibraryPosterTile.Layout.GRID)
    private val liveAdapter = LiveAdapter()
    private val skeleton = LinearLayout(context).apply {
        orientation = VERTICAL; tag = "search-skeleton"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(LinearLayout(context).apply {
            repeat(5) { addView(View(context).apply { background = GuideTheme.glass(resources.displayMetrics.density) },
                LayoutParams(dp(120), dp(145)).apply { marginEnd = dp(8) }) }
        })
        repeat(3) { addView(View(context).apply { background = GuideTheme.glass(resources.displayMetrics.density) },
            LayoutParams(-1, dp(36)).apply { topMargin = dp(8) }) }
    }
    private val tabs = linkedMapOf<String, Button>()
    private val kinds = linkedMapOf<String, Button>()
    private var allPosters = emptyList<PlaylistSearchPoster>()
    private var allLive = emptyList<PlaylistSearchChannel>()
    /** "live" or "vod": live channels and on-demand titles are never mixed in one list. */
    private var section = "live"
    private var sectionChosen = false
    private var kind = ""
    private var text = ""
    private var busy = false
    private var warning = ""
    private var waitingForResults = false
    private var focusRequest = 0
    var backgroundContent: ViewGroup? = null

    init {
        orientation = VERTICAL; tag = "playlist-search"; visibility = GONE; background = GuideTheme.guideBackground(100)
        setPadding(dp(104), dp(14), dp(24), dp(12))
        field.setPadding(0, 0, 0, dp(4))
        addView(field, LayoutParams(-1, -2))
        listOf("Live TV" to "live", "Movies and series" to "vod").forEach { (title, key) ->
            val tab = button(title) {
                section = key; sectionChosen = true; display()
                if (firstResults() != null) focusResults()
            }.apply { tag = "search-kind:$key" }
            tabs[key] = tab; filters.addView(tab)
        }
        filters.addView(View(context), LayoutParams(dp(18), 1))
        listOf("All" to "", "Movies" to "movie", "Series" to "series", "Episodes" to "episode").forEach { (title, key) ->
            val chip = button(title) {
                kind = key; display()
                if (firstResults() != null) focusResults()
            }.apply { tag = "search-kind:${key.ifEmpty { "vod-all" }}"; alpha = .85f }
            kinds[key] = chip; filters.addView(chip)
        }
        val chipViews = { tabs.values.toList() + kinds.values.filter { it.isVisible } }
        tabs.values.toList().plus(kinds.values).forEach { tab ->
            directions(tab) { key ->
                val row = chipViews(); val index = row.indexOf(tab)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> field.input.requestFocus()
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusResults()
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index <= 0) leave() else row[index - 1].requestFocus()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> row.getOrNull(index + 1)?.requestFocus()
                }
            }
        }
        field.direction = { index, key ->
            ++focusRequest; waitingForResults = false
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { field.hideKeyboard(); tabs.getValue(section).requestFocus() }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) leave() else field.controls[index - 1].requestFocus()
                KeyEvent.KEYCODE_DPAD_RIGHT -> field.controls.getOrNull(index + 1)?.requestFocus()
            }
        }
        addView(filters)
        addView(message, LayoutParams(-1, dp(22)))
        addView(skeleton, LayoutParams(-1, 0, 1f))
        addView(titleHeader, LayoutParams(-1, dp(25)))
        addView(grid, LayoutParams(-1, 0, 1f))
        addView(liveHeader, LayoutParams(-1, dp(25)))
        addView(live, LayoutParams(-1, 0, 1f))
        grid.adapter = gridAdapter; live.adapter = liveAdapter
    }

    private fun list(name: String, layout: RecyclerView.LayoutManager) = RecyclerView(context).apply {
        tag = name; layoutManager = layout; itemAnimator = null; preserveFocusAfterLayout = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    private fun directions(view: View, move: (Int) -> Unit) {
        view.setOnKeyListener { _, code, event ->
            if (code !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    ++focusRequest; waitingForResults = false
                    move(code)
                }
                true
            }
        }
    }

    private inner class PosterAdapter(private val list: RecyclerView, private val layout: LibraryPosterTile.Layout) : RecyclerView.Adapter<PosterHolder>() {
        private var items = emptyList<PlaylistSearchPoster>()
        init { setHasStableIds(true) }
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].id
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): PosterHolder {
            val tile = LibraryPosterTile(context).apply { configure(layout) }
            val holder = PosterHolder(tile)
            tile.setOnClickListener { items.getOrNull(holder.bindingAdapterPosition)?.let { field.hideKeyboard(); it.select() } }
            directions(tile) { key ->
                val at = holder.bindingAdapterPosition
                if (at == RecyclerView.NO_POSITION) return@directions
                val columns = PosterGridLayout.COLUMNS
                val horizontal = layout == LibraryPosterTile.Layout.SHELF
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (horizontal || at < columns) tabs.getValue(section).requestFocus() else focusAt(list, at - columns)
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (horizontal) focusAt(live, 0) else focusAt(list, at + columns)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (at == 0 || !horizontal && at % columns == 0) leave() else focusAt(list, at - 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (horizontal || at % columns < columns - 1) focusAt(list, at + 1)
                }
            }
            return holder
        }
        override fun onBindViewHolder(holder: PosterHolder, position: Int) {
            val item = items[position]
            holder.box.tag = item.tag; holder.box.contentDescription = item.description
            holder.box.bind(item.key, item.content, posters, item.http)
        }
        fun submit(next: List<PlaylistSearchPoster>) {
            val before = items; items = next
            diff(before, next, this, { it.key }, PlaylistSearchPoster::same)
        }
    }

    private inner class LiveAdapter : RecyclerView.Adapter<LiveHolder>() {
        private var items = emptyList<PlaylistSearchChannel>()
        init { setHasStableIds(true) }
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].channel.rowId
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): LiveHolder {
            val holder = LiveHolder()
            holder.itemView.setOnClickListener { items.getOrNull(holder.bindingAdapterPosition)?.let { field.hideKeyboard(); it.select() } }
            directions(holder.itemView) { key ->
                val at = holder.bindingAdapterPosition
                if (at == RecyclerView.NO_POSITION) return@directions
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (at > 0) focusAt(live, at - 1) else tabs.getValue(section).requestFocus()
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusAt(live, at + 1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> leave()
                }
            }
            return holder
        }
        override fun onBindViewHolder(holder: LiveHolder, position: Int) = holder.bind(items[position])
        fun submit(next: List<PlaylistSearchChannel>) {
            val before = items; items = next
            diff(before, next, this, { it.channel.id }, PlaylistSearchChannel::same)
        }
    }

    private inner class LiveHolder : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val row = itemView as LinearLayout
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val name = label(12f, true)
        private val playlist = label(9f)
        private val programme = label(12f)
        private val status = label(10f, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        private val liveBadge = label(8f, true).apply {
            text = "LIVE"; gravity = Gravity.CENTER; setTextColor(0xFFD2F6E4.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat(); setColor(0xFF28513E.toInt())
            }
        }
        private val meter = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        }
        private var previous: PlaylistSearchChannel? = null

        init {
            row.isFocusableInTouchMode = true; row.isClickable = true; row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(10), dp(2), dp(10), dp(2))
            row.background = GuideTheme.background(resources.displayMetrics.density, 0x77303943)
            row.layoutParams = RecyclerView.LayoutParams(-1, dp(maxOf(GuideTheme.rowHeightDp, 37))).apply { bottomMargin = dp(GuideTheme.rowGapDp) }
            row.addView(image, LayoutParams(dp(30), dp(26)).apply { marginEnd = dp(9) })
            row.addView(LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
                addView(name); addView(playlist)
            }, LayoutParams(dp(178), -1).apply { marginEnd = dp(12) })
            row.addView(programme, LayoutParams(0, -1, 1f))
            row.addView(LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(liveBadge, LayoutParams(dp(32), dp(15)).apply { marginEnd = dp(5) })
                    addView(status, LayoutParams(-2, -1))
                }, LayoutParams(-1, dp(18)))
                addView(meter, LayoutParams(-1, dp(3)))
            }, LayoutParams(dp(114), -1).apply { marginStart = dp(8) })
            row.setOnFocusChangeListener { _, active -> tint(active) }
            listOf(image, name, playlist, programme, status, liveBadge, meter).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        }
        fun bind(item: PlaylistSearchChannel) {
            row.tag = item.tag; row.contentDescription = item.description
            name.text = item.channel.name; playlist.text = item.playlist; playlist.isVisible = item.playlist.isNotBlank()
            programme.text = item.programme; status.text = item.status
            liveBadge.isVisible = item.progress >= 0
            meter.isVisible = item.progress >= 0; meter.progress = item.progress.coerceAtLeast(0)
            tint(row.hasFocus())
            if (previous?.channel?.logo != item.channel.logo || previous?.http != item.http) logos.bind(image, item.channel.logo, item.http)
            previous = item
        }
        private fun tint(active: Boolean) {
            val ink = if (active) GuideTheme.focusText else Color.WHITE
            name.setTextColor(ink); programme.setTextColor(ink)
            playlist.setTextColor(if (active) GuideTheme.focusText else Color.LTGRAY)
            status.setTextColor(if (active) GuideTheme.focusText else if (meter.isVisible) 0xFFA6E8C7.toInt() else Color.WHITE)
        }
    }

    private fun <T> diff(before: List<T>, next: List<T>, adapter: RecyclerView.Adapter<*>, key: (T) -> String, same: (T, T) -> Boolean) {
        if (before.isEmpty() && next.isEmpty()) return
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = before.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(old: Int, new: Int) = key(before[old]) == key(next[new])
            override fun areContentsTheSame(old: Int, new: Int) = same(before[old], next[new])
        }).dispatchUpdatesTo(adapter)
    }

    override fun focusSearch(focused: View, direction: Int): View? =
        FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused

    fun show(query: String) {
        text = query; kind = ""; section = "live"; sectionChosen = false; waitingForResults = false
        backgroundContent?.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        visibility = VISIBLE; field.visibility = VISIBLE
        display()
    }
    fun submit(query: String, channels: List<PlaylistSearchChannel>, titles: List<PlaylistSearchPoster>, loading: Boolean, notice: String = "") {
        text = query; allLive = channels; allPosters = titles; busy = loading; warning = notice
        display()
        if (waitingForResults && firstResults() != null && hasFocus()) focusResults()
    }
    private fun display() {
        val titles = if (text.isBlank()) emptyList() else if (kind.isEmpty()) allPosters else allPosters.filter { it.kind == kind }
        val channels = if (text.isNotBlank()) allLive else emptyList()
        // Open on whichever side has matches until the viewer picks a tab themselves.
        if (!sectionChosen && text.isNotBlank()) section = if (channels.isEmpty() && allPosters.isNotEmpty()) "vod" else "live"
        val showLive = section == "live"
        gridAdapter.submit(if (showLive) emptyList() else titles)
        liveAdapter.submit(if (showLive) channels else emptyList())
        grid.isVisible = !showLive && titles.isNotEmpty()
        live.isVisible = showLive && channels.isNotEmpty()
        titleHeader.isVisible = !showLive && text.isNotBlank()
        liveHeader.isVisible = showLive && text.isNotBlank()
        titleHeader.text = "On demand · ${titles.size} ${if (kind.isEmpty()) "movies, series and episodes" else kinds[kind]?.text.toString().lowercase()}"
        liveHeader.text = "Live channels · ${channels.size}"
        tabs.getValue("live").text = if (text.isBlank()) "Live TV" else "Live TV · ${allLive.size}"
        tabs.getValue("vod").text = if (text.isBlank()) "Movies and series" else "Movies and series · ${allPosters.size}"
        kinds.values.forEach { it.isVisible = !showLive }
        skeleton.isVisible = text.isNotBlank() && busy && firstResults() == null
        message.text = when {
            text.isBlank() -> "Search live channels and on-demand movies, series and episodes."
            busy -> "Searching…"
            warning.isNotBlank() -> warning
            firstResults() == null -> if (showLive) "No live channels match · try Movies and series" else "No on-demand titles match · try Live TV"
            else -> ""
        }
        message.isVisible = message.text.isNotEmpty()
        tabs.forEach { (key, tab) -> tab.isSelected = key == section }
        kinds.forEach { (key, chip) -> chip.isSelected = key == kind }
    }
    private fun firstResults(): RecyclerView? = when {
        grid.isVisible -> grid
        live.isVisible -> live
        else -> null
    }
    fun focusResults() {
        val list = firstResults()
        waitingForResults = list == null
        if (list == null) field.input.requestFocus() else focusAt(list, 0)
    }
    private fun focusAt(list: RecyclerView, position: Int) {
        val adapter = list.adapter ?: return
        if (!list.isVisible || position !in 0 until adapter.itemCount) return
        val request = ++focusRequest
        val id = adapter.getItemId(position)
        fun attempt(remaining: Int) {
            if (request != focusRequest || !isShown || !list.isShown || position !in 0 until adapter.itemCount || adapter.getItemId(position) != id) return
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null && row.requestFocus()) TvFocus.center(list, row)
            else if (remaining > 0) { list.scrollToPosition(position); list.postOnAnimation { attempt(remaining - 1) } }
        }
        attempt(4)
    }
    fun dismiss() {
        ++focusRequest; waitingForResults = false; field.hideKeyboard(); field.visibility = GONE; visibility = GONE
        backgroundContent?.descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }
    fun appearance(opacity: Int = -1) {
        background = GuideTheme.guideBackground(opacity.coerceIn(96, 100))
        AppFont.apply(this)
        for (list in listOf(grid)) for (i in 0 until list.childCount) (list.getChildAt(i) as? LibraryPosterTile)?.appearance()
    }
    fun trimImages() { posters.trim(); logos.trim() }
    fun closeImages() { posters.close(); logos.close() }
}

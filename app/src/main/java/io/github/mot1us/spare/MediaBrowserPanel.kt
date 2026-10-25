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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class BrowserRow(val key: String, val title: String, val detail: String = "", val select: () -> Unit)

/** Bounded native TV lists, including large provider catalogs. Search remains inside the page. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class MediaBrowserPanel(context: Context, button: (String, () -> Unit) -> Button, private val mode: (String) -> Unit,
    refresh: () -> Unit, private val close: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(Color.WHITE); typeface = AppFont.face() }
    private val heading = label(22f).apply { tag = "library-heading" }
    private val message = label(11f).apply { maxLines = 2; setTextColor(Color.LTGRAY); tag = "library-status" }
    private val loading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = GONE; tag = "library-loading" }
    private val search = EditText(context).apply { hint = "Search this list"; textSize = 13f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true; tag = "library-search"; typeface = AppFont.face(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    private val categories = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-categories" }
    private val rows = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "library-items" }
    private val empty = label(13f).apply { setPadding(dp(14), dp(16), dp(14), dp(16)); tag = "library-empty" }
    private var allRows = emptyList<BrowserRow>()
    private var shownRows = emptyList<BrowserRow>()
    private var categoryNames = emptyList<String>()
    private var rowGroups = emptyMap<String, String>()
    private var group: String? = null
    private var pending: RecyclerView? = null
    private var focusEpoch = 0
    private val tabs = listOf("Movies" to "movie", "Series" to "series", "Continue" to "continue", "Catch-up" to "catchup")
    init {
        orientation = VERTICAL; tag = "library-panel"; visibility = GONE; setPadding(dp(24), dp(18), dp(24), dp(18)); setBackgroundColor(0xEE0B1018.toInt())
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(button("Live TV", close).apply { tag = "library-live" })
        tabs.forEach { (title, key) -> toolbar.addView(button(title) { hideKeyboard(); mode(key) }.apply { tag = "library-tab:$key" }) }
        toolbar.addView(button("Refresh", refresh).apply { tag = "library-refresh" })
        addView(toolbar, LayoutParams(-1, dp(42))); addView(heading, LayoutParams(-1, dp(36))); addView(message, LayoutParams(-1, dp(32))); addView(loading, LayoutParams(-1, dp(3)))
        addView(search, LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })
        val body = LinearLayout(context)
        body.addView(categories, LayoutParams(dp(190), -1).apply { marginEnd = dp(16) })
        body.addView(LinearLayout(context).apply { orientation = VERTICAL; addView(empty); addView(rows, LayoutParams(-1, 0, 1f)) }, LayoutParams(0, -1, 1f))
        addView(body, LayoutParams(-1, 0, 1f))
        categories.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = categoryNames.size + 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(cell(13f, 42))
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val name = if (position == 0) null else categoryNames[position - 1]
                holder.text.text = name ?: "All"; holder.text.isSelected = group == name
                holder.text.contentDescription = "Category · ${name ?: "All"}"
                holder.text.setOnClickListener { group = name; filter(); notifyItemRangeChanged(0, itemCount); focusAt(rows, 0) }
                holder.text.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { if (position == 0 && key == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) search.requestFocus() else focusAt(categories, position + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1) }; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusAt(rows, 0); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                } }
            }
        }
        rows.adapter = object : RecyclerView.Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemId(position: Int) = Channel.stableId(shownRows[position].key).take(16).toULong(16).toLong()
            override fun getItemCount() = shownRows.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(cell(13f, 55))
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val item = shownRows[position]; holder.text.text = item.title + if (item.detail.isBlank()) "" else "\n${item.detail}"
                holder.text.tag = "library-item:${item.key}"; holder.text.setOnClickListener { hideKeyboard(); item.select() }
                holder.text.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { if (position == 0 && key == KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) search.requestFocus() else focusAt(rows, position + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1) }; true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) focusAt(categories, 0); true }
                    else -> false
                } }
            }
        }
        search.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }; override fun afterTextChanged(s: Editable?) {} })
        search.setOnEditorActionListener { _, _, _ -> hideKeyboard(); focusAt(rows, 0); true }
        search.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (event.action == KeyEvent.ACTION_DOWN) { hideKeyboard(); focusAt(rows, 0) }; true } else false }
    }
    private fun cell(size: Float, height: Int) = label(size).apply {
        isFocusableInTouchMode = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(5), dp(12), dp(5)); setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
        layoutParams = RecyclerView.LayoutParams(-1, dp(height)).apply { bottomMargin = dp(5) }
        setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pending = null } }
    }
    fun page(title: String, detail: String, busy: Boolean) { heading.text = title; message.text = detail; loading.visibility = if (busy) VISIBLE else GONE }
    @SuppressLint("NotifyDataSetChanged")
    fun submit(values: List<BrowserRow>, groups: Map<String, String> = emptyMap(), reset: Boolean = true) {
        if (reset) { group = null; search.setText("") }
        allRows = values; rowGroups = groups; categoryNames = groups.values.distinct()
        if (group !in categoryNames) group = null
        categories.adapter?.notifyDataSetChanged(); filter()
    }
    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
        val query = search.text.toString().trim()
        shownRows = allRows.filter { (group == null || rowGroups[it.key] == group) && (query.isBlank() || it.title.contains(query, true)) }
        rows.adapter?.notifyDataSetChanged(); empty.visibility = if (shownRows.isEmpty()) VISIBLE else GONE; empty.text = if (allRows.isEmpty()) "No titles to show." else "No matching titles."
    }
    fun focusRows() { if (shownRows.isEmpty()) search.requestFocus() else focusAt(rows, 0) }
    private fun focusAt(list: RecyclerView, position: Int) {
        if (position !in 0 until (list.adapter?.itemCount ?: 0) || pending != null) return
        val epoch = ++focusEpoch; val item = list.findViewHolderForAdapterPosition(position)?.itemView
        if (item != null) { item.requestFocus(); return }
        pending = list; list.stopScroll(); list.scrollToPosition(position)
        fun attempt(left: Int) {
            if (!isShown || epoch != focusEpoch) { pending = null; return }
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) { pending = null; row.requestFocus() } else if (left > 0) list.postOnAnimation { attempt(left - 1) } else pending = null
        }; list.postOnAnimation { attempt(25) }
    }
    fun hideKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) }
    fun appearance() { AppFont.apply(this); categories.adapter?.notifyItemRangeChanged(0, categoryNames.size + 1); rows.adapter?.notifyItemRangeChanged(0, shownRows.size) }
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}

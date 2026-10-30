package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

data class PlaylistSearchRow(val key: String, val title: String, val detail: String, val kind: String, val select: () -> Unit)

/** One TV-native search surface for live channels, movies, series and known episodes. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class PlaylistSearchPanel(context: Context, private val field: GuideSearchBar, button: (String, () -> Unit) -> Button,
    private val leave: () -> Unit) : LinearLayout(context) {
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private val filters = LinearLayout(context)
    private val message = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 12f; typeface = AppFont.face() }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = GONE }
    private val rows = RecyclerView(context).apply { tag = "playlist-search-results"; layoutManager = TvFocus.Layout(context); itemAnimator = null; preserveFocusAfterLayout = true }
    private val tabs = mutableMapOf<String, Button>()
    private var all = emptyList<PlaylistSearchRow>()
    private var shown = emptyList<PlaylistSearchRow>()
    private var kind = ""
    private var text = ""
    private var waitingForResults = false
    var backgroundContent: ViewGroup? = null
    init {
        orientation = VERTICAL; tag = "playlist-search"; visibility = GONE; background = GuideTheme.guideBackground()
        setPadding(dp(104), dp(18), dp(24), dp(18))
        field.setPadding(0, 0, 0, dp(8)); addView(field, LayoutParams(-1, -2))
        listOf("All" to "", "Live TV" to "live", "Movies" to "movie", "Series" to "series", "Episodes" to "episode").forEach { (title, key) ->
            val tab = button(title) { kind = key; display(); rows.post { focusResults() } }.apply { tag = "search-kind:$key" }
            tabs[key] = tab; filters.addView(tab)
        }
        addView(filters); addView(message, LayoutParams(-1, dp(28)).apply { topMargin = dp(8) }); addView(progress, LayoutParams(-1, dp(2)))
        addView(rows, LayoutParams(-1, 0, 1f))
        rows.adapter = object : RecyclerView.Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemCount() = shown.size
            override fun getItemId(position: Int) = Channel.stableLongId(shown[position].key)
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(TextView(context).apply {
                isFocusableInTouchMode = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; maxLines = 2
                typeface = AppFont.face(); textSize = 14f; setTextColor(GuideTheme.ink()); setPadding(dp(16), dp(6), dp(16), dp(6))
                background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B)
                layoutParams = RecyclerView.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) }
            })
            override fun onBindViewHolder(holder: Holder, position: Int) {
                holder.label.background = GuideTheme.background(resources.displayMetrics.density, GuideTheme.current.background or 0x66000000)
                holder.label.setTextColor(GuideTheme.ink()); holder.label.typeface = AppFont.face(); holder.label.textSize = 14f * GuideTheme.textScale
                val row = shown[position]; holder.label.text = "${row.title}\n${row.detail}"; holder.label.tag = "search-result:${row.key}"
                holder.label.setOnClickListener { field.hideKeyboard(); row.select() }
                holder.label.setOnKeyListener { _, code, event ->
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> if (holder.bindingAdapterPosition == 0) { if (event.action == KeyEvent.ACTION_DOWN) field.input.requestFocus(); true } else false
                        KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) leave(); true }
                        else -> false
                    }
                }
            }
        }
    }
    private class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
    // Search is a modal surface; Android's geometric search must not select obscured guide rows.
    override fun focusSearch(focused: View, direction: Int): View? =
        FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    fun show(query: String) { text = query; kind = ""; waitingForResults = false; backgroundContent?.descendantFocusability = FOCUS_BLOCK_DESCENDANTS; visibility = VISIBLE; field.visibility = VISIBLE; display() }
    fun submit(query: String, values: List<PlaylistSearchRow>, busy: Boolean, warning: String = "") {
        text = query; all = values; progress.visibility = if (busy) VISIBLE else GONE
        message.text = when { query.isBlank() -> "Search your playlist · Episode titles are included after opening a series"
            else -> "${if (busy) "Searching" else "Matches"} · ${values.count { it.kind == "live" }} channels · ${values.count { it.kind == "movie" }} movies · ${values.count { it.kind == "series" }} series · ${values.count { it.kind == "episode" }} episodes" + if (busy) " · Results are ready to select while more titles load…" else if (warning.isNotBlank()) " · $warning" else "" }
        display()
        if (waitingForResults && shown.isNotEmpty()) focusResults()
    }
    private fun display() {
        val before = shown; shown = if (text.isBlank()) emptyList() else all.filter { kind.isEmpty() || it.kind == kind }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = before.size
            override fun getNewListSize() = shown.size
            override fun areItemsTheSame(old: Int, new: Int) = before[old].key == shown[new].key
            override fun areContentsTheSame(old: Int, new: Int) = before[old].title == shown[new].title && before[old].detail == shown[new].detail
        })
        diff.dispatchUpdatesTo(rows.adapter!!); tabs.forEach { (key, tab) -> tab.isSelected = key == kind }
    }
    fun focusResults() { waitingForResults = shown.isEmpty(); if (shown.isEmpty()) field.input.requestFocus() else { rows.scrollToPosition(0); rows.post { if (isShown) rows.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } } }
    fun dismiss() { waitingForResults = false; field.hideKeyboard(); field.visibility = GONE; visibility = GONE; backgroundContent?.descendantFocusability = FOCUS_AFTER_DESCENDANTS }
    fun appearance(opacity: Int = -1) { background = GuideTheme.guideBackground(opacity); AppFont.apply(this); progress.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent); rows.adapter?.notifyItemRangeChanged(0, shown.size) }
}

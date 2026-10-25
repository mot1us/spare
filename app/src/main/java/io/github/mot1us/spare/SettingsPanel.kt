package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible

/** One translucent TV surface: navigation stays on the left, actual settings on the right. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SettingsPanel(context: Context, private val render: (String) -> Unit, private val close: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val navigation = LinearLayout(context).apply { orientation = VERTICAL; tag = "settings-navigation" }
    private val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(24), 0, dp(8), dp(16)); tag = "settings-content" }
    private val contentScroll = ScrollView(context).apply { overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; isFocusable = true; addView(content) }
    private val navRows = linkedMapOf<String, TextView>()
    private val controls = mutableListOf<View>()
    private var selected = "Playlists"
    val currentPage get() = selected
    val opened get() = isVisible
    init {
        orientation = HORIZONTAL; visibility = GONE; tag = "settings-panel"; setPadding(dp(28), dp(24), dp(24), dp(24))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00B1018.toInt(), 0xD90B1018.toInt()))
        val left = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, 0, dp(18), 0) }
        left.addView(label("Settings", 23f), LayoutParams(-1, dp(48)))
        left.addView(ScrollView(context).apply { overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(navigation) }, LayoutParams(-1, 0, 1f))
        left.addView(label("← / Back   Return", 10f), LayoutParams(-1, dp(26)))
        addView(left, LayoutParams(dp(220), -1))
        addView(contentScroll, LayoutParams(0, -1, 1f))
        contentScroll.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (event.action == KeyEvent.ACTION_DOWN) focusNavigation(); true } else false }
    }
    private fun label(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
    fun open(items: List<String>) {
        navigation.removeAllViews(); navRows.clear()
        items.forEachIndexed { index, item ->
            val row = label(item, 12f).apply {
                isFocusableInTouchMode = true; isClickable = true; setPadding(dp(12), 0, dp(8), 0)
                background = GuideTheme.background(resources.displayMetrics.density); setTextColor(GuideTheme.ink())
                tag = "settings-nav:$item"; layoutParams = LayoutParams(-1, dp(37)).apply { bottomMargin = dp(4) }
                setOnFocusChangeListener { _, active -> if (active && selected != item) selectPage(item) }
                setOnClickListener { selectPage(item); focusContent() }
                setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) navRows[items[(index + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(items.indices)]]?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusContent(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) close(); true }
                    else -> false
                } }
            }
            navRows[item] = row; navigation.addView(row)
        }
        if (selected !in items) selected = items.first()
        visibility = VISIBLE; selectPage(selected); navRows[selected]?.requestFocus()
    }
    private fun selectPage(item: String) { selected = item; navRows.forEach { (name, row) -> row.isSelected = name == item }; render(item) }
    fun page(title: String, description: String) {
        // Move focus to the selected navigation row before removing a focused option.
        // Otherwise Android can focus the first nav row and re-enter a different page mid-render.
        if (content.hasFocus()) navRows[selected]?.requestFocus()
        controls.clear(); content.removeAllViews()
        content.addView(label(title, 23f).apply { tag = "settings-page-title"; typeface = AppFont.face(true) }, LayoutParams(-1, dp(45)))
        if (description.isNotEmpty()) note(description)
    }
    fun note(message: String) { content.addView(label(message, 12f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(14)) }, LayoutParams(-1, -2)) }
    fun action(title: String, detail: String = "", selected: Boolean = false, execute: () -> Unit) {
        val text = label(title, 13f); val subtitle = label(detail, 11f).apply { setTextColor(Color.LTGRAY) }
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusableInTouchMode = true; isClickable = true
            setPadding(dp(14), dp(9), dp(14), dp(9)); background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B); isSelected = selected
            tag = "settings-option:$title"; contentDescription = title + (if (selected) ", selected" else "") + if (detail.isEmpty()) "" else ". $detail"
            val labels = LinearLayout(context).apply { orientation = VERTICAL; addView(text); if (detail.isNotEmpty()) addView(subtitle) }
            addView(labels, LayoutParams(0, -2, 1f)); if (selected) addView(label("✓", 14f))
            setOnFocusChangeListener { _, focused -> text.setTextColor(if (focused) Color.BLACK else Color.WHITE); subtitle.setTextColor(if (focused) Color.BLACK else Color.LTGRAY); if (childCount > 1) (getChildAt(1) as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE) }
            setOnClickListener { execute() }
            setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focusNavigation(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) controls[(controls.indexOf(this) + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(controls.indices)].requestFocus(); true }
                else -> false
            } }
        }
        controls.add(box); content.addView(box, LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }
    fun choices(values: List<String>, checked: (Int) -> Boolean, choose: (Int) -> Unit) {
        val list = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
        }
        var pending = false
        fun focusAt(index: Int) {
            if (index !in values.indices || pending) return
            val child = list.findViewHolderForAdapterPosition(index)?.itemView
            if (child != null) { child.requestFocus(); return }
            pending = true; list.stopScroll(); list.scrollToPosition(index)
            fun attempt(left: Int) {
                if (!opened) { pending = false; return }
                val row = list.findViewHolderForAdapterPosition(index)?.itemView
                if (row != null) { pending = false; row.requestFocus() } else if (left > 0) list.postOnAnimation { attempt(left - 1) } else pending = false
            }; list.postOnAnimation { attempt(20) }
        }
        list.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ChoiceHolder>() {
            override fun getItemCount() = values.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int) = ChoiceHolder(label("", 12f).apply {
                isFocusableInTouchMode = true; isClickable = true; setPadding(dp(12), 0, dp(8), 0)
                setTextColor(GuideTheme.ink()); background = GuideTheme.background(resources.displayMetrics.density)
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(38))
            })
            override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
                holder.label.text = (if (checked(position)) "✓  " else "    ") + values[position]
                holder.label.setOnClickListener { choose(position); notifyItemChanged(position) }
                holder.label.setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focusNavigation(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) { if (position == 0) { if (event.repeatCount == 0) controls.firstOrNull()?.requestFocus() } else focusAt(position - 1) }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusAt(position + 1); true }
                    else -> false
                } }
            }
        }
        controls.add(list); content.addView(list, LayoutParams(-1, dp(320)))
    }
    private class ChoiceHolder(val label: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(label)
    fun refresh(focus: String? = null) {
        val restore = focus ?: content.findFocus()?.tag?.toString()?.removePrefix("settings-option:")
        navRows.values.forEach { it.background = GuideTheme.background(resources.displayMetrics.density) }
        render(selected); if (restore != null) post { if (opened && hasWindowFocus()) content.findViewWithTag<View>("settings-option:$restore")?.requestFocus() }
    }
    fun selectSetting(item: String) { navRows[item]?.requestFocus(); selectPage(item); focusContent() }
    fun focusNavigation() { navRows[selected]?.requestFocus() }
    fun focusContent() { controls.firstOrNull()?.requestFocus() ?: contentScroll.requestFocus() }
    fun focusOption(title: String) { content.findViewWithTag<View>("settings-option:$title")?.requestFocus() }
    fun back() { if (contentScroll.hasFocus()) focusNavigation() else close() }
}

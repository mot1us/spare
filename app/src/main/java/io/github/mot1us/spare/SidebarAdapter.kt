package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class GuideSection(val title: String, val key: String, val active: Boolean = false)

class SidebarAdapter(private val context: Context, private val select: (GuideSection) -> Unit, private val enterGuide: () -> Unit, private val openToolbar: () -> Unit, private val move: (Int) -> Unit) : RecyclerView.Adapter<SidebarAdapter.Holder>() {
    private var items = emptyList<GuideSection>()
    private var focusedKey: String? = null
    @SuppressLint("NotifyDataSetChanged") fun submit(next: List<GuideSection>) { if (items != next) { items = next; notifyDataSetChanged() } }
    fun rememberedPosition() = items.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 } ?: activePosition()
    fun activePosition() = items.indexOfFirst { it.active }.coerceAtLeast(0)
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val d = context.resources.displayMetrics.density
        val text = object : TextView(context) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val at = items.indexOfFirst { it.key == tag }
                    val next = at + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next < 0) openToolbar() else move(next.coerceAtMost(items.lastIndex)); return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { openToolbar(); return true }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { enterGuide(); return true }
                return super.onKeyDown(keyCode, event)
            }
        }.apply {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; textSize = 12f; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10*d).toInt(), 0, (8*d).toInt(), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
            fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 4*d }
            background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(android.R.attr.state_selected), shape(0x553F4754)); addState(intArrayOf(), shape(Color.TRANSPARENT)) }
            layoutParams = RecyclerView.LayoutParams(-1, (34*d).toInt()).apply { bottomMargin = (4*d).toInt() }
        }
        return Holder(text)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.background = GuideTheme.background(context.resources.displayMetrics.density)
        val item = items[position]
        holder.label.tag = item.key; holder.label.text = item.title; holder.label.contentDescription = item.title
        holder.label.isSelected = item.active
        holder.label.typeface = Typeface.create(if (item.active) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        holder.label.setOnFocusChangeListener { _, focused -> if (focused) focusedKey = item.key }
        holder.label.setOnClickListener { select(item) }
    }
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}

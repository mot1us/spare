package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class GuideAdapter(
    private val context: Context, private val logos: LogoLoader,
    private val source: () -> Source?, private val guide: () -> Guide,
    private val favorites: () -> Set<String>, private val windowStart: (Channel) -> Long,
    private val selected: (Channel) -> Unit, private val favorite: (Channel) -> Unit,
    private val focus: (Channel) -> Unit, private val scrollTime: (Channel, Int) -> Unit,
    private val openSidebar: () -> Unit, private val moveChannel: (String, Int) -> Unit,
) : RecyclerView.Adapter<GuideAdapter.Holder>() {
    var channels: List<Channel> = emptyList()
        private set
    init { setHasStableIds(true) }
    @SuppressLint("NotifyDataSetChanged") fun submit(list: List<Channel>) { if (channels != list) { channels = list; notifyDataSetChanged() } }
    private var recycler: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { super.onAttachedToRecyclerView(recyclerView); recycler = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { recycler = null; super.onDetachedFromRecyclerView(recyclerView) }
    /** Canvas data changes need a redraw, not a rebind that can steal TV focus. */
    fun refreshRows() { recycler?.let { list -> for (i in 0 until list.childCount) list.getChildAt(i).invalidate() } }
    fun refreshTheme() { recycler?.let { list -> for (i in 0 until list.childCount) list.getChildAt(i).background = GuideTheme.background(context.resources.displayMetrics.density, 0x77303943) }; refreshRows() }
    override fun getItemCount() = channels.size
    override fun getItemId(position: Int) = channels[position].rowId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(Row(context))
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.row.channel = channels[position]; holder.row.background = GuideTheme.background(context.resources.displayMetrics.density, 0x77303943); holder.row.invalidate(); if (holder.row.hasFocus()) focus(channels[position]) }
    class Holder(val row: Row) : RecyclerView.ViewHolder(row)
    inner class Row(context: Context) : View(context) {
        var channel: Channel? = null
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logoBounds = RectF()
        private val star = android.graphics.Path()
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * this@Row.density }
        init {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (32 * density).toInt())
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isLongClickable = true
            setOnClickListener { channel?.let(selected) }
            setOnLongClickListener { channel?.let(favorite); true }
            background = GuideTheme.background(density, 0x77303943); clipToOutline = true
            setOnFocusChangeListener { _, hasFocus -> elevation = if (hasFocus) 4 * density else 0f; invalidate(); if (hasFocus) channel?.let(focus) }
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                channel?.let { moveChannel(it.id, if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1) }; return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val row = channel
                if (row != null && windowStart(row) > GuideTimeline.live(System.currentTimeMillis())) scrollTime(row, -1) else openSidebar()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                channel?.let { scrollTime(it, 1) }; return true
            }
            return super.onKeyDown(keyCode, event)
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh); star.reset()
            for (point in 0 until 10) {
                val angle = -Math.PI / 2 + point * Math.PI / 5
                val radius = (if (point % 2 == 0) 5.5 else 2.5) * density
                val x = (CHANNEL_WIDTH_DP - 12) * density + (kotlin.math.cos(angle) * radius).toFloat()
                val y = h / 2f + (kotlin.math.sin(angle) * radius).toFloat()
                if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val channel = channel ?: return
            val leftWidth = CHANNEL_WIDTH_DP * density
            val base = height / 2f + text.textSize * .35f
            val ink = if (hasFocus()) Color.BLACK else if (GuideTheme.style == "Nostalgia") GuideTheme.current.accent else Color.WHITE
            text.color = ink
            val logo = logos.get(channel.logo, source()?.allowHttp == true) { if (this.channel?.id == channel.id) invalidate() }
            if (logo != null) {
                val size = 24 * density
                val ratio = minOf(size / logo.width, size / logo.height)
                val w = logo.width * ratio; val h = logo.height * ratio
                logoBounds.set(6 * density + (size - w) / 2, (height - h) / 2, 6 * density + (size + w) / 2, (height + h) / 2)
                canvas.drawBitmap(logo, null, logoBounds, null)
            }
            val favorite = channel.id in favorites()
            val ahead = windowStart(channel) > GuideTimeline.live(System.currentTimeMillis())
            text.typeface = AppFont.face(true)
            canvas.drawText(TextUtils.ellipsize(channel.name, text, leftWidth - (if (favorite) 58 else 43) * density, TextUtils.TruncateAt.END).toString(), 36 * density, if (ahead) 13 * density else base, text)
            if (ahead) {
                text.textSize = 8.5f * density
                canvas.drawText(time(windowStart(channel)), 36 * density, 26 * density, text)
                text.textSize = 11.5f * density
            }
            if (favorite) {
                paint.color = if (hasFocus()) Color.BLACK else 0xFFFFD28A.toInt(); canvas.drawPath(star, paint)
            }
            text.typeface = AppFont.face()
            val start = windowStart(channel); val duration = 3 * 3600000L
            val available = width - leftWidth
            val programmes = guide().programmes[guide().key(channel)].orEmpty()
            val save = canvas.save()
            canvas.clipRect(leftWidth, 0f, width.toFloat(), height.toFloat())
            if (programmes.none { it.end > start && it.start < start + duration }) {
                canvas.drawText("No programme information", leftWidth + 12 * density, base, text)
            }
            programmes.forEach { p ->
                if (p.end <= start || p.start >= start + duration) return@forEach
                val x1 = leftWidth + (maxOf(p.start, start) - start).toFloat() / duration * available
                val x2 = leftWidth + (minOf(p.end, start + duration) - start).toFloat() / duration * available
                paint.color = if (hasFocus()) Color.LTGRAY else Color.rgb(56, 56, 56)
                canvas.drawLine(x1, if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) 0f else 6 * density, x1, if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) height.toFloat() else height - 6 * density, paint)
                if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) canvas.drawLine(x1, height - density, x2, height - density, paint)
                if (x2 - x1 > 20 * density) canvas.drawText(TextUtils.ellipsize(if (ahead) "${time(p.start)} · ${p.title}" else p.title, text, x2 - x1 - 16 * density, TextUtils.TruncateAt.END).toString(), x1 + 8 * density, base, text)
            }
            val now = System.currentTimeMillis()
            if (now in start..start + duration) {
                val x = leftWidth + (now - start).toFloat() / duration * available
                paint.color = ink; paint.strokeWidth = density
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            canvas.restoreToCount(save)
            contentDescription = "${channel.name}. ${guide().at(channel, maxOf(now, start))?.title ?: "No programme information"}${if (channel.id in favorites()) ". Favorite" else ""}"
        }
    }
    companion object {
        const val CHANNEL_WIDTH_DP = 154f
        fun time(value: Long): String = GuideClock.time(value)
    }
}

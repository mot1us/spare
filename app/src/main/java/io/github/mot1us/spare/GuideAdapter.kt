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
import androidx.core.graphics.withClip

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
    fun refreshTheme() { recycler?.let { list -> for (i in 0 until list.childCount) (list.getChildAt(i) as? Row)?.appearance() } }
    override fun getItemCount() = channels.size
    override fun getItemId(position: Int) = channels[position].rowId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(Row(context))
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.row.channel = channels[position]; holder.row.appearance(); if (holder.row.hasFocus()) focus(channels[position]) }
    class Holder(val row: Row) : RecyclerView.ViewHolder(row)
    inner class Row(context: Context) : View(context) {
        var channel: Channel? = null
        private val rowDensity = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logoBounds = RectF()
        private val star = android.graphics.Path()
        private val archiveBounds = RectF()
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var regularFace: Typeface = AppFont.face()
        private var titleFace: Typeface = AppFont.face(true)
        private var textSizePx = 11.5f * rowDensity
        private var smallTextSizePx = 8.5f * rowDensity
        init {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (GuideTheme.rowHeightDp * rowDensity).toInt()).apply { bottomMargin = (GuideTheme.rowGapDp * rowDensity).toInt() }
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isLongClickable = true
            setOnClickListener { channel?.let(selected) }
            setOnLongClickListener { channel?.let(favorite); true }
            appearance(); clipToOutline = true
            setOnFocusChangeListener { _, hasFocus -> elevation = if (hasFocus) 4 * rowDensity else 0f; invalidate(); if (hasFocus) channel?.let(focus) }
        }
        /** Theme editing redraws the same focused row instead of replacing its holder. */
        fun appearance() {
            val height = (GuideTheme.rowHeightDp * rowDensity).toInt()
            val gap = (GuideTheme.rowGapDp * rowDensity).toInt()
            val params = layoutParams as RecyclerView.LayoutParams
            if (params.height != height || params.bottomMargin != gap) {
                params.height = height; params.bottomMargin = gap; layoutParams = params
            }
            regularFace = AppFont.face(); titleFace = AppFont.face(true)
            textSizePx = 11.5f * rowDensity * GuideTheme.textScale
            smallTextSizePx = 8.5f * rowDensity * GuideTheme.textScale
            text.textSize = textSizePx
            background = GuideTheme.background(rowDensity, 0x77303943)
            invalidate()
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
                val radius = (if (point % 2 == 0) 5.5 else 2.5) * rowDensity
                val x = (CHANNEL_WIDTH_DP - 12) * rowDensity + (kotlin.math.cos(angle) * radius).toFloat()
                val y = h / 2f + (kotlin.math.sin(angle) * radius).toFloat()
                if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val channel = channel ?: return
            val now = System.currentTimeMillis()
            val snapshot = guide()
            val start = windowStart(channel)
            val favorite = channel.id in favorites()
            val archive = Catchup.supported(channel)
            val leftWidth = CHANNEL_WIDTH_DP * rowDensity
            val base = height / 2f + text.textSize * .35f
            val ink = if (hasFocus()) GuideTheme.focusText else if (GuideTheme.style == "Nostalgia") GuideTheme.current.accent else Color.WHITE
            text.color = ink
            val logo = logos.get(channel.logo, source()?.allowHttp == true) { if (this.channel?.id == channel.id) invalidate() }
            if (logo != null) {
                val size = 24 * rowDensity
                val ratio = minOf(size / logo.width, size / logo.height)
                val w = logo.width * ratio; val h = logo.height * ratio
                logoBounds.set(6 * rowDensity + (size - w) / 2, (height - h) / 2, 6 * rowDensity + (size + w) / 2, (height + h) / 2)
                canvas.drawBitmap(logo, null, logoBounds, null)
            }
            val ahead = start > GuideTimeline.live(now)
            text.typeface = titleFace
            val badges = (if (favorite) 16 else 0) + (if (archive) 16 else 0)
            canvas.drawText(TextUtils.ellipsize(channel.name, text, leftWidth - (43 + badges) * rowDensity, TextUtils.TruncateAt.END).toString(), 36 * rowDensity, if (ahead) height / 2f - 3 * rowDensity else base, text)
            if (ahead) {
                text.textSize = smallTextSizePx
                canvas.drawText(time(start), 36 * rowDensity, height / 2f + 10 * rowDensity, text)
                text.textSize = textSizePx
            }
            if (favorite) {
                paint.color = if (hasFocus()) GuideTheme.focusText else 0xFFFFD28A.toInt(); canvas.drawPath(star, paint)
            }
            if (archive) {
                val x = (CHANNEL_WIDTH_DP - 12 - if (favorite) 16 else 0) * rowDensity
                val y = height / 2f
                paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.25f * rowDensity
                archiveBounds.set(x - 4.5f * rowDensity, y - 4.5f * rowDensity, x + 4.5f * rowDensity, y + 4.5f * rowDensity)
                canvas.drawArc(archiveBounds, -70f, 285f, false, paint)
                canvas.drawLine(x, y, x, y - 3 * rowDensity, paint)
                canvas.drawLine(x, y, x + 2.5f * rowDensity, y + rowDensity, paint)
                paint.style = Paint.Style.FILL
            }
            text.typeface = regularFace
            val duration = 3 * 3600000L
            val available = width - leftWidth
            val programmes = snapshot.programmes[snapshot.key(channel)].orEmpty()
            canvas.withClip(leftWidth, 0f, width.toFloat(), height.toFloat()) {
            if (programmes.none { it.end > start && it.start < start + duration }) {
                canvas.drawText("No programme information", leftWidth + 12 * rowDensity, base, text)
            }
            programmes.forEach { p ->
                if (p.end <= start || p.start >= start + duration) return@forEach
                val x1 = leftWidth + (maxOf(p.start, start) - start).toFloat() / duration * available
                val x2 = leftWidth + (minOf(p.end, start + duration) - start).toFloat() / duration * available
                paint.color = if (hasFocus()) Color.LTGRAY else Color.rgb(56, 56, 56)
                canvas.drawLine(x1, if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) 0f else 6 * rowDensity, x1, if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) height.toFloat() else height - 6 * rowDensity, paint)
                if ((GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia")) canvas.drawLine(x1, height - rowDensity, x2, height - rowDensity, paint)
                if (x2 - x1 > 20 * rowDensity) canvas.drawText(TextUtils.ellipsize(if (ahead) "${time(p.start)} · ${p.title}" else p.title, text, x2 - x1 - 16 * rowDensity, TextUtils.TruncateAt.END).toString(), x1 + 8 * rowDensity, base, text)
            }
            if (now in start..start + duration) {
                val x = leftWidth + (now - start).toFloat() / duration * available
                paint.color = ink; paint.strokeWidth = rowDensity
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            }
            val description = "${channel.name}. ${snapshot.at(channel, maxOf(now, start))?.title ?: "No programme information"}${if (favorite) ". Favorite" else ""}${if (archive) ". Catch-up: ${channel.catchupDays} days" else ""}"
            if (contentDescription != description) contentDescription = description
        }
    }
    companion object {
        const val CHANNEL_WIDTH_DP = 154f
        fun time(value: Long): String = GuideClock.time(value)
    }
}

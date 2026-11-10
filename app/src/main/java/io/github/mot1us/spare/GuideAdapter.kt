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
    private val source: (Channel) -> Source?, private val guide: () -> Guide,
    private val favorites: () -> Set<String>, private val windowStart: (Channel) -> Long,
    private val selected: (Channel) -> Unit, private val favorite: (Channel) -> Unit,
    private val focus: (Channel) -> Unit, private val scrollTime: (Channel, Int) -> Unit,
    private val openSidebar: () -> Unit, private val moveChannel: (String, Int, Boolean) -> Unit,
    private val rowDetails: ((Channel) -> Pair<String, String>)? = null,
) : RecyclerView.Adapter<GuideAdapter.Holder>() {
    var channels: List<Channel> = emptyList()
        private set
    private var positions = emptyMap<String, Int>()
    init { setHasStableIds(true) }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Channel>) {
        if (channels == list) return
        positions = HashMap<String, Int>(list.size + list.size / 3 + 1).apply {
            list.forEachIndexed { index, channel -> putIfAbsent(channel.id, index) }
        }
        channels = list
        notifyDataSetChanged()
    }

    fun positionOf(id: String?): Int = positions[id] ?: -1
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
    private data class Span(val x1: Float, val x2: Float, val label: String?)
    private data class ProgrammeLabels(
        val programmes: List<Programme>, val start: Long, val width: Int, val formats: GuideClock.Formats?,
        val clock24: Boolean, val startLabel: String?, val spans: List<Span>,
    )
    private data class DetailLabels(val title: String, val detail: String, val width: Int, val titleLabel: String, val detailLabel: String)

    inner class Row(context: Context) : View(context) {
        private var logoBitmap: android.graphics.Bitmap? = null
        private var logoHttp = false
        var channel: Channel? = null
            set(value) { if (field?.logo != value?.logo) logoBitmap = null; field = value }
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
        private var programmeLabels: ProgrammeLabels? = null
        private var detailLabels: DetailLabels? = null
        private var labelName = ""
        private var labelBadges = -1
        private var channelLabel = ""
        private var describedName = ""
        private var describedTitle = ""
        private var describedDetail: String? = null
        private var describedFavorite = false
        private var describedArchiveDays: Int? = null
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
            programmeLabels = null; detailLabels = null; labelBadges = -1
            background = GuideTheme.background(rowDensity, 0x77303943)
            invalidate()
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                channel?.let { moveChannel(it.id, if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1, event.repeatCount > 0) }; return true
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
            val http = source(channel)?.allowHttp == true
            if (http != logoHttp) { logoBitmap = null; logoHttp = http }
            val logo = logoBitmap ?: logos.get(channel.logo, http) { if (this.channel?.id == channel.id) invalidate() }.also { logoBitmap = it }
            if (logo != null) {
                val size = 24 * rowDensity
                val ratio = minOf(size / logo.width, size / logo.height)
                val w = logo.width * ratio; val h = logo.height * ratio
                logoBounds.set(6 * rowDensity + (size - w) / 2, (height - h) / 2, 6 * rowDensity + (size + w) / 2, (height + h) / 2)
                canvas.drawBitmap(logo, null, logoBounds, null)
            }
            val ahead = start > GuideTimeline.live(now)
            val details = rowDetails?.invoke(channel)
            val labels = if (details == null || ahead) programmeLabels(snapshot.programmes[snapshot.key(channel)].orEmpty(), start, leftWidth, ahead) else null
            text.typeface = titleFace
            val badges = (if (favorite) 16 else 0) + (if (archive) 16 else 0)
            if (labelName != channel.name || labelBadges != badges) {
                labelName = channel.name
                labelBadges = badges
                channelLabel = ellipsize(channel.name, leftWidth - (43 + badges) * rowDensity)
            }
            canvas.drawText(channelLabel, 36 * rowDensity, if (ahead) height / 2f - 3 * rowDensity else base, text)
            labels?.startLabel?.let {
                text.textSize = smallTextSizePx
                canvas.drawText(it, 36 * rowDensity, height / 2f + 10 * rowDensity, text)
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
            if (details != null) {
                val (title, detail) = details
                val x = leftWidth + 10 * rowDensity
                val available = (width - x - 10 * rowDensity).coerceAtLeast(1f)
                val labels = detailLabels(title, detail, available)
                text.typeface = titleFace; text.textSize = textSizePx
                canvas.drawText(labels.titleLabel, x, height / 2f - 2 * rowDensity, text)
                text.typeface = regularFace; text.textSize = smallTextSizePx
                canvas.drawText(labels.detailLabel, x, height / 2f + 10 * rowDensity, text)
                text.textSize = textSizePx
                describe(channel.name, title, detail)
                return
            }
            text.typeface = regularFace
            val duration = GUIDE_WINDOW_MS
            val available = width - leftWidth
            val grid = GuideTheme.style == "Grid" || GuideTheme.style == "Nostalgia"
            canvas.withClip(leftWidth, 0f, width.toFloat(), height.toFloat()) {
            val spans = labels!!.spans
            if (spans.isEmpty()) canvas.drawText("No programme information", leftWidth + 12 * rowDensity, base, text)
            spans.forEach { (x1, x2, label) ->
                paint.color = if (hasFocus()) Color.LTGRAY else Color.rgb(56, 56, 56)
                canvas.drawLine(x1, if (grid) 0f else 6 * rowDensity, x1, if (grid) height.toFloat() else height - 6 * rowDensity, paint)
                if (grid) canvas.drawLine(x1, height - rowDensity, x2, height - rowDensity, paint)
                if (label != null) canvas.drawText(label, x1 + 8 * rowDensity, base, text)
            }
            if (now in start..start + duration) {
                val x = leftWidth + (now - start).toFloat() / duration * available
                paint.color = ink; paint.strokeWidth = rowDensity
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            }
            describe(channel.name, snapshot.at(channel, maxOf(now, start))?.title ?: "No programme information",
                favorite = favorite, archiveDays = channel.catchupDays.takeIf { archive })
        }

        private fun ellipsize(value: String, available: Float): String =
            TextUtils.ellipsize(value, text, available, TextUtils.TruncateAt.END).toString()

        private fun programmeLabels(programmes: List<Programme>, start: Long, leftWidth: Float, ahead: Boolean): ProgrammeLabels {
            val formats = if (ahead) GuideClock.formats() else null
            val clock24 = ahead && GuideClock.use24Hour
            programmeLabels?.let { cached ->
                if (cached.programmes === programmes && cached.start == start && cached.width == width &&
                    cached.formats === formats && cached.clock24 == clock24) return cached
            }
            val available = width - leftWidth
            val duration = GUIDE_WINDOW_MS
            text.typeface = regularFace
            val spans = buildList {
                for (programme in programmes) {
                    if (programme.end <= start || programme.start >= start + duration) continue
                    val x1 = leftWidth + (maxOf(programme.start, start) - start).toFloat() / duration * available
                    val x2 = leftWidth + (minOf(programme.end, start + duration) - start).toFloat() / duration * available
                    val label = if (x2 - x1 > 20 * rowDensity) {
                        val title = if (formats == null) programme.title else "${formats.time(programme.start, clock24)} · ${programme.title}"
                        ellipsize(title, x2 - x1 - 16 * rowDensity)
                    } else null
                    add(Span(x1, x2, label))
                }
            }
            return ProgrammeLabels(programmes, start, width, formats, clock24, formats?.time(start, clock24), spans).also { programmeLabels = it }
        }

        private fun detailLabels(title: String, detail: String, available: Float): DetailLabels {
            detailLabels?.takeIf { it.title == title && it.detail == detail && it.width == width }?.let { return it }
            text.typeface = titleFace; text.textSize = textSizePx
            val titleLabel = ellipsize(title, available)
            text.typeface = regularFace; text.textSize = smallTextSizePx
            return DetailLabels(title, detail, width, titleLabel, ellipsize(detail.replace('\n', ' '), available)).also { detailLabels = it }
        }

        private fun describe(name: String, title: String, detail: String? = null, favorite: Boolean = false, archiveDays: Int? = null) {
            if (describedName == name && describedTitle == title && describedDetail == detail &&
                describedFavorite == favorite && describedArchiveDays == archiveDays) return
            describedName = name; describedTitle = title; describedDetail = detail
            describedFavorite = favorite; describedArchiveDays = archiveDays
            contentDescription = if (detail != null) "$name. $title. $detail" else
                "$name. $title" + (if (favorite) ". Favorite" else "") + (archiveDays?.let { ". Catch-up: $it days" }.orEmpty())
        }
    }
    companion object {
        private const val GUIDE_WINDOW_MS = 3 * 3600000L
        const val CHANNEL_WIDTH_DP = 154f
        fun time(value: Long): String = GuideClock.time(value)
    }
}

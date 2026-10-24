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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GuideAdapter(
    private val context: Context, private val logos: LogoLoader,
    private val source: () -> Source?, private val guide: () -> Guide,
    private val favorites: () -> Set<String>, private val windowStart: () -> Long,
    private val selected: (Channel) -> Unit, private val favorite: (Channel) -> Unit,
    private val focus: (Channel) -> Unit, private val scrollTime: (Int) -> Unit,
) : RecyclerView.Adapter<GuideAdapter.Holder>() {
    var channels: List<Channel> = emptyList()
        private set
    init { setHasStableIds(true) }
    @SuppressLint("NotifyDataSetChanged") fun submit(list: List<Channel>) { channels = list; notifyDataSetChanged() }
    override fun getItemCount() = channels.size
    override fun getItemId(position: Int) = Channel.stableId(channels[position].id).take(16).toULong(16).toLong()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(Row(context))
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.row.channel = channels[position]; holder.row.invalidate() }
    class Holder(val row: Row) : RecyclerView.ViewHolder(row)
    inner class Row(context: Context) : View(context) {
        var channel: Channel? = null
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logoBounds = RectF()
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * this@Row.density }
        private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        init {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (42 * density).toInt())
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true; isLongClickable = true
            setOnClickListener { channel?.let(selected) }
            setOnLongClickListener { channel?.let(favorite); true }
            setOnFocusChangeListener { _, hasFocus -> invalidate(); if (hasFocus) channel?.let(focus) }
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                scrollTime(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1); return true
            }
            return super.onKeyDown(keyCode, event)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val channel = channel ?: return
            val leftWidth = 220 * density
            val base = height / 2f + text.textSize * .35f
            paint.color = if (hasFocus()) Color.WHITE else Color.rgb(24, 24, 24)
            canvas.drawRoundRect(0f, 2 * density, width.toFloat(), height - 2 * density, 6 * density, 6 * density, paint)
            val ink = if (hasFocus()) Color.BLACK else Color.WHITE
            text.color = ink
            val logo = logos.get(channel.logo, source()?.allowHttp == true) { if (this.channel?.id == channel.id) invalidate() }
            if (logo != null) {
                val size = 32 * density
                val ratio = minOf(size / logo.width, size / logo.height)
                val w = logo.width * ratio; val h = logo.height * ratio
                logoBounds.set(8 * density + (size - w) / 2, (height - h) / 2, 8 * density + (size + w) / 2, (height + h) / 2)
                canvas.drawBitmap(logo, null, logoBounds, null)
            }
            val name = (if (channel.id in favorites()) "★ " else "") + channel.name
            text.typeface = medium
            canvas.drawText(TextUtils.ellipsize(name, text, leftWidth - 62 * density, TextUtils.TruncateAt.END).toString(), 54 * density, base, text)
            text.typeface = Typeface.DEFAULT
            val start = windowStart(); val duration = 3 * 3600000L
            val available = width - leftWidth
            val programmes = guide().programmes[channel.guideId].orEmpty()
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
                canvas.drawLine(x1, 6 * density, x1, height - 6 * density, paint)
                if (x2 - x1 > 20 * density) canvas.drawText(TextUtils.ellipsize(p.title, text, x2 - x1 - 16 * density, TextUtils.TruncateAt.END).toString(), x1 + 8 * density, base, text)
            }
            val now = System.currentTimeMillis()
            if (now in start..start + duration) {
                val x = leftWidth + (now - start).toFloat() / duration * available
                paint.color = ink; paint.strokeWidth = density
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            canvas.restoreToCount(save)
            contentDescription = "${channel.name}. ${guide().at(channel, now)?.title ?: "No programme information"}${if (channel.id in favorites()) ". Favorite" else ""}"
        }
    }
    companion object {
        fun time(value: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(value))
    }
}

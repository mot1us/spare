package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

@SuppressLint("ViewConstructor", "SetTextI18n")
class OnDemandControls(context: Context, button: (String, () -> Unit) -> Button, toggle: () -> Unit, seek: (Long) -> Unit,
    restart: () -> Unit, subtitles: () -> Unit, audio: () -> Unit, next: () -> Unit, library: () -> Unit, live: () -> Unit, stop: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val title = TextView(context).apply { textSize = 19f; setTextColor(Color.WHITE); maxLines = 2; typeface = AppFont.face(true) }
    private val position = TextView(context).apply { textSize = 12f; setTextColor(Color.LTGRAY); typeface = AppFont.face() }
    private val timeline = SeekBar(context).apply { max = 1000; tag = "on-demand-seek"; keyProgressIncrement = 10 }
    val pause = button("Pause", toggle).apply { tag = "on-demand-pause" }
    private val nextButton = button("Next episode", next).apply { tag = "on-demand-next" }
    private var duration = 0L
    private var dragging = false
    init {
        orientation = VERTICAL; visibility = GONE; tag = "on-demand-controls"; setPadding(dp(22), dp(16), dp(22), dp(16)); background = GuideTheme.glass(resources.displayMetrics.density)
        addView(title); addView(position, LayoutParams(-1, -2).apply { topMargin = dp(8) }); addView(timeline, LayoutParams(-1, dp(32)))
        val actions = LinearLayout(context)
        listOf(pause, button("Start over", restart).apply { tag = "on-demand-restart" }, button("Subtitles", subtitles), button("Audio", audio), nextButton, button("Library", library).apply { tag = "on-demand-library" }, button("Stop", stop).apply { tag = "on-demand-stop" }, button("Live TV", live)).forEach(actions::addView)
        addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, LayoutParams(-1, dp(36)))
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; seek(duration * bar.progress / 1000) }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser && !dragging) seek(duration * progress / 1000) }
        })
    }
    fun update(item: OnDemand, current: Long, total: Long, playing: Boolean, seekable: Boolean, hasNext: Boolean, ended: Boolean) {
        title.text = item.title + if (item.kind == "catchup") " · Catch-up" else ""
        duration = total.coerceAtLeast(0)
        position.text = (if (ended) "Finished · " else "") + LibraryController.duration(current) + if (duration > 0) " / ${LibraryController.duration(duration)}" else " · Duration unavailable"
        if (!dragging) timeline.progress = if (duration > 0) (current.coerceAtLeast(0) * 1000 / duration).toInt().coerceIn(0, 1000) else 0
        timeline.isEnabled = seekable && duration > 0; pause.text = if (playing) "Pause" else "Play"; nextButton.visibility = if (hasNext) VISIBLE else GONE
    }
}

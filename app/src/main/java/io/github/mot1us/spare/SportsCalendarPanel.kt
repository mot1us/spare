package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object SportsCalendar {
    fun days(month: YearMonth): List<LocalDate> {
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
        return List(42) { start.plusDays(it.toLong()) }
    }
}

/** A real month grid on the same translucent full-screen surface as TV settings. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class SportsCalendarPanel(context: Context, button: (String, () -> Unit) -> Button,
    private val choose: (LocalDate) -> Unit, private val preview: (LocalDate, Boolean) -> Unit,
    private val gameSelected: (LocalDate, SportsGame) -> Unit, private val cancel: () -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
    private var selected = LocalDate.now()
    private var month = YearMonth.from(selected)
    var activeDate: LocalDate = selected
        private set
    private var inputEpoch = 0
    private var focusEpoch = 0
    private var pendingGames: Int? = null
    private var games = emptyList<SportsGame>()
    private var hideScores = false
    private val counts = linkedMapOf<LocalDate, Int>()
    private var dates = emptyList<LocalDate>()
    private val days = mutableListOf<Button>()
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "sports-calendar-grid" }
    private val monthTitle = label(23f).apply { tag = "sports-calendar-month"; gravity = Gravity.CENTER; typeface = AppFont.face(true) }
    private val detail = label(12f).apply { tag = "sports-calendar-detail" }
    private val previous = button("‹") { changeMonth(-1) }.apply { tag = "calendar-previous"; contentDescription = "Previous month" }
    private val next = button("›") { changeMonth(1) }.apply { tag = "calendar-next"; contentDescription = "Next month" }
    private val today = button("Today") { month = YearMonth.now(); render(); focusDate(LocalDate.now()) }.apply { tag = "calendar-today" }
    private val agendaTitle = label(17f).apply { typeface = AppFont.face(true); tag = "calendar-games-date" }
    private val agendaStatus = label(11f).apply { setTextColor(Color.LTGRAY); maxLines = 2; tag = "calendar-games-status" }
    private val agendaProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = INVISIBLE; tag = "calendar-games-loading" }
    private val openDate = button("Open date in Sports") { choose(activeDate) }.apply { tag = "calendar-open-date" }
    private val refresh = button("Refresh") { preview(activeDate, true) }.apply { tag = "calendar-refresh" }
    private val agenda = RecyclerView(context).apply { layoutManager = TvFocus.Layout(context); itemAnimator = null; overScrollMode = OVER_SCROLL_NEVER; tag = "calendar-games" }
    private class GameRow(val box: LinearLayout, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(box)
    private val gameAdapter = object : RecyclerView.Adapter<GameRow>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = games.size
        override fun getItemId(position: Int) = Channel.stableLongId(games[position].key)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): GameRow {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true; isClickable = true; setPadding(dp(12), dp(8), dp(12), dp(8)); background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B); layoutParams = RecyclerView.LayoutParams(-1, dp(80)).apply { bottomMargin = dp(7) } }
            val title = label(13f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
            val info = label(11f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            box.addView(title, LinearLayout.LayoutParams(-1, 0, 1f)); box.addView(info)
            return GameRow(box, title, info)
        }
        override fun onBindViewHolder(holder: GameRow, position: Int) {
            val game = games[position]
            holder.box.tag = "calendar-game:${game.key}"
            holder.title.text = SportsText.score(game, hideScores)
            val now = System.currentTimeMillis()
            holder.detail.text = SportsLeagues.label(game.leagueId) + " · " + when { game.live(now) -> "● LIVE · ${GuideClock.time(game.start)}"; game.state == "pre" -> GuideClock.time(game.start); else -> SportsText.status(game, hideScores, now) }
            holder.box.contentDescription = holder.title.text.toString() + ". " + holder.detail.text
            fun ink(active: Boolean) { holder.title.setTextColor(if (active) Color.BLACK else Color.WHITE); holder.detail.setTextColor(if (active) Color.BLACK else Color.LTGRAY) }
            ink(holder.box.hasFocus()); holder.box.setOnFocusChangeListener { _, active -> ink(active); if (active) TvFocus.center(agenda, holder.box) }
            holder.box.setOnClickListener { gameSelected(activeDate, game) }
            holder.box.setOnKeyListener { _, key, event ->
                if (key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) false else {
                    if (event.action == KeyEvent.ACTION_DOWN) when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> focusDate(activeDate)
                        KeyEvent.KEYCODE_DPAD_UP -> if (holder.bindingAdapterPosition <= 0) openDate.requestFocus() else focusGame(holder.bindingAdapterPosition - 1)
                        else -> focusGame((holder.bindingAdapterPosition + 1).coerceAtMost(games.lastIndex))
                    }; true
                }
            }
        }
    }
    val opened get() = isVisible
    init {
        tag = "sports-calendar"; visibility = GONE
        background = GuideTheme.guideBackground()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(24), dp(28), dp(20)) }
        content.addView(label(15f).apply { text = "SPORTS CALENDAR"; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(30)))
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(previous, LinearLayout.LayoutParams(dp(46), dp(38)))
        controls.addView(monthTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(next, LinearLayout.LayoutParams(dp(46), dp(38)))
        controls.addView(today, LinearLayout.LayoutParams(dp(94), dp(38)).apply { marginStart = dp(12) })
        controls.addView(button("Close", cancel).apply { tag = "calendar-close" }, LinearLayout.LayoutParams(dp(94), dp(38)).apply { marginStart = dp(8) })
        content.addView(controls, LinearLayout.LayoutParams(-1, dp(50)))
        val weekdays = LinearLayout(context)
        listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach { name -> weekdays.addView(label(10f).apply { text = name; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(0, dp(30), 1f)) }
        val monthArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(weekdays); addView(grid, LinearLayout.LayoutParams(-1, 0, 1f)) }
        val gameArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        gameArea.addView(agendaTitle, LinearLayout.LayoutParams(-1, dp(30)))
        gameArea.addView(agendaStatus, LinearLayout.LayoutParams(-1, dp(34)))
        gameArea.addView(agendaProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { bottomMargin = dp(7) })
        val gameControls = LinearLayout(context)
        gameControls.addView(openDate, LinearLayout.LayoutParams(0, dp(36), 2f).apply { marginEnd = dp(6) }); gameControls.addView(refresh, LinearLayout.LayoutParams(0, dp(36), 1f))
        gameArea.addView(gameControls, LinearLayout.LayoutParams(-1, dp(44)))
        agenda.adapter = gameAdapter; gameArea.addView(agenda, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(context)
        body.addView(monthArea, LinearLayout.LayoutParams(0, -1, 3f)); body.addView(gameArea, LinearLayout.LayoutParams(0, -1, 2f))
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        listOf(openDate, refresh).forEach { control -> control.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT) { if (event.action == KeyEvent.ACTION_DOWN) { if (key == KeyEvent.KEYCODE_DPAD_LEFT) focusDate(activeDate) else focusGame(0) }; true } else false
        } }
        content.addView(detail, LinearLayout.LayoutParams(-1, dp(28)))
        content.addView(label(10f).apply { text = "OK  Games     ·  Right at edge  Games     ·  Left  Calendar     ·  Back  Close"; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(20)))
        addView(content, LayoutParams(-1, -1))
    }
    fun open(date: LocalDate) {
        selected = date; activeDate = date; month = YearMonth.from(date); pendingGames = null; ++focusEpoch; visibility = VISIBLE
        render(); focusDate(date)
    }
    private fun changeMonth(amount: Long) { month = month.plusMonths(amount); render() }
    private fun render() {
        monthTitle.text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        dates = SportsCalendar.days(month); grid.removeAllViews(); days.clear()
        val now = LocalDate.now()
        dates.chunked(7).forEachIndexed { week, values ->
            val row = LinearLayout(context)
            values.forEachIndexed { column, date ->
                val index = week * 7 + column
                val day = Button(context).apply {
                    tag = "calendar-day:$date"; text = dayText(date, now)
                    typeface = AppFont.face(date == selected || date == now); textSize = 16f
                    isFocusableInTouchMode = true; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0); stateListAnimator = null
                    background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
                    isSelected = date == selected
                    val resting = if (YearMonth.from(date) == month) Color.WHITE else Color.GRAY
                    setTextColor(resting)
                    contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + (if (date == now) ", today" else "") + if (date == selected) ", selected" else ""
                    setOnFocusChangeListener { _, active -> setTextColor(if (active) Color.BLACK else resting); if (active) { detail.text = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + if (date == selected) " · Selected" else ""; previewDate(date) } }
                    setOnClickListener { if (date != activeDate) previewDate(date); if (games.isNotEmpty()) focusGame(0) else { pendingGames = inputEpoch; openDate.requestFocus() } }
                    setOnKeyListener { _, key, event ->
                        val shift = when (key) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_UP -> -7; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> 0 }
                        if (shift == 0) false else {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                val target = index + shift
                                when { key == KeyEvent.KEYCODE_DPAD_RIGHT && column == 6 -> { if (games.isNotEmpty()) focusGame(0) else openDate.requestFocus() }; target < 0 -> previous.requestFocus(); target >= days.size -> today.requestFocus(); else -> days[target].requestFocus() }
                            }; true
                        }
                    }
                }
                days.add(day); row.addView(day, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        listOf(previous, next, today).forEach { control -> control.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (event.action == KeyEvent.ACTION_DOWN) focusDate(if (YearMonth.from(selected) == month) selected else month.atDay(1)); true } else false
        } }
    }
    private fun dayText(day: LocalDate, today: LocalDate = LocalDate.now()) = day.dayOfMonth.toString() + (if (day == today) " •" else "") + (counts[day]?.takeIf { it > 0 }?.let { "\n$it ${if (it == 1) "game" else "games"}" }.orEmpty())
    private fun previewDate(day: LocalDate) {
        if (day != activeDate) { val count = games.size; games = emptyList(); if (count > 0) gameAdapter.notifyItemRangeRemoved(0, count); pendingGames = null; ++focusEpoch }
        activeDate = day; agendaTitle.text = day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        preview(day, false)
    }
    fun submit(day: LocalDate, next: List<SportsGame>, loading: Boolean, failed: Boolean, spoilers: Boolean) {
        if (!opened || day != activeDate) return
        val before = games; val previousSpoilers = hideScores; val changed = before != next || previousSpoilers != spoilers; hideScores = spoilers
        if (changed) {
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = before.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = before[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = before[a] == next[b] && previousSpoilers == spoilers
            })
            games = next; diff.dispatchUpdatesTo(gameAdapter)
        }
        if (!loading && !failed) { counts[day] = next.size; while (counts.size > 42) counts.remove(counts.keys.first()); days.forEachIndexed { index, button -> button.text = dayText(dates[index]) } }
        agendaProgress.visibility = if (loading) VISIBLE else INVISIBLE
        val noun = if (next.size == 1) "game" else "games"
        agendaStatus.text = when { loading && next.isEmpty() -> "Loading games…"; loading -> "${next.size} saved $noun · Updating…"; failed && next.isEmpty() -> "Games unavailable · Refresh to retry"; failed -> "${next.size} saved $noun · Some leagues unavailable"; next.isEmpty() -> "No games in your leagues on this date"; else -> "${next.size} $noun · Your leagues" }
        if (!loading && next.isNotEmpty() && pendingGames == inputEpoch) { pendingGames = null; focusGame(0) }
    }
    private fun focusGame(position: Int) {
        if (position !in games.indices) return
        val token = ++focusEpoch; val input = inputEpoch; val key = games[position].key
        agenda.scrollToPosition(position)
        fun attempt(left: Int) {
            if (!opened || token != focusEpoch || input != inputEpoch || games.getOrNull(position)?.key != key) return
            val row = agenda.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) row.requestFocus() else if (left > 0) agenda.postOnAnimation { attempt(left - 1) }
        }; attempt(12)
    }
    private fun focusDate(date: LocalDate) { dates.indexOf(date).takeIf { it >= 0 }?.let { days[it].requestFocus() } }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) ++inputEpoch
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancel(); return true }
        return super.dispatchKeyEvent(event)
    }
}

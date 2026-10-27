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
    private val choose: (LocalDate) -> Unit, private val cancel: () -> Unit) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply { textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
    private var selected = LocalDate.now()
    private var month = YearMonth.from(selected)
    private var dates = emptyList<LocalDate>()
    private val days = mutableListOf<Button>()
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "sports-calendar-grid" }
    private val monthTitle = label(23f).apply { tag = "sports-calendar-month"; gravity = Gravity.CENTER; typeface = AppFont.face(true) }
    private val detail = label(12f).apply { tag = "sports-calendar-detail" }
    private val previous = button("‹") { changeMonth(-1) }.apply { tag = "calendar-previous"; contentDescription = "Previous month" }
    private val next = button("›") { changeMonth(1) }.apply { tag = "calendar-next"; contentDescription = "Next month" }
    private val today = button("Today") { month = YearMonth.now(); render(); focusDate(LocalDate.now()) }.apply { tag = "calendar-today" }
    val opened get() = isVisible
    init {
        tag = "sports-calendar"; visibility = GONE
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00B1018.toInt(), 0xD90B1018.toInt()))
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
        content.addView(weekdays)
        content.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(detail, LinearLayout.LayoutParams(-1, dp(28)))
        content.addView(label(10f).apply { text = "OK  View games     ·  Today returns to this month     ·  Back  Cancel"; setTextColor(Color.LTGRAY) }, LinearLayout.LayoutParams(-1, dp(20)))
        addView(content, LayoutParams(dp(760), -1, Gravity.CENTER))
    }
    fun open(date: LocalDate) {
        selected = date; month = YearMonth.from(date); visibility = VISIBLE
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
                    tag = "calendar-day:$date"; text = date.dayOfMonth.toString() + if (date == now) " •" else ""
                    typeface = AppFont.face(date == selected || date == now); textSize = 16f
                    isFocusableInTouchMode = true; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0); stateListAnimator = null
                    background = GuideTheme.background(resources.displayMetrics.density, 0x44323D4B)
                    isSelected = date == selected
                    val resting = if (YearMonth.from(date) == month) Color.WHITE else Color.GRAY
                    setTextColor(resting)
                    contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + (if (date == now) ", today" else "") + if (date == selected) ", selected" else ""
                    setOnFocusChangeListener { _, active -> setTextColor(if (active) Color.BLACK else resting); if (active) detail.text = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + if (date == selected) " · Selected" else "" }
                    setOnClickListener { choose(date) }
                    setOnKeyListener { _, key, event ->
                        val shift = when (key) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_UP -> -7; KeyEvent.KEYCODE_DPAD_DOWN -> 7; else -> 0 }
                        if (shift == 0) false else {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                val target = index + shift
                                when { target < 0 -> previous.requestFocus(); target >= days.size -> today.requestFocus(); else -> days[target].requestFocus() }
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
    private fun focusDate(date: LocalDate) { dates.indexOf(date).takeIf { it >= 0 }?.let { days[it].requestFocus() } }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancel(); return true }
        return super.dispatchKeyEvent(event)
    }
}

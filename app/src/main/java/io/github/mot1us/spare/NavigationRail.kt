package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Fixed geometry and one focus column across Guide, Sports, Library and Settings. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class NavigationRail(context: Context, search: () -> Unit, guide: () -> Unit, sports: () -> Unit,
    library: () -> Unit, settings: () -> Unit, private val enter: () -> Unit, private val focused: () -> Unit) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val controls = mutableListOf<Button>()
    private var remembered: Button? = null
    var expanded = true
        private set
    var moving = false
        private set
    private var motionEpoch = 0
    private var hiddenDock = false
    var expansionChanged: (() -> Unit)? = null
    fun configure(hidden: Boolean) {
        hiddenDock = hidden; background = if (hidden) GuideTheme.glass(resources.displayMetrics.density) else null
        elevation = if (hidden) dp(10).toFloat() else 0f
        setExpanded(!hidden || hasFocus(), animated = false)
    }
    fun setExpanded(open: Boolean, animated: Boolean = true) {
        val next = !hiddenDock || open
        if (animated && expanded == next) return
        expanded = next
        val epoch = ++motionEpoch
        animate().cancel()
        moving = animated && isLaidOut
        if (moving) {
            // Translate one cached surface; fading its shadow and children together caused closing shimmer.
            alpha = 1f
            animate().translationX(if (next) 0f else -dp(94).toFloat()).withLayer()
                .setDuration(if (next) 180L else 150L)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                .withEndAction { if (epoch == motionEpoch) { moving = false; alpha = if (expanded) 1f else 0f; expansionChanged?.invoke() } }.start()
        } else { translationX = if (next) 0f else -dp(94).toFloat(); alpha = if (next) 1f else 0f }
        expansionChanged?.invoke()
    }
    fun focusChanged(inside: Boolean) { setExpanded(inside) }

    private fun item(label: String, key: String, icon: Int, click: () -> Unit): Button {
        val control = object : Button(context) {
            private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (isSelected) { marker.color = if (hasFocus()) Color.BLACK else GuideTheme.current.accent; canvas.drawRect(dp(2).toFloat(), dp(17).toFloat(), dp(4).toFloat(), (height - dp(17)).toFloat(), marker) }
            }
        }.apply {
            text = label; tag = key; contentDescription = label; id = View.generateViewId()
            isFocusableInTouchMode = true; isAllCaps = false; textSize = 10f; typeface = AppFont.face()
            maxLines = 1; gravity = Gravity.CENTER; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(5), dp(7), dp(5), dp(5)); compoundDrawablePadding = dp(3); stateListAnimator = null
            val glyph = context.getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(22), dp(22)) }
            setCompoundDrawables(null, glyph, null, null)
            setOnClickListener { click() }
            setOnFocusChangeListener { _, active -> if (active) { remembered = this; focused() } }
            setOnKeyListener { _, keyCode, event -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) controls.getOrNull((controls.indexOf(this) + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(controls.indices))?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) enter(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            } }
        }
        controls.add(control)
        val slot = FrameLayout(context).apply { addView(control, FrameLayout.LayoutParams(-1, -1)) }
        addView(slot, LayoutParams(-1, dp(62)).apply { bottomMargin = dp(8) })
        return control
    }
    val search = item("Search", "search", R.drawable.ic_search, search)
    val guide = item("Guide", "guide-mode", R.drawable.ic_guide, guide)
    val sports = item("Sports", "sports-mode", R.drawable.ic_sports, sports)
    val library = item("Library", "library", R.drawable.ic_library, library)
    val settings = item("Settings", "settings", R.drawable.ic_settings, settings)
    val badge = TextView(context).apply { text = "•"; textSize = 16f; setTextColor(Color.WHITE); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = GONE }
    init {
        orientation = VERTICAL; tag = "app-navigation"; setPadding(dp(4), dp(4), dp(4), 0)
        (this.settings.parent as FrameLayout).addView(badge, FrameLayout.LayoutParams(dp(12), dp(20), Gravity.TOP or Gravity.END))
        appearance(); activate("guide-mode")
    }
    fun activate(key: String) { controls.forEach { it.isSelected = it.tag == key; it.invalidate() } }
    fun focusCurrent() { setExpanded(true); (controls.firstOrNull { it.isSelected } ?: remembered ?: guide).requestFocus() }
    fun appearance() {
        background = if (hiddenDock) GuideTheme.glass(resources.displayMetrics.density) else null
        controls.forEach { it.background = GuideTheme.background(resources.displayMetrics.density, 0x440B1018); it.setTextColor(GuideTheme.ink()); it.compoundDrawableTintList = GuideTheme.ink(); it.invalidate() }
    }
}

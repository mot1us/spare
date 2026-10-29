package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** A bounded, remote-first editor. Preview changes do not touch playback. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class ThemeEditor(context: Context, private val store: SecureStore, private val changed: () -> Unit, private val leave: () -> Unit) : LinearLayout(context), SettingsContent {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val pages = listOf("Look", "Colors", "Type", "Guide")
    private var page = "Look"
    private val tabs = linkedMapOf<String, TextView>()
    private val controls = mutableListOf<View>()
    private val updates = mutableListOf<() -> Unit>()
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val preview = Preview(context)
    private val swatches = listOf(0xFFFFFF, 0x90CAFF, 0x8CE5C1, 0xFFD28A, 0xFFADC9, 0xC6B5FF, 0xFFAD9B, 0xD3EFA0, 0x00C6D7, 0xE35369)
    private val backgrounds = listOf(0x000000, 0x0B1018, 0x091325, 0x0A1B17, 0x1E160B, 0x200F19, 0x160E26, 0x23252A, 0x152829)

    init {
        orientation = HORIZONTAL; tag = "theme-editor"; clipChildren = false
        val editor = LinearLayout(context).apply { orientation = VERTICAL }
        val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        pages.forEachIndexed { index, title ->
            val tab = text(title, 12f).apply {
                tag = "theme-tab:$title"; gravity = Gravity.CENTER; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { _, active -> if (active) selectPage(title) }
                setOnClickListener { selectPage(title); controls.firstOrNull()?.requestFocus() }
                setOnKeyListener { _, key, event -> when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val next = index + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            if (next < 0) leave() else tabs[pages[next.coerceAtMost(pages.lastIndex)]]?.requestFocus()
                        }; true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) controls.firstOrNull()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) controls.firstOrNull()?.requestFocus(); true }
                    else -> false
                } }
            }
            tabs[title] = tab
            tabRow.addView(tab, LayoutParams(0, dp(38), 1f).apply { if (index < pages.lastIndex) rightMargin = dp(3) })
        }
        editor.addView(tabRow, LayoutParams(-1, dp(38)))
        editor.addView(rows, LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        editor.addView(text("← → adjust    OK next\nSaved automatically", 10f).apply { setTextColor(0xFFBAC8DC.toInt()); setPadding(dp(6), dp(4), 0, 0) }, LayoutParams(-1, dp(38)))
        addView(editor, LayoutParams(0, -1, 1.1f))
        addView(preview, LayoutParams(0, -1, 1f).apply { leftMargin = dp(16) })
        selectPage(page, true)
    }

    override fun focusContent(): Boolean = focusEditor()
    fun focusEditor(): Boolean = tabs[page]?.requestFocus() == true
    override fun handlesLeft(focused: View?): Boolean {
        var view = focused
        while (view != null) { if (view === this) return true; view = view.parent as? View }
        return false
    }

    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; typeface = AppFont.faceFor(store.font)
    }
    private fun selectPage(title: String, force: Boolean = false) {
        if (!force && page == title) return
        page = title; controls.clear(); updates.clear(); rows.removeAllViews()
        when (title) {
            "Look" -> {
                choice("Palette", { store.theme }, { step -> store.theme = cycle(GuideTheme.palettes.map { it.name }, store.theme, step) })
                choice("Style", { store.themeStyle }, { step -> store.themeStyle = cycle(GuideTheme.styles, store.themeStyle, step) })
                choice("Corners", { if (store.cornerRadius < 0) "Style default" else "${store.cornerRadius} dp" }, { step -> store.cornerRadius = cycle(listOf(-1, 0, 3, 6, 8, 12, 16, 20), store.cornerRadius, step) })
                choice("Borders", { if (store.borderWidth < 0) "Style default" else "${store.borderWidth} dp" }, { step -> store.borderWidth = cycle(listOf(-1, 0, 1, 2, 3), store.borderWidth, step) })
                rows.addView(action("Reset defaults") { reset() }, LayoutParams(-1, dp(43)))
            }
            "Colors" -> {
                choice("Accent", { colorValue(store.customAccent, 0) }, { step -> store.customAccent = cycle(listOf(0) + swatches.map { it or 0xFF000000.toInt() }, store.customAccent, step) }, { accent() })
                hex("Accent hex", { store.customAccent }, 0) { store.customAccent = it or 0xFF000000.toInt() }
                choice("Background tint", { colorValue(store.customBackground, -1) }, { step -> store.customBackground = cycle(listOf(-1) + backgrounds, store.customBackground, step) }, { backgroundColor() })
                hex("Background hex", { store.customBackground }, -1) { store.customBackground = it }
            }
            "Type" -> {
                choice("Font", { store.font }, { step -> store.font = cycle(AppFont.names, store.font, step) })
                choice("Guide text size", { "${store.guideTextScale}%" }, { step -> store.guideTextScale = cycle((85..120 step 5).toList(), store.guideTextScale, step) })
            }
            "Guide" -> {
                choice("Row height", { "${store.guideRowHeight} dp" }, { step -> store.guideRowHeight = cycle((28..44 step 2).toList(), store.guideRowHeight, step) })
                choice("Row gap", { "${store.guideRowGap} dp" }, { step -> store.guideRowGap = cycle((2..8).toList(), store.guideRowGap, step) })
                choice("Opacity", { if (store.guideOpacity < 0) "Style default" else "${store.guideOpacity}%" }, { step -> store.guideOpacity = cycle(listOf(-1) + (0..100 step 10).toList(), store.guideOpacity, step) })
                choice("Guide video", { if (store.smallPreview) "Small preview" else "Background video" }, { _ -> store.smallPreview = !store.smallPreview })
            }
        }
        refreshPreview()
    }
    private fun <T> cycle(values: List<T>, current: T, step: Int): T {
        val at = values.indexOf(current).takeIf { it >= 0 } ?: 0
        return values[Math.floorMod(at + step, values.size)]
    }
    private fun save() { refreshPreview(); changed() }
    private fun choice(title: String, value: () -> String, adjust: (Int) -> Unit, swatch: (() -> Int)? = null) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusableInTouchMode = true; isClickable = true
            tag = "theme-control:$title"; setPadding(dp(10), 0, dp(8), 0)
        }
        val name = text(title, 12f)
        val state = text("", 11f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 2 }
        val chip = swatch?.let { View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } }
        row.addView(name, LayoutParams(0, -1, 1f))
        row.addView(state, LayoutParams(0, -1, 1.1f))
        if (chip != null) row.addView(chip, LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(7) })
        fun paint() {
            state.text = value(); row.contentDescription = "$title, ${value()}. Left or right to change."
            row.background = controlBackground(row.hasFocus())
            val ink = if (row.hasFocus()) contrastingInk(accent()) else Color.WHITE
            name.setTextColor(ink); state.setTextColor(ink)
            name.typeface = AppFont.faceFor(store.font); state.typeface = AppFont.faceFor(store.font)
            chip?.background = GradientDrawable().apply { setColor(swatch!!.invoke()); cornerRadius = dp(3).toFloat(); setStroke(dp(1).coerceAtLeast(1), if (row.hasFocus()) ink else 0xAAFFFFFF.toInt()) }
        }
        updates.add(::paint)
        row.setOnFocusChangeListener { _, _ -> paint() }
        row.setOnClickListener { adjust(1); save() }
        row.setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) { adjust(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); save() }; true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { adjust(1); save() }; true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) move(row, if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1); true }
            else -> false
        } }
        controls.add(row); rows.addView(row, LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
    }
    private fun action(title: String, run: () -> Unit): TextView = text(title, 11f).apply {
        tag = "theme-control:$title"; gravity = Gravity.CENTER; isFocusableInTouchMode = true; isClickable = true
        controls.add(this)
        fun paint() { background = controlBackground(hasFocus()); setTextColor(if (hasFocus()) contrastingInk(accent()) else Color.WHITE); typeface = AppFont.faceFor(store.font) }
        updates.add(::paint); setOnFocusChangeListener { _, _ -> paint() }
        setOnClickListener { run(); save() }
        setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) move(this, if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { run(); save() }; true }
            else -> false
        } }
    }
    private fun hex(title: String, read: () -> Int, automatic: Int, write: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        val label = text(title, 11f)
        val input = EditText(context).apply {
            tag = "theme-control:$title"; contentDescription = "$title, six RGB hexadecimal digits"; textSize = 12f
            setTextColor(Color.WHITE); setHintTextColor(0xFFBAC8DC.toInt()); hint = "RRGGBB"; setSingleLine(); setPadding(dp(8), 0, dp(4), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                val value = source.subSequence(start, end); val allowed = value.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                if (allowed.length == value.length) null else allowed
            }, InputFilter.LengthFilter(6))
        }
        var syncing = false
        fun sync() {
            if (!input.hasFocus()) {
                val value = if (read() == automatic) "" else hexValue(read())
                if (input.text.toString() != value) { syncing = true; input.setText(value); syncing = false }
            }
            input.background = controlBackground(input.hasFocus()); label.typeface = AppFont.faceFor(store.font); input.typeface = AppFont.faceFor(store.font)
            input.setTextColor(if (input.hasFocus()) contrastingInk(accent()) else Color.WHITE)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(value: Editable?) {
                if (syncing || value?.length != 6) return
                value.toString().toIntOrNull(16)?.let { if (read() == automatic || (read() and 0xFFFFFF) != it) { write(it); save() } }
            }
        })
        input.setOnFocusChangeListener { _, _ -> sync() }
        input.setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) move(input, if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1); true }
            else -> false // Left and right keep normal text cursor behavior.
        } }
        updates.add(::sync); controls.add(input)
        row.addView(label, LayoutParams(0, -1, 1f)); row.addView(input, LayoutParams(0, dp(42), 1.1f))
        rows.addView(row, LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
    }
    private fun move(view: View, step: Int) {
        val next = controls.indexOf(view) + step
        if (next < 0) tabs[page]?.requestFocus() else controls[next.coerceAtMost(controls.lastIndex)].requestFocus()
    }
    private fun reset() {
        store.theme = GuideTheme.palettes.first().name
        store.themeStyle = "Glass"; store.font = "Default"
        store.customAccent = 0; store.customBackground = -1; store.cornerRadius = -1; store.borderWidth = -1
        store.guideTextScale = 100; store.guideRowHeight = 32; store.guideRowGap = 3; store.guideOpacity = -1; store.smallPreview = true
    }
    fun refreshPreview() {
        tabs.forEach { (title, tab) ->
            tab.isSelected = title == page; tab.background = controlBackground(title == page)
            tab.setTextColor(if (title == page) contrastingInk(accent()) else Color.WHITE); tab.typeface = AppFont.faceFor(store.font, title == page)
            tab.contentDescription = "$title tab${if (title == page) ", selected" else ""}"
        }
        updates.forEach { it() }; preview.invalidate()
    }
    private fun hexValue(value: Int) = String.format(Locale.US, "%06X", value and 0xFFFFFF)
    private fun colorValue(value: Int, automatic: Int) = if (value == automatic) "Palette" else "#${hexValue(value)}"
    private fun palette() = GuideTheme.palettes.firstOrNull { it.name == store.theme } ?: GuideTheme.palettes.first()
    private fun accent() = (if (store.customAccent != 0) store.customAccent else palette().accent) or 0xFF000000.toInt()
    private fun backgroundColor() = (if (store.customBackground >= 0) GuideTheme.panelTint(store.customBackground) else palette().background) or 0xFF000000.toInt()
    private fun radius() = if (store.cornerRadius >= 0) store.cornerRadius.toFloat() else when (store.themeStyle) { "Soft" -> 14f; "Rounded" -> 20f; "Broadcast" -> 2f; "Glass" -> 6f; "Outline" -> 3f; "Cinema" -> 8f; else -> 0f }
    private fun border() = if (store.borderWidth >= 0) store.borderWidth.toFloat() else when (store.themeStyle) { "Nostalgia" -> 2f; "Flat", "Soft", "Cinema" -> 0f; else -> 1f }
    private fun contrastingInk(color: Int): Int = if ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 >= 145) Color.BLACK else Color.WHITE
    private fun controlBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) accent() else 0x66313C4B); cornerRadius = dp(6).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (active) accent() else 0x334F657C)
    }

    private inner class Preview(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tileRect = RectF()
        private var videoGradient: LinearGradient? = null
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val d = resources.displayMetrics.density
            videoGradient = LinearGradient(0f, 142f, w / d, (h / d - 39f).coerceAtLeast(242f), 0xFF41627A.toInt(), 0xFF163F34.toInt(), Shader.TileMode.CLAMP)
        }
        init { tag = "theme-preview"; contentDescription = "Live preview of the guide and Sports appearance"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density; canvas.save(); canvas.scale(density, density)
            val w = width / density; val h = height / density
            paint.shader = null; paint.style = Paint.Style.FILL; paint.color = backgroundColor(); canvas.drawRoundRect(0f, 0f, w, h, 10f, 10f, paint)
            fun label(text: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, bold: Boolean = false) {
                paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size; paint.typeface = AppFont.faceFor(store.font, bold)
                canvas.drawText(text, x, y, paint)
            }
            fun tile(left: Float, top: Float, right: Float, bottom: Float, color: Int, selected: Boolean = false) {
                tileRect.set(left, top, right, bottom)
                paint.style = Paint.Style.FILL; paint.color = color; canvas.drawRoundRect(tileRect, radius(), radius(), paint)
                if (border() > 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = border(); paint.color = if (selected || store.themeStyle in listOf("Outline", "Nostalgia", "Terminal", "Broadcast")) accent() else 0x66FFFFFF; canvas.drawRoundRect(tileRect, radius(), radius(), paint); paint.style = Paint.Style.FILL }
            }
            label("Live preview", 14f, 24f, 14f, bold = true)
            label("${store.theme} · ${store.themeStyle}", 14f, 43f, 9f, 0xFFBAC8DC.toInt())
            tile(12f, 57f, w - 12f, 124f, 0x55313C4B)
            label("SPORTS   ·   LIVE", 23f, 76f, 9f, accent(), true)
            label("Seahawks vs 49ers", 23f, 98f, 12f * store.guideTextScale / 100f, bold = true)
            label("Game coverage", 23f, 114f, 9f, 0xFFBAC8DC.toInt())
            val guideTop = 142f; val bottom = (h - 39f).coerceAtLeast(guideTop + 100f)
            paint.shader = videoGradient
            canvas.drawRoundRect(12f, guideTop, w - 12f, bottom, 5f, 5f, paint); paint.shader = null
            val opacity = if (store.guideOpacity >= 0) store.guideOpacity else if (store.themeStyle == "Cinema") 65 else 91
            paint.color = (backgroundColor() and 0xFFFFFF) or ((opacity * 255 / 100) shl 24)
            canvas.drawRoundRect(12f, guideTop, w - 12f, bottom, 5f, 5f, paint)
            if (store.smallPreview) {
                tile(w - 65f, guideTop + 6f, w - 18f, guideTop + 31f, 0xFF30556A.toInt())
                label("VIDEO", w - 57f, guideTop + 22f, 7f)
            }
            label("GUIDE", 20f, guideTop + 21f, 10f, accent(), true)
            val channelWidth = (w * .25f).coerceIn(45f, 65f)
            val start = 16f + channelWidth
            val rowHeight = store.guideRowHeight * .65f
            val gap = store.guideRowGap.toFloat()
            val names = listOf("ESPN", "FOX", "NBC", "RedZone")
            val shows = listOf("SEA vs SF", "Live sports", "Game day", "Live NFL")
            canvas.save(); canvas.clipRect(14f, guideTop + 32f, w - 14f, bottom - 4f)
            names.forEachIndexed { index, name ->
                val y = guideTop + 38f + index * (rowHeight + gap)
                val selected = index == 0; val fill = if (selected) accent() else 0x55313C4B
                tile(17f, y, start - gap, y + rowHeight, 0x66313C4B)
                tile(start, y, w - 18f, y + rowHeight, fill, selected)
                val textY = y + rowHeight / 2f + 3f; val textSize = 9f * store.guideTextScale / 100f
                label(name, 21f, textY, textSize, bold = true)
                label(shows[index], start + 6f, textY, textSize, if (selected) contrastingInk(accent()) else Color.WHITE)
            }
            canvas.restore()
            label("${store.font} · ${store.guideTextScale}% · ${store.guideRowHeight} dp rows", 14f, h - 16f, 9f, 0xFFBAC8DC.toInt())
            canvas.restore()
        }
    }
}

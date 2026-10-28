package io.github.mot1us.spare

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/** Appearance changes never recreate the player. */
object GuideTheme {
    data class Palette(val name: String, val accent: Int, val background: Int)
    val palettes = listOf(
        Palette("Monochrome", Color.WHITE, 0x000B1018),
        Palette("Ocean", 0xFF90CAFF.toInt(), 0x00091325),
        Palette("Mint", 0xFF8CE5C1.toInt(), 0x000A1B17),
        Palette("Amber", 0xFFFFD28A.toInt(), 0x001E160B),
        Palette("Rose", 0xFFFFADC9.toInt(), 0x00200F19),
        Palette("Violet", 0xFFC6B5FF.toInt(), 0x00160E26),
        Palette("Sky", 0xFF99E1FF.toInt(), 0x000A1D27),
        Palette("Coral", 0xFFFFAD9B.toInt(), 0x00221110),
        Palette("Lime", 0xFFD3EFA0.toInt(), 0x00151C0B),
        Palette("Ice", 0xFFD5E9F1.toInt(), 0x0010191E),
        Palette("Copper", 0xFFEBC09E.toInt(), 0x001E1510),
        Palette("Phosphor", 0xFFB2F59B.toInt(), 0x00081108),
        Palette("Slate", 0xFFBAC8DC.toInt(), 0x00141A23),
        Palette("Cobalt", 0xFF9DB8FF.toInt(), 0x00080F28),
        Palette("Sunset", 0xFFFFC7A4.toInt(), 0x00201422),
        Palette("Forest", 0xFFB7D9B2.toInt(), 0x000D1912),
        Palette("Sand", 0xFFE9DCBE.toInt(), 0x001B1810),
        Palette("Teal", 0xFF8DDDD5.toInt(), 0x000A1A1D),
        Palette("Cherry", 0xFFFFAEB5.toInt(), 0x00210E15),
    )
    val styles = listOf("Glass", "Flat", "Grid", "Soft", "Outline", "Cinema", "Nostalgia", "Rounded", "Broadcast", "Terminal")
    var style = "Glass"
        private set
    fun selectStyle(name: String) { style = name.takeIf { it in styles } ?: "Glass" }
    var current = palettes.first()
        private set
    fun select(name: String) { current = palettes.firstOrNull { it.name == name } ?: palettes.first() }
    var rowHeightDp = 32; private set
    var rowGapDp = 3; private set
    var textScale = 1f; private set
    private var customRadius = -1
    private var customBorder = -1
    val cornerRadiusDp get() = if (customRadius >= 0) customRadius.toFloat() else when (style) { "Glass" -> 6f; "Soft" -> 14f; "Outline" -> 3f; "Cinema" -> 8f; "Rounded" -> 20f; "Broadcast" -> 2f; else -> 0f }
    private val radius get() = cornerRadiusDp
    fun configure(accent: Int, background: Int, corner: Int, border: Int, rowHeight: Int, rowGap: Int, textPercent: Int) {
        current = current.copy(accent = if (accent == 0) current.accent else accent or 0xFF000000.toInt(), background = if (background < 0) current.background else panelTint(background))
        customRadius = corner.coerceIn(-1, 20); customBorder = border.coerceIn(-1, 3)
        rowHeightDp = rowHeight.coerceIn(28, 44); rowGapDp = rowGap.coerceIn(2, 8); textScale = textPercent.coerceIn(85, 120) / 100f
    }
    /** Background is a tint on a dark TV surface so arbitrary colors retain readable white type. */
    fun panelTint(rgb: Int): Int {
        var color = rgb or 0xFF000000.toInt()
        repeat(12) { if (Color.luminance(color) > .10f) color = Color.rgb((Color.red(color) * .82f).toInt(), (Color.green(color) * .82f).toInt(), (Color.blue(color) * .82f).toInt()) }
        return color and 0xFFFFFF
    }
    private fun stroke(density: Float, default: Int = 1) = (density * (if (customBorder >= 0) customBorder else default)).toInt().coerceAtLeast(0)
    fun glass(density: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, when (style) {
        "Glass" -> intArrayOf(current.background or 0xEE202020.toInt(), current.background or 0xE8080808.toInt())
        "Cinema" -> intArrayOf(current.background or 0xC0000000.toInt(), current.background or 0xA0000000.toInt())
        else -> intArrayOf(current.background or 0xF0000000.toInt(), current.background or 0xF0000000.toInt())
    }).apply {
        cornerRadius = (if (style == "Glass" && customRadius < 0) 12f else radius) * density
        setStroke(stroke(density, if (style == "Nostalgia") 2 else 1), when (style) { "Nostalgia" -> GuideTheme.current.accent; "Flat", "Cinema", "Soft" -> Color.TRANSPARENT; "Outline", "Terminal" -> GuideTheme.current.accent; else -> 0x40FFFFFF })
    }
    fun guideBackground(opacity: Int = -1) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (opacity >= 0) IntArray(2) { (current.background and 0x00FFFFFF) or ((opacity.coerceIn(0, 100) * 255 / 100) shl 24) } else when (style) {
        "Cinema" -> intArrayOf(current.background or 0xC8000000.toInt(), current.background or 0x88000000.toInt())
        "Flat", "Soft" -> intArrayOf(current.background or 0xF6000000.toInt(), current.background or 0xE8000000.toInt())
        else -> intArrayOf(current.background or 0xF2000000.toInt(), current.background or 0xC9000000.toInt())
    })
    val focusText get() = if (Color.luminance(current.accent) > .42f) Color.BLACK else Color.WHITE
    fun ink() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focusText, Color.WHITE))
    fun background(density: Float, idle: Int = Color.TRANSPARENT, selected: Int = 0x553F4754): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = radius * density
            setStroke(stroke(density), when {
                style == "Nostalgia" || style == "Terminal" -> GuideTheme.current.accent and 0x88FFFFFF.toInt()
                style == "Outline" -> if (color == GuideTheme.current.accent) Color.TRANSPARENT else GuideTheme.current.accent and 0x66FFFFFF
                style == "Flat" || style == "Soft" || style == "Cinema" || color == Color.TRANSPARENT -> Color.TRANSPARENT
                else -> 0x22FFFFFF
            })
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(GuideTheme.current.accent))
            addState(intArrayOf(android.R.attr.state_selected), shape(selected))
            addState(intArrayOf(), shape(idle))
        }
    }
}

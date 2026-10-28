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
    )
    val styles = listOf("Glass", "Flat", "Grid", "Soft", "Outline", "Cinema", "Nostalgia")
    var style = "Glass"
        private set
    fun selectStyle(name: String) { style = name.takeIf { it in styles } ?: "Glass" }
    var current = palettes.first()
        private set
    fun select(name: String) { current = palettes.firstOrNull { it.name == name } ?: palettes.first() }
    private val radius get() = when (style) { "Glass" -> 6f; "Soft" -> 14f; "Outline" -> 3f; "Cinema" -> 8f; else -> 0f }
    fun glass(density: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, when (style) {
        "Glass" -> intArrayOf(current.background or 0xEE202020.toInt(), current.background or 0xE8080808.toInt())
        "Cinema" -> intArrayOf(current.background or 0xC0000000.toInt(), current.background or 0xA0000000.toInt())
        else -> intArrayOf(current.background or 0xF0000000.toInt(), current.background or 0xF0000000.toInt())
    }).apply {
        cornerRadius = (if (style == "Glass") 12f else radius) * density
        setStroke((density * if (style == "Nostalgia") 2 else 1).toInt().coerceAtLeast(1), when (style) { "Nostalgia" -> GuideTheme.current.accent; "Flat", "Cinema", "Soft" -> Color.TRANSPARENT; "Outline" -> GuideTheme.current.accent; else -> 0x40FFFFFF })
    }
    fun guideBackground(opacity: Int = -1) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (opacity >= 0) IntArray(2) { (current.background and 0x00FFFFFF) or ((opacity.coerceIn(0, 100) * 255 / 100) shl 24) } else when (style) {
        "Cinema" -> intArrayOf(current.background or 0xC8000000.toInt(), current.background or 0x88000000.toInt())
        "Flat", "Soft" -> intArrayOf(current.background or 0xF6000000.toInt(), current.background or 0xE8000000.toInt())
        else -> intArrayOf(current.background or 0xF2000000.toInt(), current.background or 0xC9000000.toInt())
    })
    fun ink() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE))
    fun background(density: Float, idle: Int = Color.TRANSPARENT, selected: Int = 0x553F4754): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color); cornerRadius = radius * density
            setStroke(density.toInt().coerceAtLeast(1), when {
                style == "Nostalgia" -> GuideTheme.current.accent and 0x88FFFFFF.toInt()
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

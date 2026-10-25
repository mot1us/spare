package io.github.mot1us.spare

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/** Only guide colors change; switching themes never recreates the player. */
object GuideTheme {
    data class Palette(val name: String, val accent: Int, val background: Int)
    val palettes = listOf(
        Palette("Monochrome", Color.WHITE, 0x000B1018),
        Palette("Ocean", 0xFF90CAFF.toInt(), 0x00091325),
        Palette("Mint", 0xFF8CE5C1.toInt(), 0x000A1B17),
        Palette("Amber", 0xFFFFD28A.toInt(), 0x001E160B),
        Palette("Rose", 0xFFFFADC9.toInt(), 0x00200F19),
    )
    var current = palettes.first()
        private set
    fun select(name: String) { current = palettes.firstOrNull { it.name == name } ?: palettes.first() }
    fun glass(density: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xEE202936.toInt(), 0xE80D131D.toInt())).apply {
        cornerRadius = 12 * density; setStroke(density.toInt().coerceAtLeast(1), 0x40FFFFFF)
    }
    fun ink() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE))
    fun background(density: Float, idle: Int = Color.TRANSPARENT, selected: Int = 0x553F4754): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 6 * density; setStroke(density.toInt().coerceAtLeast(1), if (color == Color.TRANSPARENT) Color.TRANSPARENT else 0x22FFFFFF) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(GuideTheme.current.accent))
            addState(intArrayOf(android.R.attr.state_selected), shape(selected))
            addState(intArrayOf(), shape(idle))
        }
    }
}

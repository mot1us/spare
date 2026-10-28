package io.github.mot1us.spare

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Device fonts keep the APK small and work offline. Canvas rows use these same faces. */
object AppFont {
    val names = listOf("Default", "Compact", "Casual", "Serif", "Monospace", "Light", "Medium", "Bold", "Compact light", "Small caps", "Serif mono", "Cursive", "Thin", "Condensed bold")
    var name = names.first()
        private set
    fun select(value: String) { name = value.takeIf { it in names } ?: names.first() }
    private fun family(value: String) = when (value) { "Compact", "Condensed bold" -> "sans-serif-condensed"; "Casual" -> "casual"; "Serif" -> "serif"; "Monospace" -> "monospace"; "Light" -> "sans-serif-light"; "Medium" -> "sans-serif-medium"; "Bold" -> "sans-serif-black"; "Compact light" -> "sans-serif-condensed-light"; "Small caps" -> "sans-serif-smallcaps"; "Serif mono" -> "serif-monospace"; "Cursive" -> "cursive"; "Thin" -> "sans-serif-thin"; else -> "sans-serif" }
    fun face(medium: Boolean = false): Typeface = faceFor(name, medium)
    fun faceFor(value: String, medium: Boolean = false): Typeface = Typeface.create(if (medium && value == "Default") "sans-serif-medium" else family(value), if (value == "Condensed bold" || medium && value != "Default") Typeface.BOLD else Typeface.NORMAL)
    fun apply(view: View) {
        if (view is TextView) view.typeface = face(view.typeface?.isBold == true || view is android.widget.Button || view.tag == "settings-page-title")
        if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
        view.invalidate()
    }
}

package io.github.mot1us.spare

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

@UnstableApi
object SubtitleAppearance {
    val sizes = linkedMapOf("Small" to .028f, "Medium" to .036f, "Large" to .045f, "Extra large" to .055f)
    val styles = listOf("Outline", "Shadow", "Black background")
    fun apply(view: PlayerView, size: String, style: String) {
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(sizes[size] ?: sizes.getValue("Small"))
            setStyle(CaptionStyleCompat(Color.WHITE, if (style == "Black background") 0xCC000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT, if (style == "Shadow") CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else if (style == "Black background") CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK, AppFont.face()))
        }
        render(view, view.player?.currentCues?.cues.orEmpty(), size)
    }
    /** PGS/VobSub are pictures: scale their existing artwork without OCR or bitmap copies. */
    fun render(view: PlayerView, cues: List<Cue>, size: String) {
        if (cues.none { it.bitmap != null }) return
        view.subtitleView?.setCues(cues.map { cue ->
            if (cue.bitmap == null || cue.size == Cue.DIMEN_UNSET) cue else {
                val factor = bitmapScale(size, cue.size, cue.bitmapHeight)
                cue.buildUpon().setSize(cue.size * factor).apply {
                    if (cue.bitmapHeight != Cue.DIMEN_UNSET) setBitmapHeight(cue.bitmapHeight * factor)
                }.build()
            }
        })
    }
    internal fun bitmapScale(size: String, width: Float, height: Float): Float {
        val requested = (sizes[size] ?: sizes.getValue("Small")) / sizes.getValue("Medium")
        return minOf(requested, if (width > 0) 1f / width else requested, if (height > 0) 1f / height else requested)
    }
}

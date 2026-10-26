package io.github.mot1us.spare

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
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
    }
}

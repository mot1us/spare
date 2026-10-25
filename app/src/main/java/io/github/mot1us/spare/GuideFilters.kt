package io.github.mot1us.spare

import org.json.JSONObject

data class VideoInfo(val width: Int, val height: Int, val fps: Float) {
    fun json() = JSONObject().put("width", width).put("height", height).put("fps", fps.toDouble())
    fun label(): String = "${height}p" + if (fps > 0) " · ${if (kotlin.math.abs(fps - fps.toInt()) < .01f) fps.toInt().toString() else "%.2f".format(java.util.Locale.US, fps)} fps" else " · Measuring fps…"
    companion object {
        fun from(j: JSONObject) = VideoInfo(j.optInt("width"), j.optInt("height"), j.optDouble("fps", 0.0).toFloat())
    }
}

object FrameRateFilter {
    val choices = linkedMapOf(24 to "24 fps · includes 23.976", 25 to "25 fps", 30 to "30 fps · includes 29.97", 48 to "48 fps", 50 to "50 fps", 60 to "60 fps · includes 59.94", 100 to "100 fps", 120 to "120 fps · includes 119.88", 0 to "Other known rates")
    val onlySixty = choices.keys - 60
    fun bucket(fps: Float): Int = choices.keys.firstOrNull { it != 0 && kotlin.math.abs(fps - it) <= .2f } ?: 0
}

data class GuideFilters(val minHeight: Int = 0, val excludedFps: Set<Int> = emptySet(), val groups: Set<String> = emptySet()) {
    fun matches(channel: Channel, info: VideoInfo?): Boolean =
        (groups.isEmpty() || channel.group in groups) &&
            (minHeight == 0 || (info != null && minOf(info.width, info.height) >= minHeight)) &&
            (excludedFps.isEmpty() || (info != null && info.fps.isFinite() && info.fps > 0 && FrameRateFilter.bucket(info.fps) !in excludedFps))
    fun summary(): String = listOfNotNull(
        if (groups.isNotEmpty()) "${groups.size} categories" else null,
        when (minHeight) { 720 -> "HD+"; 1080 -> "FHD+"; 2160 -> "4K"; else -> null },
        when { excludedFps.isEmpty() -> null; excludedFps == FrameRateFilter.onlySixty -> "60 fps"; else -> "FPS filtered" },
    ).joinToString(" · ").ifBlank { "All channels" }
}

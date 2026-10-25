package io.github.mot1us.spare

import org.json.JSONObject

data class VideoInfo(val width: Int, val height: Int, val fps: Float) {
    fun json() = JSONObject().put("width", width).put("height", height).put("fps", fps.toDouble())
    fun label(): String = "${height}p" + if (fps > 0) " · ${if (kotlin.math.abs(fps - fps.toInt()) < .01f) fps.toInt().toString() else "%.2f".format(java.util.Locale.US, fps)} fps" else ""
    companion object {
        fun from(j: JSONObject) = VideoInfo(j.optInt("width"), j.optInt("height"), j.optDouble("fps", 0.0).toFloat())
    }
}

data class GuideFilters(val minHeight: Int = 0, val sixtyOnly: Boolean = false, val groups: Set<String> = emptySet()) {
    fun matches(channel: Channel, info: VideoInfo?): Boolean =
        (groups.isEmpty() || channel.group in groups) &&
            (minHeight == 0 || (info != null && minOf(info.width, info.height) >= minHeight)) &&
            (!sixtyOnly || (info != null && info.fps in 59.8f..60.2f))
    fun summary(): String = listOfNotNull(
        if (groups.isNotEmpty()) "${groups.size} categories" else null,
        when (minHeight) { 720 -> "HD+"; 1080 -> "FHD+"; 2160 -> "4K"; else -> null },
        if (sixtyOnly) "60 fps" else null,
    ).joinToString(" · ").ifBlank { "All channels" }
}

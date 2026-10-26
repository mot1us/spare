package io.github.mot1us.spare

import org.json.JSONObject

/** Provider declarations are usable hints; actual playback always takes precedence. */
object ProviderQuality {
    private val size = Regex("(?i)([0-9]{3,4})\\s*[x×]\\s*([0-9]{3,4})")
    private val height = Regex("(?i)\\b(720|1080|2160)[pi]?(?:[ /@-]*(?:23\\.976|24|25|29\\.97|30|50|59\\.94|60))?\\b")
    private val rate = Regex("(?i)\\b(23\\.976|24|25|29\\.97|30|48|50|59\\.94|60|100|119\\.88|120)\\s*(?:fps|hz)\\b|(?:720|1080|2160)[pi]\\s*(?:@|/)?\\s*(23\\.976|24|25|29\\.97|30|50|59\\.94|60)\\b")
    private val fields = listOf("info", "stream_info", "video_info", "video", "width", "height", "video_width", "video_height", "resolution", "video_resolution", "tvg-resolution", "fps", "frame_rate", "framerate", "r_frame_rate", "avg_frame_rate", "tvg-fps")
    private val hint = Regex("(?i)(?:[0-9]{3,4}[x×][0-9]{3,4}|720|1080|2160|\\b(?:fhd|uhd|hd|4k)\\b|fps|hz)")
    private val ultra = Regex("(?i)\\b(?:4k|uhd)\\b")
    private val full = Regex("(?i)\\bfhd\\b")
    private val hd = Regex("(?i)\\bhd\\b")
    private val scan = Regex("(?i)\\b(?:720|1080|2160)i\\b")
    private fun fps(value: String): Float {
        val parts = value.trim().split('/')
        val n = parts.firstOrNull()?.toFloatOrNull() ?: return 0f
        return (if (parts.size == 2) n / (parts[1].toFloatOrNull() ?: 0f) else n).takeIf { it.isFinite() && it in 10f..120f } ?: 0f
    }
    fun parse(json: JSONObject, name: String = ""): VideoInfo? {
        if (fields.none(json::has) && !hint.containsMatchIn(name)) return null
        val nodes = mutableListOf(json)
        listOf("info", "stream_info", "video_info", "video").forEach { key -> json.optJSONObject(key)?.let(nodes::add) }
        nodes.toList().forEach { it.optJSONObject("video")?.let(nodes::add) }
        fun value(vararg keys: String) = nodes.asSequence().flatMap { node -> keys.asSequence().map { node.optString(it).take(100) } }.firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
        val resolution = value("resolution", "video_resolution", "tvg-resolution").ifBlank { name }
        val dimensions = size.find(resolution)
        val h = value("height", "video_height").toIntOrNull()?.takeIf { it in 144..8640 }
            ?: dimensions?.groupValues?.get(2)?.toIntOrNull()
            ?: height.find(resolution)?.groupValues?.get(1)?.toIntOrNull()
            ?: when { ultra.containsMatchIn(resolution) -> 2160; full.containsMatchIn(resolution) -> 1080; hd.containsMatchIn(resolution) -> 720; else -> 0 }
        val w = value("width", "video_width").toIntOrNull()?.takeIf { it in 144..15360 } ?: dimensions?.groupValues?.get(1)?.toIntOrNull() ?: h * 16 / 9
        val declared = fps(value("fps", "frame_rate", "framerate", "r_frame_rate", "avg_frame_rate", "tvg-fps"))
        val labelled = rate.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let(::fps) ?: 0f
        val frames = if (declared > 0) declared else labelled
        if (h == 0 && frames == 0f) return null
        val interlaced = scan.containsMatchIn(resolution) || value("field_order") in setOf("tt", "bb", "tb", "bt")
        return VideoInfo(w, h, VideoRate.resolve(frames, frames, interlaced = interlaced), interlaced)
    }
}

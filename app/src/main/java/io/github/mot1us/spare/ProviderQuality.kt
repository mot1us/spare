package io.github.mot1us.spare

import org.json.JSONObject

/** Provider declarations are usable hints; actual playback always takes precedence. */
object ProviderQuality {
    private const val SCHEMA = 2
    private val size = Regex("(?i)([0-9]{3,4})\\s*[x×]\\s*([0-9]{3,4})")
    private val height = Regex("(?i)\\b(720|1080|2160)[pi]?(?:[ /@-]*(?:23\\.976|24|25|29\\.97|30|48|50|59\\.94|60|100|119\\.88|120))?\\b")
    private val rate = Regex("(?i)\\b(23\\.976|24|25|29\\.97|30|48|50|59\\.94|60|100|119\\.88|120)\\s*(?:fps|hz)\\b|(?:720|1080|2160)[pi]\\s*(?:@|/)?\\s*(23\\.976|24|25|29\\.97|30|48|50|59\\.94|60|100|119\\.88|120)\\b")
    private val fields = listOf("info", "stream_info", "video_info", "video", "width", "height", "video_width", "video_height", "resolution", "video_resolution", "stream_resolution", "quality", "tvg-resolution", "fps", "video_fps", "stream_fps", "frame_rate", "framerate", "r_frame_rate", "avg_frame_rate", "tvg-fps", "tvg-framerate", "field_order")
    private val hint = Regex("(?i)(?:[0-9]{3,4}[x×][0-9]{3,4}|720|1080|2160|\\b(?:fhd|uhd|hd|4k)\\b|fps|hz)")
    private val ultra = Regex("(?i)\\b(?:4k|uhd)\\b")
    private val full = Regex("(?i)\\bfhd\\b")
    private val hd = Regex("(?i)\\bhd\\b")
    private val scan = Regex("(?i)\\b(?:720|1080|2160)i(?=\\b|[0-9])")
    private fun fps(value: String): Float {
        val parts = value.trim().lowercase(java.util.Locale.ROOT).removeSuffix("fps").removeSuffix("hz").trim().split('/').map(String::trim)
        if (parts.size !in 1..2) return 0f
        val n = parts.firstOrNull()?.toFloatOrNull() ?: return 0f
        return (if (parts.size == 2) n / (parts[1].toFloatOrNull() ?: 0f) else n).takeIf { it.isFinite() && it in 10f..120f } ?: 0f
    }
    /** This stamp belongs only to provider declarations, never playback observations. */
    fun json(info: VideoInfo): JSONObject = info.json().put("providerSchema", SCHEMA)
    fun restore(saved: JSONObject?, name: String): VideoInfo? {
        val labelled = parse(JSONObject(), name)
        val known = saved?.let { value ->
            val info = VideoInfo.from(value)
            val encoded = info.encodedFps.isFinite() && info.encodedFps > 0f
            if (value.optInt("providerSchema") < SCHEMA && info.interlaced && !encoded) {
                // Older caches doubled i25/i30 labels without retaining whether
                // the rate came from a label or encoded-frame metadata. Reuse
                // an explicit name rate; otherwise retain only the resolution.
                info.copy(fps = labelled?.fps?.takeIf { it.isFinite() && it > 0f } ?: 0f, encodedFps = 0f)
            } else info
        }
        if (labelled == null) return known
        return known?.copy(width = known.width.takeIf { it > 0 } ?: labelled.width, height = known.height.takeIf { it > 0 } ?: labelled.height,
            fps = known.fps.takeIf { it.isFinite() && it > 0 } ?: labelled.fps) ?: labelled
    }
    fun parse(json: JSONObject, name: String = ""): VideoInfo? {
        if (fields.none(json::has) && !hint.containsMatchIn(name)) return null
        val nodes = mutableListOf(json)
        // Providers use either nested objects or JSON strings for the same video metadata.
        repeat(2) {
            nodes.toList().forEach { node -> listOf("info", "stream_info", "video_info", "video").forEach { key ->
                val nested = node.optJSONObject(key) ?: node.optString(key).takeIf { it.length in 2..4096 && it.startsWith('{') }?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (nested != null && nodes.none { it === nested } && nodes.size < 12) nodes.add(nested)
            } }
        }
        fun values(vararg keys: String) = nodes.asSequence().flatMap { node -> keys.asSequence().map { node.optString(it).take(100) } }.filter { it.isNotBlank() && it != "null" }
        val resolutions = (values("resolution", "video_resolution", "stream_resolution", "tvg-resolution", "quality") + sequenceOf(name)).toList()
        val dimensions = resolutions.firstNotNullOfOrNull { text -> size.find(text)?.takeIf { it.groupValues[1].toInt() in 144..15360 && it.groupValues[2].toInt() in 144..8640 } }
        val h = values("height", "video_height").mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 144..8640 } }.firstOrNull()
            ?: dimensions?.groupValues?.get(2)?.toIntOrNull()
            ?: resolutions.firstNotNullOfOrNull { height.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: when { resolutions.any(ultra::containsMatchIn) -> 2160; resolutions.any(full::containsMatchIn) -> 1080; resolutions.any(hd::containsMatchIn) -> 720; else -> 0 }
        val w = values("width", "video_width").mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 144..15360 } }.firstOrNull()
            ?: dimensions?.takeIf { it.groupValues[2].toInt() == h }?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val declared = values("fps", "video_fps", "stream_fps", "frame_rate", "framerate", "r_frame_rate", "avg_frame_rate", "tvg-fps", "tvg-framerate").map(::fps).firstOrNull { it > 0 } ?: 0f
        val labelled = resolutions.firstNotNullOfOrNull { text -> rate.find(text)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.let(::fps)?.takeIf { it > 0 } } ?: 0f
        val frames = if (declared > 0) declared else labelled
        if (h == 0 && frames == 0f) return null
        val fieldOrder = values("field_order").map { it.lowercase(java.util.Locale.ROOT) }.firstOrNull { it in setOf("tt", "bb", "tb", "bt", "progressive") }
        val interlaced = fieldOrder?.let { it != "progressive" } ?: resolutions.any(scan::containsMatchIn)
        // A channel label's i50/i60 is already a field cadence. Only explicit
        // frame-rate metadata plus an explicit field order warrants conversion.
        val motion = if (declared > 0 && fieldOrder != null && interlaced) VideoRate.resolve(declared, declared, interlaced = true) else frames
        return VideoInfo(w, h, motion, interlaced, encodedFps = if (declared > 0 && fieldOrder != null) declared else 0f, evidence = "provider")
    }
}

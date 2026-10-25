package io.github.mot1us.spare

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

object XtreamEpg {
    fun load(source: Source, channel: Channel, depth: Int = 24): List<Programme> {
        val id = channel.id.removePrefix("xc:"); require(id.matches(Regex("[0-9]{1,20}")))
        val url = Importers.endpoint(source, "player_api.php", "get_short_epg") + "&stream_id=$id&limit=${depth.coerceIn(24, 72)}"
        return parse(Network.get(url, source.allowHttp, 512 * 1024).toString(Charsets.UTF_8), channel.guideId.ifBlank { channel.id })
    }
    fun parse(text: String, channel: String, now: Long = System.currentTimeMillis()): List<Programme> {
        val root = JSONObject(text.trimStart('\uFEFF'))
        val entries = root.optJSONArray("epg_listings") ?: throw SourceFailure(SourceProblem.GUIDE_RESPONSE)
        require(entries.length() <= 2000)
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            fun epoch(name: String): Long {
                val raw = entry.optString(name).toLongOrNull() ?: return 0
                return if (raw in 1_000_000_000L..99_999_999_999L) raw * 1000 else if (raw in 1_000_000_000_000L..99_999_999_999_999L) raw else 0
            }
            val start = epoch("start_timestamp")
            val end = epoch("stop_timestamp").takeIf { it > 0 } ?: epoch("end_timestamp")
            if (start <= 0 || end <= start || end <= now - 3600000 || start >= now + 3 * 86400000L) return@mapNotNull null
            Programme(channel, decode(entry.optString("title")).take(512).ifBlank { "Untitled" }, start, end, decode(entry.optString("description")).take(2048))
        }.distinctBy { it.start }.sortedBy { it.start }.take(72)
    }
    private fun decode(value: String): String {
        val bounded = value.take(16384)
        return runCatching {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Base64.getDecoder().decode(bounded))).toString()
            require(decoded.isNotBlank() && decoded.none { it.isISOControl() && it !in "\n\r\t" }); decoded
        }.getOrDefault(bounded)
    }
}

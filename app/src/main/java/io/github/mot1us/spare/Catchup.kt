package io.github.mot1us.spare

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Only advertised archive formats are used; live URLs are never guessed into archives. */
object Catchup {
    private const val DAY = 86400000L
    fun supported(channel: Channel) = channel.archiveMetadataKnown && channel.catchupDays > 0 && channel.catchupMode in setOf("xc", "default", "append", "shift") &&
        (channel.catchupMode !in setOf("default", "append") || channel.catchupTemplate.isNotBlank())
    /** Capabilities come from every playlist entry, independent of guide/category filters. */
    fun channels(guide: Guide) = guide.channels.filter { !it.media && supported(it) }.distinctBy { it.id }
    fun available(channel: Channel, programme: Programme, now: Long = System.currentTimeMillis()) = supported(channel) &&
        programme.start >= now - channel.catchupDays * DAY && programme.start > 0 && programme.end <= now && programme.end > programme.start && programme.end - programme.start <= DAY
    fun url(channel: Channel, programme: Programme, http: Boolean, now: Long = System.currentTimeMillis()): String? {
        if (!available(channel, programme, now)) return null
        val seconds = (programme.end - programme.start) / 1000
        val start = programme.start / 1000; val end = programme.end / 1000
        val value = when (channel.catchupMode) {
            "xc" -> {
                val uri = runCatching { URI(channel.url) }.getOrNull() ?: return null
                val parts = uri.rawPath.trimEnd('/').split('/'); val at = parts.indexOfLast { it == "live" }
                val user = if (at >= 0) at + 1 else parts.size - 3
                if (user < 1 || parts.size != user + 3 || parts[user].isBlank() || parts[user + 1].isBlank()) return null
                val id = parts.last().substringBefore('.').takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return null
                val base = "${uri.scheme}://${uri.rawAuthority}${parts.take(if (at >= 0) at else user).joinToString("/")}"
                val date = DateTimeFormatter.ofPattern("uuuu-MM-dd:HH-mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(programme.start))
                "$base/timeshift/${parts[user]}/${parts[user + 1]}/${ceil(seconds / 60.0).toLong()}/$date/$id.ts"
            }
            "shift" -> channel.url + (if ('?' in channel.url) "&" else "?") + "utc=$start&lutc=${now / 1000}"
            else -> {
                var template = channel.catchupTemplate
                val values = mapOf("utc" to start, "start" to start, "utcend" to end, "end" to end, "duration" to seconds, "lutc" to now / 1000, "now" to now / 1000, "timestamp" to now / 1000)
                values.forEach { (key, value) -> template = template.replace("\${$key}", value.toString()).replace("{$key}", value.toString()) }
                val date = Instant.ofEpochMilli(programme.start).atZone(ZoneOffset.UTC)
                mapOf("Y" to date.year.toString(), "m" to "%02d".format(date.monthValue), "d" to "%02d".format(date.dayOfMonth), "H" to "%02d".format(date.hour), "M" to "%02d".format(date.minute), "S" to "%02d".format(date.second)).forEach { (key, value) -> template = template.replace("{$key}", value) }
                template = Regex("\\{duration:([0-9]+)}").replace(template) { m -> (m.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { seconds / it }?.toString() ?: "{unsupported}") }
                if (template.contains('{') || template.contains('}')) return null
                if (channel.catchupMode == "append") channel.url + template else template
            }
        }
        return value.takeIf { Network.allowed(it, http) }
    }
    fun load(source: Source, channel: Channel, timeoutMs: Int = 20000): List<Programme> {
        return load(source, channel, timeoutMs) { url, timeout -> Network.get(url, source.allowHttp, 4 * 1024 * 1024, timeout) }
    }
    internal fun load(source: Source, channel: Channel, timeoutMs: Int, get: (String, Int) -> ByteArray): List<Programme> {
        require(source.kind == "xtream" && channel.id.matches(Regex("xc:[0-9]{1,20}")))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        fun request(action: String): List<Programme> {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val left = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(timeoutMs.toLong()).toInt()
            if (left <= 0) throw java.net.SocketTimeoutException("Archive lookup deadline exceeded")
            val url = Importers.endpoint(source, "player_api.php", action) + "&stream_id=${channel.id.removePrefix("xc:")}"
            return XtreamEpg.parse(get(url, left).toString(Charsets.UTF_8), channel.guideId.ifBlank { channel.id }, pastDays = channel.catchupDays)
        }
        val primary = runCatching { request("get_simple_data_table") }
        primary.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Older Xtream panels use this misspelled action. Both share one deadline.
        return try { request("get_simple_date_table") } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted) throw failure
            primary.getOrNull() ?: throw failure
        }
    }
}

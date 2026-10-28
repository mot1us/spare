package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class Source(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val kind: String, val url: String, val username: String = "", val password: String = "",
    val epgUrl: String = "", val allowHttp: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("url", url)
        .put("username", username).put("password", password).put("epg", epgUrl).put("http", allowHttp)
    companion object {
        fun from(j: JSONObject) = Source(j.getString("id"), j.getString("name"), j.getString("kind"),
            j.getString("url"), j.optString("username"), j.optString("password"), j.optString("epg"), j.optBoolean("http"))
    }
}
data class Channel(val id: String, val name: String, val group: String, val url: String, val logo: String = "", val guideId: String = "",
    val catchupDays: Int = 0, val catchupMode: String = "", val catchupTemplate: String = "", val media: Boolean = false, val reportedQuality: VideoInfo? = null) {
    val rowId: Long by lazy { stableLongId(id) }
    val searchKey: String = ChannelSearch.key(name)
    val sportsKey: String by lazy { " ${SportsCatalog.normalize(name)} " }
    fun quality(observed: VideoInfo?): VideoInfo? {
        if (observed == null) return reportedQuality
        val provider = reportedQuality ?: return observed
        val sameFormat = (observed.height <= 0 || provider.height <= 0 || observed.height == provider.height) &&
            (observed.width <= 0 || provider.width <= 0 || observed.width == provider.width) &&
            (observed.height <= 0 || observed.interlaced == provider.interlaced)
        return observed.copy(width = observed.width.takeIf { it > 0 } ?: provider.width, height = observed.height.takeIf { it > 0 } ?: provider.height,
            fps = observed.fps.takeIf { it.isFinite() && it > 0 } ?: provider.fps.takeIf { sameFormat } ?: 0f,
            interlaced = if (observed.height <= 0 && (!observed.fps.isFinite() || observed.fps <= 0)) provider.interlaced else observed.interlaced)
    }
    /** Keep source declarations separate from rates actually observed during playback. */
    fun qualityLabel(observed: VideoInfo?): String {
        val info = quality(observed) ?: return "Quality unknown"
        val observedRate = observed?.fps?.let { it.isFinite() && it > 0f } == true
        val providerRate = !observedRate && info.fps.isFinite() && info.fps > 0f && reportedQuality != null
        val source = when {
            providerRate || observed == null -> "Provider reported"
            observedRate -> when (observed?.evidence) {
                "input timestamps", "timestamps" -> "Measured FPS"
                "SPS timing" -> "Stream timing"
                "stream" -> "Stream reported"
                "decoder" -> "Decoder reported"
                "provider" -> "Provider reported"
                "unverified", "needs verification" -> "FPS unverified"
                else -> "Observed in playback"
            }
            else -> "Playback resolution"
        }
        val mixed = when {
            observedRate && (observed?.height ?: 0) <= 0 && (reportedQuality?.height ?: 0) > 0 -> " · Provider resolution"
            providerRate && (observed?.height ?: 0) > 0 -> " · Playback resolution"
            else -> ""
        }
        return info.label() + " · " + source + mixed
    }
    fun json() = JSONObject().put("id", id).put("name", name).put("group", group).put("url", url).put("logo", logo).put("guide", guideId)
        .put("catchupDays", catchupDays).put("catchupMode", catchupMode).put("catchupTemplate", catchupTemplate).put("media", media).put("quality", reportedQuality?.let(ProviderQuality::json))
    companion object {
        fun from(j: JSONObject) = Channel(j.getString("id"), j.getString("name"), j.getString("group"), j.getString("url"), j.optString("logo"), j.optString("guide"),
            j.optInt("catchupDays").coerceIn(0, 30), j.optString("catchupMode"), j.optString("catchupTemplate"), j.optBoolean("media"), ProviderQuality.restore(j.optJSONObject("quality"), j.getString("name")))
        /** Same first 64 SHA-256 bits as the existing RecyclerView IDs, without hex formatting. */
        fun stableLongId(value: String): Long = java.nio.ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())).long
        fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
data class Programme(val channel: String, val title: String, val start: Long, val end: Long, val description: String = "", val artwork: String = "") {
    fun json() = JSONArray().put(channel).put(title).put(start).put(end).put(description).put(artwork)
    companion object { fun from(j: JSONArray) = Programme(j.getString(0), j.getString(1), j.getLong(2), j.getLong(3), j.optString(4), j.optString(5)) }
}
data class Lineup(val channels: List<Channel>, val epgUrl: String = "", val categoryOrder: List<String> = emptyList())
data class Guide(val channels: List<Channel>, val programmes: Map<String, List<Programme>>, val categoryOrder: List<String> = emptyList()) {
    fun key(channel: Channel) = channel.guideId.ifBlank { channel.id }
    fun at(channel: Channel, time: Long): Programme? = programmes[key(channel)]?.firstOrNull { time >= it.start && time < it.end }
    /** Short EPG belongs to a stream; migrate only unambiguous legacy cache entries. */
    fun streamGuide(): Guide {
        if (channels.all { key(it) == it.id }) return this
        val counts = channels.groupingBy { key(it) }.eachCount()
        val migrated = linkedMapOf<String, List<Programme>>()
        for (channel in channels) {
            val entries = programmes[channel.id] ?: programmes[key(channel)].takeIf { counts[key(channel)] == 1 }
            if (!entries.isNullOrEmpty()) migrated[channel.id] = entries.map { it.copy(channel = channel.id) }
        }
        return copy(channels = channels.map { it.copy(guideId = it.id) }, programmes = migrated)
    }
    fun json(): JSONObject = JSONObject().put("channels", JSONArray(channels.map { it.json() }))
        .put("programmes", JSONArray(programmes.values.flatten().map { it.json() })).put("categories", JSONArray(categoryOrder))
    companion object {
        val EMPTY = Guide(emptyList(), emptyMap())
        fun from(j: JSONObject): Guide {
            val channels = j.getJSONArray("channels").let { a -> List(a.length()) { Channel.from(a.getJSONObject(it)) } }
            val programmes = j.getJSONArray("programmes").let { a -> List(a.length()) { Programme.from(a.getJSONArray(it)) } }
            val categories = j.optJSONArray("categories")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            return Guide(channels, programmes.groupBy { it.channel }, categories)
        }
    }
}

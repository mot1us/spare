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
    fun quality(observed: VideoInfo?): VideoInfo? = if (observed == null) reportedQuality else if (observed.fps <= 0 && (reportedQuality?.fps ?: 0f) > 0) observed.copy(fps = reportedQuality!!.fps) else observed
    fun json() = JSONObject().put("id", id).put("name", name).put("group", group).put("url", url).put("logo", logo).put("guide", guideId)
        .put("catchupDays", catchupDays).put("catchupMode", catchupMode).put("catchupTemplate", catchupTemplate).put("media", media).put("quality", reportedQuality?.json())
    companion object {
        fun from(j: JSONObject) = Channel(j.getString("id"), j.getString("name"), j.getString("group"), j.getString("url"), j.optString("logo"), j.optString("guide"),
            j.optInt("catchupDays").coerceIn(0, 30), j.optString("catchupMode"), j.optString("catchupTemplate"), j.optBoolean("media"), j.optJSONObject("quality")?.let(VideoInfo::from) ?: ProviderQuality.parse(JSONObject(), j.optString("name")))
        fun stableId(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
data class Programme(val channel: String, val title: String, val start: Long, val end: Long, val description: String = "") {
    fun json() = JSONArray().put(channel).put(title).put(start).put(end).put(description)
    companion object { fun from(j: JSONArray) = Programme(j.getString(0), j.getString(1), j.getLong(2), j.getLong(3), j.optString(4)) }
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

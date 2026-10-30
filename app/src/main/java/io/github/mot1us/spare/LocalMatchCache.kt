package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Content-addressed results; playlist or location edits invalidate the encrypted disk cache. */
object LocalMatchCache {
    private const val SCHEMA = 2
    fun fingerprint(channels: List<Channel>, location: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update((bytes.size.toString() + ":").toByteArray()); digest.update(bytes) }
        add(SCHEMA.toString()); add(location.trim().lowercase(java.util.Locale.ROOT))
        channels.forEach { add(it.id); add(it.name); add(it.group); add(it.guideId); add(it.media.toString()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun encode(fingerprint: String, ids: Set<String>): ByteArray = JSONObject().put("schema", SCHEMA).put("fingerprint", fingerprint)
        .put("ids", JSONArray(ids.toList())).toString().toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray?, fingerprint: String): Set<String>? = runCatching {
        if (bytes == null || bytes.size > 8 * 1024 * 1024) return null
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        if (json.optInt("schema") != SCHEMA || json.optString("fingerprint") != fingerprint) return null
        val ids = json.getJSONArray("ids"); require(ids.length() <= Importers.MAX_CHANNELS)
        (0 until ids.length()).map { ids.getString(it).also { id -> require(id.length in 1..512) } }.toSet()
    }.getOrNull()
}

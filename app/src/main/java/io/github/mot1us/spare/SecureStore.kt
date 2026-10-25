package io.github.mot1us.spare

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("view-state", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("spare-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("spare-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun write(name: String, bytes: ByteArray) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val file = AtomicFile(File(context.filesDir, name))
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1)); output.write(cipher.iv); output.write(cipher.doFinal(bytes)); file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized fun read(name: String): ByteArray? {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val file = AtomicFile(File(context.filesDir, name))
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { Network.readLimited(it, 128 * 1024 * 1024) }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        return cipher.doFinal(bytes, 13, bytes.size - 13)
    }
    fun sources(): List<Source> = read("sources.enc")?.let { bytes ->
        val array = JSONArray(bytes.toString(Charsets.UTF_8)); List(array.length()) { Source.from(array.getJSONObject(it)) }
    } ?: emptyList()
    fun saveSources(sources: List<Source>) = write("sources.enc", JSONArray(sources.map { it.json() }).toString().toByteArray())
    fun cache(source: Source, guide: Guide) = write("cache-${source.id}.enc", guide.json().toString().toByteArray())
    fun cached(source: Source): Guide? = read("cache-${source.id}.enc")?.let { Guide.from(JSONObject(it.toString(Charsets.UTF_8))) }
    fun removeCache(source: Source) {
        AtomicFile(File(context.filesDir, "cache-${source.id}.enc")).delete()
        preferences.edit { listOf("min-height", "sixty", "groups", "stream-format", "video-info", "favorites", "last").forEach { remove("$it-${source.id}") } }
    }
    fun filters(source: Source) = GuideFilters(preferences.getInt("min-height-${source.id}", 0), preferences.getBoolean("sixty-${source.id}", false), preferences.getStringSet("groups-${source.id}", emptySet()).orEmpty().toSet())
    fun setFilters(source: Source, value: GuideFilters) { preferences.edit {
        putInt("min-height-${source.id}", value.minHeight); putBoolean("sixty-${source.id}", value.sixtyOnly); putStringSet("groups-${source.id}", value.groups)
    } }
    fun streamFormat(source: Source) = preferences.getString("stream-format-${source.id}", "auto").orEmpty()
    fun setStreamFormat(source: Source, value: String) { preferences.edit { putString("stream-format-${source.id}", value) } }
    fun videoInfo(source: Source): MutableMap<String, VideoInfo> = runCatching {
        val j = JSONObject(preferences.getString("video-info-${source.id}", "{}").orEmpty())
        j.keys().asSequence().associateWith { VideoInfo.from(j.getJSONObject(it)) }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    fun saveVideoInfo(source: Source, values: Map<String, VideoInfo>) {
        val j = JSONObject(); values.entries.toList().takeLast(5000).forEach { j.put(it.key, it.value.json()) }
        preferences.edit { putString("video-info-${source.id}", j.toString()) }
    }
    var active: String
        get() = preferences.getString("active", "").orEmpty()
        set(value) { preferences.edit { putString("active", value) } }
    fun last(source: Source) = preferences.getString("last-${source.id}", "").orEmpty()
    fun setLast(source: Source, channel: Channel) { preferences.edit { putString("last-${source.id}", channel.id) } }
    fun favorites(source: Source) = preferences.getStringSet("favorites-${source.id}", emptySet()).orEmpty().toSet()
    fun toggleFavorite(source: Source, channel: Channel) {
        val favorites = favorites(source).toMutableSet()
        if (!favorites.add(channel.id)) favorites.remove(channel.id)
        preferences.edit { putStringSet("favorites-${source.id}", favorites) }
    }
    var lastUpdateCheck: Long
        get() = preferences.getLong("update-check", 0)
        set(value) { preferences.edit { putLong("update-check", value) } }
}

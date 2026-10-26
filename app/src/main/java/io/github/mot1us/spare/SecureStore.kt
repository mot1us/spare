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
    fun delete(name: String) { require(name.matches(Regex("[a-zA-Z0-9_.-]+"))); AtomicFile(File(context.filesDir, name)).delete() }
    fun pruneCatalog(prefix: String, keep: Set<String>) {
        require(prefix.matches(Regex("catalog-[a-zA-Z0-9_-]+-")))
        context.filesDir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".enc") && it.name !in keep }?.forEach { AtomicFile(it).delete() }
    }
    fun sources(): List<Source> = read("sources.enc")?.let { bytes ->
        val array = JSONArray(bytes.toString(Charsets.UTF_8)); List(array.length()) { Source.from(array.getJSONObject(it)) }
    } ?: emptyList()
    fun saveSources(sources: List<Source>) = write("sources.enc", JSONArray(sources.map { it.json() }).toString().toByteArray())
    fun cache(source: Source, guide: Guide) = write("cache-${source.id}.enc", guide.json().toString().toByteArray())
    fun cached(source: Source): Guide? = read("cache-${source.id}.enc")?.let { Guide.from(JSONObject(it.toString(Charsets.UTF_8))) }
    fun catalog(source: Source, kind: String): Pair<Long, List<OnDemand>>? {
        require(kind in setOf("movie", "series"))
        return read("library-${source.id}-$kind.enc")?.let { bytes ->
            val root = JSONObject(bytes.toString(Charsets.UTF_8)); val entries = root.getJSONArray("entries")
            require(entries.length() <= Importers.MAX_CHANNELS)
            root.getLong("updated") to List(entries.length()) { OnDemand.from(entries.getJSONObject(it)) }
        }
    }
    fun richCatalog(source: Source, kind: String) = preferences.getInt("catalog-schema-${source.id}-$kind", 0) >= 2
    fun metadataAddon(): String = read("metadata-addon.enc")?.toString(Charsets.UTF_8).orEmpty()
    fun saveMetadataAddon(value: String) { write("metadata-addon.enc", value.toByteArray()) }
    fun cacheCatalog(source: Source, kind: String, entries: List<OnDemand>) {
        require(kind in setOf("movie", "series"))
        write("library-${source.id}-$kind.enc", JSONObject().put("updated", System.currentTimeMillis()).put("entries", JSONArray(entries.map { it.json() })).toString().toByteArray())
        preferences.edit { putInt("catalog-schema-${source.id}-$kind", 2) }
    }
    @Synchronized fun watchProgress(source: Source): List<WatchProgress> = read("watch-${source.id}.enc")?.let { bytes ->
        val entries = JSONArray(bytes.toString(Charsets.UTF_8)); require(entries.length() <= 100)
        List(entries.length()) { WatchProgress.from(entries.getJSONObject(it)) }
    }.orEmpty()
    @Synchronized fun saveWatchProgress(source: Source, item: OnDemand, position: Long, duration: Long, ended: Boolean = false) {
        val entries = runCatching { watchProgress(source) }.getOrDefault(emptyList()).filter { it.item.id != item.id }.toMutableList()
        if (!ended && position >= 10000 && (duration <= 0 || position < duration - 30000)) entries.add(WatchProgress(item, position, duration.coerceAtLeast(0), System.currentTimeMillis()))
        write("watch-${source.id}.enc", JSONArray(entries.takeLast(100).map { it.json() }).toString().toByteArray())
    }
    fun providerQualityReady(source: Source) = preferences.getBoolean("provider-quality-${source.id}", false)
    fun markProviderQuality(source: Source) { preferences.edit { putBoolean("provider-quality-${source.id}", true) } }
    fun libraryMetadataReady(source: Source) = preferences.getBoolean("library-metadata-${source.id}", false)
    fun markLibraryMetadata(source: Source) { preferences.edit { putBoolean("library-metadata-${source.id}", true); putBoolean("provider-quality-${source.id}", true) } }
    fun removeCache(source: Source) {
        pruneCatalog("catalog-${source.id}-", emptySet())
        AtomicFile(File(context.filesDir, "cache-${source.id}.enc")).delete()
        listOf("library-${source.id}-movie.enc", "library-${source.id}-series.enc", "watch-${source.id}.enc").forEach { AtomicFile(File(context.filesDir, it)).delete() }
        preferences.edit { listOf("min-height", "sixty", "excluded-fps", "groups", "stream-format", "video-info", "unavailable", "favorites", "last", "previous", "category-order", "playlist-refreshed", "channel-captions", "library-metadata", "provider-quality").forEach { remove("$it-${source.id}") } }
    }
    fun filters(source: Source) = GuideFilters(preferences.getInt("min-height-${source.id}", 0), if (preferences.contains("excluded-fps-${source.id}")) preferences.getStringSet("excluded-fps-${source.id}", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet() else if (preferences.getBoolean("sixty-${source.id}", false)) FrameRateFilter.onlySixty else emptySet(), preferences.getStringSet("groups-${source.id}", emptySet()).orEmpty().toSet())
    fun setFilters(source: Source, value: GuideFilters) { preferences.edit {
        putInt("min-height-${source.id}", value.minHeight); remove("sixty-${source.id}"); putStringSet("excluded-fps-${source.id}", value.excludedFps.map { it.toString() }.toSet()); putStringSet("groups-${source.id}", value.groups)
    } }
    fun streamFormat(source: Source) = preferences.getString("stream-format-${source.id}", "auto").orEmpty()
    fun setStreamFormat(source: Source, value: String) { preferences.edit { putString("stream-format-${source.id}", value) } }
    fun usaFirst(source: Source) = preferences.getBoolean("category-order-${source.id}", false)
    fun setUsaFirst(source: Source, value: Boolean) { preferences.edit { putBoolean("category-order-${source.id}", value) } }
    fun playlistRefreshed(source: Source) = preferences.getLong("playlist-refreshed-${source.id}", 0)
    fun markPlaylistRefreshed(source: Source) { preferences.edit { putLong("playlist-refreshed-${source.id}", System.currentTimeMillis()); putBoolean("library-metadata-${source.id}", true); putBoolean("provider-quality-${source.id}", true) } }
    fun videoInfo(source: Source): MutableMap<String, VideoInfo> = runCatching {
        val j = JSONObject(preferences.getString("video-info-${source.id}", "{}").orEmpty())
        j.keys().asSequence().associateWith { VideoInfo.from(j.getJSONObject(it)) }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    fun saveVideoInfo(source: Source, values: Map<String, VideoInfo>) {
        val j = JSONObject(); values.entries.toList().takeLast(5000).forEach { j.put(it.key, it.value.json()) }
        preferences.edit { putString("video-info-${source.id}", j.toString()) }
    }
    fun unavailable(source: Source): UnavailableChannels = runCatching {
        val json = JSONObject(preferences.getString("unavailable-${source.id}", "{}").orEmpty())
        UnavailableChannels(json.keys().asSequence().associateWith { json.getLong(it) }.toMutableMap())
    }.getOrDefault(UnavailableChannels())
    fun saveUnavailable(source: Source, channels: UnavailableChannels) {
        preferences.edit { putString("unavailable-${source.id}", JSONObject(channels.snapshot()).toString()) }
    }
    fun channelCaptions(source: Source, channel: Channel): String? = runCatching {
        JSONObject(preferences.getString("channel-captions-${source.id}", "{}").orEmpty()).optString(channel.id).takeIf { it.isNotBlank() }
    }.getOrNull()
    fun setChannelCaptions(source: Source, channel: Channel, mode: String?) {
        val entries = runCatching { JSONObject(preferences.getString("channel-captions-${source.id}", "{}").orEmpty()) }.getOrDefault(JSONObject())
        entries.remove(channel.id)
        if (mode != null) entries.put(channel.id, mode)
        while (entries.length() > 5000) entries.remove(entries.keys().next())
        preferences.edit { putString("channel-captions-${source.id}", entries.toString()) }
    }
    var sportsTeam: String
        get() = preferences.getString("sports-team-selected", "").orEmpty()
        set(value) { preferences.edit { putString("sports-team-selected", value.takeIf { it in SportsCatalog.byId }.orEmpty()) } }
    var followedTeams: Set<String>
        get() = preferences.getStringSet("sports-teams", emptySet()).orEmpty().filter { it in SportsCatalog.byId }.toSet()
        set(value) { preferences.edit { putStringSet("sports-teams", value.filter { it in SportsCatalog.byId }.toSet()) } }
    var theme: String
        get() = preferences.getString("theme", "Monochrome").orEmpty()
        set(value) { preferences.edit { putString("theme", value) } }
    var themeStyle: String
        get() = preferences.getString("theme-style", "Glass").orEmpty()
        set(value) { preferences.edit { putString("theme-style", value) } }
    var subtitleSize: String
        get() = preferences.getString("subtitle-size", "Small").orEmpty()
        set(value) { preferences.edit { putString("subtitle-size", value) } }
    var subtitleStyle: String
        get() = preferences.getString("subtitle-style", "Outline").orEmpty()
        set(value) { preferences.edit { putString("subtitle-style", value) } }
    var font: String
        get() = preferences.getString("font", "Default").orEmpty()
        set(value) { preferences.edit { putString("font", value) } }
    var clock24: Boolean
        get() = preferences.getBoolean("clock-24", false)
        set(value) { preferences.edit { putBoolean("clock-24", value) } }
    var captions: Boolean
        get() = preferences.getBoolean("captions", false)
        set(value) { preferences.edit { putBoolean("captions", value) } }
    var hiddenDock: Boolean
        get() = preferences.getBoolean("hidden-dock", true)
        set(value) { preferences.edit { putBoolean("hidden-dock", value) } }
    var smallPreview: Boolean
        get() = preferences.getBoolean("small-preview", true)
        set(value) { preferences.edit { putBoolean("small-preview", value) } }
    var active: String
        get() = preferences.getString("active", "").orEmpty()
        set(value) { preferences.edit { putString("active", value) } }
    fun last(source: Source) = preferences.getString("last-${source.id}", "").orEmpty()
    fun previous(source: Source) = preferences.getString("previous-${source.id}", "").orEmpty()
    // Called only after a rendered frame: a failed tune must not replace watch history.
    fun setLast(source: Source, channel: Channel) {
        val prior = last(source)
        preferences.edit {
            if (prior.isNotBlank() && prior != channel.id) putString("previous-${source.id}", prior)
            putString("last-${source.id}", channel.id)
        }
    }
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

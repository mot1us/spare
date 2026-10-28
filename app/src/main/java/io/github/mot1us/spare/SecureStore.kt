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
    var localLocation: String
        get() = preferences.getString("local-location", "").orEmpty()
        set(value) { preferences.edit { putString("local-location", value.trim().take(80)) } }
    var guideOpacity: Int
        get() = preferences.getInt("guide-opacity", -1).coerceIn(-1, 100)
        set(value) { preferences.edit { putInt("guide-opacity", value.coerceIn(-1, 100)) } }
    var customAccent: Int
        get() = preferences.getInt("theme-accent", 0)
        set(value) { preferences.edit { putInt("theme-accent", value) } }
    var customBackground: Int
        get() = preferences.getInt("theme-background", -1).coerceIn(-1, 0xFFFFFF)
        set(value) { preferences.edit { putInt("theme-background", value.coerceIn(-1, 0xFFFFFF)) } }
    var cornerRadius: Int
        get() = preferences.getInt("theme-corner", -1).coerceIn(-1, 20)
        set(value) { preferences.edit { putInt("theme-corner", value.coerceIn(-1, 20)) } }
    var borderWidth: Int
        get() = preferences.getInt("theme-border", -1).coerceIn(-1, 3)
        set(value) { preferences.edit { putInt("theme-border", value.coerceIn(-1, 3)) } }
    var guideTextScale: Int
        get() = preferences.getInt("guide-text-scale", 100).coerceIn(85, 120)
        set(value) { preferences.edit { putInt("guide-text-scale", value.coerceIn(85, 120)) } }
    var guideRowHeight: Int
        get() = preferences.getInt("guide-row-height", 32).coerceIn(28, 44)
        set(value) { preferences.edit { putInt("guide-row-height", value.coerceIn(28, 44)) } }
    var guideRowGap: Int
        get() = preferences.getInt("guide-row-gap", 3).coerceIn(2, 8)
        set(value) { preferences.edit { putInt("guide-row-gap", value.coerceIn(2, 8)) } }
    private val key: SecretKey by lazy { synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("spare-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("spare-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    } }
    fun write(name: String, bytes: ByteArray) = synchronized(fileLock(name)) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val file = AtomicFile(File(context.filesDir, name))
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1)); output.write(cipher.iv); output.write(cipher.doFinal(bytes)); file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun read(name: String): ByteArray? = synchronized(fileLock(name)) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val file = AtomicFile(File(context.filesDir, name))
        if (!file.baseFile.exists()) return@synchronized null
        val bytes = file.openRead().use { Network.readLimited(it, 128 * 1024 * 1024) }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        cipher.doFinal(bytes, 13, bytes.size - 13)
    }
    fun delete(name: String) = synchronized(fileLock(name)) { require(name.matches(Regex("[a-zA-Z0-9_.-]+"))); AtomicFile(File(context.filesDir, name)).delete() }
    fun pruneCatalog(prefix: String, keep: Set<String>) {
        require(prefix.matches(Regex("catalog-[a-zA-Z0-9_-]+-")))
        context.filesDir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".enc") && it.name !in keep }?.forEach { delete(it.name) }
    }
    fun sources(): List<Source> = read("sources.enc")?.let { bytes ->
        val array = JSONArray(bytes.toString(Charsets.UTF_8)); List(array.length()) { Source.from(array.getJSONObject(it)) }
    } ?: emptyList()
    fun saveSources(sources: List<Source>) = write("sources.enc", JSONArray(sources.map { it.json() }).toString().toByteArray())
    fun cache(source: Source, guide: Guide) { saveLineup(source, guide); cacheProgrammes(source, guide.programmes, System.currentTimeMillis()) }
    fun saveLineup(source: Source, guide: Guide, url: String = guideUrl(source)) {
        write("lineup-${source.id}.enc", guide.copy(programmes = emptyMap()).json().toString().toByteArray())
        if (url.isNotBlank()) write("guide-url-${source.id}.enc", url.toByteArray())
    }
    fun guideUrl(source: Source) = runCatching { read("guide-url-${source.id}.enc")?.toString(Charsets.UTF_8).orEmpty() }.getOrDefault("")
    fun cachedLineup(source: Source): Guide? = read("lineup-${source.id}.enc")?.let { Guide.from(JSONObject(it.toString(Charsets.UTF_8))) }
        ?: read("cache-${source.id}.enc")?.let { GuideDisk.lineup(it).also { guide -> saveLineup(source, guide) } }
    fun cachedProgrammes(source: Source): Map<String, List<Programme>> = read("epg-${source.id}.enc")?.let { Guide.from(JSONObject(it.toString(Charsets.UTF_8))).programmes }
        ?: read("cache-${source.id}.enc")?.let {
            val entries = Guide.from(JSONObject(it.toString(Charsets.UTF_8))).programmes
            cacheProgrammes(source, entries, System.currentTimeMillis())
            if (java.io.File(context.filesDir, "lineup-${source.id}.enc").exists()) delete("cache-${source.id}.enc")
            entries
        }.orEmpty()
    fun cacheProgrammes(source: Source, programmes: Map<String, List<Programme>>, observedAt: Long) = synchronized(cacheLock) {
        if (observedAt >= preferences.getLong("epg-cache-${source.id}", 0)) {
            write("epg-${source.id}.enc", Guide(emptyList(), programmes).json().toString().toByteArray())
            preferences.edit { putLong("epg-cache-${source.id}", observedAt) }
        }
    }
    fun cached(source: Source): Guide? = cachedLineup(source)?.copy(programmes = cachedProgrammes(source))
    fun refreshPolicy(source: Source) = RefreshPolicy(preferences.getInt("playlist-hours-${source.id}", 0), preferences.getInt("epg-hours-${source.id}", 0), preferences.getBoolean("playlist-launch-${source.id}", false), preferences.getBoolean("epg-launch-${source.id}", false))
    fun setRefreshPolicy(source: Source, policy: RefreshPolicy) { preferences.edit {
        putInt("playlist-hours-${source.id}", policy.playlistHours); putInt("epg-hours-${source.id}", policy.epgHours)
        putBoolean("playlist-launch-${source.id}", policy.playlistOnLaunch); putBoolean("epg-launch-${source.id}", policy.epgOnLaunch)
    } }
    fun epgRefreshed(source: Source) = preferences.getLong("epg-refreshed-${source.id}", 0)
    fun markEpgRefreshed(source: Source) { preferences.edit { putLong("epg-refreshed-${source.id}", System.currentTimeMillis()) } }
    fun refreshError(source: Source) = preferences.getString("refresh-error-${source.id}", "").orEmpty()
    fun refreshError(source: Source, value: String) { preferences.edit { putString("refresh-error-${source.id}", value.take(600)) } }
    companion object { private val cacheLock = Any(); private val keyLock = Any(); private val fileLocks = Array(64) { Any() }; private fun fileLock(name: String) = fileLocks[(name.hashCode() and Int.MAX_VALUE) % fileLocks.size] }
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
        delete("cache-${source.id}.enc")
        listOf("lineup-${source.id}.enc", "local-matches-${source.id}.enc", "epg-${source.id}.enc", "guide-url-${source.id}.enc", "library-${source.id}-movie.enc", "library-${source.id}-series.enc", "watch-${source.id}.enc").forEach { delete(it) }
        preferences.edit { listOf("min-height", "sixty", "excluded-fps", "groups", "stream-format", "video-info", "unavailable", "favorites", "last", "previous", "category-order", "playlist-refreshed", "channel-captions", "library-metadata", "provider-quality", "recent", "playlist-hours", "epg-hours", "epg-refreshed", "epg-cache", "playlist-launch", "epg-launch", "refresh-error").forEach { remove("$it-${source.id}") } }
    }
    fun filters(source: Source) = GuideFilters(groups = preferences.getStringSet("groups-${source.id}", emptySet()).orEmpty().toSet())
    fun setFilters(source: Source, value: GuideFilters) { preferences.edit { putStringSet("groups-${source.id}", value.groups) } }
    // Reuse the old quality keys so existing choices become Sports-only without a destructive migration.
    fun sportsFilters(source: Source) = GuideFilters(preferences.getInt("min-height-${source.id}", 0), if (preferences.contains("excluded-fps-${source.id}")) preferences.getStringSet("excluded-fps-${source.id}", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet() else if (preferences.getBoolean("sixty-${source.id}", false)) FrameRateFilter.onlySixty else emptySet())
    fun setSportsFilters(source: Source, value: GuideFilters) { preferences.edit {
        putInt("min-height-${source.id}", value.minHeight); remove("sixty-${source.id}"); putStringSet("excluded-fps-${source.id}", value.excludedFps.map { it.toString() }.toSet())
    } }
    fun streamFormat(source: Source) = preferences.getString("stream-format-${source.id}", "auto").orEmpty()
    fun setStreamFormat(source: Source, value: String) { preferences.edit { putString("stream-format-${source.id}", value) } }
    fun usaFirst(source: Source) = preferences.getBoolean("category-order-${source.id}", false)
    fun setUsaFirst(source: Source, value: Boolean) { preferences.edit { putBoolean("category-order-${source.id}", value) } }
    fun playlistRefreshed(source: Source) = preferences.getLong("playlist-refreshed-${source.id}", 0)
    fun markPlaylistRefreshed(source: Source) { preferences.edit { putLong("playlist-refreshed-${source.id}", System.currentTimeMillis()); putBoolean("library-metadata-${source.id}", true); putBoolean("provider-quality-${source.id}", true) } }
    fun videoInfo(source: Source): MutableMap<String, VideoInfo> = runCatching {
        val j = JSONObject(preferences.getString("video-info-${source.id}", "{}").orEmpty())
        j.keys().asSequence().associateWith {
            val value = j.getJSONObject(it); val info = VideoInfo.from(value)
            if (value.optInt("detector") < 4) info.copy(fps = 0f, encodedFps = 0f, evidence = "needs verification") else info
        }.toMutableMap()
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
    var hideCompletedScores: Boolean
        get() = preferences.getBoolean("sports-hide-completed-scores", true)
        set(value) { preferences.edit { putBoolean("sports-hide-completed-scores", value) } }
    var sportsFollowedOnly: Boolean
        get() = preferences.getBoolean("sports-followed-only", false)
        set(value) { preferences.edit { putBoolean("sports-followed-only", value) } }
    var sportsAutoPlay: Boolean
        get() = preferences.getBoolean("sports-auto-play", false)
        set(value) { preferences.edit { putBoolean("sports-auto-play", value) } }
    var sportsTeam: String
        get() = preferences.getString("sports-team-selected", "").orEmpty()
        set(value) { preferences.edit { putString("sports-team-selected", value.takeIf { it in SportsCatalog.byId || it in setOf("today", "followed") || it.removePrefix("league:") in SportsLeagues.byId }.orEmpty()) } }
    var followedTeams: Set<String>
        get() = preferences.getStringSet("sports-teams", emptySet()).orEmpty().filter { it in SportsCatalog.byId }.toSet()
        set(value) { preferences.edit { putStringSet("sports-teams", value.filter { it in SportsCatalog.byId }.toSet()) } }
    var sportsLeagues: Set<String>
        get() = preferences.getStringSet("sports-leagues", SportsLeagues.byId.keys).orEmpty().filter { it in SportsLeagues.byId }.toSet().ifEmpty { SportsLeagues.byId.keys }
        set(value) { val valid = value.filter { it in SportsLeagues.byId }.toSet(); require(valid.isNotEmpty()); preferences.edit { putStringSet("sports-leagues", valid) } }
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
    var frameRateMatching: Boolean
        get() = preferences.getBoolean("frame-rate-matching", true)
        set(value) { preferences.edit { putBoolean("frame-rate-matching", value) } }
    var smallPreview: Boolean
        get() = preferences.getBoolean("small-preview", true)
        set(value) { preferences.edit { putBoolean("small-preview", value) } }
    var active: String
        get() = preferences.getString("active", "").orEmpty()
        set(value) { preferences.edit { putString("active", value) } }
    fun last(source: Source) = preferences.getString("last-${source.id}", "").orEmpty()
    fun previous(source: Source) = preferences.getString("previous-${source.id}", "").orEmpty()
    fun recent(source: Source): List<String> = runCatching {
        val entries = JSONArray(preferences.getString("recent-${source.id}", "[]"))
        (List(minOf(entries.length(), 12)) { entries.optString(it) } + listOf(last(source), previous(source))).filter { it.isNotBlank() }.distinct().take(12)
    }.getOrDefault(listOf(last(source), previous(source)).filter { it.isNotBlank() }.distinct())
    // Called only after a rendered frame: a failed tune must not replace watch history.
    fun setLast(source: Source, channel: Channel) {
        val prior = last(source)
        val history = (listOf(channel.id) + recent(source)).distinct().take(12)
        preferences.edit {
            putString("recent-${source.id}", JSONArray(history).toString())
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

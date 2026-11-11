package io.github.mot1us.spare

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors

/** One bounded background queue shared by the app and Android jobs. Never touches a player. */
object PlaylistRefresh {
    data class Result(val guide: Guide?, val error: String = "", val playlistChanged: Boolean = false)
    private val worker = Executors.newSingleThreadExecutor { work -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "spare-refresh") }
    private val handler = Handler(Looper.getMainLooper())
    // Requests may share a saved ID while an account/address edit replaces its owner.
    // Only the exact source snapshot may receive another request's cached result.
    private val pending = mutableMapOf<Source, MutableList<(Result) -> Unit>>()
    private val listeners = mutableMapOf<Any, (Source, Result) -> Unit>()
    fun listen(owner: Any, changed: (Source, Result) -> Unit) { listeners[owner] = changed }
    fun unlisten(owner: Any) { listeners.remove(owner) }
    private val progress = mutableMapOf<Source, String>()
    fun running(id: String) = synchronized(pending) { pending.keys.any { it.id == id } }
    fun stage(id: String) = synchronized(pending) { progress.entries.lastOrNull { it.key.id == id }?.value.orEmpty() }
    fun request(context: Context, source: Source, playlist: Boolean, epg: Boolean, done: (Result) -> Unit): Boolean {
        synchronized(pending) { pending[source]?.let { if (it.size < 8) it.add(done); return false }; pending[source] = mutableListOf(done); progress[source] = "Refreshing in background…" }
        val app = context.applicationContext
        worker.execute {
            val store = SecureStore(app)
            fun stage(text: String) { synchronized(pending) { progress[source] = text } }
            val result = runCatching {
                var cached = store.cachedLineup(source) ?: Guide.EMPTY
                var changed = false; val errors = mutableListOf<String>()
                if (playlist || cached.channels.isEmpty() || !store.epgMappingReady(source)) {
                    runCatching {
                        stage("Downloading playlist…")
                        val lineup = if (source.kind == "xtream") {
                            Importers.xtream(source)
                        } else {
                            // Keep provider URLs in memory and exclude parser work from the socket budget.
                            Network.consume(source.url, source.allowHttp, networkTimeOnly = true) { input ->
                                Importers.m3u(input.bufferedReader(Charsets.UTF_8), source.allowHttp)
                            }
                        }
                        require(lineup.channels.isNotEmpty()) { "The provider returned an empty playlist" }
                        // Source edits/removal must invalidate an in-flight response.
                        require(store.sources().any { it == source })
                        cached = Guide(lineup.channels, emptyMap(), lineup.categoryOrder)
                        store.saveLineup(source, cached, source.epgUrl.ifBlank { lineup.epgUrl }); store.markArchiveMetadataAttempted(source); store.markPlaylistRefreshed(source); changed = true
                    }.onFailure { errors.add("Playlist: ${SourceErrors.message(it)}") }
                }
                val savedStamp = store.programmesStamp(source)
                val saved = runCatching { store.cachedProgrammes(source) }.getOrDefault(emptyMap())
                var programmes = saved
                if (epg && cached.channels.isNotEmpty()) {
                    runCatching {
                        stage("Updating programme guide…")
                        val url = source.epgUrl.ifBlank { store.guideUrl(source) }.ifBlank { if (source.kind == "xtream") Importers.endpoint(source, "xmltv.php") else "" }
                        var fullFailure: Throwable? = null
                        val full = if (url.isBlank()) emptyMap() else runCatching { FullEpg.load(source, cached.channels, url) }
                            .onFailure { fullFailure = it }.getOrDefault(emptyMap())
                        if (full.isNotEmpty()) programmes = saved + full
                        else if (source.kind == "xtream" && source.epgUrl.isBlank()) {
                            // Unsupported XMLTV never blocks browsing: the visible guide still uses short EPG.
                            // Keep scheduled fallback work bounded instead of querying the entire playlist.
                            stage("Updating recently watched channels…")
                            val wanted = (listOf(store.last(source)) + store.recent(source) + store.favorites(source)).toSet()
                            val seed = !store.fullEpgAttempted(source)
                            val at = System.currentTimeMillis()
                            val targets = (cached.channels.filter { it.id in wanted } + cached.channels.filter { it.id in saved.keys }.take(4) + cached.channels.take(4)).distinctBy { it.id }
                                .filter { channel -> !seed || saved[channel.guideId.ifBlank { channel.id }].orEmpty().none { at >= it.start && at < it.end } }.take(8)
                            val updated = linkedMapOf<String, List<Programme>>(); var failure: Throwable? = null
                            targets.forEach { channel -> runCatching { XtreamEpg.load(source, channel, 24, timeoutMs = 6000) }.onSuccess { if (it.isNotEmpty()) updated[channel.guideId.ifBlank { channel.id }] = it }.onFailure { failure = it } }
                            if (updated.isEmpty() && failure != null) throw failure!!
                            programmes = saved + updated
                        } else if (fullFailure != null) throw fullFailure!!
                        // Retain archive history and any visible-channel data saved during the download.
                        val latest = if (store.programmesStamp(source) == savedStamp) saved else runCatching { store.cachedProgrammes(source) }.getOrDefault(saved)
                        val now = System.currentTimeMillis()
                        val days = HashMap<String, Int>()
                        cached.channels.forEach { channel -> val key = channel.guideId.ifBlank { channel.id }; days[key] = maxOf(days[key] ?: 0, channel.catchupDays) }
                        programmes = latest + programmes.mapValues { (key, entries) -> GuideHistory.merge(latest[key].orEmpty(), entries, days[key] ?: 0, now) }
                        require(store.sources().any { it == source })
                        store.cacheProgrammes(source, programmes, System.currentTimeMillis()); store.markEpgRefreshed(source)
                    }.onFailure { errors.add("EPG: ${SourceErrors.message(it)}") }
                    if (store.epgMappingReady(source)) store.markFullEpgAttempted(source)
                }
                Result(cached.takeIf { it.channels.isNotEmpty() }?.copy(programmes = programmes), errors.joinToString(" · "), changed)
            }.getOrElse { Result(null, SourceErrors.message(it)) }
            store.refreshError(source, result.error)
            val callbacks = synchronized(pending) { progress.remove(source); pending.remove(source).orEmpty() }
            handler.post { RefreshJobs.configure(app, store.sources()); listeners.values.toList().forEach { it(source, result) }; callbacks.forEach { it(result) } }
        }
        return true
    }
}

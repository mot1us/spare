package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Explicit, playlist-wide title search. Catalog pages stay encrypted; no video streams are probed. */
class PlaylistSearch(private val store: SecureStore, private val repository: LibraryCatalog = LibraryCatalog(store), private val deliver: (List<OnDemand>, Boolean, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val reader = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var source: Source? = null
    private var context = 0
    private var query = ""
    private var active = false
    private var closed = false
    private var extras = emptyList<OnDemand>()
    private val catalogs = mutableMapOf<String, LibraryCatalog.Snapshot>()
    private val matches = mutableMapOf<String, List<OnDemand>>()
    private val loads = mutableMapOf<String, Future<*>?>()
    private val loading = mutableSetOf<String>()
    private val failures = mutableSetOf<String>()
    private var readTask: Future<*>? = null
    private var publishPending = false
    private val publish = Runnable {
        val value = synchronized(lock) {
            publishPending = false
            if (!active || closed) return@Runnable
            Triple(listOf("movie", "series", "episode").flatMap { matches[it].orEmpty() }, loading.isNotEmpty(),
                if (failures.isEmpty()) "" else "Some library titles could not load. Reopen Search to retry.")
        }
        deliver(value.first, value.second, value.third)
    }
    private fun publish() { if (!publishPending) { publishPending = true; handler.postDelayed(publish, 100) } }
    fun search(selected: Source, text: String, knownEpisodes: List<OnDemand>) {
        synchronized(lock) {
            if (closed) return
            if (source != selected) { cancelLoads(); catalogs.clear(); source = selected; ++context }
            active = true; query = ChannelSearch.key(text); extras = knownEpisodes
            matches.clear()
            if (query.isEmpty()) { readTask?.cancel(true); publish(); return }
            read()
            if (selected.kind == "xtream") listOf("movie", "series").forEach { mode -> if (mode !in loads || (mode in failures && loads[mode]?.isDone == true)) load(selected, mode, context) }
            publish()
        }
    }
    /** Caller holds lock; catalog downloads continue through query changes. */
    private fun read() {
        readTask?.cancel(true)
        val token = context; val term = query; val snapshots = catalogs.toMap(); val episodes = extras; val selected = source
        readTask = reader.submit {
            val watched = selected?.let { runCatching { store.watchProgress(it).map { entry -> entry.item }.filter { item -> item.kind == "episode" } }.getOrDefault(emptyList()) }.orEmpty()
            val found = snapshots.mapValues { (_, snapshot) -> repository.query(snapshot, null, term, limit = 40).entries } +
                ("episode" to (episodes + watched).filter { ChannelSearch.matches(it.title, term) }.distinctBy { it.id }.take(40))
            synchronized(lock) {
                if (!active || closed || token != context || term != query || Thread.currentThread().isInterrupted) return@submit
                found.forEach { (mode, items) -> matches[mode] = (items + matches[mode].orEmpty()).distinctBy { it.id }.take(40) }
                publish()
            }
        }
    }
    private fun load(selected: Source, mode: String, token: Int) {
        failures.remove(mode)
        loading.add(mode)
        loads[mode] = io.submit {
            try {
                val cached = repository.cached(selected, mode)
                if (cached != null) synchronized(lock) { if (token == context) { catalogs[mode] = cached; read() } }
                // Upgrade old page-only caches once; subsequent searches read the compact title index.
                if (cached == null || cached.chunks.any { it.keys.size != it.count } || System.currentTimeMillis() - cached.updated !in 0 until 6 * 3600000L) {
                    val writer = repository.Writer(selected, mode)
                    var publishedAt = 0L
                    VodCatalog.stream(selected, mode) { batch ->
                        writer.append(batch)
                        val now = android.os.SystemClock.elapsedRealtime()
                        val snapshot = if (now - publishedAt >= 750) writer.snapshot(emptyMap()).also { publishedAt = now } else null
                        synchronized(lock) {
                            if (closed || token != context || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Search cancelled")
                            if (snapshot != null) catalogs[mode] = snapshot
                            if (query.isNotEmpty()) {
                                val additions = batch.filter { ChannelSearch.key(it.title).contains(query) }
                                if (additions.isNotEmpty()) { matches[mode] = (matches[mode].orEmpty() + additions).distinctBy { it.id }.take(40); publish() }
                            }
                        }
                    }
                    val complete = writer.finish(emptyMap())
                    synchronized(lock) { if (token == context) { catalogs[mode] = complete; read() } }
                }
            } catch (_: Exception) { synchronized(lock) { if (token == context && !Thread.currentThread().isInterrupted) failures.add(mode) } }
            finally { synchronized(lock) { if (token == context) { loading.remove(mode); publish() } } }
        }
    }
    private fun cancelLoads() { loads.values.forEach { it?.cancel(true) }; loads.clear(); loading.clear(); readTask?.cancel(true) }
    // Leaving the search surface must not throw away a nearly finished metadata-only import.
    fun pause(cancel: Boolean = false) { synchronized(lock) {
        active = false; readTask?.cancel(true)
        if (cancel) { ++context; cancelLoads() }
        handler.removeCallbacks(publish); publishPending = false
    } }
    fun close() { pause(cancel = true); closed = true; io.shutdownNow(); reader.shutdownNow() }
}

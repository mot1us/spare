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
    private var modes = setOf("movie", "series")
    private var active = false
    private var closed = false
    private var extras = emptyList<OnDemand>()
    private val catalogs = mutableMapOf<String, LibraryCatalog.Snapshot>()
    private val matches = mutableMapOf<String, List<OnDemand>>()
    private val loads = mutableMapOf<String, Future<*>?>()
    private val loading = mutableSetOf<String>()
    private val failures = mutableSetOf<String>()
    private var readTask: Future<*>? = null
    private var readEpoch = 0
    private var reading = false
    private var publishPending = false
    private var warming = false
    private var restoring = false
    var stateChanged: (() -> Unit)? = null
    data class CatalogStatus(val checking: Boolean, val complete: Boolean, val failed: Boolean)
    fun catalogStatus(selected: Source, kinds: Set<String>): CatalogStatus = synchronized(lock) {
        if (source != selected) return@synchronized CatalogStatus(false, false, false)
        val complete = kinds.all { mode -> repository.memory(selected, mode)?.let { it.complete && it.chunks.all { chunk -> chunk.titles.size == chunk.count } } == true }
        CatalogStatus(restoring || kinds.any { it in loading } || warming, complete, kinds.any { it in failures })
    }
    fun prepare(selected: Source) {
        synchronized(lock) {
            if (closed || source == selected) return
            cancelLoads(); warming = false; catalogs.clear(); failures.clear(); source = selected; ++context
            val token = context
            restoring = true; publish()
            io.submit {
                try { listOf("movie", "series").forEach { mode ->
                    val saved = runCatching { repository.cached(selected, mode) }.getOrNull()
                    synchronized(lock) { if (token == context && saved != null) { catalogs[mode] = saved; if (active) read() } }
                } } finally { synchronized(lock) { if (token == context) { restoring = false; publish() } } }
            }
        }
    }
    /** One metadata import at a time while idle. Does not open any playback connection. */
    fun prefetch(selected: Source) {
        prepare(selected)
        synchronized(lock) {
            if (closed || active || selected.kind != "xtream") return
            val next = listOf("movie", "series").firstOrNull { it !in loads && it !in failures } ?: return
            warming = true; query = ""
            load(selected, next, context)
        }
    }
    fun pausePrefetch() { synchronized(lock) { if (warming && !active) { warming = false; ++context; cancelLoads(); publish() } } }
    fun refresh(selected: Source, mode: String) {
        prepare(selected)
        synchronized(lock) { if (!closed) { ++context; cancelLoads(); failures.clear(); load(selected, mode, context, force = true) } }
    }
    private val publish = Runnable {
        val value = synchronized(lock) {
            publishPending = false
            if (closed) return@Runnable
            if (active) Triple(listOf("movie", "series", "episode").flatMap { matches[it].orEmpty() }, loading.isNotEmpty() || reading,
                if (failures.isEmpty()) "" else "Some library titles could not load. Reopen Search to retry.") else null
        }
        stateChanged?.invoke()
        if (value != null) deliver(value.first, value.second, value.third)
    }
    private fun publish() { if (!publishPending) { publishPending = true; handler.postDelayed(publish, 100) } }
    fun search(selected: Source, text: String, knownEpisodes: List<OnDemand>, kinds: Set<String> = setOf("movie", "series")) {
        require(kinds.isNotEmpty() && kinds.all { it in setOf("movie", "series") })
        synchronized(lock) {
            if (closed) return
            warming = false
            if (source != selected) { cancelLoads(); catalogs.clear(); failures.clear(); source = selected; ++context }
            if (modes != kinds) { cancelLoads(); failures.clear(); modes = kinds.toSet(); ++context }
            active = true; query = ChannelSearch.key(text); extras = knownEpisodes
            matches.clear()
            if (query.isEmpty()) { ++readEpoch; reading = false; readTask?.cancel(true); publish(); return }
            read()
            if (selected.kind == "xtream") modes.forEach { mode -> if (mode !in loads || (mode in failures && loads[mode]?.isDone == true)) load(selected, mode, context) }
            publish()
        }
    }
    /** Caller holds lock; catalog downloads continue through query changes. */
    private fun read() {
        readTask?.cancel(true)
        val readToken = ++readEpoch; reading = true
        val token = context; val term = query; val wanted = modes.toSet()
        val snapshots = catalogs.filterKeys { it in wanted }; val known = extras; val selected = source
        readTask = reader.submit {
            fun accept(found: Map<String, List<OnDemand>>) = synchronized(lock) {
                if (!active || closed || token != context || readToken != readEpoch || term != query || Thread.currentThread().isInterrupted) return@synchronized
                failures.remove("cache")
                found.forEach { (mode, items) -> matches[mode] = (items + matches[mode].orEmpty()).distinctBy { it.id }.take(40) }
                publish()
            }
            try {
                val watched = selected?.let { runCatching { store.watchProgress(it).map { entry -> entry.item } }.getOrDefault(emptyList()) }.orEmpty()
                val remembered = (known + watched).filter { (it.kind in wanted || (wanted.size > 1 && it.kind == "episode")) && ChannelSearch.key(it.title).contains(term) }
                    .distinctBy { it.id }.groupBy { it.kind }.mapValues { it.value.take(40) }
                accept(remembered)
                accept(snapshots.mapValues { (_, snapshot) -> repository.query(snapshot, null, term, limit = 40).entries })
            } catch (_: Exception) {
                synchronized(lock) { if (token == context && readToken == readEpoch && !Thread.currentThread().isInterrupted) failures.add("cache") }
            } finally {
                synchronized(lock) { if (token == context && readToken == readEpoch) { reading = false; publish() } }
            }
        }
    }
    private fun load(selected: Source, mode: String, token: Int, force: Boolean = false) {
        failures.remove(mode)
        loading.add(mode)
        publish()
        loads[mode] = io.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val cached = runCatching { repository.cached(selected, mode) }.getOrNull()
                if (cached != null) synchronized(lock) { if (token == context) { catalogs[mode] = cached; if (active) read() } }
                // Upgrade old page-only caches once; subsequent searches read the compact title index.
                if (force || cached == null || !cached.complete || cached.chunks.any { it.titles.size != it.count } || System.currentTimeMillis() - cached.updated !in 0 until 6 * 3600000L) {
                    val writer = repository.Writer(selected, mode, indexOnly = true)
                    var publishedAt = 0L; var savedAt = 0L
                    VodCatalog.stream(selected, mode, lean = true) { batch ->
                        synchronized(lock) {
                            if (closed || token != context || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Search cancelled")
                            if (query.isNotEmpty()) {
                                val additions = batch.filter { ChannelSearch.key(it.title).contains(query) }
                                if (additions.isNotEmpty()) { matches[mode] = (matches[mode].orEmpty() + additions).distinctBy { it.id }.take(40); publish() }
                            }
                        }
                        writer.append(batch)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - publishedAt >= 750) {
                            val snapshot = writer.snapshot(emptyMap()); publishedAt = now
                            synchronized(lock) { if (token == context && snapshot.count >= (catalogs[mode]?.count ?: 0)) catalogs[mode] = snapshot }
                            if (cached?.complete != true && snapshot.count >= (cached?.count ?: 0) && now - savedAt >= 5000) {
                                writer.checkpoint(emptyMap()); savedAt = now
                            }
                        }
                    }
                    val complete = writer.finish(emptyMap())
                    synchronized(lock) { if (token == context) { catalogs[mode] = complete; if (active) read() } }
                }
            } catch (_: Exception) { synchronized(lock) { if (token == context && !Thread.currentThread().isInterrupted) failures.add(mode) } }
            finally { synchronized(lock) {
                if (token == context) {
                    loading.remove(mode); publish()
                    if (warming && !active && mode == "movie" && "series" !in loads && "series" !in failures) load(selected, "series", token)
                    if (loading.isEmpty()) warming = false
                }
            } }
        }
    }
    private fun cancelLoads() { loads.values.forEach { it?.cancel(true) }; loads.clear(); loading.clear(); restoring = false; ++readEpoch; reading = false; readTask?.cancel(true) }
    // Leaving the search surface must not throw away a nearly finished metadata-only import.
    fun pause(cancel: Boolean = false) { synchronized(lock) {
        active = false; ++readEpoch; reading = false; readTask?.cancel(true)
        if (cancel) { warming = false; ++context; cancelLoads() }
        handler.removeCallbacks(publish); publishPending = false
    } }
    fun close() { pause(cancel = true); closed = true; io.shutdownNow(); reader.shutdownNow() }
}

package io.github.mot1us.spare

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.core.view.isVisible
import java.util.concurrent.Executors

/** Provider I/O and encrypted catalog reads stay off the UI thread. No catalog fetch at startup. */
class LibraryController(private val activity: Activity, private val store: SecureStore, private val button: (String, () -> Unit) -> Button,
    private val source: () -> Source?, private val guide: () -> Guide, private val play: (OnDemand, Long) -> Unit, private val live: () -> Unit,
    private val closed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private var task: java.util.concurrent.Future<*>? = null
    private var epoch = 0
    private var kind = "movie"
    private var parentSeries: OnDemand? = null
    private var archive: Channel? = null
    private var episodeList = emptyList<OnDemand>()
    private var playingEpisodes = emptyList<OnDemand>()
    private var destroyed = false
    private val disk = Executors.newSingleThreadExecutor()
    private val positions = Executors.newSingleThreadExecutor()
    private val episodeCache = linkedMapOf<String, List<OnDemand>>()
    private val catalogs = linkedMapOf<String, LibraryCatalog.Snapshot>()
    private val repository = LibraryCatalog(store)
    private val catalogImports = mutableMapOf<String, java.util.concurrent.Future<*>>()
    private val catalogGenerations = mutableMapOf<String, Int>()
    private var catalogGeneration = 0
    private var catalogImporting = false
    private val catalogReader = Executors.newSingleThreadExecutor()
    private var pageTask: java.util.concurrent.Future<*>? = null
    private var pageEpoch = 0
    private var activeCatalog = ""
    private var activeSnapshot: LibraryCatalog.Snapshot? = null
    private var activeGroup: String? = null
    private var activeQuery = ""
    private data class BrowsePage(val version: String, val rows: List<BrowserRow>, val cursor: Int, val more: Boolean)
    private val browsePages = linkedMapOf<String, BrowsePage>()
    private val details = linkedMapOf<String, OnDemand>()
    private var detailItem: OnDemand? = null
    private var labels = emptyMap<String, String>()
    val panel = MediaBrowserPanel(activity, button, { open(it) }, { refresh() }, { dismiss(); live() })
    val opened get() = panel.isVisible
    fun open(mode: String = kind) {
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        parentSeries = null; archive = null; detailItem = null; kind = mode; panel.resumeImages(); panel.visibility = View.VISIBLE
        val selected = source()
        task?.cancel(true); ++epoch; panel.browse(); panel.allowArtworkHttp(selected?.allowHttp == true)
        if (selected == null) { panel.submit(emptyList()); panel.page("Library", "Add a playlist in Settings first.", false); panel.focusRows(); return }
        if (mode == "catchup") { catchupChannels(); return }
        if (mode == "continue") { continueWatching(selected); return }
        if (selected.kind != "xtream") {
            panel.submit(emptyList())
            val items = if (mode == "movie") guide().channels.filter { it.media }.map { OnDemand("m3u:${it.id}", it.name, it.group, it.url) } else emptyList()
            showItems(items, if (mode == "movie") "Movies" else "Series", "M3U movies require media=\"true\" entries. Series browsing requires an Xtream playlist.", false)
            return
        }
        catalog(selected, mode, false)
    }
    private fun request(job: (Int) -> Unit) { task?.cancel(true); val token = ++epoch; task = worker.submit { if (!destroyed) job(token) } }
    private fun ui(token: Int, work: () -> Unit) { handler.post { if (!destroyed && token == epoch && opened) work() } }
    private val categoryNames = mutableMapOf<String, Map<String, String>>()
    private fun catalog(selected: Source, mode: String, force: Boolean) {
        val root = "${selected.id}:$mode"; activeCatalog = root
        val title = if (mode == "movie") "Movies" else "Series"
        panel.page(title, "Loading categories…", true)
        fun bind(names: Map<String, String>) {
            categoryNames[root] = names
            panel.page(title, "Choose a category · Only the selected category is downloaded", false)
            panel.bindCatalog(root, names, { group, query ->
                val scope = group ?: names.keys.first()
                scopedCatalog(selected, mode, root, scope, names, query, force)
            }, { loadCatalogPage(null, activeQuery, true) }, "Featured")
        }
        val memory = categoryNames[root]
        if (!force && !memory.isNullOrEmpty()) { bind(memory); return }
        request { token ->
            val file = "library-categories-${selected.id}-$mode.enc"
            val cached = runCatching { store.read(file)?.let { org.json.JSONObject(it.toString(Charsets.UTF_8)) }?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } }.getOrNull()
            if (!force && !cached.isNullOrEmpty()) { ui(token) { bind(cached) }; return@request }
            val names = runCatching { VodCatalog.categories(selected, mode).filterKeys { it.matches(Regex("[0-9]{1,20}")) } }.getOrDefault(emptyMap())
            if (names.isNotEmpty()) runCatching { store.write(file, org.json.JSONObject(names).toString().toByteArray()) }
            ui(token) { if (names.isEmpty()) bulkCatalog(selected, mode, force) else bind(names) }
        }
    }
    private fun scopedCatalog(selected: Source, mode: String, root: String, scope: String, names: Map<String, String>, query: String, force: Boolean) {
        val key = "$root:category:$scope"
        catalogImports.filterKeys { it.startsWith("$root:category:") && it != key }.values.forEach { if (!it.isDone) it.cancel(true) }
        activeCatalog = key; activeGroup = null; activeQuery = query
        val title = names[scope].orEmpty()
        fun display(snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
            activeSnapshot = snapshot; catalogImporting = busy
            panel.page(title, "${snapshot.count} titles${if (busy) " · Loading more…" else " · Saved on this TV"}", busy)
            loadCatalogPage(null, activeQuery, false)
        }
        val memory = catalogs[key]
        if (memory != null) display(memory, !memory.complete) else { panel.catalogPage(emptyList(), false, false); panel.catalogBusy(); panel.page(title, "Loading this category…", true) }
        if (!force && catalogImports[key]?.isDone == false) return
        if (!force && memory?.complete == true && System.currentTimeMillis() - memory.updated in 0 until 6 * 3600000L) return
        catalogImports[key]?.cancel(true)
        val generation = ++catalogGeneration; catalogGenerations[key] = generation
        fun deliver(snapshot: LibraryCatalog.Snapshot, busy: Boolean) { handler.post {
            if (!destroyed && catalogGenerations[key] == generation) {
                catalogs[key] = snapshot
                if (opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) display(snapshot, busy)
            }
        } }
        catalogImports[key] = worker.submit {
            val cached = memory?.takeIf { it.complete } ?: runCatching { repository.cached(selected, mode, scope) }.getOrNull()
            if (cached != null) deliver(cached, false)
            if (!force && cached != null && System.currentTimeMillis() - cached.updated in 0 until 6 * 3600000L) return@submit
            runCatching {
                val writer = repository.Writer(selected, mode, scope = scope); var batches = 0
                VodCatalog.stream(selected, mode, scope) { batch ->
                    writer.append(batch); batches++
                    if (cached == null && batches % 4 == 1) deliver(writer.snapshot(names), true)
                }
                writer.finish(names)
            }.onSuccess { deliver(it, false) }.onFailure { failure -> handler.post {
                if (!destroyed && opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) panel.page(title, "${if (cached != null) "Using saved category · " else ""}${SourceErrors.message(failure)} · Refresh to retry", false)
            } }
        }
    }
    private fun bulkCatalog(selected: Source, mode: String, force: Boolean) {
        val key = "${selected.id}:$mode"; val title = if (mode == "movie") "Movies" else "Series"
        val memory = catalogs[key]
        activeCatalog = key
        if (!force && catalogImports[key]?.isDone == false) {
            if (memory != null) activateCatalog(key, memory, !memory.complete)
            else panel.page(title, "Loading titles in the background…", true)
            return
        }
        if (!force && memory?.complete == true && System.currentTimeMillis() - memory.updated in 0 until 6 * 3600000L) {
            activateCatalog(key, memory, false); return
        }
        if (memory == null) { panel.submit(emptyList()); panel.page(title, "Loading saved library…", true); panel.focusRows() }
        else activateCatalog(key, memory, true)
        catalogImports[key]?.cancel(true)
        val generation = ++catalogGeneration; catalogGenerations[key] = generation
        fun deliver(snapshot: LibraryCatalog.Snapshot, busy: Boolean) { handler.post {
            if (!destroyed && catalogGenerations[key] == generation) {
                catalogs[key] = snapshot
                if (opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) activateCatalog(key, snapshot, busy)
            }
        } }
        catalogImports[key] = worker.submit {
            val began = android.os.SystemClock.elapsedRealtime()
            val cached = memory?.takeIf { it.complete } ?: runCatching { repository.cached(selected, mode) }.getOrNull()
            if (cached != null) deliver(cached, force || System.currentTimeMillis() - cached.updated >= 6 * 3600000L)
            if (!force && cached != null && System.currentTimeMillis() - cached.updated in 0 until 6 * 3600000L) return@submit
            val categories = disk.submit<Map<String, String>> { runCatching { VodCatalog.categories(selected, mode) }.getOrDefault(emptyMap()) }
            runCatching {
                val writer = repository.Writer(selected, mode)
                var first = true
                VodCatalog.stream(selected, mode) { batch ->
                    writer.append(batch)
                    if (first && cached == null) {
                        first = false
                        val partial = writer.snapshot(emptyMap())
                        deliver(partial, true)
                        if (BuildConfig.DEBUG) android.util.Log.d("SpareLibrary", "First catalog page: ${android.os.SystemClock.elapsedRealtime() - began} ms")
                    }
                }
                val names = try { categories.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                catch (cancelled: InterruptedException) { Thread.currentThread().interrupt(); throw cancelled }
                catch (_: Exception) { emptyMap() }
                writer.finish(names)
            }.onSuccess { snapshot ->
                if (BuildConfig.DEBUG) android.util.Log.d("SpareLibrary", "Catalog import: ${android.os.SystemClock.elapsedRealtime() - began} ms, ${snapshot.count} titles")
                deliver(snapshot, false)
            }.onFailure { failure -> handler.post {
                if (!destroyed && catalogGenerations[key] == generation && opened && activeCatalog == key && detailItem == null && parentSeries == null) {
                    catalogImporting = false
                    panel.page(title, (if (cached != null) "Using saved library · " else "Library unavailable · ") + SourceErrors.message(failure) + " · Refresh to retry.", false)
                }
            } }
            categories.cancel(true)
        }
    }
    private fun activateCatalog(key: String, snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
        activeCatalog = key; activeSnapshot = snapshot; catalogImporting = busy
        panel.page(if (kind == "movie") "Movies" else "Series", "${snapshot.count} titles" + if (busy) " · Loading more in the background…" else "", busy)
        panel.bindCatalog(key, snapshot.categories, { group, query -> loadCatalogPage(group, query, false) }, { loadCatalogPage(activeGroup, activeQuery, true) })
    }
    private fun browserRow(item: OnDemand) = BrowserRow(item.id, item.title,
        if (item.kind == "episode") "Episode ${item.episode} · ${item.runtime}" else listOf(item.year, item.category).filter { it.isNotBlank() }.joinToString(" · "),
        poster = item.poster, description = item.description) { select(item) }
    private fun loadCatalogPage(group: String?, query: String, more: Boolean) {
        val snapshot = activeSnapshot ?: return
        val catalogKey = activeCatalog; val stateKey = "$catalogKey:${group.orEmpty()}:$query"
        val version = "${snapshot.updated}:${snapshot.count}:${snapshot.labels.hashCode()}"
        activeGroup = group; activeQuery = query
        pageTask?.cancel(true); val token = ++pageEpoch
        val old = browsePages[stateKey]?.takeIf { it.version == version }
        if (!more && old != null) { panel.catalogPage(old.rows, false, old.more); return }
        if (more && old?.more == false) return
        panel.catalogBusy()
        pageTask = catalogReader.submit {
            runCatching {
                val page = repository.query(snapshot, group, query, if (more) old?.cursor ?: 0 else 0)
                BrowsePage(version, (if (more) old?.rows.orEmpty() else emptyList()) + page.entries.map(::browserRow), page.cursor, page.more)
            }.onSuccess { page -> handler.post {
                if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null && parentSeries == null) {
                    browsePages[stateKey] = page
                    while (browsePages.size > 8) browsePages.remove(browsePages.keys.first())
                    panel.catalogPage(page.rows, more, page.more)
                    panel.page(if (kind == "movie") "Movies" else "Series", "${page.rows.size} titles shown · ${snapshot.count} in library" + if (catalogImporting) " · Loading more…" else "", catalogImporting)
                }
            } }.onFailure { handler.post { if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null) panel.page("Library", "Could not read this page · Refresh to retry.", false) } }
        }
    }
    private fun showItems(items: List<OnDemand>, title: String, detail: String, busy: Boolean) {
        panel.page(title, detail, busy)
        panel.submit(items.map(::browserRow), items.associate { it.id to it.category }, reset = false, posters = items.firstOrNull()?.kind != "episode")
    }
    private fun select(item: OnDemand) {
        val selected = source() ?: return
        pageTask?.cancel(true); ++pageEpoch; panel.stopCatalog()
        if (item.kind == "series") { episodes(selected, item); return }
        if (item.kind == "movie") { openDetails(selected, item); return }
        playSelected(item)
    }
    private fun playSelected(item: OnDemand) {
        val selected = source() ?: return
        task?.cancel(true); val token = ++epoch
        positions.execute {
            val position = runCatching { store.watchProgress(selected).firstOrNull { it.item.id == item.id }?.position ?: 0L }.getOrDefault(0L)
            ui(token) { playingEpisodes = if (item.kind == "episode") episodeList.filter { it.seriesId == item.seriesId } else emptyList(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; play(item, position) }
        }
    }
    private fun openDetails(selected: Source, item: OnDemand) {
        val key = "${selected.id}:${item.id}"; detailItem = details[key] ?: item
        fun render(value: OnDemand, busy: Boolean) { detailItem = value; panel.detail(value, button, { playSelected(detailItem ?: value) }, { closeDetails() }, busy) }
        render(detailItem!!, details[key] == null)
        if (details[key] != null) return
        request { token ->
            var loaded = runCatching { VodCatalog.details(selected, item) }.getOrDefault(item)
            val addon = runCatching { store.metadataAddon() }.getOrDefault("")
            val provider = loaded
            ui(token) { details[key] = provider; while (details.size > 200) details.remove(details.keys.first()); render(provider, false) }
            if (addon.isNotBlank()) {
                loaded = runCatching { MetadataAddon.enrich(addon, provider) }.getOrDefault(provider)
                val enriched = loaded
                ui(token) { details[key] = enriched; render(enriched, false) }
            }
        }
    }
    private fun closeDetails() {
        task?.cancel(true); ++epoch; detailItem = null; panel.browse()
        val selected = source() ?: return
        if (kind == "continue") continueWatching(selected) else catalog(selected, kind, false)
    }
    private fun episodes(selected: Source, series: OnDemand) {
        detailItem = null; parentSeries = series
        val cacheKey = "${selected.id}:${series.id}"
        episodeCache[cacheKey]?.let { entries -> episodeList = entries; showItems(entries, series.title, series.description.ifBlank { "${entries.size} episodes · Back returns to Series" }, false); panel.focusRows(); return }
        panel.submit(emptyList()); panel.page(series.title, "Loading seasons and episodes…", true)
        request { token -> runCatching { VodCatalog.episodes(selected, series) }.onSuccess { entries -> ui(token) {
            if (entries.size <= 2000) { episodeCache[cacheKey] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) }
            episodeList = entries; showItems(entries, series.title, series.description.ifBlank { "${entries.size} episodes · Back returns to Series" }, false); panel.focusRows()
            worker.execute {
                val url = runCatching { store.metadataAddon() }.getOrDefault("")
                if (url.isNotBlank()) runCatching { MetadataAddon.enrich(url, series) }.onSuccess { enriched -> ui(token) { if (enriched.description.isNotBlank()) panel.page(series.title, enriched.description, false) } }
            }
        } }.onFailure { ui(token) { panel.page(series.title, "Episodes unavailable · ${SourceErrors.message(it)} · Refresh to retry.", false); panel.focusRows() } } }
    }
    private fun continueWatching(selected: Source) {
        panel.submit(emptyList()); panel.page("Continue watching", "Loading saved positions…", true)
        request { token ->
            val entries = runCatching { store.watchProgress(selected).sortedByDescending { it.updated } }.getOrDefault(emptyList())
            ui(token) {
                panel.page("Continue watching", "Saved on this TV for ${selected.name}. Finished titles disappear automatically.", false)
                panel.submit(entries.map { entry -> BrowserRow(entry.item.id, entry.item.title, "Resume at ${duration(entry.position)}", poster = entry.item.poster) { select(entry.item) } }); panel.focusRows()
            }
        }
    }
    private fun catchupChannels() {
        task?.cancel(true); ++epoch; parentSeries = null; archive = null
        val channels = guide().channels.filter { Catchup.supported(it) }
        panel.page("Catch-up", if (channels.isEmpty()) "This playlist has no advertised archives. Refresh the playlist if archive support was recently added." else "Choose a channel to browse its provider archive. Only completed programmes are listed.", false)
        panel.submit(channels.map { channel -> BrowserRow(channel.id, channel.name, "${channel.catchupDays} days · ${channel.group}") { openCatchup(channel) } }, channels.associate { it.id to it.group }, posters = false); panel.focusRows()
    }
    fun openCatchup(channel: Channel) {
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        kind = "catchup"; archive = channel; parentSeries = null; panel.resumeImages(); panel.visibility = View.VISIBLE
        task?.cancel(true); ++epoch
        val selected = source() ?: return
        fun show(entries: List<Programme>, message: String, busy: Boolean) {
            val now = System.currentTimeMillis()
            val items = entries.asSequence().filter { Catchup.available(channel, it, now) }.sortedByDescending { it.start }.mapNotNull { programme ->
                Catchup.url(channel, programme, selected.allowHttp, now)?.let { url ->
                    programme to OnDemand("catchup:${channel.id}:${programme.start}", programme.title, channel.name, url, "catchup", programme.description)
                }
            }.toList()
            panel.page("Catch-up · ${channel.name}", message, busy)
            panel.submit(items.map { (programme, item) -> BrowserRow(item.id, item.title, "${GuideClock.dayTime(programme.start)}–${GuideClock.time(programme.end)}") { select(item) } }, items.associate { (programme, item) -> item.id to java.time.Instant.ofEpochMilli(programme.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }, posters = false)
        }
        val cached = guide().programmes[guide().key(channel)].orEmpty()
        show(cached, if (selected.kind == "xtream") "Loading provider archive…" else "Archive availability depends on the provider and XMLTV history.", selected.kind == "xtream"); panel.focusRows()
        if (selected.kind != "xtream") return
        request { token -> runCatching { Catchup.load(selected, channel) }.onSuccess { entries -> ui(token) { show(entries, "${channel.catchupDays}-day provider archive · Back returns to channels", false); panel.focusRows() } }
            .onFailure { failure -> ui(token) { show(cached, "Using saved guide · ${SourceErrors.message(failure)} · Refresh to retry.", false) } } }
    }
    fun clearMetadata() { details.clear() }
    fun reopen() { open(kind) }
    fun next(item: OnDemand) = playingEpisodes.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { playingEpisodes.getOrNull(it + 1) }
    fun refresh() { val selected = source() ?: return; when { archive != null -> openCatchup(archive!!); parentSeries != null -> episodes(selected, parentSeries!!); kind in setOf("movie", "series") && selected.kind == "xtream" -> catalog(selected, kind, true); else -> open(kind) } }
    fun back() { when { detailItem != null -> closeDetails(); archive != null -> catchupChannels(); parentSeries != null -> open("series"); else -> { dismiss(); closed() } } }
    fun dismiss() { pageTask?.cancel(true); ++pageEpoch; panel.stopCatalog(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; task?.cancel(true); ++epoch }
    fun destroy() { catalogImports.values.forEach { it.cancel(true) }; pageTask?.cancel(true); catalogReader.shutdownNow(); panel.closeImages(); disk.shutdown(); positions.shutdownNow(); destroyed = true; task?.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object { fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) } }
}

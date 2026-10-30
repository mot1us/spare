package io.github.mot1us.spare

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.core.view.isVisible
import java.util.concurrent.Executors

/** Provider I/O stays off the UI thread; share the restored/background title index with search. */
class LibraryController(private val activity: Activity, private val store: SecureStore, private val button: (String, () -> Unit) -> Button,
    private val source: () -> Source?, private val guide: () -> Guide, private val play: (OnDemand, Long) -> Unit,
    private val closed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private var task: java.util.concurrent.Future<*>? = null
    private var epoch = 0
    private var kind = "movie"
    private var parentSeries: OnDemand? = null
    private var archive: Channel? = null
    private var directCatchup = false
    private var fromBeginning = false
    private var startTask: java.util.concurrent.Future<*>? = null
    private var startEpoch = 0
    private var startEpisode: Pair<Int, Int>? = null
    var catchupClosed: (() -> Unit)? = null
    var archiveLoaded: ((Source, Channel, List<Programme>) -> Unit)? = null
    private var episodeList = emptyList<OnDemand>()
    private var playingEpisodes = emptyList<OnDemand>()
    private var destroyed = false
    @Volatile private var favoriteState = LibraryFavorites()
    private var favoriteSource = ""
    private val favoriteWorker = Executors.newSingleThreadExecutor()
    private var favoriteTask: java.util.concurrent.Future<*>? = null
    private var favoriteEpoch = 0
    private var checkingFavorites = false
    private val disk = Executors.newSingleThreadExecutor()
    private val positions = Executors.newSingleThreadExecutor()
    private val episodeCache = linkedMapOf<String, List<OnDemand>>()
    private val catalogs = linkedMapOf<String, LibraryCatalog.Snapshot>()
    val repository = LibraryCatalog(store)
    var catalogChanged: (() -> Unit)? = null
    var refreshCatalogs: ((String) -> Unit)? = null
    private val indexChanged = Runnable {
        if (!destroyed) {
            catalogChanged?.invoke()
            val selected = source()
            if (selected != null && opened && kind in setOf("movie", "series") && activeCatalog == "${selected.id}:$kind" && detailItem == null && parentSeries == null) {
                repository.memory(selected, kind)?.let { activateCatalog(activeCatalog, it.copy(labels = categoryNames[activeCatalog] ?: it.labels), !it.complete) }
            }
        }
    }
    init { repository.changed = { handler.removeCallbacks(indexChanged); handler.postDelayed(indexChanged, 250) } }
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
    private var activeCatalogTitle = "Library"
    private data class BrowsePage(val version: String, val rows: List<BrowserRow>, val cursor: Int, val more: Boolean, val available: Int)
    private val browsePages = linkedMapOf<String, BrowsePage>()
    private val posterMetadata = PosterMetadata(store)
    private val details = linkedMapOf<String, OnDemand>()
    private var detailItem: OnDemand? = null
    val panel = MediaBrowserPanel(activity, button, { open(it) }, { refresh() })
    init {
        panel.favoriteAction = { (detailItem ?: parentSeries)?.let(::toggleLibraryFavorite) }
        panel.markWatchedAction = {
            val selected = source()
            if (selected != null && parentSeries != null) { val ids = episodeList.filter { it.seriesId == parentSeries?.id }.map { it.id }
                val allWatched = ids.isNotEmpty() && ids.all { it in favoriteState.watched }
                editFavorites(selected) { if (allWatched) it.unwatched(ids) else it.completed(ids) }
            }
        }
    }
    val opened get() = panel.isVisible
    val browsingCatchup get() = kind == "catchup"
    private fun presentFavorites() {
        panel.favorites(favoriteState.entries.map { it.item.id }.toSet(), favoriteState.entries.filter { it.item.kind == "series" }.associate { it.item.id to it.label(favoriteState.watched) })
        val item = detailItem ?: parentSeries
        panel.favoriteTitle(item, item?.let { favoriteState.contains(it.id) } == true, episodeList.isNotEmpty(), episodeList.isNotEmpty() && episodeList.all { it.id in favoriteState.watched })
        if (opened && kind == "favorites" && item == null) displayFavorites()
    }
    private fun prepareFavorites(selected: Source) {
        if (favoriteSource != selected.id) { favoriteSource = selected.id; favoriteState = LibraryFavorites(); presentFavorites() }
        disk.execute {
            val saved = runCatching { store.libraryFavorites(selected) }.getOrDefault(LibraryFavorites())
            handler.post { if (!destroyed && source()?.id == selected.id && favoriteSource == selected.id) {
                favoriteState = saved; presentFavorites(); if (opened && kind == "favorites") checkFavoriteEpisodes(selected)
            } }
        }
    }
    private fun editFavorites(selected: Source, change: (LibraryFavorites) -> LibraryFavorites) {
        runCatching { change(favoriteState) }.onSuccess { favoriteState = it; presentFavorites() }.onFailure {
            android.widget.Toast.makeText(activity, it.message ?: "Could not change Watchlist", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        disk.execute {
            val result = runCatching { store.editLibraryFavorites(selected, change) }
            handler.post { if (!destroyed && source()?.id == selected.id) {
                result.onSuccess { favoriteState = it; presentFavorites() }.onFailure { android.widget.Toast.makeText(activity, "Could not save Watchlist", android.widget.Toast.LENGTH_SHORT).show() }
            } }
        }
    }
    private fun toggleLibraryFavorite(item: OnDemand) {
        val selected = source() ?: return
        val enabled = !favoriteState.contains(item.id)
        val episodes = episodeList.takeIf { parentSeries?.id == item.id }
        editFavorites(selected) { state -> state.save(item, enabled).let { if (enabled && episodes != null) it.update(item, episodes, System.currentTimeMillis()) else it } }
        panel.confirmation((if (enabled) "Added to Watchlist · " else "Removed from Watchlist · ") + item.title)
    }
    private fun displayFavorites(reset: Boolean = false) {
        val entries = favoriteState.entries
        panel.page("Watchlist", if (checkingFavorites) "Checking saved series for new episodes…" else "${entries.size} saved movies and series · Hold OK on a title to add it", checkingFavorites)
        panel.submit(entries.map { entry -> browserRow(entry.item).copy(detail = entry.label(favoriteState.watched)) }, entries.associate { it.item.id to if (it.item.kind == "series") "Series" else "Movies" }, reset = reset)
    }
    private fun checkFavoriteEpisodes(selected: Source, force: Boolean = false) {
        if (checkingFavorites || selected.kind != "xtream") return
        val now = System.currentTimeMillis()
        val due = favoriteState.entries.filter { it.item.kind == "series" && (force || now - it.checked !in 0 until 15 * 60000L) }.sortedBy { it.checked }.take(24)
        if (due.isEmpty()) return
        checkingFavorites = true; if (kind == "favorites" && parentSeries == null && detailItem == null) displayFavorites()
        val token = ++favoriteEpoch
        favoriteTask = favoriteWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val deadline = System.nanoTime() + 20_000_000_000L
            for (entry in due) {
                if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) break
                val result = runCatching {
                    val episodes = VodCatalog.episodes(selected, entry.item, 3000)
                    store.editLibraryFavorites(selected) { it.update(entry.item, episodes, System.currentTimeMillis()) }
                }
                handler.post { if (!destroyed && token == favoriteEpoch && source()?.id == selected.id) result.onSuccess { favoriteState = it; presentFavorites() } }
            }
            handler.post { if (!destroyed && token == favoriteEpoch) { checkingFavorites = false; presentFavorites() } }
        }
    }
    fun completed(item: OnDemand) { if (item.kind == "episode" && item.id !in favoriteState.watched) { favoriteState = favoriteState.completed(listOf(item.id)); presentFavorites() } }
    fun knownEpisodes(selected: Source) = episodeCache.filterKeys { it.startsWith("${selected.id}:") }.values.flatten()
    fun knownTitles(selected: Source) = details.filterKeys { it.startsWith("${selected.id}:") }.values.toList() + knownEpisodes(selected)
    /** Resolve the exact broadcast episode without opening or focusing the Library. */
    fun resolveEpisode(series: OnDemand, season: Int?, episode: Int?, result: (Result<OnDemand>) -> Unit) {
        cancelStart()
        val selected = source() ?: return
        val key = "${selected.id}:${series.id}"; val cached = episodeCache[key]
        val token = startEpoch
        startTask = positions.submit {
            val loaded = runCatching {
                require(season != null && episode != null) { "Choose an episode in Library to start this series." }
                val entries = cached ?: VodCatalog.episodes(selected, series)
                val item = entries.singleOrNull { it.season == season && it.episode == episode }
                    ?: error("This episode is not available in your provider's Library.")
                entries to item
            }
            handler.post {
                if (destroyed || token != startEpoch || source()?.id != selected.id) return@post
                loaded.onSuccess { (entries, _) ->
                    if (entries.size <= 2000) { episodeCache[key] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) }
                    playingEpisodes = entries.filter { it.seriesId == series.id }
                    parentSeries = series; episodeList = entries; detailItem = null; kind = "series"
                }
                result(loaded.map { it.second })
            }
        }
    }
    fun cancelStart() { ++startEpoch; startTask?.cancel(true); startTask = null }
    fun openSearchResult(item: OnDemand, restart: Boolean = false, season: Int? = null, episode: Int? = null) {
        val selected = source() ?: return
        prepareFavorites(selected)
        dismiss(); kind = if (item.kind == "movie") "movie" else "series"
        fromBeginning = restart; startEpisode = if (restart && season != null && episode != null) season to episode else null
        directCatchup = false; archive = null; detailItem = null; parentSeries = null
        panel.archiveMode(false)
        panel.selectTab(kind); panel.allowArtworkHttp(selected.allowHttp); panel.browse(); panel.resumeImages(); panel.visibility = View.VISIBLE
        episodeList = knownEpisodes(selected).filter { it.seriesId == item.seriesId }
        select(item)
    }
    private fun startRequestedEpisode(entries: List<OnDemand>): Boolean {
        val target = startEpisode ?: return false
        startEpisode = null
        val item = entries.singleOrNull { it.season == target.first && it.episode == target.second } ?: return false
        episodeList = entries; playSelected(item); return true
    }
    fun open(mode: String = if (kind == "catchup") "movie" else kind) {
        panel.archiveMode(mode == "catchup")
        fromBeginning = false; startEpisode = null
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        panel.selectTab(mode); panel.seriesArtwork(null)
        directCatchup = false; parentSeries = null; archive = null; detailItem = null; kind = mode; panel.resumeImages(); panel.visibility = View.VISIBLE
        val selected = source()
        panel.favoriteTitle(null, false)
        task?.cancel(true); ++epoch; panel.browse(); panel.allowArtworkHttp(selected?.allowHttp == true)
        if (selected == null) { panel.submit(emptyList()); panel.page("Library", "Add a playlist in Settings first.", false); panel.focusRows(); return }
        prepareFavorites(selected)
        if (mode == "favorites") { displayFavorites(reset = true); checkFavoriteEpisodes(selected); panel.focusRows(); return }
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
            repository.memory(selected, mode)?.takeIf { it.chunks.any { chunk -> chunk.titles.isNotEmpty() } }?.let {
                activateCatalog(root, it.copy(labels = names), !it.complete); return
            }
            panel.page(title, "Choose a category · Only the selected category is downloaded", false)
            var refreshPending = force
            panel.bindCatalog(root, names, { group, query ->
                val scope = group ?: names.keys.first(); val reload = refreshPending; refreshPending = false
                scopedCatalog(selected, mode, root, scope, names, query, reload)
            }, { loadCatalogPage(null, activeQuery, true) }, "Featured")
        }
        val memory = categoryNames[root]
        if (!force && !memory.isNullOrEmpty()) { bind(memory); return }
        if (!force) repository.memory(selected, mode)?.let { activateCatalog(root, it, !it.complete) }
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
        catalogImports.filterKeys { it.startsWith("$root:category:") && it != key }.forEach { (oldKey, task) ->
            if (!task.isDone) { catalogGenerations.remove(oldKey); task.cancel(true) }
        }
        if (activeCatalog != key || activeQuery != query) {
            pageTask?.cancel(true); ++pageEpoch; activeSnapshot = null; panel.catalogBusy(block = true)
        }
        activeCatalog = key; activeGroup = null; activeQuery = query
        val title = names[scope].orEmpty()
        activeCatalogTitle = title
        fun display(snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
            activeSnapshot = snapshot; catalogImporting = busy
            panel.page(title, "${snapshot.count} titles${if (busy) " · Loading more…" else " · Saved on this TV"}", busy)
            loadCatalogPage(null, activeQuery, false)
        }
        val memory = catalogs[key]
        if (memory != null) display(memory, !memory.complete) else { panel.catalogBusy(block = true); panel.page(title, "Loading this category…", true) }
        if (!force && catalogImports[key]?.isDone == false) return
        if (!force && memory?.complete == true && System.currentTimeMillis() - memory.updated in 0 until 6 * 3600000L) return
        catalogImports[key]?.cancel(true)
        val generation = ++catalogGeneration; catalogGenerations[key] = generation
        fun deliver(snapshot: LibraryCatalog.Snapshot, busy: Boolean) { handler.post {
            if (!destroyed && catalogGenerations[key] == generation) {
                val prior = catalogs[key]
                // A restarted import must not replace a useful page with a smaller partial page.
                if (!snapshot.complete && prior != null && snapshot.count < prior.count) return@post
                catalogs[key] = snapshot
                if (opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) display(snapshot, busy)
            }
        } }
        catalogImports[key] = worker.submit {
            val cached = memory?.takeIf { it.complete } ?: runCatching {
                repository.cached(selected, mode, scope) ?: repository.cached(selected, mode)?.takeIf { full ->
                    full.chunks.any { "Category $scope" in it.groups }
                }?.copy(labels = names, scopeGroup = "Category $scope")
            }.getOrNull()
            if (cached != null) deliver(cached, !cached.complete)
            if (!force && cached?.complete == true && System.currentTimeMillis() - cached.updated in 0 until 6 * 3600000L) return@submit
            runCatching {
                val writer = repository.Writer(selected, mode, scope = scope); var publishedAt = 0L
                VodCatalog.stream(selected, mode, scope) { batch ->
                    writer.append(batch)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (cached == null && now - publishedAt >= 750) { publishedAt = now; deliver(writer.snapshot(names), true) }
                }
                writer.finish(names)
            }.onSuccess { deliver(it, false) }.onFailure { failure -> handler.post {
                if (!destroyed && catalogGenerations[key] == generation && opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) panel.page(title, "${if (cached != null) "Using saved category · " else ""}${SourceErrors.message(failure)} · Refresh to retry", false)
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
            if (cached != null) deliver(cached, force || !cached.complete || System.currentTimeMillis() - cached.updated >= 6 * 3600000L)
            if (!force && cached?.complete == true && System.currentTimeMillis() - cached.updated in 0 until 6 * 3600000L) return@submit
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
        activeCatalogTitle = if (kind == "movie") "Movies" else "Series"
        panel.page(if (kind == "movie") "Movies" else "Series", "${snapshot.count} titles" + if (busy) " · Loading more in the background…" else "", busy)
        val groups = snapshot.categories + snapshot.labels.mapKeys { "Category ${it.key}" }
        panel.bindCatalog(key, groups, { group, query ->
            val selected = source(); val scope = group?.removePrefix("Category ")
            if (selected != null && scope != null && scope.matches(Regex("[0-9]{1,20}")) && snapshot.chunks.none { group in it.groups })
                scopedCatalog(selected, kind, key, scope, snapshot.labels, query, false)
            else { activeCatalog = key; activeSnapshot = snapshot; loadCatalogPage(group, query, false) }
        }, { loadCatalogPage(activeGroup, activeQuery, true) })
    }
    private fun browserRow(item: OnDemand) = BrowserRow(item.id, item.title,
        if (item.kind == "episode") "Episode ${item.episode} · ${item.runtime}" else listOf(item.year, item.category).filter { it.isNotBlank() }.joinToString(" · "),
        poster = item.poster, description = item.description, requestPoster = { done -> posterMetadata.request(item, done) }, longPress = if (item.kind in setOf("movie", "series")) ({ toggleLibraryFavorite(item) }) else null) { select(posterMetadata.known(item)) }
    private fun loadCatalogPage(group: String?, query: String, more: Boolean) {
        val snapshot = activeSnapshot ?: return
        val catalogKey = activeCatalog; val stateKey = "$catalogKey:${group.orEmpty()}:$query"
        val version = "${snapshot.updated}:${snapshot.labels.hashCode()}"
        activeGroup = group; activeQuery = query
        pageTask?.cancel(true); val token = ++pageEpoch
        val old = browsePages[stateKey]?.takeIf { it.version == version }
        if (!more && old != null && (old.rows.size >= 80 || old.available == snapshot.count)) {
            val updated = old.copy(more = old.cursor < snapshot.count, available = snapshot.count)
            browsePages[stateKey] = updated; panel.catalogPage(updated.rows, false, updated.more); return
        }
        if (more && old?.more == false && old.available == snapshot.count) return
        panel.catalogBusy()
        pageTask = catalogReader.submit {
            runCatching {
                val page = repository.query(snapshot, group, query, if (more) old?.cursor ?: 0 else 0)
                BrowsePage(version, (if (more) old?.rows.orEmpty() else emptyList()) + page.entries.map(::browserRow), page.cursor, page.more, snapshot.count)
            }.onSuccess { page -> handler.post {
                if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null && parentSeries == null) {
                    browsePages[stateKey] = page
                    while (browsePages.size > 8) browsePages.remove(browsePages.keys.first())
                    panel.catalogPage(page.rows, more, page.more)
                    panel.page(activeCatalogTitle, "${page.rows.size} titles shown · ${snapshot.count} in library" + if (catalogImporting) " · Loading more…" else "", catalogImporting)
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
        favoriteTask?.cancel(true); ++favoriteEpoch; checkingFavorites = false
        val selected = source() ?: return
        task?.cancel(true); val token = ++epoch
        positions.execute {
            val position = if (fromBeginning) 0L else runCatching { store.watchProgress(selected).firstOrNull { it.item.id == item.id }?.position ?: 0L }.getOrDefault(0L)
            ui(token) { playingEpisodes = if (item.kind == "episode") episodeList.filter { it.seriesId == item.seriesId } else emptyList(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; play(item, position) }
        }
    }
    private fun openDetails(selected: Source, item: OnDemand) {
        val key = "${selected.id}:${item.id}"; detailItem = details[key] ?: item
        fun render(value: OnDemand, busy: Boolean) { detailItem = value; panel.detail(value, button, { playSelected(detailItem ?: value) }, { closeDetails() }, busy); presentFavorites() }
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
        when {
            kind == "favorites" -> { displayFavorites(); panel.favoriteTitle(null, false) }
            kind == "continue" -> continueWatching(selected)
            selected.kind == "xtream" -> catalog(selected, kind, false)
            else -> open(kind)
        }
    }
    private fun episodes(selected: Source, series: OnDemand, force: Boolean = false) {
        if (parentSeries?.id != series.id) episodeList = emptyList()
        detailItem = null; parentSeries = series
        presentFavorites()
        val cacheKey = "${selected.id}:${series.id}"
        val cached = episodeCache[cacheKey]
        if (cached != null) {
            if (startRequestedEpisode(cached)) return
            episodeList = cached; showItems(cached, series.title, series.description.ifBlank { "${cached.size} episodes · Back returns to Series" }, force); panel.seriesArtwork(posterMetadata.known(series)); presentFavorites()
            if (!force) { panel.focusRows(); return }
        } else { panel.submit(emptyList()); panel.page(series.title, "Loading seasons and episodes…", true) }
        request { token -> runCatching { VodCatalog.episodes(selected, series) }.onSuccess { entries -> ui(token) {
            if (entries.size <= 2000) { episodeCache[cacheKey] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) } else episodeCache.remove(cacheKey)
            if (startRequestedEpisode(entries)) return@ui
            episodeList = entries; showItems(entries, series.title, series.description.ifBlank { "${entries.size} episodes · Back returns to Series" }, false); panel.seriesArtwork(posterMetadata.known(series)); presentFavorites(); if (!force) panel.focusRows()
            if (favoriteState.contains(series.id)) editFavorites(selected) { it.update(series, entries, System.currentTimeMillis()) }
            worker.execute {
                val url = runCatching { store.metadataAddon() }.getOrDefault("")
                if (url.isNotBlank()) runCatching { MetadataAddon.enrich(url, series) }.onSuccess { enriched -> ui(token) { panel.seriesArtwork(enriched); if (enriched.description.isNotBlank()) panel.page(series.title, enriched.description, false) } }
            }
        } }.onFailure { ui(token) { panel.page(series.title, "${if (cached != null) "Using saved episodes · " else "Episodes unavailable · "}${SourceErrors.message(it)} · Refresh to retry.", false); if (!force) panel.focusRows() } } }
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
        panel.favoriteTitle(null, false); panel.archiveMode(true)
        val channels = Catchup.channels(guide())
        panel.page("Catch-up", if (channels.isEmpty()) "This playlist has no advertised archives. Refresh the playlist if archive support was recently added." else "${channels.size} catch-up channels in your playlist · Choose a channel to browse its provider archive.", false)
        panel.submit(channels.map { channel -> BrowserRow(channel.id, channel.name, "${channel.catchupDays} days · ${channel.group}") { openCatchup(channel) } }, channels.associate { it.id to it.group }, posters = false); panel.focusRows()
    }
    fun openCatchup(channel: Channel, direct: Boolean = false) {
        directCatchup = direct
        detailItem = null; panel.favoriteTitle(null, false); panel.archiveMode(true)
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        panel.selectTab("catchup"); panel.seriesArtwork(null)
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
        request { token -> runCatching { Catchup.load(selected, channel) }.onSuccess { entries -> ui(token) { archiveLoaded?.invoke(selected, channel, entries); show(GuideHistory.merge(cached, entries, channel.catchupDays, System.currentTimeMillis()), "${channel.catchupDays}-day provider archive · Back returns to channels", false); panel.focusRows() } }
            .onFailure { failure -> ui(token) { show(cached, "Using saved guide · ${SourceErrors.message(failure)} · Refresh to retry.", false) } } }
    }
    fun clearMetadata() { details.clear(); posterMetadata.clear(); panel.refreshPosters() }
    fun reopen(item: OnDemand? = null) { val selected = source()
        if (item?.kind == "movie") { open("movie"); return }
        val series = parentSeries?.takeIf { item == null || item.kind == "episode" && it.id == item.seriesId }
            ?: favoriteState.entries.firstOrNull { it.item.id == item?.seriesId }?.item
        if (selected != null && series != null) {
            panel.archiveMode(false)
            panel.browse(); panel.selectTab("series"); panel.resumeImages(); panel.visibility = View.VISIBLE; kind = "series"; prepareFavorites(selected); episodes(selected, series)
        } else open(if (item?.kind == "episode") "series" else kind)
    }
    fun next(item: OnDemand) = playingEpisodes.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { playingEpisodes.getOrNull(it + 1) }
    fun refresh() { val selected = source() ?: return; when { archive != null -> openCatchup(archive!!, directCatchup); parentSeries != null -> episodes(selected, parentSeries!!, force = true); kind == "favorites" -> { displayFavorites(); checkFavoriteEpisodes(selected, true) }; kind in setOf("movie", "series") && selected.kind == "xtream" -> { refreshCatalogs?.invoke(kind); catalog(selected, kind, true) }; else -> open(kind) } }
    fun back() { when { directCatchup -> { directCatchup = false; dismiss(); (catchupClosed ?: closed).invoke() }; detailItem != null -> closeDetails(); archive != null -> catchupChannels(); parentSeries != null -> open(if (kind == "favorites") "favorites" else "series"); else -> { dismiss(); closed() } } }
    fun dismiss() {
        favoriteTask?.cancel(true); ++favoriteEpoch; checkingFavorites = false
        fromBeginning = false; startEpisode = null; pageTask?.cancel(true); ++pageEpoch
        panel.stopCatalog(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; task?.cancel(true); ++epoch
        catalogImports.forEach { (key, import) -> if (!import.isDone) { catalogGenerations.remove(key); import.cancel(true) } }
    }
    fun destroy() { cancelStart(); favoriteTask?.cancel(true); favoriteWorker.shutdownNow(); posterMetadata.close(); catalogImports.values.forEach { it.cancel(true) }; pageTask?.cancel(true); catalogReader.shutdownNow(); panel.closeImages(); disk.shutdown(); positions.shutdownNow(); destroyed = true; task?.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object { fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) } }
}

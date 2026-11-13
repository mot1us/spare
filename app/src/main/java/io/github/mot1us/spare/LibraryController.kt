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
    private val closed: () -> Unit, private val resolveSource: (String) -> Source? = { id -> source()?.takeIf { it.id == id } },
    private val sourceFor: (Channel) -> Source? = { channel -> if (channel.playlistId.isBlank()) source() else resolveSource(channel.playlistId) }) {
    private var playlist: String? = null
    private fun selectedSource(): Source? = playlist?.let(resolveSource) ?: if (playlist == null) source() else null
    val currentSource get() = selectedSource()
    fun selectPlaylist(selected: Source?) { if (playlist != selected?.id) { task?.cancel(true); ++epoch; playlist = selected?.id; detailItem = null; parentSeries = null; favoriteSource = ""; shelfCategory = null } }
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
            val selected = selectedSource()
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
        panel.sortLabel(store.librarySort)
        panel.sortAction = {
            val sorts = LibraryCatalog.SORTS
            store.librarySort = sorts[(sorts.indexOf(store.librarySort) + 1) % sorts.size]
            if (kind in setOf("movie", "series") && activeSnapshot != null) loadCatalogPage(activeGroup, activeQuery, false)
            store.librarySort
        }
        panel.markWatchedAction = {
            val selected = selectedSource()
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
            handler.post { if (!destroyed && selectedSource()?.let { it.id == selected.id && it.providerKey() == selected.providerKey() } == true && favoriteSource == selected.id) {
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
            handler.post { if (!destroyed && selectedSource()?.let { it.id == selected.id && it.providerKey() == selected.providerKey() } == true) {
                result.onSuccess { favoriteState = it; presentFavorites() }.onFailure { android.widget.Toast.makeText(activity, "Could not save Watchlist", android.widget.Toast.LENGTH_SHORT).show() }
            } }
        }
    }
    private fun toggleLibraryFavorite(item: OnDemand) {
        val selected = selectedSource() ?: return
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
                handler.post { if (!destroyed && token == favoriteEpoch && selectedSource()?.let { it.id == selected.id && it.providerKey() == selected.providerKey() } == true) result.onSuccess { favoriteState = it; presentFavorites() } }
            }
            handler.post { if (!destroyed && token == favoriteEpoch) { checkingFavorites = false; presentFavorites() } }
        }
    }
    fun completed(item: OnDemand) {
        if (item.kind != "episode") return
        val owner = if (item.playlistId.isBlank()) selectedSource() else resolveSource(item.playlistId)
        if (owner != null && owner.id != favoriteSource) { disk.execute { runCatching { store.editLibraryFavorites(owner) { it.completed(listOf(item.id)) } } }; return }
        if (item.kind == "episode" && item.id !in favoriteState.watched) { favoriteState = favoriteState.completed(listOf(item.id)); presentFavorites() } }
    fun knownEpisodes(selected: Source) = episodeCache.filterKeys { it.startsWith("${selected.id}:") }.values.flatten()
    fun knownTitles(selected: Source) = details.filterKeys { it.startsWith("${selected.id}:") }.values.toList() + knownEpisodes(selected)
    /** Resolve the exact broadcast episode without opening or focusing the Library. */
    fun resolveEpisode(series: OnDemand, season: Int?, episode: Int?, result: (Result<OnDemand>) -> Unit) {
        if (series.playlistId.isNotBlank()) selectPlaylist(resolveSource(series.playlistId))
        cancelStart()
        val selected = selectedSource() ?: return
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
                if (destroyed || token != startEpoch || selectedSource()?.id != selected.id) return@post
                loaded.onSuccess { (entries, _) ->
                    if (entries.size <= 2000) { episodeCache[key] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) }
                    playingEpisodes = entries.filter { it.seriesId == series.id }.map { it.copy(playlistId = selected.id) }
                    parentSeries = series; episodeList = entries; detailItem = null; kind = "series"
                }
                result(loaded.map { it.second.copy(playlistId = selected.id) })
            }
        }
    }
    fun cancelStart() { ++startEpoch; startTask?.cancel(true); startTask = null }
    fun openSearchResult(item: OnDemand, restart: Boolean = false, season: Int? = null, episode: Int? = null) {
        if (item.playlistId.isNotBlank()) selectPlaylist(resolveSource(item.playlistId))
        val selected = selectedSource() ?: return
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
    fun open(mode: String = if (kind == "catchup") "movie" else kind, focusContent: Boolean = false, keepCategory: Boolean = false) {
        warmTask?.cancel(true); warmTask = null
        spotTask?.cancel(true)
        if (!keepCategory || mode != kind) shelfCategory = null
        // A hidden tab cannot keep a category download ahead of the selected tab.
        if (mode != kind) {
            catalogImports.values.forEach { it.cancel(true) }; catalogImports.clear(); catalogGenerations.clear()
        }
        panel.archiveMode(mode == "catchup")
        fromBeginning = false; startEpisode = null
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        panel.selectTab(mode); panel.seriesArtwork(null)
        directCatchup = false; parentSeries = null; archive = null; detailItem = null; kind = mode; returnToUpNext = false; panel.resumeImages(); panel.visibility = View.VISIBLE
        val selected = selectedSource()
        panel.favoriteTitle(null, false)
        task?.cancel(true); ++epoch; panel.browse(); panel.allowArtworkHttp(selected?.allowHttp == true)
        if (selected == null) { panel.submit(emptyList()); panel.page("Library", "Add a playlist in Settings first.", false); panel.focusRows(); return }
        prepareFavorites(selected)
        if (mode == "favorites") { displayFavorites(reset = true); checkFavoriteEpisodes(selected); panel.focusRows(); return }
        if (mode == "catchup") { catchupChannels(); return }
        if (mode == "continue") { continueWatching(selected); return }
        if (mode == "upnext") { upNext(selected); return }
        if (selected.kind != "xtream") {
            panel.submit(emptyList())
            val items = if (mode == "movie") guide().channels.filter { it.media }.map { OnDemand("m3u:${it.id}", it.name, it.group, it.url) } else emptyList()
            showItems(items, if (mode == "movie") "Movies" else "Series", "M3U movies require media=\"true\" entries. Series browsing requires an Xtream playlist.", false)
            if (focusContent) panel.focusRows()
            return
        }
        catalog(selected, mode, false, focusContent)
        if (focusContent) panel.requestContentFocus()
    }
    private fun request(job: (Int) -> Unit) { task?.cancel(true); val token = ++epoch; task = worker.submit { if (!destroyed) job(token) } }
    private fun ui(token: Int, work: () -> Unit) { handler.post { if (!destroyed && token == epoch && opened) work() } }
    private val categoryNames = mutableMapOf<String, Map<String, String>>()
    @Volatile private var openBegan = 0L
    @Volatile private var firstTitlesRecorded = false
    private fun catalog(selected: Source, mode: String, force: Boolean, focus: Boolean = false) {
        val root = "${selected.id}:$mode"; activeCatalog = root
        openBegan = Timings.now(); firstTitlesRecorded = false
        val title = if (mode == "movie") "Movies" else "Series"
        panel.page(title, "Loading categories…", true)
        fun bind(names: Map<String, String>) {
            categoryNames[root] = names
            val scope = shelfCategory?.takeIf { it in names } ?: selectedCategories[root]?.takeIf { it in names } ?: names.keys.first()
            openCategory(selected, mode, root, names, scope, force, focus)
        }
        val memory = categoryNames[root]
        if (!force && !memory.isNullOrEmpty()) { bind(memory); return }
        request { token ->
            val names = categoriesFor(selected, mode, force)
            Timings.since("Category list", openBegan)
            ui(token) { if (names.isEmpty()) bulkCatalog(selected, mode, force) else bind(names) }
        }
    }
    private fun categoriesFor(selected: Source, mode: String, force: Boolean): Map<String, String> {
        val file = "library-categories-${selected.id}-${selected.providerKey().take(16)}-$mode.enc"
        val cached = runCatching { store.read(file)?.let { org.json.JSONObject(it.toString(Charsets.UTF_8)) }?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } }.getOrNull()
        if (!force && !cached.isNullOrEmpty()) return cached
        val names = runCatching { VodCatalog.categories(selected, mode).filterKeys { it.matches(Regex("[0-9]{1,20}")) } }.getOrDefault(emptyMap())
        if (names.isNotEmpty()) runCatching { store.write(file, org.json.JSONObject(names).toString().toByteArray()) }
        return names.ifEmpty { cached.orEmpty() }
    }
    private var shelfCategory: String? = null
    private val selectedCategories = mutableMapOf<String, String>()
    /** Browse one category at a time; keep its cached page and cursor on return. */
    private fun openCategory(selected: Source, mode: String, root: String, names: Map<String, String>, scope: String, force: Boolean = false, focus: Boolean = true) {
        shelfCategory = scope; selectedCategories[root] = scope
        var refreshPending = force
        panel.bindCatalog(root, names, { group, query ->
            val target = group ?: scope; val reload = refreshPending; refreshPending = false
            scopedCatalog(selected, mode, root, target, names, query, reload)
        }, { loadCatalogPage(null, activeQuery, true) }, "All categories", initialGroup = scope,
            picked = { next -> if (next == null) { shelfCategory = null; panel.clearCategorySelection(); bulkCatalog(selected, mode, false) } else openCategory(selected, mode, root, names, next, focus = true) })
        if (focus) panel.requestContentFocus()
    }
    private val warming = Executors.newSingleThreadExecutor { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-library-warm") }
    private var warmTask: java.util.concurrent.Future<*>? = null
    /**
     * Quietly prepare what Library opens to — the category list and the first ("Featured")
     * category of Movies and Series — so the first visit reads saved pages instead of waiting
     * on the provider. Skipped while Library is open; never touches the player.
     */
    fun prewarm(selected: Source) {
        if (destroyed || opened || selected.kind != "xtream" || !selected.enabled || warmTask?.isDone == false) return
        warmTask = warming.submit {
            for (mode in listOf("movie", "series")) {
                if (Thread.currentThread().isInterrupted || destroyed) return@submit
                val names = runCatching { categoriesFor(selected, mode, false) }.getOrDefault(emptyMap())
                val first = names.keys.firstOrNull() ?: continue
                runCatching { repository.load(selected, mode, first, false, names) }.getOrNull()?.let { snapshot ->
                    // Decode the posters Library opens to now, so the first visit is not waiting on disk and network.
                    val urls = runCatching { repository.query(snapshot, null, "", 0, 21).entries.map { it.poster }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
                    handler.post { if (!destroyed && !opened) panel.warmPosters(urls, selected.allowHttp) }
                }
            }
        }
    }
    private fun scopedCatalog(selected: Source, mode: String, root: String, scope: String, names: Map<String, String>, query: String, force: Boolean) {
        val key = "$root:category:$scope"
        // Only the category on screen downloads; an abandoned one (including the other tab's) must not hold a worker.
        catalogImports.filterKeys { it.contains(":category:") && it != key }.forEach { (oldKey, task) ->
            if (!task.isDone) { catalogGenerations.remove(oldKey); task.cancel(true) }
        }
        if (activeCatalog != key || activeQuery != query) {
            pageTask?.cancel(true); ++pageEpoch; activeSnapshot = null; panel.catalogBusy(block = true)
        }
        activeCatalog = key; activeGroup = null; activeQuery = query
        val title = names[scope].orEmpty()
        activeCatalogTitle = title
        fun display(snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
            if (!firstTitlesRecorded && snapshot.count > 0) { firstTitlesRecorded = true; Timings.since("First titles", openBegan) }
            activeSnapshot = snapshot; catalogImporting = busy
            panel.page(title, "${counted(snapshot.count, "title")}${if (busy) " · Loading more…" else " · Saved on this TV"}", busy)
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
                // Only reuse an already-parsed full catalog here; decrypting a large saved one first delays the category.
                repository.cached(selected, mode, scope) ?: repository.memory(selected, mode)?.takeIf { full ->
                    full.complete && full.chunks.any { "Category $scope" in it.groups }
                }?.copy(labels = names, scopeGroup = "Category $scope")
            }.getOrNull()
            if (cached != null) deliver(cached, !cached.complete)
            if (!force && cached?.complete == true && System.currentTimeMillis() - cached.updated in 0 until 6 * 3600000L) return@submit
            val began = Timings.now()
            runCatching {
                repository.load(selected, mode, scope, force, names) { snapshot ->
                    if (cached == null || snapshot.complete || snapshot.count >= cached.count) deliver(snapshot, !snapshot.complete)
                }
            }.onSuccess { Timings.since("Category", began); deliver(it, false) }.onFailure { failure ->
                val saved = cached ?: if (Thread.currentThread().isInterrupted) null else runCatching {
                    repository.cached(selected, mode)?.takeIf { full -> full.chunks.any { "Category $scope" in it.groups } }?.copy(labels = names, scopeGroup = "Category $scope")
                }.getOrNull()
                if (saved != null && cached == null) deliver(saved, false)
                handler.post {
                    if (!destroyed && catalogGenerations[key] == generation && opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) panel.page(title, "${if (saved != null) "Using saved category · " else ""}${SourceErrors.message(failure)} · Refresh to retry", false)
                }
            }
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
            runCatching {
                repository.load(selected, mode, force = force) { snapshot ->
                    if (cached == null || snapshot.complete || snapshot.count >= cached.count) deliver(snapshot, !snapshot.complete)
                }
            }.onSuccess { snapshot ->
                if (BuildConfig.DEBUG) android.util.Log.d("SpareLibrary", "Catalog import: ${android.os.SystemClock.elapsedRealtime() - began} ms, ${snapshot.count} titles")
                deliver(snapshot, false)
            }.onFailure { failure -> handler.post {
                if (!destroyed && catalogGenerations[key] == generation && opened && activeCatalog == key && detailItem == null && parentSeries == null) {
                    catalogImporting = false
                    panel.page(title, (if (cached != null) "Using saved library · " else "Library unavailable · ") + SourceErrors.message(failure) + " · Refresh to retry.", false)
                }
            } }
        }
    }
    private fun activateCatalog(key: String, snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
        activeCatalog = key; activeSnapshot = snapshot; catalogImporting = busy
        activeCatalogTitle = if (kind == "movie") "Movies" else "Series"
        panel.page(if (kind == "movie") "Movies" else "Series", counted(snapshot.count, "title") + if (busy) " · Loading more in the background…" else "", busy)
        val groups = snapshot.categories + snapshot.labels.mapKeys { "Category ${it.key}" }
        panel.bindCatalog(key, groups, { group, query ->
            val selected = selectedSource(); val scope = group?.removePrefix("Category ")
            if (selected != null && scope != null && scope.matches(Regex("[0-9]{1,20}"))) {
                shelfCategory = scope; selectedCategories["${selected.id}:$kind"] = scope
            }
            if (selected != null && scope != null && scope.matches(Regex("[0-9]{1,20}")) && snapshot.chunks.none { group in it.groups })
                scopedCatalog(selected, kind, key, scope, snapshot.labels, query, false)
            else { activeCatalog = key; activeSnapshot = snapshot; loadCatalogPage(group, query, false) }
        }, { loadCatalogPage(activeGroup, activeQuery, true) })
    }
    private fun browserRow(item: OnDemand) = BrowserRow(item.id, item.title,
        if (item.kind == "episode") listOf("Episode ${item.episode}", item.runtime).filter { it.isNotBlank() }.joinToString(" · ") else listOf(item.year, item.category).filter { it.isNotBlank() }.joinToString(" · "),
        poster = item.poster, description = item.description, requestPoster = { done -> posterMetadata.request(item, done) }, longPress = if (item.kind in setOf("movie", "series")) ({ toggleLibraryFavorite(item) }) else null,
        backdrop = item.backdrop, spotlight = if (item.kind in setOf("movie", "series")) ({ done -> spotlightDetails(item, done) }) else null) { select(posterMetadata.known(item)) }
    private val spotWorker = Executors.newSingleThreadExecutor { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-library-spotlight") }
    private var spotTask: java.util.concurrent.Future<*>? = null
    private val seriesSpots = linkedMapOf<String, Spotlight>()
    private fun spotlight(value: OnDemand) = Spotlight(listOf(value.year, value.genre.substringBefore(',').trim(), value.runtime, value.category).filter { it.isNotBlank() }.distinct().joinToString("  ·  "), value.description, value.backdrop)
    /** One request at a time for the poster under the cursor; results are shared with the details page. */
    private fun spotlightDetails(item: OnDemand, done: (Spotlight) -> Unit) {
        val selected = selectedSource() ?: return
        if (selected.kind != "xtream") return
        val key = "${selected.id}:${item.id}"
        details[key]?.let { done(spotlight(it.copy(category = item.category))); return }
        seriesSpots[key]?.let { done(it); return }
        spotTask?.cancel(true)
        spotTask = spotWorker.submit {
            val began = Timings.now()
            val (loaded, entries) = runCatching { if (item.kind == "movie") VodCatalog.details(selected, item) to emptyList() else VodCatalog.seriesInfo(selected, item) }.getOrNull() ?: return@submit
            Timings.since(if (item.kind == "movie") "Movie details" else "Series info", began)
            handler.post {
                if (destroyed) return@post
                val spot = spotlight(loaded.copy(category = item.category))
                if (item.kind == "movie") { details[key] = loaded; while (details.size > 200) details.remove(details.keys.first()) }
                else { rememberSeries(key, spot, entries) }
                done(spot)
            }
        }
    }
    /** The spotlight's series fetch already holds the episode list, so opening that series is instant. */
    private fun rememberSeries(key: String, spot: Spotlight, entries: List<OnDemand>) {
        seriesSpots[key] = spot; while (seriesSpots.size > 200) seriesSpots.remove(seriesSpots.keys.first())
        if (entries.isNotEmpty() && entries.size <= 2000 && episodeCache[key] == null) { episodeCache[key] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) }
    }
    private fun loadCatalogPage(group: String?, query: String, more: Boolean) {
        val snapshot = activeSnapshot ?: return
        // A category still downloading cannot be sorted yet; show it in provider order at once and sort when it finishes.
        val sort = store.librarySort.takeIf { snapshot.complete } ?: LibraryCatalog.SORT_PROVIDER
        val catalogKey = activeCatalog; val stateKey = "$catalogKey:${group.orEmpty()}:$query:$sort"
        val version = "${snapshot.updated}:${snapshot.labels.hashCode()}"
        activeGroup = group; activeQuery = query
        pageTask?.cancel(true); val token = ++pageEpoch
        val old = browsePages[stateKey]?.takeIf { it.version == version }
        if (!more && old != null && (old.rows.size >= 80 || old.available == snapshot.count)) {
            val updated = old.copy(more = if (sort == LibraryCatalog.SORT_PROVIDER) old.cursor < snapshot.count else old.more, available = snapshot.count)
            browsePages[stateKey] = updated; panel.catalogPage(updated.rows, false, updated.more); return
        }
        if (more && old?.more == false && old.available == snapshot.count) return
        // A category still downloading publishes a larger snapshot every ~¾ s. Rebuilding the grid each time
        // reloaded every poster and could drop rows you had scrolled to: keep what is shown and only extend it.
        val previous = browsePages[stateKey]?.takeIf { !more && it.rows.isNotEmpty() && it.version != version }
        if (previous != null && (sort == LibraryCatalog.SORT_PROVIDER || !snapshot.complete)) {
            val kept = previous.copy(version = version, more = if (sort == LibraryCatalog.SORT_PROVIDER) previous.cursor < snapshot.count else previous.more, available = snapshot.count)
            browsePages[stateKey] = kept; panel.catalogPage(kept.rows, false, kept.more)
            panel.page(activeCatalogTitle, "${counted(kept.rows.size, "title")} shown · ${snapshot.count} in library" + if (catalogImporting) " · Loading more…" else "", catalogImporting)
            return
        }
        panel.catalogBusy()
        pageTask = catalogReader.submit {
            runCatching {
                // A finished sorted import re-sorts once, keeping as many rows as were already shown.
                val wanted = previous?.rows?.size ?: 0
                var page = repository.query(snapshot, group, query, if (more) old?.cursor ?: 0 else 0, sort = sort)
                val entries = page.entries.toMutableList()
                while (entries.size < wanted && page.more && !Thread.currentThread().isInterrupted) { page = repository.query(snapshot, group, query, page.cursor, sort = sort); entries += page.entries }
                val rows = (if (more) old?.rows.orEmpty() else emptyList()) + entries.map(::browserRow)
                BrowsePage(version, rows.distinctBy { it.key }, page.cursor, page.more, snapshot.count)
            }.onSuccess { page -> handler.post {
                if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null && parentSeries == null) {
                    browsePages[stateKey] = page
                    while (browsePages.size > 8) browsePages.remove(browsePages.keys.first())
                    panel.catalogPage(page.rows, more, page.more)
                    panel.page(activeCatalogTitle, "${counted(page.rows.size, "title")} shown · ${snapshot.count} in library" + if (catalogImporting) " · Loading more…" else "", catalogImporting)
                }
            } }.onFailure { handler.post { if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null) panel.page("Library", "Could not read this page · Refresh to retry.", false) } }
        }
    }
    private fun showItems(items: List<OnDemand>, title: String, detail: String, busy: Boolean) {
        panel.page(title, detail, busy)
        panel.submit(items.map(::browserRow), items.associate { it.id to it.category }, reset = false, posters = items.firstOrNull()?.kind != "episode")
    }
    private fun select(item: OnDemand) {
        val selected = selectedSource() ?: return
        pageTask?.cancel(true); ++pageEpoch; panel.stopCatalog()
        if (item.kind == "series") { episodes(selected, item); return }
        if (item.kind == "movie") { openDetails(selected, item); return }
        playSelected(item)
    }
    private fun playSelected(item: OnDemand) {
        favoriteTask?.cancel(true); ++favoriteEpoch; checkingFavorites = false
        val selected = selectedSource() ?: return
        task?.cancel(true); val token = ++epoch
        // Starting an episode follows its series for Up next, without touching the Watchlist.
        if (item.kind == "episode") (parentSeries?.takeIf { it.id == item.seriesId } ?: upNextSeries[item.seriesId])?.let { series ->
            disk.execute { runCatching { store.editFollowedSeries(selected) { it.follow(series) } } }; upNextChecked = 0L
        }
        positions.execute {
            val position = if (fromBeginning) 0L else runCatching { store.watchProgress(selected).firstOrNull { it.item.id == item.id }?.position ?: 0L }.getOrDefault(0L)
            ui(token) { playingEpisodes = if (item.kind == "episode") episodeList.filter { it.seriesId == item.seriesId }.map { it.copy(playlistId = selected.id) } else emptyList(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; play(item.copy(playlistId = selected.id), position) }
        }
    }
    private fun openDetails(selected: Source, item: OnDemand) {
        val key = "${selected.id}:${item.id}"; detailItem = details[key] ?: item
        fun render(value: OnDemand, busy: Boolean) { detailItem = value; panel.detail(value, button, { playSelected(detailItem ?: value) }, { closeDetails() }, busy); presentFavorites() }
        render(detailItem!!, details[key] == null)
        if (details[key] != null) return
        request { token ->
            val began = Timings.now()
            var loaded = runCatching { VodCatalog.details(selected, item) }.getOrDefault(item)
            Timings.since("Movie details", began)
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
        val selected = selectedSource() ?: return
        when {
            kind == "favorites" -> { displayFavorites(); panel.favoriteTitle(null, false) }
            kind == "continue" -> continueWatching(selected)
            kind == "upnext" -> upNext(selected)
            selected.kind == "xtream" -> catalog(selected, kind, false, focus = true)
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
            episodeList = cached; showItems(cached, "Series", "${counted(cached.size, "episode")} · Back returns to Series", force); panel.seriesArtwork(posterMetadata.known(series)); presentFavorites()
            if (!force) { panel.focusRows(); return }
        } else { panel.submit(emptyList()); panel.page(series.title, "Loading seasons and episodes…", true) }
        val began = Timings.now()
        request { token -> runCatching { VodCatalog.seriesInfo(selected, series).also { Timings.since("Series info", began) } }.onSuccess { (info, entries) -> ui(token) {
            if (seriesSpots[cacheKey] == null) seriesSpots[cacheKey] = spotlight(info.copy(category = series.category))
            if (entries.size <= 2000) { episodeCache[cacheKey] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) } else episodeCache.remove(cacheKey)
            if (startRequestedEpisode(entries)) return@ui
            episodeList = entries; showItems(entries, "Series", "${counted(entries.size, "episode")} · Back returns to Series", false); panel.seriesArtwork(posterMetadata.known(series)); presentFavorites(); if (!force) panel.focusRows()
            spotlightDetails(series) { spot -> if (parentSeries?.id == series.id) panel.seriesSpotlight(posterMetadata.known(series), spot) }
            if (favoriteState.contains(series.id)) editFavorites(selected) { it.update(series, entries, System.currentTimeMillis()) }
            worker.execute {
                val url = runCatching { store.metadataAddon() }.getOrDefault("")
                if (url.isNotBlank()) runCatching { MetadataAddon.enrich(url, series) }.onSuccess { enriched -> ui(token) { panel.seriesArtwork(enriched) } }
            }
        } }.onFailure { ui(token) { panel.page(series.title, "${if (cached != null) "Using saved episodes · " else "Episodes unavailable · "}${SourceErrors.message(it)} · Refresh to retry.", false); if (!force) panel.focusRows() } } }
    }
    private var upNextRows: List<BrowserRow>? = null
    private var returnToUpNext = false
    private var upNextFor = ""
    private var upNextChecked = 0L
    private val upNextSeries = mutableMapOf<String, OnDemand>()
    /**
     * One row per followed series (Watchlist series and series you have started): resume, next or newly
     * released episode. Provider episode lists are re-checked at most every 15 minutes, bounded in count and time.
     */
    private fun upNext(selected: Source, force: Boolean = false) {
        val owner = "${selected.id}:${selected.providerKey()}"
        if (upNextFor != owner) { upNextFor = owner; upNextRows = null; upNextChecked = 0L }
        val cached = upNextRows
        panel.page("Up next", if (cached == null) "Checking your series for new episodes…" else upNextSummary(cached.size), true)
        panel.submit(cached.orEmpty(), reset = true); panel.focusRows()
        if (!force && cached != null && System.currentTimeMillis() - upNextChecked in 0 until 15 * 60000L) { panel.page("Up next", upNextSummary(cached.size), false); return }
        val known = episodeCache.toMap()
        request { token ->
            val now = System.currentTimeMillis()
            val favorites = runCatching { store.libraryFavorites(selected) }.getOrDefault(LibraryFavorites())
            val followed = runCatching { store.followedSeries(selected) }.getOrDefault(LibraryFavorites())
            val progress = runCatching { store.watchProgress(selected) }.getOrDefault(emptyList())
            val tracked = (followed.entries + favorites.entries.filter { it.item.kind == "series" }).distinctBy { it.item.id }.take(40)
            val deadline = System.nanoTime() + 25_000_000_000L
            val loaded = linkedMapOf<String, List<OnDemand>>(); val picks = mutableListOf<UpNext.Pick>()
            var followState = followed; var favoriteSaved = favorites
            for (entry in tracked) {
                if (Thread.currentThread().isInterrupted) return@request
                val key = "${selected.id}:${entry.item.id}"
                val episodes = known[key] ?: if (System.nanoTime() < deadline) runCatching { VodCatalog.episodes(selected, entry.item, 4000) }.getOrNull() else null
                if (episodes == null) continue
                loaded[key] = episodes
                // Record the episode list so later checks can tell which episodes are new.
                if (followState.contains(entry.item.id)) followState = runCatching { store.editFollowedSeries(selected) { it.update(entry.item, episodes, now) } }.getOrDefault(followState)
                if (favoriteSaved.contains(entry.item.id)) favoriteSaved = runCatching { store.editLibraryFavorites(selected) { it.update(entry.item, episodes, now) } }.getOrDefault(favoriteSaved)
                val added = (followState.entries + favoriteSaved.entries).filter { it.item.id == entry.item.id }.flatMap { it.added }.toSet()
                UpNext.pick(entry.item, episodes, favoriteSaved.watched, progress, added)?.let(picks::add)
            }
            val ordered = UpNext.order(picks)
            ui(token) {
                loaded.forEach { (key, value) -> episodeCache[key] = value }; while (episodeCache.size > 64) episodeCache.remove(episodeCache.keys.first())
                tracked.forEach { upNextSeries[it.item.id] = it.item }
                favoriteState = favoriteSaved; presentFavorites()
                val rows = ordered.map { pick ->
                    val episodes = loaded["${selected.id}:${pick.series.id}"].orEmpty()
                    BrowserRow(pick.episode.id, pick.series.title, pick.label, poster = pick.series.poster.ifBlank { pick.episode.poster }, description = pick.episode.title,
                        backdrop = pick.series.backdrop) { parentSeries = pick.series; episodeList = episodes; returnToUpNext = true; playSelected(pick.episode) }
                }
                upNextRows = rows; upNextChecked = System.currentTimeMillis()
                panel.page("Up next", upNextSummary(rows.size, ordered.count { it.fresh }), false)
                panel.submit(rows, reset = false); panel.focusRows()
            }
        }
    }
    private fun upNextSummary(count: Int, fresh: Int = upNextRows.orEmpty().count { it.detail.startsWith("New") }) =
        if (count == 0) "Nothing new yet. Series you start or add to your Watchlist appear here with their next episode."
        else "${counted(count, "series", "series")} to continue" + if (fresh > 0) " · $fresh with new episodes" else ""
    private fun continueWatching(selected: Source) {
        panel.submit(emptyList()); panel.page("Continue watching", "Loading saved positions…", true)
        request { token ->
            val entries = runCatching { store.watchProgress(selected).sortedByDescending { it.updated } }.getOrDefault(emptyList())
            // Episodes are shown as their series: its poster and name, with the episode code and resume point.
            val series = runCatching { (store.followedSeries(selected).entries + store.libraryFavorites(selected).entries).map { it.item }.filter { it.kind == "series" }.associateBy { it.id } }.getOrDefault(emptyMap())
            ui(token) {
                panel.page("Continue watching", "Saved on this TV for ${selected.name}. Finished titles disappear automatically.", false)
                panel.submit(entries.map { entry ->
                    val item = entry.item; val show = series[item.seriesId].takeIf { item.kind == "episode" }
                    val resume = "Resume at ${duration(entry.position)}"
                    BrowserRow(item.id, show?.title ?: item.title, if (item.kind == "episode" && item.season > 0) "S${item.season} E${item.episode} · $resume" else resume,
                        poster = item.poster.ifBlank { show?.poster.orEmpty() }, description = if (show != null) item.title else item.description, backdrop = show?.backdrop.orEmpty()) {
                        if (show != null) { parentSeries = show; episodeList = episodeCache["${selected.id}:${show.id}"].orEmpty() }
                        select(item)
                    }
                }); panel.focusRows()
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
        selectPlaylist(sourceFor(channel))
        directCatchup = direct
        detailItem = null; panel.favoriteTitle(null, false); panel.archiveMode(true)
        pageTask?.cancel(true); ++pageEpoch; activeCatalog = ""; panel.stopCatalog()
        panel.selectTab("catchup"); panel.seriesArtwork(null)
        kind = "catchup"; archive = channel; parentSeries = null; panel.resumeImages(); panel.visibility = View.VISIBLE
        task?.cancel(true); ++epoch
        val selected = selectedSource() ?: return
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
    fun reopen(item: OnDemand? = null) {
        if (item?.playlistId?.isNotBlank() == true) selectPlaylist(resolveSource(item.playlistId))
        val selected = selectedSource()
        if (item?.kind == "movie") { open("movie", focusContent = true, keepCategory = true); return }
        // Stopping an episode started from Up next returns there, with the list re-checked from cached episodes.
        if (returnToUpNext && item?.kind == "episode") { returnToUpNext = false; parentSeries = null; open("upnext"); return }
        val series = parentSeries?.takeIf { item == null || item.kind == "episode" && it.id == item.seriesId }
            ?: favoriteState.entries.firstOrNull { it.item.id == item?.seriesId }?.item
        if (selected != null && series != null) {
            panel.archiveMode(false)
            panel.browse(); panel.selectTab("series"); panel.resumeImages(); panel.visibility = View.VISIBLE; kind = "series"; prepareFavorites(selected); episodes(selected, series)
        } else open(if (item?.kind == "episode") "series" else kind)
    }
    fun next(item: OnDemand) = playingEpisodes.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { playingEpisodes.getOrNull(it + 1) }
    fun refresh() { val selected = selectedSource() ?: return; when { archive != null -> openCatchup(archive!!, directCatchup); parentSeries != null -> episodes(selected, parentSeries!!, force = true); kind == "favorites" -> { displayFavorites(); checkFavoriteEpisodes(selected, true) }; kind == "upnext" && parentSeries == null -> upNext(selected, force = true); kind in setOf("movie", "series") && selected.kind == "xtream" -> { refreshCatalogs?.invoke(kind); catalog(selected, kind, true) }; else -> open(kind) } }
    fun back() {
        when {
            panel.closeCategories() -> Unit
            directCatchup -> { directCatchup = false; dismiss(); (catchupClosed ?: closed).invoke() }
            detailItem != null -> closeDetails()
            archive != null -> catchupChannels()
            parentSeries != null -> open(if (kind == "favorites") "favorites" else "series", focusContent = true, keepCategory = true)
            panel.focusRailFromContent() -> Unit
            else -> { dismiss(); closed() }
        }
    }
    fun dismiss() {
        favoriteTask?.cancel(true); ++favoriteEpoch; checkingFavorites = false
        fromBeginning = false; startEpisode = null; pageTask?.cancel(true); ++pageEpoch
        panel.stopCatalog(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; task?.cancel(true); ++epoch
        catalogImports.forEach { (key, import) -> if (!import.isDone) { catalogGenerations.remove(key); import.cancel(true) } }
    }
    fun invalidateProvider(id: String) {
        cancelStart(); ++epoch; task?.cancel(true); favoriteTask?.cancel(true); ++favoriteEpoch
        catalogImports.values.forEach { it.cancel(true) }; catalogImports.clear(); catalogGenerations.clear()
        pageTask?.cancel(true); ++pageEpoch; activeSnapshot = null; activeCatalog = ""
        catalogs.clear(); browsePages.clear(); categoryNames.clear(); selectedCategories.clear(); episodeCache.clear(); details.clear()
        parentSeries = null; detailItem = null; episodeList = emptyList(); playingEpisodes = emptyList()
        favoriteSource = ""; favoriteState = LibraryFavorites(); repository.invalidate(id)
    }
    fun destroy() { warmTask?.cancel(true); warming.shutdownNow(); spotTask?.cancel(true); spotWorker.shutdownNow(); repository.close(); cancelStart(); favoriteTask?.cancel(true); favoriteWorker.shutdownNow(); posterMetadata.close(); catalogImports.values.forEach { it.cancel(true) }; pageTask?.cancel(true); catalogReader.shutdownNow(); panel.closeImages(); disk.shutdown(); positions.shutdownNow(); destroyed = true; task?.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object { fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) } }
}

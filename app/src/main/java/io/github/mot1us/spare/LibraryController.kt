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
    fun selectPlaylist(selected: Source?) { if (playlist != selected?.id) { task?.cancel(true); ++epoch; playlist = selected?.id; detailItem = null; parentSeries = null; favoriteSource = ""; shelfCategory = null } }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2, namedThreads("library"))
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
    private val favoriteWorker = Executors.newSingleThreadExecutor(namedThreads("library-watchlist"))
    private var favoriteTask: java.util.concurrent.Future<*>? = null
    private var favoriteEpoch = 0
    private var checkingFavorites = false
    private val disk = Executors.newSingleThreadExecutor()
    private val positions = Executors.newSingleThreadExecutor()
    private val episodeCache = linkedMapOf<String, List<OnDemand>>()
    private val catalogs = linkedMapOf<String, LibraryCatalog.Snapshot>()
    val repository = LibraryCatalog(store)
    var catalogChanged: (() -> Unit)? = null
    private val indexChanged = Runnable {
        if (!destroyed) {
            catalogChanged?.invoke()
            val selected = selectedSource()
            if (selected != null && opened && kind in setOf("movie", "series") && activeCatalog == "${selected.id}:$kind" && detailItem == null && parentSeries == null &&
                // The rows home is a finished view of the catalog; a background refresh must not swap it for the grid.
                !homeActive() &&
                // While Library gets the catalog ready it shows calm steps; a partial copy that grows and redraws made the
                // count tick and the title under the cursor flicker.
                !panel.loadingShown &&
                // Nor replace a finished grid: a refresh that completes keeps the copy on screen until Library reopens.
                activeSnapshot?.let { it.complete && it.whole } != true) {
                repository.memory(selected, kind)?.let { activateCatalog(activeCatalog, it.copy(labels = categoryNames[activeCatalog] ?: it.labels), !it.complete) }
            }
        }
    }
    init { repository.changed = { handler.removeCallbacks(indexChanged); handler.postDelayed(indexChanged, 250) } }
    /** Old saved catalogs refresh here, one at a time, apart from [worker] so Library's own requests never cancel them. */
    private val refresher = Executors.newSingleThreadExecutor(namedThreads("library-refresh"))
    private val refreshing = mutableMapOf<String, java.util.concurrent.Future<*>>()
    private val refreshTried = mutableMapOf<String, Long>()
    /** Counts Library visits: copies held for rows on screen are released when the visit that held them ends. */
    @Volatile private var visit = 1
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
    private val details = linkedMapOf<String, OnDemand>()
    private var detailItem: OnDemand? = null
    val panel = MediaBrowserPanel(activity, button, { open(it) }, { refresh() })
    init {
        panel.cachedSpotlight = { row ->
            selectedSource()?.let { selected ->
                val key = "${selected.id}:${row.key}"
                details[key]?.let(::spotlight) ?: seriesSpots[key]
            }
        }
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
        if (!keepCategory || mode != kind) { shelfCategory = null; gridFromHome = false; homeSearch = false }
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
    private var lastProgressDisplay = 0L
    @Volatile private var firstTitlesRecorded = false
    private fun catalog(selected: Source, mode: String, force: Boolean, focus: Boolean = false) {
        val root = "${selected.id}:$mode"; activeCatalog = root
        openBegan = Timings.now(); firstTitlesRecorded = false
        Diagnostics.libraryOpened("Library chosen · $mode${if (wholeCatalog(selected, mode) != null) " · catalog already in memory" else ""}")
        val title = if (mode == "movie") "Movies" else "Series"
        fun proceed(names: Map<String, String>) {
            categoryNames[root] = names
            val full = wholeCatalog(selected, mode)
            when {
                names.isEmpty() -> bulkCatalog(selected, mode, force)
                // Returning from a title or its episodes: back to the grid it was opened from.
                full != null && homeSearch -> activateCatalog(root, full, false)
                full != null && shelfCategory?.let { it in names } == true -> openCategory(selected, mode, root, names, shelfCategory!!, force, focus)
                full != null -> home(selected, mode, root, names, full, focus)
                // The whole catalog could not be imported: browse one category at a time, as before.
                else -> openCategory(selected, mode, root, names, shelfCategory?.takeIf { it in names } ?: selectedCategories[root]?.takeIf { it in names } ?: names.keys.first(), force, focus)
            }
        }
        val memory = categoryNames[root]
        if (!force && !memory.isNullOrEmpty() && wholeCatalog(selected, mode) != null) { proceed(memory); return }
        panel.page(title, "Getting your library ready…", true)
        // No finished catalog in memory: the loading steps show, after a moment so a quick open from this TV never flashes them.
        val calm = wholeCatalog(selected, mode) == null
        val loading = Runnable { if (!destroyed && opened && activeCatalog == root) panel.showLoading(mode, downloading = false) }
        if (calm) handler.postDelayed(loading, LOADING_DELAY_MS)
        request { token ->
            val names = categoriesFor(selected, mode, force)
            Timings.since("Category list", openBegan); Diagnostics.library("Category list read · ${names.size} categories")
            if (calm) ui(token) { panel.loadingStep(LibraryLoading.Step.TITLES) }
            // Restore the selected tab before deciding whether it needs a download.
            // Even an older complete catalog is usable until the viewer asks to refresh it.
            if (!force && wholeCatalog(selected, mode) == null) runCatching { repository.cached(selected, mode) }
            if (names.isNotEmpty() && (force || wholeCatalog(selected, mode) == null)) {
                if (calm) ui(token) { handler.removeCallbacks(loading); panel.showLoading(mode, downloading = true); panel.loadingStep(LibraryLoading.Step.TITLES) }
                runCatching { repository.load(selected, mode, "", force, names) }
                    .onFailure { if (it !is InterruptedException && it !is java.io.InterruptedIOException) Diagnostics.library("Catalog download failed · ${SourceErrors.message(it)}") }
            }
            Diagnostics.library("Saved catalog ready")
            ui(token) { handler.removeCallbacks(loading); panel.loadingStep(LibraryLoading.Step.ROWS); proceed(names) }
        }
    }
    private fun wholeCatalog(selected: Source, mode: String) = repository.memory(selected, mode)?.takeIf { it.complete && it.whole && it.count > 0 }
    /** Set while the rows are being prepared off the UI thread: any other request (a new epoch) supersedes it. */
    private var homeToken = -1
    /** The rows are on screen or about to be: catalog updates and late grid pages must not replace them. */
    private fun homeActive() = panel.shelfCatalog != null || homeToken == epoch
    /** [data] fills in as category rows scroll into view and read their titles (see [loadShelf]). */
    private class Home(val stamp: Long, val shelves: Map<String, String>, val data: MutableMap<String, CatalogShelves.Data>, val counts: Map<String, Int>)
    private val shelfLoads = HashSet<String>()
    /** One category row's titles, read from the database when the row comes into view. Main thread. */
    private fun loadShelf(root: String, names: Map<String, String>, full: LibraryCatalog.Snapshot, id: String) {
        val home = homes[root] ?: return
        if (id !in names || home.data[id] != null || !shelfLoads.add("$root:$id")) return
        catalogReader.submit {
            val items = runCatching { repository.row(full, id, names) }.getOrDefault(emptyList())
            handler.post {
                shelfLoads.remove("$root:$id")
                if (destroyed) return@post
                val value = CatalogShelves.Data(items.map(::browserRow), home.counts[id] ?: items.size, busy = false, failed = items.isEmpty())
                home.data[id] = value
                if (panel.shelfCatalog == root) panel.shelf(id, value)
            }
        }
    }
    private val homes = mutableMapOf<String, Home>()
    /** A category or search grid was opened from the rows home: Back returns to the rows. */
    private var gridFromHome = false
    private var homeSearch = false
    /** Movies or Series as rows — Recently added, Top rated, then every provider category — read from the catalog in memory. */
    private fun home(selected: Source, mode: String, root: String, names: Map<String, String>, full: LibraryCatalog.Snapshot, focus: Boolean) {
        activeCatalog = root; gridFromHome = false; homeSearch = false; shelfCategory = null
        pageTask?.cancel(true); ++pageEpoch; activeSnapshot = null
        val title = if (mode == "movie") "Movies" else "Series"
        fun show(home: Home, personal: Map<String, Pair<String, CatalogShelves.Data>>) {
            homeToken = -1; pageTask?.cancel(true); ++pageEpoch
            if (!firstTitlesRecorded) { firstTitlesRecorded = true; Timings.since("First titles", openBegan); Diagnostics.library("Rows shown") }
            panel.page(title, homeNote(root, full), false)
            panel.showShelves(root, personal.mapValues { it.value.first } + home.shelves, { id -> loadShelf(root, names, full, id) }, {}, { id -> when {
                    id == HOME_CONTINUE -> open("continue"); id == HOME_WATCHLIST -> open("favorites")
                    id in names -> { gridFromHome = true; openCategory(selected, mode, root, names, id, focus = true) }
                } },
                { next -> if (next != null && next in names) { gridFromHome = true; openCategory(selected, mode, root, names, next, focus = true) } }, focus,
                initial = personal.mapValues { it.value.second } + home.data, rail = emptyMap(), search = { gridFromHome = true; homeSearch = true; activateCatalog(root, full, false) })
        }
        val ready = homes[root]?.takeIf { it.stamp == full.updated }
        if (ready == null) panel.page(title, "Getting your library ready…", true)
        refreshIfDue(selected, mode, root, names, full)
        val visit = visit
        request { token ->
            // Keep this copy's titles while its rows are on screen, even if a refresh replaces it. If one already has,
            // show the new copy instead.
            if (!repository.hold(full, visit)) {
                val current = wholeCatalog(selected, mode)
                if (current != null && current !== full) { ui(token) { home(selected, mode, root, names, current, focus) }; return@request }
            }
            // Continue watching and Watchlist change with every visit: two small saved files, read each time the rows are shown.
            val personal = personalShelves(selected, mode)
            Diagnostics.library("Continue watching and Watchlist read")
            if (ready != null) { ui(token) { show(ready, personal) }; return@request }
            val rows = repository.home(full, names)
            val shelves = linkedMapOf<String, String>(); val data = HashMap<String, CatalogShelves.Data>()
            if (rows.recent.isNotEmpty()) { shelves[HOME_RECENT] = "Recently added"; data[HOME_RECENT] = CatalogShelves.Data(rows.recent.map(::browserRow), 0, busy = false, more = false) }
            if (rows.top.isNotEmpty()) { shelves[HOME_TOP] = "Top rated"; data[HOME_TOP] = CatalogShelves.Data(rows.top.map(::browserRow), 0, busy = false, more = false) }
            // Every category with titles gets a row; only the first few read their titles now, the rest as they scroll into view.
            names.keys.filter { (rows.counts[it] ?: 0) > 0 }.forEach { id -> shelves[id] = names[id].orEmpty() }
            rows.categories.forEach { (id, items) -> data[id] = CatalogShelves.Data(items.map(::browserRow), rows.counts[id] ?: items.size, busy = false) }
            val built = Home(full.updated, shelves, data, rows.counts)
            ui(token) { homes[root] = built; while (homes.size > 4) homes.remove(homes.keys.first()); show(built, personal) }
        }
        homeToken = epoch
    }
    private fun homeNote(root: String, full: LibraryCatalog.Snapshot): String {
        val checking = refreshing[root]?.isDone == false
        if (checking && LibraryCatalog.outgrown(full)) return "${counted(full.count, "title")} · Getting the rest of your titles…"
        return "${counted(full.count, "title")} · ${savedAge(full.updated, System.currentTimeMillis(), checking = checking)}"
    }
    /**
     * A saved catalog older than [LibraryCatalog.REFRESH_AFTER], or cut short by an older title limit, downloads again quietly once its rows are open. The rows and
     * grids on screen keep reading the copy they show; the new one appears the next time the rows are opened. Main thread.
     */
    private fun refreshIfDue(selected: Source, mode: String, root: String, names: Map<String, String>, full: LibraryCatalog.Snapshot) {
        val now = System.currentTimeMillis()
        if (!LibraryCatalog.due(full, now) || refreshing[root]?.isDone == false || now - (refreshTried[root] ?: 0L) in 0 until REFRESH_RETRY) return
        refreshTried[root] = now
        val began = Timings.now(); val title = if (mode == "movie") "Movies" else "Series"
        Diagnostics.library(if (LibraryCatalog.outgrown(full)) "Saved catalog stopped at the old limit of ${full.capped} titles · getting the rest in the background"
            else "Saved catalog is ${(now - full.updated) / 3600000L} h old · refreshing in the background")
        var job: java.util.concurrent.Future<*>? = null
        job = refresher.submit {
            val result = runCatching { repository.load(selected, mode, "", false, names) }
            handler.post {
                if (refreshing[root] === job) refreshing.remove(root)
                if (destroyed) return@post
                val replaced = result.getOrNull()?.takeIf { it.dbGen != full.dbGen }
                if (replaced != null) { Timings.since("Catalog refresh", began); Diagnostics.library("Catalog refreshed in ${(Timings.now() - began) / 1000} s · ${replaced.count} titles") }
                else Diagnostics.library("Catalog refresh ${result.exceptionOrNull()?.let { "failed · ${SourceErrors.message(it)}" } ?: "found nothing newer"}")
                if (opened && panel.shelfCatalog == root && homes[root]?.stamp == full.updated)
                    panel.page(title, if (replaced != null) "${counted(replaced.count, "title")} · Updated just now · New titles show when you reopen Library" else homeNote(root, full), false)
            }
        }
        refreshing[root] = job
    }
    /** Rows that lead the home: titles of this kind with a saved position, then this kind's Watchlist. Off the UI thread. */
    private fun personalShelves(selected: Source, mode: String): Map<String, Pair<String, CatalogShelves.Data>> {
        val result = linkedMapOf<String, Pair<String, CatalogShelves.Data>>()
        val favorites = runCatching { store.libraryFavorites(selected) }.getOrDefault(LibraryFavorites())
        val progress = runCatching { store.watchProgress(selected).sortedByDescending { it.updated } }.getOrDefault(emptyList())
        val resumed = if (mode == "movie") progress.filter { it.item.kind == "movie" }.map { entry -> browserRow(entry.item).copy(detail = "Resume at ${duration(entry.position)}", personal = true) }
        else {
            // One tile per series: its most recently watched episode.
            val series = runCatching { (store.followedSeries(selected).entries + favorites.entries).map { it.item }.filter { it.kind == "series" }.associateBy { it.id } }.getOrDefault(emptyMap())
            progress.filter { it.item.kind == "episode" }.distinctBy { it.item.seriesId.ifBlank { it.item.id } }.map { continueRow(selected, it, series) }
        }
        fun shelf(id: String, title: String, rows: List<BrowserRow>) {
            // See all (only when the row is cut short) opens the full list in its own Library section.
            if (rows.isNotEmpty()) result[id] = title to CatalogShelves.Data(rows.take(HOME_PERSONAL), if (rows.size > HOME_PERSONAL) rows.size else 0, busy = false, more = rows.size > HOME_PERSONAL)
        }
        shelf(HOME_CONTINUE, "Continue watching", resumed)
        shelf(HOME_WATCHLIST, "Watchlist", favorites.entries.filter { it.item.kind == mode }.map { entry -> browserRow(entry.item).copy(detail = entry.label(favorites.watched), personal = entry.item.kind == "series") })
        return result
    }
    /** Episodes are shown as their series: its poster and name, with the episode code and resume point. */
    private fun continueRow(selected: Source, entry: WatchProgress, series: Map<String, OnDemand>): BrowserRow {
        val item = entry.item; val show = series[item.seriesId].takeIf { item.kind == "episode" }
        val resume = "Resume at ${duration(entry.position)}"
        return BrowserRow(item.id, show?.title ?: item.title, if (item.kind == "episode" && item.season > 0) "S${item.season} E${item.episode} · $resume" else resume,
            poster = item.poster.ifBlank { show?.poster.orEmpty() }, description = if (show != null) item.title else item.description, backdrop = show?.backdrop.orEmpty()) {
            if (show != null) { parentSeries = show; episodeList = episodeCache["${selected.id}:${show.id}"].orEmpty() }
            select(item)
        }
    }
    private fun returnHome(focus: Boolean = true) {
        gridFromHome = false; homeSearch = false; shelfCategory = null
        val selected = selectedSource() ?: return
        catalog(selected, kind, false, focus)
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
        }, { loadCatalogPage(null, activeQuery, true) }, if (wholeCatalog(selected, mode) != null) "Home" else "All categories", initialGroup = scope,
            picked = { next -> if (next == null) { shelfCategory = null; panel.clearCategorySelection(); if (wholeCatalog(selected, mode) != null) returnHome() else bulkCatalog(selected, mode, false) } else openCategory(selected, mode, root, names, next, focus = true) })
        if (focus) panel.requestContentFocus()
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
            panel.page(title, if (busy) "Loading more titles…" else "${counted(snapshot.shown, "title")} · Saved on this TV", busy)
            loadCatalogPage(null, activeQuery, false)
        }
        // With the whole catalog in memory a category is shown right here; it never waits for a worker or the provider.
        if (!force) repository.category(selected, mode, scope, names)?.let { whole -> catalogs[key] = whole; Timings.since("Category", openBegan); display(whole, false); return }
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
                // A category still downloading publishes about every ¾ s; redrawing that often starves paging on slow TV hardware.
                val now = android.os.SystemClock.elapsedRealtime()
                if (busy && prior != null && prior.count > 0 && now - lastProgressDisplay < 2500) return@post
                if (busy) lastProgressDisplay = now
                if (opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null) display(snapshot, busy)
            }
        } }
        catalogImports[key] = worker.submit {
            // The whole catalog is imported in the background; once it is in memory a category needs no provider request.
            if (force) runCatching { repository.load(selected, mode, "", true, names) }
            repository.category(selected, mode, scope, names)?.let { whole -> Timings.since("Category", openBegan); deliver(whole, false); return@submit }
            val cached = memory?.takeIf { it.complete } ?: runCatching {
                // Only reuse an already-parsed full catalog here; decrypting a large saved one first delays the category.
                repository.cached(selected, mode, scope) ?: repository.memory(selected, mode)?.takeIf { full ->
                    full.complete && repository.hasCategory(full, scope)
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
                    repository.cached(selected, mode)?.takeIf { full -> repository.hasCategory(full, scope) }?.copy(labels = names, scopeGroup = "Category $scope")
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
        if (memory == null) { panel.submit(emptyList()); panel.page(title, "Getting your library ready…", true); panel.showLoading(mode, downloading = true); panel.loadingStep(LibraryLoading.Step.TITLES) }
        else activateCatalog(key, memory, true)
        catalogImports[key]?.cancel(true)
        val generation = ++catalogGeneration; catalogGenerations[key] = generation
        fun deliver(snapshot: LibraryCatalog.Snapshot, busy: Boolean) { handler.post {
            if (!destroyed && catalogGenerations[key] == generation) {
                catalogs[key] = snapshot
                // The loading steps stay until the whole list is saved, rather than a grid that grows and redraws.
                if (busy && panel.loadingShown) return@post
                if (opened && activeCatalog == key && kind == mode && detailItem == null && parentSeries == null && !homeActive()) activateCatalog(key, snapshot, busy)
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
                    catalogImporting = false; panel.hideLoading()
                    panel.page(title, (if (cached != null) "Using saved library · " else "Library unavailable · ") + SourceErrors.message(failure) + " · Refresh to retry.", false)
                }
            } }
        }
    }
    private fun activateCatalog(key: String, snapshot: LibraryCatalog.Snapshot, busy: Boolean) {
        activeCatalog = key; activeSnapshot = snapshot; catalogImporting = busy
        activeCatalogTitle = if (kind == "movie") "Movies" else "Series"
        panel.page(if (kind == "movie") "Movies" else "Series", if (busy) "Loading more titles in the background…" else counted(snapshot.count, "title"), busy)
        panel.hideLoading()
        val groups = snapshot.categories + snapshot.labels.mapKeys { "Category ${it.key}" }
        panel.bindCatalog(key, groups, { group, query ->
            // Clearing a search started on the rows home returns to the rows.
            if (homeSearch && group == null && query.isEmpty()) { handler.post { if (!destroyed && opened && homeSearch) returnHome(focus = false) }; return@bindCatalog }
            val selected = selectedSource(); val scope = group?.removePrefix("Category ")
            if (selected != null && scope != null && scope.matches(Regex("[0-9]{1,20}"))) {
                shelfCategory = scope; selectedCategories["${selected.id}:$kind"] = scope
            }
            if (selected != null && scope != null && scope.matches(Regex("[0-9]{1,20}")) && !repository.hasGroup(snapshot, group))
                scopedCatalog(selected, kind, key, scope, snapshot.labels, query, false)
            else { activeCatalog = key; activeSnapshot = snapshot; loadCatalogPage(group, query, false) }
        }, { loadCatalogPage(activeGroup, activeQuery, true) })
    }
    private fun browserRow(item: OnDemand) = BrowserRow(item.id, item.title,
        if (item.kind == "episode") listOf("Episode ${item.episode}", item.runtime).filter { it.isNotBlank() }.joinToString(" · ") else listOf(item.year, item.category).filter { it.isNotBlank() }.joinToString(" · "),
        poster = item.poster, description = item.description, longPress = if (item.kind in setOf("movie", "series")) ({ toggleLibraryFavorite(item) }) else null,
        backdrop = item.backdrop) { select(item) }
    private val seriesSpots = linkedMapOf<String, Spotlight>()
    private fun spotlight(value: OnDemand) = Spotlight(LibraryFacts.meta(value), value.description, value.backdrop)
    private fun loadCatalogPage(group: String?, query: String, more: Boolean) {
        val snapshot = activeSnapshot ?: return
        // A category still downloading cannot be sorted yet; show it in provider order at once and sort when it finishes.
        val sort = store.librarySort.takeIf { snapshot.complete } ?: LibraryCatalog.SORT_PROVIDER
        val catalogKey = activeCatalog; val stateKey = "$catalogKey:${group.orEmpty()}:$query:$sort"
        val version = "${snapshot.updated}:${snapshot.labels.hashCode()}"
        activeGroup = group; activeQuery = query
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
            panel.page(activeCatalogTitle, if (catalogImporting) "Loading more titles…" else "${counted(kept.rows.size, "title")} shown · ${snapshot.shown} in library", catalogImporting)
            return
        }
        // Cancel an in-flight page only when a new one replaces it; a download's progress updates must not starve "load more".
        pageTask?.cancel(true); val token = ++pageEpoch
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
                if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null && parentSeries == null && panel.shelfCatalog == null) {
                    browsePages[stateKey] = page
                    while (browsePages.size > 8) browsePages.remove(browsePages.keys.first())
                    panel.catalogPage(page.rows, more, page.more)
                    panel.page(activeCatalogTitle, if (catalogImporting) "Loading more titles…" else "${counted(page.rows.size, "title")} shown · ${snapshot.shown} in library", catalogImporting)
                }
            } }.onFailure { handler.post { if (!destroyed && token == pageEpoch && opened && activeCatalog == catalogKey && kind in setOf("movie", "series") && detailItem == null) panel.page("Library", "Could not read this page · Refresh to retry.", false) } }
        }
    }
    private fun showItems(items: List<OnDemand>, title: String, detail: String, busy: Boolean) {
        panel.page(title, detail, busy)
        val episodes = items.firstOrNull()?.kind == "episode"
        // A series opens on one season — the first with an unwatched episode — instead of every episode in one list.
        val season = if (episodes) (items.firstOrNull { it.id !in favoriteState.watched } ?: items.first()).category else null
        panel.submit(items.map(::browserRow), items.associate { it.id to it.category }, reset = false, posters = !episodes, initialGroup = season, allTitle = if (episodes) "All seasons" else "All")
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
        fun render(value: OnDemand, busy: Boolean) { detailItem = value; panel.detail(value, button, { playSelected(detailItem ?: value) }, busy); presentFavorites() }
        render(detailItem!!, details[key] == null)
        if (details[key] != null) return
        request { token ->
            val began = Timings.now()
            val provider = runCatching { VodCatalog.details(selected, item) }.getOrDefault(item)
            Timings.since("Movie details", began)
            ui(token) { details[key] = provider; while (details.size > 200) details.remove(details.keys.first()); render(provider, false) }
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
            episodeList = cached; showItems(cached, "Series", "${counted(cached.size, "episode")} · Back returns to Series", force); panel.seriesArtwork(series, seriesSpots[cacheKey]); presentFavorites()
            if (!force) { panel.focusRows(); return }
        } else { panel.submit(emptyList()); panel.page(series.title, "Loading seasons and episodes…", true) }
        val began = Timings.now()
        request { token -> runCatching { VodCatalog.seriesInfo(selected, series).also { Timings.since("Series info", began) } }.onSuccess { (info, entries) -> ui(token) {
            seriesSpots[cacheKey] = spotlight(info.copy(category = series.category))
            while (seriesSpots.size > 200) seriesSpots.remove(seriesSpots.keys.first())
            if (entries.size <= 2000) { episodeCache[cacheKey] = entries; while (episodeCache.size > 5) episodeCache.remove(episodeCache.keys.first()) } else episodeCache.remove(cacheKey)
            if (startRequestedEpisode(entries)) return@ui
            episodeList = entries; showItems(entries, "Series", "${counted(entries.size, "episode")} · Back returns to Series", false); panel.seriesArtwork(series, seriesSpots[cacheKey]); presentFavorites(); if (!force) panel.focusRows()
            if (favoriteState.contains(series.id)) editFavorites(selected) { it.update(series, entries, System.currentTimeMillis()) }
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
            val series = runCatching { (store.followedSeries(selected).entries + store.libraryFavorites(selected).entries).map { it.item }.filter { it.kind == "series" }.associateBy { it.id } }.getOrDefault(emptyMap())
            ui(token) {
                panel.page("Continue watching", "Saved on this TV for ${selected.name}. Finished titles disappear automatically.", false)
                panel.submit(entries.map { continueRow(selected, it, series) }); panel.focusRows()
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
    /** Library from the main menu: back to the section, rows position, category grid or series you left. */
    fun resume() {
        val series = parentSeries; val before = kind
        open(focusContent = true, keepCategory = true)
        val selected = selectedSource() ?: return
        if (series != null && selected.kind == "xtream" && kind == before && kind in setOf("series", "favorites")) episodes(selected, series)
    }
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
    fun refresh() { val selected = selectedSource() ?: return; when { archive != null -> openCatchup(archive!!, directCatchup); parentSeries != null -> episodes(selected, parentSeries!!, force = true); kind == "favorites" -> { displayFavorites(); checkFavoriteEpisodes(selected, true) }; kind == "upnext" && parentSeries == null -> upNext(selected, force = true); kind in setOf("movie", "series") && selected.kind == "xtream" -> catalog(selected, kind, true); else -> open(kind) } }
    fun back() {
        when {
            directCatchup -> { directCatchup = false; dismiss(); (catchupClosed ?: closed).invoke() }
            detailItem != null -> closeDetails()
            archive != null -> catchupChannels()
            parentSeries != null -> open(if (kind == "favorites") "favorites" else "series", focusContent = true, keepCategory = true)
            gridFromHome && kind in setOf("movie", "series") && selectedSource()?.let { wholeCatalog(it, kind) } != null -> returnHome()
            panel.focusRailFromContent() -> Unit
            else -> { dismiss(); closed() }
        }
    }
    fun dismiss() {
        favoriteTask?.cancel(true); ++favoriteEpoch; checkingFavorites = false
        fromBeginning = false; startEpisode = null; pageTask?.cancel(true); ++pageEpoch
        panel.stopCatalog(); panel.hideKeyboard(); panel.pauseImages(); panel.visibility = View.GONE; task?.cancel(true); ++epoch
        catalogImports.forEach { (key, import) -> if (!import.isDone) { catalogGenerations.remove(key); import.cancel(true) } }
        val ended = visit++
        disk.execute { repository.release(ended) }
    }
    fun invalidateProvider(id: String) {
        cancelStart(); ++epoch; task?.cancel(true); favoriteTask?.cancel(true); ++favoriteEpoch
        catalogImports.values.forEach { it.cancel(true) }; catalogImports.clear(); catalogGenerations.clear()
        pageTask?.cancel(true); ++pageEpoch; activeSnapshot = null; activeCatalog = ""
        catalogs.clear(); browsePages.clear(); categoryNames.clear(); selectedCategories.clear(); episodeCache.clear(); details.clear(); seriesSpots.clear()
        parentSeries = null; detailItem = null; episodeList = emptyList(); playingEpisodes = emptyList()
        favoriteSource = ""; favoriteState = LibraryFavorites(); refreshTried.keys.removeAll { it.startsWith("$id:") }; repository.invalidate(id)
    }
    fun destroy() { refresher.shutdownNow(); repository.close(); cancelStart(); favoriteTask?.cancel(true); favoriteWorker.shutdownNow(); catalogImports.values.forEach { it.cancel(true) }; pageTask?.cancel(true); catalogReader.shutdownNow(); panel.closeImages(); disk.shutdown(); positions.shutdownNow(); destroyed = true; task?.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object {
        /** A failed background refresh is tried again after this long, at the next visit to the rows. */
        const val REFRESH_RETRY = 30 * 60000L
        /** A catalog read from this TV usually opens faster than this; only a slower one shows the loading steps. */
        const val LOADING_DELAY_MS = 300L
        /** How old the saved copy on the rows is, and whether a background refresh is checking for new titles. */
        fun savedAge(updated: Long, now: Long, checking: Boolean = false): String {
            val known = updated > 0L && now >= updated
            val days = if (known) (now - updated) / 86400000L else 0L
            val age = when {
                !known -> "Saved on this TV"
                days < 1 -> "Saved today"
                days < 2 -> "Saved yesterday"
                else -> "Saved $days days ago"
            }
            return age + when {
                checking -> " · Checking for new titles…"
                days >= 2 -> " · Refresh for new titles"
                else -> ""
            }
        }
        const val HOME_RECENT = "recent"; const val HOME_TOP = "top"; const val HOME_CONTINUE = "continue"; const val HOME_WATCHLIST = "watchlist"; const val HOME_PERSONAL = 20; fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) } }
}

package io.github.mot1us.spare

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.core.view.isVisible
import java.util.concurrent.Executors

/** Provider I/O and encrypted catalog reads stay off the UI thread. No catalog fetch at startup. */
class LibraryController(private val activity: Activity, private val store: SecureStore, button: (String, () -> Unit) -> Button,
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
    val panel = MediaBrowserPanel(activity, button, { open(it) }, { refresh() }, { dismiss(); live() })
    val opened get() = panel.isVisible
    fun open(mode: String = kind) {
        parentSeries = null; archive = null; kind = mode; panel.visibility = View.VISIBLE
        val selected = source()
        task?.cancel(true); ++epoch
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
    private fun catalog(selected: Source, mode: String, force: Boolean) {
        panel.submit(emptyList()); panel.page(if (mode == "movie") "Movies" else "Series", "Loading your provider’s library…", true); panel.focusRows()
        request { token ->
            val cached = runCatching { store.catalog(selected, mode) }.getOrNull()
            if (cached != null) ui(token) { showItems(cached.second, if (mode == "movie") "Movies" else "Series", "Saved library · refreshing in the background", false) }
            if (!force && cached != null && System.currentTimeMillis() - cached.first in 0 until 6 * 3600000L) { ui(token) { panel.page(if (mode == "movie") "Movies" else "Series", "${cached.second.size} titles · ${selected.name}", false) }; return@request }
            runCatching { VodCatalog.load(selected, mode) }.onSuccess { items ->
                if (Thread.currentThread().isInterrupted) return@onSuccess
                runCatching { store.cacheCatalog(selected, mode, items) }
                ui(token) { showItems(items, if (mode == "movie") "Movies" else "Series", "${items.size} titles · ${selected.name}", false) }
            }.onFailure { failure -> ui(token) { panel.page(if (mode == "movie") "Movies" else "Series", (if (cached != null) "Using saved library · " else "Library unavailable · ") + SourceErrors.message(failure) + " · Refresh to retry.", false) } }
        }
    }
    private fun showItems(items: List<OnDemand>, title: String, detail: String, busy: Boolean) {
        panel.page(title, detail, busy)
        panel.submit(items.map { item -> BrowserRow(item.id, item.title, if (item.kind == "series") "${item.category} · Choose episodes" else item.category) { select(item) } }, items.associate { it.id to it.category }, reset = false)
    }
    private fun select(item: OnDemand) {
        val selected = source() ?: return
        if (item.kind == "series") { episodes(selected, item); return }
        request { token ->
            val position = runCatching { store.watchProgress(selected).firstOrNull { it.item.id == item.id }?.position ?: 0L }.getOrDefault(0L)
            ui(token) { playingEpisodes = if (item.kind == "episode") episodeList.filter { it.seriesId == item.seriesId } else emptyList(); panel.hideKeyboard(); panel.visibility = View.GONE; play(item, position) }
        }
    }
    private fun episodes(selected: Source, series: OnDemand) {
        parentSeries = series; panel.submit(emptyList()); panel.page(series.title, "Loading seasons and episodes…", true)
        request { token -> runCatching { VodCatalog.episodes(selected, series) }.onSuccess { entries -> ui(token) {
            episodeList = entries; showItems(entries, series.title, "${entries.size} episodes · Back returns to Series", false); panel.focusRows()
        } }.onFailure { ui(token) { panel.page(series.title, "Episodes unavailable · ${SourceErrors.message(it)} · Refresh to retry.", false); panel.focusRows() } } }
    }
    private fun continueWatching(selected: Source) {
        panel.submit(emptyList()); panel.page("Continue watching", "Loading saved positions…", true)
        request { token ->
            val entries = runCatching { store.watchProgress(selected).sortedByDescending { it.updated } }.getOrDefault(emptyList())
            ui(token) {
                panel.page("Continue watching", "Saved on this TV for ${selected.name}. Finished titles disappear automatically.", false)
                panel.submit(entries.map { entry -> BrowserRow(entry.item.id, entry.item.title, "Resume at ${duration(entry.position)}") { select(entry.item) } }); panel.focusRows()
            }
        }
    }
    private fun catchupChannels() {
        task?.cancel(true); ++epoch; parentSeries = null; archive = null
        val channels = guide().channels.filter { Catchup.supported(it) }
        panel.page("Catch-up", if (channels.isEmpty()) "This playlist has no advertised archives. Refresh the playlist if archive support was recently added." else "Choose a channel to browse its provider archive. Only completed programmes are listed.", false)
        panel.submit(channels.map { channel -> BrowserRow(channel.id, channel.name, "${channel.catchupDays} days · ${channel.group}") { openCatchup(channel) } }, channels.associate { it.id to it.group }); panel.focusRows()
    }
    fun openCatchup(channel: Channel) {
        kind = "catchup"; archive = channel; parentSeries = null; panel.visibility = View.VISIBLE
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
            panel.submit(items.map { (programme, item) -> BrowserRow(item.id, item.title, "${GuideClock.dayTime(programme.start)}–${GuideClock.time(programme.end)}") { select(item) } }, items.associate { (programme, item) -> item.id to java.time.Instant.ofEpochMilli(programme.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() })
        }
        val cached = guide().programmes[guide().key(channel)].orEmpty()
        show(cached, if (selected.kind == "xtream") "Loading provider archive…" else "Archive availability depends on the provider and XMLTV history.", selected.kind == "xtream"); panel.focusRows()
        if (selected.kind != "xtream") return
        request { token -> runCatching { Catchup.load(selected, channel) }.onSuccess { entries -> ui(token) { show(entries, "${channel.catchupDays}-day provider archive · Back returns to channels", false); panel.focusRows() } }
            .onFailure { failure -> ui(token) { show(cached, "Using saved guide · ${SourceErrors.message(failure)} · Refresh to retry.", false) } } }
    }
    fun reopen() { panel.visibility = View.VISIBLE; panel.focusRows() }
    fun next(item: OnDemand) = playingEpisodes.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { playingEpisodes.getOrNull(it + 1) }
    fun refresh() { val selected = source() ?: return; when { archive != null -> openCatchup(archive!!); parentSeries != null -> episodes(selected, parentSeries!!); kind in setOf("movie", "series") && selected.kind == "xtream" -> catalog(selected, kind, true); else -> open(kind) } }
    fun back() { when { archive != null -> catchupChannels(); parentSeries != null -> open("series"); else -> { dismiss(); closed() } } }
    fun dismiss() { panel.hideKeyboard(); panel.visibility = View.GONE; task?.cancel(true); ++epoch }
    fun destroy() { destroyed = true; task?.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object { fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) } }
}

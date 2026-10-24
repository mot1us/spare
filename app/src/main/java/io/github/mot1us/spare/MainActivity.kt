package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

@UnstableApi
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val imports = Executors.newFixedThreadPool(2)
    private var importTask: java.util.concurrent.Future<*>? = null
    private val logos = LogoLoader()
    private lateinit var store: SecureStore
    private lateinit var updater: Updater
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var frameRates: FrameRates
    private lateinit var root: FrameLayout
    private lateinit var guidePanel: LinearLayout
    private lateinit var channelInfo: TextView
    private lateinit var status: TextView
    private lateinit var times: TimeRuler
    private lateinit var nowPlaying: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: GuideAdapter
    private lateinit var groupBar: LinearLayout
    private lateinit var favoritesButton: Button
    private lateinit var searchButton: Button
    private lateinit var updateButton: Button
    private lateinit var retryPanel: LinearLayout
    private var sources = emptyList<Source>()
    private var source: Source? = null
    private var guide = Guide.EMPTY
    private var current: Channel? = null
    private var focused: Channel? = null
    private var favorites = emptySet<String>()
    private var favoritesOnly = false
    private var group = ""
    private var query = ""
    @Volatile private var generation = 0
    private var guideVisible = true
    private var started = false
    private var closed = false
    private var windowStart = nowWindow()
    private var retryCount = 0
    private val retry = Runnable { if (started && current != null) { player.prepare(); player.play() } }
    private val hideNowPlaying = Runnable { nowPlaying.visibility = View.GONE }
    private val minuteTick = object : Runnable {
        override fun run() { if (!closed) { if (guideVisible) { adapter.notifyItemRangeChanged(0, adapter.itemCount); current?.let(::showInfo) }; handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        store = SecureStore(this)
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val http = DefaultHttpDataSource.Factory().setUserAgent("Spare/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(12000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(false)
        val providerFactory = DataSource.Factory { ProviderDataSource(http.createDataSource()) { source?.allowHttp == true } }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(providerFactory))
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        video.player = player
        frameRates = FrameRates(this, video.videoSurfaceView as SurfaceView)
        player.setVideoFrameMetadataListener { _, _, format, _ -> frameRates.request(format.frameRate) }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!started) return
                if (retryCount++ < 2) { status.text = "Reconnecting…"; handler.postDelayed(retry, retryCount * 1500L) }
                else { retryPanel.visibility = View.VISIBLE; retryPanel.getChildAt(1).requestFocus(); status.text = "Playback unavailable" }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retryPanel.visibility = View.GONE; status.text = "" }
                else if (state == Player.STATE_BUFFERING) status.text = "Loading channel…"
            }
            override fun onRenderedFirstFrame() { retryCount = 0 }
        })
        updater = Updater(this, store) { release -> updateButton.text = release?.let { "Update ${it.version}" } ?: "Updates" }
        sources = runCatching { store.sources() }.getOrElse {
            notice("Source storage unavailable", "Android could not unlock saved credentials. Add your source again; no credentials have been sent elsewhere.")
            emptyList()
        }
        val selected = sources.firstOrNull { it.id == store.active } ?: sources.firstOrNull()
        if (selected == null) { status.text = "Add a source to start watching"; handler.post { editSource(null) } }
        else load(selected)
        handler.post(minuteTick)
        updater.check()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false; setPadding(dp(14), 0, dp(14), 0)
        minHeight = dp(44); minimumHeight = dp(44)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); setStroke(dp(1), Color.WHITE) }
        background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(), shape(Color.BLACK)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }; setContentView(root)
        guidePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(28), dp(20), dp(28), dp(20)) }
        root.addView(guidePanel, FrameLayout.LayoutParams(-1, -1))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(260), 0) }
        heading.addView(text("Spare", 25f))
        channelInfo = text("A minimal, free IPTV player", 17f).apply { maxLines = 4 }; heading.addView(channelInfo)
        status = text("", 14f); heading.addView(status)
        guidePanel.addView(heading, LinearLayout.LayoutParams(-1, dp(140)))
        val actions = LinearLayout(this)
        actions.addView(button("Sources") { showSources() })
        favoritesButton = button("Favorites") { favoritesOnly = !favoritesOnly; applyFilter() }; actions.addView(favoritesButton)
        searchButton = button("Search") { search() }; actions.addView(searchButton)
        groupBar = LinearLayout(this); actions.addView(groupBar)
        actions.addView(button("Playback") { playbackMenu() })
        updateButton = button("Updates") { updater.offer() }; actions.addView(updateButton)
        guidePanel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, LinearLayout.LayoutParams(-1, dp(48)))
        times = TimeRuler(this) { windowStart }
        guidePanel.addView(times, LinearLayout.LayoutParams(-1, dp(28)))
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; clipToPadding = false }
        adapter = GuideAdapter(this, logos, { source }, { guide }, { favorites }, { windowStart },
            { tune(it); showGuide(false) }, { channel ->
                source?.let { store.toggleFavorite(it, channel); favorites = store.favorites(it); applyFilter() }
            }, { channel -> focused = channel; showInfo(channel) }, { amount ->
                windowStart = (windowStart + amount * 1800000L).coerceIn(nowWindow(), nowWindow() + 2 * 86400000L)
                updateTimes(); adapter.notifyItemRangeChanged(0, adapter.itemCount)
            })
        list.adapter = adapter
        guidePanel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        video = PlayerView(this).apply {
            useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            contentDescription = "Current channel video"
        }
        root.addView(video)
        nowPlaying = text("", 17f).apply { setBackgroundColor(Color.BLACK); setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE }
        root.addView(nowPlaying, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(28); bottomMargin = dp(24) })
        retryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20)); setBackgroundColor(Color.BLACK)
            addView(text("Could not play this channel. Check your connection or provider."))
            addView(button("Retry") { retryCount = 0; retryPanel.visibility = View.GONE; current?.let { tune(it) } })
            addView(button("Guide") { visibility = View.GONE; showGuide(true) })
            visibility = View.GONE
        }
        root.addView(retryPanel, FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        updateTimes(); showGuide(true)
    }

    private fun load(selected: Source) {
        val epoch = ++generation
        importTask?.cancel(true)
        handler.removeCallbacks(retry); player.stop(); frameRates.clear(); current = null
        source = selected; focused = null; store.active = selected.id; favorites = store.favorites(selected)
        group = ""; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; showGuide(true)
        importTask = imports.submit {
            val cached = runCatching { store.cached(selected) }.getOrNull()
            if (cached != null) ui(epoch) { accept(cached, resume = true); status.text = "Refreshing guide…" }
            val loaded = runCatching {
                val lineup = if (selected.kind == "xtream") Importers.xtream(selected)
                else Importers.m3u(Network.get(selected.url, selected.allowHttp).toString(Charsets.UTF_8), selected.allowHttp)
                Guide(lineup.channels, cached?.programmes.orEmpty()).also { initial -> ui(epoch) { accept(initial, resume = cached == null) } } to
                    selected.epgUrl.ifBlank { lineup.epgUrl }
            }
            loaded.onSuccess { (initial, epg) ->
                val programmes = if (epg.isNotBlank()) runCatching {
                    Importers.xmltv(Network.get(epg, selected.allowHttp, 64 * 1024 * 1024), initial.channels.map { it.guideId }.filter { it.isNotEmpty() }.toSet())
                } else Result.success(emptyMap())
                val fresh = Guide(initial.channels, programmes.getOrDefault(cached?.programmes.orEmpty()))
                if (!Thread.currentThread().isInterrupted && generation == epoch) runCatching { store.cache(selected, fresh) }
                ui(epoch) { accept(fresh, resume = false); status.text = if (programmes.isFailure) "Channels ready · guide refresh failed" else "" }
            }.onFailure { ui(epoch) {
                status.text = if (cached != null) "Provider unavailable · using saved guide" else "Could not load source. Check its address and credentials."
                if (cached == null) notice("Source unavailable", "Could not load live channels. Check the source settings, HTTP consent, and your provider connection.")
            } }
        }
    }
    private fun ui(epoch: Int, task: () -> Unit) { handler.post { if (!closed && epoch == generation) task() } }
    private fun accept(next: Guide, resume: Boolean) {
        guide = next; updateGroups(); applyFilter()
        if (resume && current == null) {
            val last = source?.let { selected -> next.channels.firstOrNull { it.id == store.last(selected) } } ?: next.channels.firstOrNull()
            if (last != null) { tune(last); showGuide(false) }
        } else current = next.channels.firstOrNull { it.id == current?.id } ?: current
        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
    }
    private fun tune(channel: Channel) {
        val selected = source ?: return
        if (!Network.allowed(channel.url, selected.allowHttp)) { toast("This channel needs an allowed HTTP or HTTPS address"); return }
        handler.removeCallbacks(retry); retryCount = 0; retryPanel.visibility = View.GONE
        current = channel; store.setLast(selected, channel); showInfo(channel)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        player.setMediaItem(MediaItem.fromUri(channel.url.toUri()))
        if (started) { player.prepare(); player.play() }
        if (!guideVisible) showNowPlaying()
    }
    private fun showInfo(channel: Channel) {
        val programme = guide.at(channel, System.currentTimeMillis())
        channelInfo.text = channel.name + (programme?.let { "\n${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}  ${it.title}\n${it.description}" } ?: "\nNo programme information")
    }
    private fun applyFilter() {
        val filtered = guide.channels.filter { (!favoritesOnly || it.id in favorites) && (group.isBlank() || it.group == group) && (query.isBlank() || it.name.contains(query, true)) }
        adapter.submit(filtered)
        favoritesButton.text = if (favoritesOnly) "Favorites ✓" else "Favorites"
        searchButton.text = if (query.isEmpty()) "Search" else "Search: $query"
        if (filtered.isEmpty() && guide.channels.isNotEmpty()) status.text = "No channels match this filter"
    }
    private fun updateGroups() {
        groupBar.removeAllViews()
        val groups = listOf("") + guide.channels.map { it.group }.distinct()
        groupBar.addView(button("Group: " + group.ifBlank { "All" }.take(24)) {
            AlertDialog.Builder(this).setTitle("Channel group").setSingleChoiceItems(groups.map { it.ifBlank { "All groups" } }.toTypedArray(), groups.indexOf(group)) { dialog, index ->
                group = groups[index]; dialog.dismiss(); updateGroups(); applyFilter()
            }.setNegativeButton("Close", null).show()
        })
    }
    private fun updateTimes() {
        times.invalidate()
    }
    private fun showGuide(show: Boolean) {
        guideVisible = show
        guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.visibility = View.GONE
        video.layoutParams = if (show) FrameLayout.LayoutParams(dp(240), dp(135), Gravity.TOP or Gravity.END).apply { topMargin = dp(20); marginEnd = dp(28) }
        else FrameLayout.LayoutParams(-1, -1)
        if (show) {
            val position = adapter.channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            list.scrollToPosition(position)
            list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: favoritesButton.requestFocus() }
        } else showNowPlaying()
    }
    private fun showNowPlaying() {
        val channel = current ?: return
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.text = channel.name + (guide.at(channel, System.currentTimeMillis())?.let { "  ·  ${it.title}" } ?: "")
        nowPlaying.visibility = View.VISIBLE
        handler.postDelayed(hideNowPlaying, 1800)
    }
    private fun search() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true); setText(query); hint = "Channel name" }
        AlertDialog.Builder(this).setTitle("Find channel").setView(input)
            .setPositiveButton("Search") { _, _ -> query = input.text.toString().trim().take(128); applyFilter() }
            .setNeutralButton("Clear") { _, _ -> query = ""; applyFilter() }.setNegativeButton("Cancel", null).show()
    }
    private fun showSources() {
        val builder = AlertDialog.Builder(this).setTitle("Sources")
            .setSingleChoiceItems(sources.map { it.name }.toTypedArray(), sources.indexOfFirst { it.id == source?.id }) { dialog, which -> dialog.dismiss(); load(sources[which]) }
            .setPositiveButton("Add") { _, _ -> editSource(null) }.setNegativeButton("Close", null)
        if (source != null) builder.setNeutralButton("Edit current") { _, _ -> editSource(source) }
        builder.show()
    }
    private fun editSource(existing: Source?) = SourceDialog.show(this, existing, { selected ->
        runCatching { val next = sources.filterNot { it.id == selected.id } + selected; store.saveSources(next); sources = next }
            .onSuccess { load(selected) }.onFailure { notice("Could not save", "Android could not securely save this source. Try again.") }
    }, { removed ->
        runCatching { val next = sources.filterNot { it.id == removed.id }; store.saveSources(next); store.removeCache(removed); sources = next }
            .onSuccess {
                sources.firstOrNull()?.let(::load) ?: run {
                    ++generation; player.stop(); frameRates.clear(); source = null; current = null; guide = Guide.EMPTY; updateGroups(); applyFilter(); showGuide(true); status.text = "Add a source to start watching"
                }
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    private fun playbackMenu() {
        AlertDialog.Builder(this).setTitle("Playback").setItems(arrayOf("Audio track", "Subtitles", "Refresh guide", "About Spare", "Open-source licenses")) { _, item ->
            when (item) {
                0 -> tracks(C.TRACK_TYPE_AUDIO)
                1 -> tracks(C.TRACK_TYPE_TEXT)
                2 -> source?.let(::load)
                3 -> notice("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player.\nMIT License · github.com/mot1us/spare\n\nOK: guide · Up/Down: channel\nLong-press a guide channel: favorite\nLeft/Right in guide: browse time\n\nNo app accounts, ads, or analytics. Sources and guide cache stay in encrypted app storage. Provider requests and update checks require network access.\n\nMedia playback: AndroidX Media3 (Apache 2.0). Other notices are included in the source repository.")
                4 -> {
                    val content = listOf("NOTICES.txt", "MIT.txt", "Apache-2.0.txt").joinToString("\n\n") { name -> assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                    val label = text(content, 13f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
                    AlertDialog.Builder(this).setTitle("Open-source licenses").setView(android.widget.ScrollView(this).apply { addView(label) }).setPositiveButton("Close", null).show()
                }
            }
        }.show()
    }
    private fun tracks(type: Int) {
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        val names = arrayListOf(if (type == C.TRACK_TYPE_TEXT) "Off" else "Auto")
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                val f = g.getTrackFormat(i); choices.add(g to i); names.add(listOfNotNull(f.label, f.language, f.codecs).joinToString(" · ").ifBlank { "Track ${choices.size}" })
            }
        }
        AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio").setItems(names.toTypedArray()) { _, index ->
            val selection = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && index == 0)
            if (index > 0) choices[index - 1].let { (g, track) -> selection.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, track)) }
            player.trackSelectionParameters = selection.build()
        }.show()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!guideVisible && retryPanel.visibility != View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val channels = adapter.channels.ifEmpty { guide.channels }
                    if (channels.isNotEmpty()) {
                        val index = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
                        val next = (index + (if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1) + channels.size) % channels.size
                        tune(channels[next])
                    }
                }
                return true
            }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_UP) showGuide(true)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) playbackMenu(); return true }
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Fallback for API 26–32")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the registered OnBackInvokedCallback above.
    override fun onBackPressed() = navigateBack()
    private fun navigateBack() {
        if (retryPanel.isVisible) { retryPanel.visibility = View.GONE; showGuide(true) }
        else if (guideVisible && current != null) showGuide(false)
        else finish()
    }
    override fun onStart() { super.onStart(); started = true; if (::player.isInitialized && current != null) { player.prepare(); player.play() } }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.resume() }
    override fun onStop() { started = false; handler.removeCallbacks(retry); if (::player.isInitialized) player.stop(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun nowWindow() = System.currentTimeMillis() / 1800000L * 1800000L
}

package io.github.mot1us.spare

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ProgressBar
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
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
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
    private lateinit var programmeInfo: TextView
    private lateinit var status: TextView
    private lateinit var times: TimeRuler
    private lateinit var nowPlaying: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: GuideAdapter
    private lateinit var sidebar: RecyclerView
    private lateinit var sidebarAdapter: SidebarAdapter
    private lateinit var filterInfo: TextView
    private lateinit var emptyInfo: TextView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var playbackLoading: LinearLayout
    private lateinit var playbackLoadingText: TextView
    private lateinit var playbackError: TextView
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
    private var filters = GuideFilters()
    private var videoInfo = mutableMapOf<String, VideoInfo>()
    private var providerGroups = emptyList<String>()
    private var browseAll = false
    private var playbackUrl = ""
    @Volatile private var firstFrame = false
    private var expectedMediaId = ""
    private var alternateUsed = false
    @Volatile private var lastVideoInfo: VideoInfo? = null
    @Volatile private var playbackToken = 0
    private lateinit var providerFactory: DataSource.Factory
    private lateinit var mediaFactory: DefaultMediaSourceFactory
    private val tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
    private var group: String? = null
    private var query = ""
    @Volatile private var generation = 0
    private var guideVisible = true
    private var started = false
    private var closed = false
    private var windowStart = nowWindow()
    private var retryCount = 0
    private val retry = Runnable { if (started && current != null) playAttempt() }
    private val startupTimeout = Runnable {
        if (started && current != null && !firstFrame) {
            if (!tryAlternate()) showPlaybackFailure("No video arrived within 15 seconds. Try another channel or choose a stream format in Settings. (STREAM_TIMEOUT)")
        }
    }
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
        providerFactory = DataSource.Factory { ProviderDataSource { source?.allowHttp == true } }
        mediaFactory = DefaultMediaSourceFactory(providerFactory, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags))
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setMediaSourceFactory(mediaFactory)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        video.player = player
        frameRates = FrameRates(this, video.videoSurfaceView as SurfaceView)
        player.setVideoFrameMetadataListener { _, _, format, _ ->
            frameRates.request(format.frameRate)
            if (firstFrame && format.width > 0 && format.height > 0) {
                val info = VideoInfo(format.width, format.height, format.frameRate.takeIf { it.isFinite() && it > 0 } ?: 0f)
                if (info != lastVideoInfo) {
                    val token = playbackToken
                    handler.post { if (firstFrame && token == playbackToken && player.videoFormat?.width == info.width) recordVideo(info) }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!started || current == null) return
                handler.removeCallbacks(startupTimeout)
                if (!firstFrame && tryAlternate()) return
                if (retryCount++ < 1) { playbackLoadingText.text = "Reconnecting…"; playbackLoading.visibility = View.VISIBLE; handler.postDelayed(retry, 1500) }
                else showPlaybackFailure(PlaybackErrors.message(error))
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) { playbackLoading.visibility = View.VISIBLE }
                else if (state == Player.STATE_READY && firstFrame) playbackLoading.visibility = View.GONE
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.timeline.isEmpty || eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != expectedMediaId) return
                firstFrame = true; retryCount = 0; handler.removeCallbacks(startupTimeout); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
                player.videoFormat?.let { format ->
                    if (format.width > 0 && format.height > 0) recordVideo(VideoInfo(format.width, format.height, format.frameRate.takeIf { it.isFinite() && it > 0 } ?: 0f))
                }
            }
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
        isFocusableInTouchMode = true; text = value; textSize = 12f; isAllCaps = false; setPadding(dp(14), 0, dp(14), 0)
        minHeight = dp(34); minimumHeight = dp(34); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE)))
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() }
        background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(), shape(Color.rgb(30, 30, 30))) }
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }; setContentView(root)
        video = PlayerView(this).apply {
            useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); contentDescription = "Current channel video"
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        guidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B1018.toInt(), 0xC90B1018.toInt()))
        }
        root.addView(guidePanel, FrameLayout.LayoutParams(-1, -1))
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        navigation.addView(text("spare", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(10), 0, 0, dp(14)) })
        sidebar = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; tag = "guide-categories" }
        sidebarAdapter = SidebarAdapter(this, { section ->
            favoritesOnly = section.key == "favorites"
            group = if (section.key.startsWith("group:")) section.key.removePrefix("group:") else null
            applyFilter(); focusChannel()
        }, { focusChannel() })
        sidebar.adapter = sidebarAdapter
        navigation.addView(sidebar, LinearLayout.LayoutParams(-1, 0, 1f))
        searchButton = button("Search") { search() }; navigation.addView(searchButton)
        navigation.addView(button("Browse all") { browseAll = !browseAll; group = null; favoritesOnly = false; applyFilter(); focusChannel() }.apply { tag = "browse-all" })
        navigation.addView(button("Sources") { showSources() })
        navigation.addView(button("Settings") { playbackMenu() })
        updateButton = button("Updates") { updater.offer() }; navigation.addView(updateButton)
        for (i in 1 until navigation.childCount) if (navigation.getChildAt(i) is Button) {
            navigation.getChildAt(i).layoutParams = LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(6) }
        }
        guidePanel.addView(navigation, LinearLayout.LayoutParams(dp(160), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guidePanel.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        channelInfo = text("Your TV, kept simple.", 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        content.addView(channelInfo, LinearLayout.LayoutParams(-1, dp(28)))
        programmeInfo = text("Add your playlist to start watching.", 12f).apply { maxLines = 2; setTextColor(Color.LTGRAY) }
        content.addView(programmeInfo, LinearLayout.LayoutParams(-1, dp(38)))
        status = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 1 }
        content.addView(status, LinearLayout.LayoutParams(-1, dp(18)))
        val timeline = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filterInfo = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        timeline.addView(filterInfo, LinearLayout.LayoutParams(0, dp(30), 1f))
        timeline.addView(button("‹") { moveTime(-1) }.apply { contentDescription = "Earlier guide" })
        timeline.addView(button("Now") { windowStart = nowWindow(); updateTimes(); adapter.notifyItemRangeChanged(0, adapter.itemCount) })
        timeline.addView(button("›") { moveTime(1) }.apply { contentDescription = "Later guide" })
        content.addView(timeline, LinearLayout.LayoutParams(-1, dp(38)))
        times = TimeRuler(this) { windowStart }; content.addView(times, LinearLayout.LayoutParams(-1, dp(24)))
        emptyInfo = text("", 13f).apply { setPadding(dp(12), dp(24), dp(12), dp(24)); visibility = View.GONE }
        content.addView(emptyInfo)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; clipToPadding = false; tag = "channel-guide" }
        adapter = GuideAdapter(this, logos, { source }, { guide }, { favorites }, { windowStart },
            { tune(it); showGuide(false) }, { channel ->
                source?.let { store.toggleFavorite(it, channel); favorites = store.favorites(it); applyFilter() }
            }, { channel -> focused = channel; showInfo(channel) }, { moveTime(it) }, { focusSidebar() })
        list.adapter = adapter; content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("← Categories     OK Watch     Hold OK Favorite     Back Guide / TV", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) })
        nowPlaying = text("", 15f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE }
        root.addView(nowPlaying, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(28); bottomMargin = dp(24) })
        playbackLoading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(0xE60B1018.toInt()); visibility = View.GONE
            addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        playbackLoadingText = text("Opening channel…", 13f).apply { setPadding(dp(14), 0, 0, 0) }; playbackLoading.addView(playbackLoadingText)
        root.addView(playbackLoading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        loadingPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(0xF20B1018.toInt()); visibility = View.GONE; tag = "import-progress" }
        loadingText = text("Loading playlist…", 11f); loadingPanel.addView(loadingText)
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }; loadingPanel.addView(loadingBar, LinearLayout.LayoutParams(-1, dp(10)))
        root.addView(loadingPanel, FrameLayout.LayoutParams(dp(300), -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(20); bottomMargin = dp(16) })
        retryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20)); setBackgroundColor(0xF20B1018.toInt()); visibility = View.GONE }
        playbackError = text("", 14f); retryPanel.addView(playbackError, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val retryActions = LinearLayout(this)
        retryActions.addView(button("Retry") { current?.let { tune(it) } })
        retryActions.addView(button("Guide") { retryPanel.visibility = View.GONE; showGuide(true) }); retryPanel.addView(retryActions)
        root.addView(retryPanel, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER))
        updateTimes(); showGuide(true)
    }
    private fun moveTime(amount: Int) {
        windowStart = (windowStart + amount * 1800000L).coerceIn(nowWindow(), nowWindow() + 2 * 86400000L)
        updateTimes(); adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }
    private fun focusChannel() {
        val position = adapter.channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        list.scrollToPosition(position)
        list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: focusSidebar() }
    }
    private fun focusSidebar() { sidebar.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: searchButton.requestFocus() }
    private fun importProgress(epoch: Int, stage: String, done: Long = 0, total: Long = -1) = ui(epoch) {
        loadingPanel.visibility = View.VISIBLE; loadingBar.isIndeterminate = total <= 0
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
        if (percent != null) loadingBar.progress = percent
        loadingText.text = stage + (percent?.let { " · $it%" } ?: "…")
    }

    private fun load(selected: Source) {
        val epoch = ++generation
        importTask?.cancel(true)
        firstFrame = false; expectedMediaId = ""; playbackUrl = ""
        handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); playbackToken++; player.stop(); frameRates.clear(); current = null; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        source = selected; focused = null; store.active = selected.id; favorites = store.favorites(selected)
        filters = store.filters(selected); videoInfo = store.videoInfo(selected); browseAll = false
        group = null; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; importProgress(epoch, "Opening playlist"); showGuide(true)
        importTask = imports.submit {
            val cached = runCatching { store.cached(selected) }.getOrNull()
            if (cached != null) ui(epoch) { accept(cached, resume = true); status.text = "Refreshing guide…" }
            val loaded = runCatching {
                val lineup = if (selected.kind == "xtream") Importers.xtream(selected) { stage, done, total -> importProgress(epoch, stage, done, total) }
                else Importers.m3u(Network.get(selected.url, selected.allowHttp, progress = { done, total -> importProgress(epoch, "Downloading channels", done, total) }).toString(Charsets.UTF_8), selected.allowHttp)
                Guide(lineup.channels, cached?.programmes.orEmpty()).also { initial -> ui(epoch) { accept(initial, resume = cached == null) } } to
                    selected.epgUrl.ifBlank { lineup.epgUrl }
            }
            loaded.onSuccess { (initial, epg) ->
                val programmes = if (epg.isNotBlank()) runCatching {
                    val bytes = Network.get(epg, selected.allowHttp, 64 * 1024 * 1024) { done, total -> importProgress(epoch, "Downloading guide", done, total) }
                    importProgress(epoch, "Reading guide")
                    Importers.xmltv(bytes, initial.channels.map { it.guideId }.filter { it.isNotEmpty() }.toSet())
                } else Result.success(emptyMap())
                val fresh = Guide(initial.channels, programmes.getOrDefault(cached?.programmes.orEmpty()))
                if (!Thread.currentThread().isInterrupted && generation == epoch) runCatching { store.cache(selected, fresh) }
                ui(epoch) { loadingPanel.visibility = View.GONE; accept(fresh, resume = false); status.text = if (programmes.isFailure) "Channels ready · guide refresh failed" else "" }
            }.onFailure { failure -> ui(epoch) {
                loadingPanel.visibility = View.GONE
                val detail = SourceErrors.message(failure)
                status.text = if (cached != null) "Using saved guide · $detail" else detail
                if (cached == null && !isFinishing) AlertDialog.Builder(this).setTitle("Could not load source").setMessage(detail)
                    .setPositiveButton("Edit source") { _, _ -> editSource(selected) }
                    .setNeutralButton("Retry") { _, _ -> load(selected) }.setNegativeButton("Close", null).show()
            } }
        }
    }
    private fun ui(epoch: Int, task: () -> Unit) { handler.post { if (!closed && epoch == generation) task() } }
    private fun accept(next: Guide, resume: Boolean) {
        guide = next; updateGroups(); applyFilter()
        if (resume && current == null) {
            val last = source?.let { selected -> adapter.channels.firstOrNull { it.id == store.last(selected) } } ?: adapter.channels.firstOrNull()
            if (last != null) { tune(last); showGuide(false) }
        } else current = next.channels.firstOrNull { it.id == current?.id } ?: current
        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
    }
    private fun tune(channel: Channel) {
        val selected = source ?: return
        if (!Network.allowed(channel.url, selected.allowHttp)) { showPlaybackFailure("This channel needs an allowed HTTP or HTTPS address. Check this source’s HTTP setting."); return }
        handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); playbackToken++
        retryCount = 0; firstFrame = false; alternateUsed = false; lastVideoInfo = null; retryPanel.visibility = View.GONE
        current = channel; store.setLast(selected, channel); showInfo(channel)
        playbackUrl = if (selected.kind == "xtream") when (store.streamFormat(selected)) { "ts" -> StreamUrls.format(channel.url, "ts") ?: channel.url; "hls" -> StreamUrls.format(channel.url, "m3u8") ?: channel.url; else -> channel.url } else channel.url
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        if (started) playAttempt()
        if (!guideVisible) showNowPlaying()
    }
    private fun playAttempt() {
        if (playbackUrl.isBlank()) return
        handler.removeCallbacks(startupTimeout); handler.removeCallbacks(retry)
        playbackToken++; expectedMediaId = "${current?.id}:$playbackToken"
        firstFrame = false; lastVideoInfo = null; playbackLoadingText.text = "Opening channel…"; playbackLoading.visibility = View.VISIBLE
        val item = MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId(expectedMediaId).build()
        val media = if (playbackUrl.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(providerFactory).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
        } else mediaFactory.createMediaSource(item)
        player.setMediaSource(media); player.prepare(); player.play()
        handler.postDelayed(startupTimeout, 15000)
    }
    private fun tryAlternate(): Boolean {
        val selected = source ?: return false
        if (selected.kind != "xtream" || store.streamFormat(selected) != "auto" || alternateUsed) return false
        val alternate = StreamUrls.alternate(playbackUrl) ?: return false
        alternateUsed = true; playbackUrl = alternate; playAttempt(); playbackLoadingText.text = "Trying alternate stream format…"
        return true
    }
    private fun showPlaybackFailure(detail: String) {
        handler.removeCallbacks(startupTimeout); handler.removeCallbacks(retry)
        player.stop(); playbackLoading.visibility = View.GONE; playbackError.text = detail; retryPanel.visibility = View.VISIBLE
        (retryPanel.getChildAt(1) as ViewGroup).getChildAt(0).requestFocus()
    }
    private fun recordVideo(info: VideoInfo) {
        val selected = source ?: return; val channel = current ?: return
        lastVideoInfo = info
        if (videoInfo[channel.id] == info) return
        videoInfo[channel.id] = info; store.saveVideoInfo(selected, videoInfo)
        applyFilter(); if (!guideVisible || focused?.id == channel.id) showInfo(channel)
    }
    private fun showInfo(channel: Channel) {
        val programme = guide.at(channel, System.currentTimeMillis())
        channelInfo.text = programme?.title ?: channel.name
        val quality = videoInfo[channel.id]?.label() ?: "Quality unverified"
        programmeInfo.text = channel.name + "  ·  " + quality + (programme?.let { "  ·  ${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}\n${it.description}" } ?: "\nNo programme information")
    }
    private fun applyFilter() {
        val filtered = guide.channels.filter { (!favoritesOnly || it.id in favorites) && ((group == null) || it.group == group) &&
            (query.isBlank() || it.name.contains(query, true)) && (browseAll || filters.matches(it, videoInfo[it.id])) }
        adapter.submit(filtered)
        searchButton.text = if (query.isEmpty()) "Search" else "Search: $query"
        root.findViewWithTag<Button>("browse-all")?.text = if (browseAll) "My guide" else "Browse all"
        val selection = if (favoritesOnly) "Favorites" else group
        val profileLabel = if (browseAll) "Filters paused" else filters.summary().takeUnless { it == "All channels" && selection != null }
        filterInfo.text = "${filtered.size} channels · " + listOfNotNull(selection, profileLabel).joinToString(" · ")
        emptyInfo.visibility = if (filtered.isEmpty() && guide.channels.isNotEmpty()) View.VISIBLE else View.GONE
        emptyInfo.text = if (!browseAll && (filters.sixtyOnly || filters.minHeight > 0)) "No verified channels match yet. Choose Browse all, then play channels to verify their quality. Unknown quality stays hidden in My guide."
            else "No channels match. Try another category, clear Search, or choose Browse all."
        updateGroups()
    }
    private fun updateGroups() {
        providerGroups = guide.channels.map { it.group }.distinct()
        val visibleGroups = providerGroups.filter { browseAll || filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection(if (browseAll) "All channels" else "My guide", "all", !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", favoritesOnly)) + visibleGroups.map { GuideSection(it.ifBlank { "Uncategorized" }, "group:$it", !favoritesOnly && group == it && (group != null)) })
    }
    private fun updateTimes() { times.invalidate() }
    private fun showGuide(show: Boolean) {
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideNowPlaying); nowPlaying.visibility = View.GONE
        if (show) focusChannel() else showNowPlaying()
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
                    ++generation; importTask?.cancel(true); handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); playbackToken++; player.stop(); frameRates.clear(); loadingPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE; source = null; current = null; filters = GuideFilters(); videoInfo.clear(); guide = Guide.EMPTY; updateGroups(); applyFilter(); showGuide(true); status.text = "Add a source to start watching"
                }
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    private fun playbackMenu() {
        val choices = mutableListOf("Guide filters", "Audio track", "Subtitles", "Refresh playlist & guide", "About Spare", "Open-source licenses", "Exit Spare")
        if (source?.kind == "xtream") choices.add(1, "Stream format")
        AlertDialog.Builder(this).setTitle("Settings").setItems(choices.toTypedArray()) { _, item ->
            when (choices[item]) {
                "Guide filters" -> filterMenu()
                "Stream format" -> streamFormatMenu()
                "Audio track" -> tracks(C.TRACK_TYPE_AUDIO)
                "Subtitles" -> tracks(C.TRACK_TYPE_TEXT)
                "Refresh playlist & guide" -> source?.let(::load)
                "Exit Spare" -> finish()
                "About Spare" -> notice("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player.\nMIT License · github.com/mot1us/spare\n\nOK: guide · Up/Down: channel\nHold OK on a guide channel: favorite\nLeft: categories · Back: guide / TV\n\nQuality filters use observed playback, never channel names. Unknown channels stay hidden when quality filters are enabled; Browse all lets you verify them. No background stream scanning.\n\nNo app accounts, ads, or analytics. Credentials and guide cache stay in encrypted app storage.")
                "Open-source licenses" -> {
                    val content = listOf("NOTICES.txt", "MIT.txt", "Apache-2.0.txt").joinToString("\n\n") { name -> assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                    val label = text(content, 12f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
                    AlertDialog.Builder(this).setTitle("Open-source licenses").setView(android.widget.ScrollView(this).apply { addView(label) }).setPositiveButton("Close", null).show()
                }
            }
        }.show()
    }
    private fun saveFilters(next: GuideFilters) {
        source?.let { store.setFilters(it, next) }; filters = next; browseAll = false; group = null; applyFilter()
    }
    private fun filterMenu() {
        if (source == null) { toast("Add a source first"); return }
        AlertDialog.Builder(this).setTitle("My guide · ${filters.summary()}").setItems(arrayOf("MLB + NFL · FHD+ · 60 fps preset", "Minimum resolution", "60 fps only: ${if (filters.sixtyOnly) "On" else "Off"}", "Choose categories", "Reset filters")) { _, item ->
            when (item) {
                0 -> {
                    val sports = providerGroups.filter { Regex("(?i)\\b(MLB|NFL)\\b").containsMatchIn(it) }.toSet()
                    saveFilters(GuideFilters(1080, true, sports))
                    notice("Sports guide saved", "FHD or better and 59.94/60 fps only. " + if (sports.isEmpty()) "No MLB/NFL categories were found. Choose your provider’s sports categories in Guide filters. Use Browse all to play and verify channels." else "${sports.size} MLB/NFL categories selected. Use Browse all to play and verify channels; unverified channels stay hidden in My guide.")
                }
                1 -> {
                    val heights = listOf(0, 720, 1080, 2160)
                    AlertDialog.Builder(this).setTitle("Minimum resolution").setSingleChoiceItems(arrayOf("Any", "HD · 720p or better", "FHD · 1080p or better", "4K · 2160p or better"), heights.indexOf(filters.minHeight)) { dialog, which -> saveFilters(filters.copy(minHeight = heights[which])); dialog.dismiss() }.setNegativeButton("Cancel", null).show()
                }
                2 -> { saveFilters(filters.copy(sixtyOnly = !filters.sixtyOnly)); filterMenu() }
                3 -> {
                    val chosen = filters.groups.toMutableSet()
                    AlertDialog.Builder(this).setTitle("Categories · none selected means all").setMultiChoiceItems(providerGroups.map { it.ifBlank { "Uncategorized" } }.toTypedArray(), providerGroups.map { it in chosen }.toBooleanArray()) { _, which, checked -> if (checked) chosen.add(providerGroups[which]) else chosen.remove(providerGroups[which]) }
                        .setPositiveButton("Save") { _, _ -> saveFilters(filters.copy(groups = chosen)) }.setNeutralButton("All") { _, _ -> saveFilters(filters.copy(groups = emptySet())) }.setNegativeButton("Cancel", null).show()
                }
                4 -> saveFilters(GuideFilters())
            }
        }.setNegativeButton("Close", null).show()
    }
    private fun streamFormatMenu() {
        val selected = source ?: return
        val values = listOf("auto", "ts", "hls")
        AlertDialog.Builder(this).setTitle("Provider stream format").setSingleChoiceItems(arrayOf("Auto · retry alternate format", "MPEG-TS", "HLS"), values.indexOf(store.streamFormat(selected))) { dialog, which ->
            store.setStreamFormat(selected, values[which]); dialog.dismiss(); current?.let(::tune)
        }.setNegativeButton("Cancel", null).show()
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
                    val channels = adapter.channels
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
        else if (!guideVisible) showGuide(true)
        else if (current != null) showGuide(false)
        else focusSidebar()
    }
    override fun onStart() { super.onStart(); started = true; if (::player.isInitialized && current != null) playAttempt() }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.resume() }
    override fun onStop() { started = false; firstFrame = false; expectedMediaId = ""; handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); if (::player.isInitialized) player.stop(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun nowWindow() = System.currentTimeMillis() / 1800000L * 1800000L
}

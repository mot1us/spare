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
import android.view.ViewConfiguration
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
import androidx.media3.exoplayer.DefaultLoadControl
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
    private val epgWorkers = Executors.newFixedThreadPool(2)
    private var epgActive = 0
    private var epgWanted = emptyList<Channel>()
    private val epgInFlight = mutableSetOf<String>()
    private val epgCheckedAt = mutableMapOf<String, Long>()
    private val epgErrors = mutableMapOf<String, String>()
    private val fetchVisibleEpg = Runnable { collectVisibleEpg() }
    private val cacheEpg = Runnable {
        val selected = source; val snapshot = guide; val epoch = generation
        if (selected != null) imports.submit { if (!closed && epoch == generation) runCatching { store.cache(selected, snapshot) } }
    }
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
    private lateinit var previewCaption: TextView
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
    private var hasPlayedChannel = false
    private var playbackPhase = PlaybackPhase.OPENING
    private var startupFailures = StartupFailures()
    private var reconnects = ReconnectBudget()
    private var unavailable = UnavailableChannels()
    private var lastWorkingChannel: Channel? = null
    private var groupsForChannels: List<Channel>? = null
    private val workingUrls = mutableMapOf<String, String>()
    private val retry = Runnable { if (started && current != null) playAttempt(PlaybackPhase.RECONNECTING) }
    private val revealBuffering = Runnable { if (started && current != null && player.playbackState == Player.STATE_BUFFERING) showPlaybackLoading(PlaybackPhase.BUFFERING) }
    private val bufferingTimeout = Runnable {
        if (started && current != null && hasPlayedChannel && player.playbackState == Player.STATE_BUFFERING) reconnectOrFail("The provider stopped sending video. Try another channel or Retry. (STREAM_STALLED)")
    }
    private val startupTimeout = Runnable {
        if (started && current != null && !firstFrame) {
            if (!hasPlayedChannel && tryAlternate()) return@Runnable
            showPlaybackFailure("No video arrived within 15 seconds. Try another channel or choose a stream format in Settings. (STREAM_TIMEOUT)")
        }
    }
    private var backLongPressed = false
    private val backHold = Runnable { if (guideVisible && current != null) { backLongPressed = true; showGuide(false) } }
    private val hideNowPlaying = Runnable { nowPlaying.visibility = View.GONE }
    private val minuteTick = object : Runnable {
        override fun run() { if (!closed) { if (unavailable.prune(System.currentTimeMillis())) { saveUnavailable(); applyFilter() }; if (guideVisible) { adapter.notifyItemRangeChanged(0, adapter.itemCount); (focused ?: current)?.let(::showInfo) }; handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        store = SecureStore(this)
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        providerFactory = DataSource.Factory { ProviderDataSource { source?.allowHttp == true } }
        mediaFactory = DefaultMediaSourceFactory(providerFactory, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).setLoadErrorHandlingPolicy(ProviderLoadErrors())
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(50000, 60000, 2500, 8000)
            .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setMediaSourceFactory(mediaFactory).setLoadControl(loadControl)
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
                if (!started || current == null || player.playerError !== error) return
                cancelPlaybackCallbacks()
                val http = PlaybackErrors.httpFailure(error)
                val statusCode = http?.responseCode
                if (!hasPlayedChannel) {
                    startupFailures.record(statusCode, http?.dataSpec?.uri?.toString(), playbackUrl)
                    if (statusCode !in setOf(401, 403, 429) && tryAlternate()) return
                    if (startupFailures.allEndpointsMissing()) { hideUnavailable(); return }
                }
                if (statusCode != null && statusCode in 400..499 && statusCode !in setOf(408, 429)) showPlaybackFailure(PlaybackErrors.message(error))
                else if (statusCode == 429) showPlaybackFailure(PlaybackErrors.message(error))
                else reconnectOrFail(PlaybackErrors.message(error))
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != player.playbackState) return
                handler.removeCallbacks(revealBuffering); handler.removeCallbacks(bufferingTimeout)
                if (state == Player.STATE_BUFFERING && hasPlayedChannel && firstFrame) {
                    handler.postDelayed(revealBuffering, 750); handler.postDelayed(bufferingTimeout, 20000)
                } else if (state == Player.STATE_READY && firstFrame) playbackLoading.visibility = View.GONE
                else if (state == Player.STATE_ENDED && started && current != null) reconnectOrFail("The provider ended this stream. Try another channel or Retry. (STREAM_ENDED)")
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.timeline.isEmpty || eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != expectedMediaId) return
                firstFrame = true; hasPlayedChannel = true; lastWorkingChannel = current
                current?.let { channel -> workingUrls[channel.id] = playbackUrl; if (unavailable.restore(channel.id)) { saveUnavailable(); applyFilter() } }
                cancelPlaybackCallbacks(); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
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
        minWidth = 0; minimumWidth = 0; minHeight = dp(34); minimumHeight = dp(34); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            setKeepContentOnPlayerReset(true); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); contentDescription = "Current channel video"
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        guidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B1018.toInt(), 0xC90B1018.toInt()))
        }
        root.addView(guidePanel, FrameLayout.LayoutParams(-1, -1))
        root.removeView(video); root.addView(video, FrameLayout.LayoutParams(-1, -1))
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "guide-toolbar" }
        toolbar.addView(text("spare", 20f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(dp(160), -2))
        searchButton = button("Search") { search() }; toolbar.addView(searchButton)
        toolbar.addView(button("Browse all") { browseAll = !browseAll; group = null; favoritesOnly = false; applyFilter(); focusChannel() }.apply { tag = "browse-all" })
        toolbar.addView(button("Sources") { showSources() })
        toolbar.addView(button("Settings") { playbackMenu() }.apply { tag = "settings" })
        updateButton = button("Updates") { updater.offer() }; toolbar.addView(updateButton)
        guidePanel.addView(toolbar, LinearLayout.LayoutParams(-1, dp(44)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guidePanel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        sidebar = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; tag = "guide-categories" }
        sidebarAdapter = SidebarAdapter(this, { section ->
            favoritesOnly = section.key == "favorites"
            group = if (section.key.startsWith("group:")) section.key.removePrefix("group:") else null
            applyFilter(); focusChannel()
        }, { focusChannel() })
        sidebar.adapter = sidebarAdapter
        navigation.addView(sidebar, LinearLayout.LayoutParams(-1, -1))
        body.addView(navigation, LinearLayout.LayoutParams(dp(160), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        channelInfo = text("Your TV, kept simple.", 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        channelInfo.setPadding(0, 0, dp(230), 0); content.addView(channelInfo, LinearLayout.LayoutParams(-1, dp(28)))
        programmeInfo = text("Add your playlist to start watching.", 12f).apply { maxLines = 3; setTextColor(Color.LTGRAY); setPadding(0, 0, dp(230), 0) }
        content.addView(programmeInfo, LinearLayout.LayoutParams(-1, dp(56)))
        status = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 1 }
        status.setPadding(0, 0, dp(230), 0); content.addView(status, LinearLayout.LayoutParams(-1, dp(18)))
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
            { if (it.id != current?.id || !hasPlayedChannel || player.playerError != null) tune(it); showGuide(false) }, { channel ->
                source?.let { store.toggleFavorite(it, channel); favorites = store.favorites(it); applyFilter() }
            }, { channel -> focused = channel; showInfo(channel); scheduleEpg() }, { moveTime(it) }, { focusSidebar() })
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleEpg() }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("← Categories     OK Full screen     Hold OK Favorite     Hold Back Full screen", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) })
        previewCaption = text("Select a channel", 10f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(6), dp(2), dp(6), dp(2)); tag = "preview-caption"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(previewCaption, FrameLayout.LayoutParams(dp(216), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(142); marginEnd = dp(18) })
        nowPlaying = text("", 15f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE }
        root.addView(nowPlaying, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(28); bottomMargin = dp(24) })
        playbackLoading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(0xE60B1018.toInt()); visibility = View.GONE
            addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        playbackLoadingText = text("Opening channel…", 12f).apply { tag = "playback-status" }.apply { setPadding(dp(14), 0, 0, 0) }; playbackLoading.addView(playbackLoadingText)
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
    private fun focusSidebar() {
        val position = sidebarAdapter.activePosition(); sidebar.scrollToPosition(position)
        sidebar.post { sidebar.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: searchButton.requestFocus() }
    }
    private fun importProgress(epoch: Int, stage: String, done: Long = 0, total: Long = -1) = ui(epoch) {
        loadingPanel.visibility = View.VISIBLE; loadingBar.isIndeterminate = total <= 0
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
        if (percent != null) loadingBar.progress = percent
        loadingText.text = stage + (percent?.let { " · $it%" } ?: "…")
    }

    private fun load(selected: Source) {
        val epoch = ++generation
        epgWanted = emptyList(); epgCheckedAt.clear(); epgErrors.clear(); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg)
        importTask?.cancel(true)
        firstFrame = false; expectedMediaId = ""; playbackUrl = ""
        cancelPlaybackCallbacks(); playbackToken++; player.stop(); frameRates.clear(); current = null; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        source = selected; focused = null; store.active = selected.id; favorites = store.favorites(selected)
        filters = store.filters(selected); videoInfo = store.videoInfo(selected); unavailable = store.unavailable(selected); unavailable.prune(System.currentTimeMillis()); workingUrls.clear(); lastWorkingChannel = null; browseAll = false
        group = null; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; importProgress(epoch, "Opening playlist"); showGuide(true)
        importTask = imports.submit {
            val cached = runCatching { store.cached(selected) }.getOrNull()
            if (cached != null) ui(epoch) { accept(cached, resume = true); status.text = "Refreshing guide…" }
            val loaded = runCatching {
                val lineup = if (selected.kind == "xtream") Importers.xtream(selected) { stage, done, total -> importProgress(epoch, stage, done, total) }
                else Importers.m3u(Network.get(selected.url, selected.allowHttp, progress = { done, total -> importProgress(epoch, "Downloading channels", done, total) }).toString(Charsets.UTF_8), selected.allowHttp)
                Guide(lineup.channels, cached?.programmes.orEmpty()).also { initial -> ui(epoch) { accept(initial, resume = cached == null) } } to
                    selected.epgUrl.ifBlank { if (selected.kind == "xtream") "" else lineup.epgUrl }
            }
            loaded.onSuccess { (initial, epg) ->
                val programmes = if (epg.isNotBlank()) runCatching {
                    val bytes = Network.get(epg, selected.allowHttp, 64 * 1024 * 1024) { done, total -> importProgress(epoch, "Downloading guide", done, total) }
                    importProgress(epoch, "Reading guide")
                    Importers.xmltv(bytes, initial.channels.map { it.guideId }.filter { it.isNotEmpty() }.toSet())
                } else Result.success(emptyMap())
                val fresh = Guide(initial.channels, programmes.getOrDefault(cached?.programmes.orEmpty()))
                if (!Thread.currentThread().isInterrupted && generation == epoch) runCatching { store.cache(selected, fresh) }
                ui(epoch) { loadingPanel.visibility = View.GONE; accept(fresh, resume = false); status.text = if (programmes.isFailure) "Guide refresh failed · ${SourceErrors.message(programmes.exceptionOrNull()!!)}" else if (epg.isNotBlank() && fresh.programmes.isEmpty()) "Guide downloaded, but no current programmes matched the channel IDs." else "" }
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
        guide = if (source?.kind == "xtream" && source?.epgUrl.isNullOrBlank()) Guide(next.channels, next.programmes + guide.programmes) else next; updateGroups(); applyFilter()
        if (resume && current == null) {
            val last = source?.let { selected -> adapter.channels.firstOrNull { it.id == store.last(selected) } } ?: adapter.channels.firstOrNull()
            if (last != null) { tune(last); showGuide(false) }
        } else current = next.channels.firstOrNull { it.id == current?.id } ?: current
        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
        scheduleEpg()
    }
    private fun tune(channel: Channel) {
        val selected = source ?: return
        if (!Network.allowed(channel.url, selected.allowHttp)) { showPlaybackFailure("This channel needs an allowed HTTP or HTTPS address. Check this source’s HTTP setting."); return }
        cancelPlaybackCallbacks(); playbackToken++
        hasPlayedChannel = false; startupFailures = StartupFailures(); reconnects = ReconnectBudget(); firstFrame = false; alternateUsed = false; lastVideoInfo = null; retryPanel.visibility = View.GONE
        current = channel; previewCaption.text = "Live · ${channel.name}"; store.setLast(selected, channel); showInfo(channel)
        playbackUrl = if (selected.kind == "xtream") when (store.streamFormat(selected)) { "ts" -> StreamUrls.format(channel.url, "ts") ?: channel.url; "hls" -> StreamUrls.format(channel.url, "m3u8") ?: channel.url; else -> channel.url } else channel.url
        if (selected.kind == "xtream" && store.streamFormat(selected) == "auto") playbackUrl = workingUrls[channel.id] ?: playbackUrl
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        if (started) playAttempt()
        if (!guideVisible) showNowPlaying()
        scheduleEpg()
    }
    private fun playAttempt(phase: PlaybackPhase = PlaybackPhase.OPENING) {
        if (playbackUrl.isBlank()) return
        cancelPlaybackCallbacks()
        playbackToken++; expectedMediaId = "${current?.id}:$playbackToken"
        firstFrame = false; lastVideoInfo = null; showPlaybackLoading(phase)
        val item = MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId(expectedMediaId).setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()).build()
        val media = if (playbackUrl.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(providerFactory).setLoadErrorHandlingPolicy(ProviderLoadErrors()).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
        } else mediaFactory.createMediaSource(item)
        player.setMediaSource(media); player.prepare(); player.play()
        handler.postDelayed(startupTimeout, 15000)
    }
    private fun tryAlternate(): Boolean {
        val selected = source ?: return false
        if (hasPlayedChannel || selected.kind != "xtream" || store.streamFormat(selected) != "auto" || alternateUsed) return false
        val alternate = StreamUrls.alternate(playbackUrl) ?: return false
        alternateUsed = true; playbackUrl = alternate; playAttempt(PlaybackPhase.ALTERNATE)
        return true
    }
    private fun showPlaybackFailure(detail: String) {
        cancelPlaybackCallbacks()
        player.stop(); playbackLoading.visibility = View.GONE; playbackError.text = detail; retryPanel.visibility = View.VISIBLE
        (retryPanel.getChildAt(1) as ViewGroup).getChildAt(0).requestFocus()
    }
    private fun cancelPlaybackCallbacks() {
        handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); handler.removeCallbacks(revealBuffering); handler.removeCallbacks(bufferingTimeout)
    }
    private fun showPlaybackLoading(phase: PlaybackPhase) {
        playbackPhase = phase; playbackLoadingText.text = phase.label
        playbackLoading.layoutParams = FrameLayout.LayoutParams(-2, -2, if (phase == PlaybackPhase.OPENING || phase == PlaybackPhase.ALTERNATE) Gravity.CENTER else Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(24); bottomMargin = dp(if (loadingPanel.isVisible) 76 else 24)
        }
        playbackLoading.visibility = View.VISIBLE
    }
    private fun reconnectOrFail(detail: String) {
        cancelPlaybackCallbacks()
        if (!reconnects.take(android.os.SystemClock.elapsedRealtime())) { showPlaybackFailure(detail); return }
        player.stop(); showPlaybackLoading(PlaybackPhase.RECONNECTING); handler.postDelayed(retry, 1500)
    }
    private fun saveUnavailable() { source?.let { store.saveUnavailable(it, unavailable) } }
    private fun hideUnavailable() {
        val failed = current ?: return
        unavailable.hide(failed.id, System.currentTimeMillis()); saveUnavailable()
        cancelPlaybackCallbacks(); expectedMediaId = ""; firstFrame = false; player.stop(); playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        current = null; applyFilter(); showGuide(true)
        status.text = "${failed.name} is unavailable · hidden for 15 minutes. Retry in Settings."
        toast("Channel unavailable · hidden temporarily")
        lastWorkingChannel?.takeIf { it.id != failed.id && guide.channels.any { next -> next.id == it.id } && !unavailable.contains(it.id, System.currentTimeMillis()) }?.let { tune(it) }
    }
    private fun unavailableMenu() {
        val channels = guide.channels.filter { unavailable.contains(it.id, System.currentTimeMillis()) }
        AlertDialog.Builder(this).setTitle("Unavailable · hidden for 15 minutes").setItems(channels.map { it.name }.toTypedArray()) { _, which ->
            val channel = channels[which]; unavailable.restore(channel.id); saveUnavailable(); applyFilter(); tune(channel); showGuide(false)
        }.setPositiveButton("Restore all") { _, _ -> unavailable.clear(); saveUnavailable(); applyFilter() }.setNegativeButton("Close", null).show()
    }
    private fun recordVideo(info: VideoInfo) {
        val selected = source ?: return; val channel = current ?: return
        lastVideoInfo = info
        if (videoInfo[channel.id] == info) return
        videoInfo[channel.id] = info; store.saveVideoInfo(selected, videoInfo)
        applyFilter(); if (!guideVisible || focused?.id == channel.id) showInfo(channel)
    }
    private fun scheduleEpg() { handler.removeCallbacks(fetchVisibleEpg); handler.postDelayed(fetchVisibleEpg, 250) }
    private fun collectVisibleEpg() {
        val selected = source ?: return
        if (selected.kind != "xtream" || selected.epgUrl.isNotBlank() || !started) return
        val visible = if (guideVisible) {
            val layout = list.layoutManager as LinearLayoutManager
            val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last = layout.findLastVisibleItemPosition().coerceAtLeast(first)
            adapter.channels.subList(first.coerceAtMost(adapter.channels.size), minOf(last + 1, first + 20, adapter.channels.size))
        } else emptyList()
        epgWanted = (listOfNotNull(current, focused?.takeIf { guideVisible }) + visible).distinctBy { it.id }
        pumpEpg()
    }
    private fun pumpEpg() {
        val selected = source ?: return
        if (closed || !started || selected.kind != "xtream" || selected.epgUrl.isNotBlank()) return
        val epoch = generation; val now = System.currentTimeMillis()
        while (epgActive < 2) {
            val channel = epgWanted.firstOrNull { "$epoch:${it.id}" !in epgInFlight && now - (epgCheckedAt[it.id] ?: 0) >= 5 * 60000L } ?: return
            val request = "$epoch:${channel.id}"; epgInFlight.add(request); epgCheckedAt[channel.id] = now; epgActive++
            epgWorkers.execute {
                val result = runCatching { XtreamEpg.load(selected, channel) }
                handler.post {
                    epgActive--; epgInFlight.remove(request)
                    if (!closed && epoch == generation) {
                        result.onSuccess { entries ->
                            if (entries.isNotEmpty()) {
                                val programmes = LinkedHashMap(guide.programmes); programmes[guide.key(channel)] = entries
                                while (programmes.size > 5000) programmes.remove(programmes.keys.first())
                                guide = Guide(guide.channels, programmes); epgErrors.remove(channel.id)
                                handler.removeCallbacks(cacheEpg); handler.postDelayed(cacheEpg, 1500)
                            } else epgErrors[channel.id] = "Provider supplied no current programme data."
                        }.onFailure { epgErrors[channel.id] = "Guide unavailable · ${SourceErrors.message(it)}" }
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                    }
                    if (!closed) pumpEpg()
                }
            }
        }
    }
    private fun showInfo(channel: Channel) {
        val programme = guide.at(channel, System.currentTimeMillis())
        channelInfo.text = programme?.title ?: channel.name
        val quality = videoInfo[channel.id]?.label() ?: "Quality unverified"
        programmeInfo.text = channel.name + "  ·  " + quality + (programme?.let { "  ·  ${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}\n${it.description}" } ?: "\n${epgErrors[channel.id] ?: if (epgInFlight.any { it == "$generation:${channel.id}" }) "Loading programme guide…" else "No programme information"}")
    }
    private fun applyFilter() {
        val now = System.currentTimeMillis()
        val hidden = guide.channels.count { unavailable.contains(it.id, now) }
        val filtered = guide.channels.filter { !unavailable.contains(it.id, now) && (!favoritesOnly || it.id in favorites) && ((group == null) || it.group == group) &&
            (query.isBlank() || it.name.contains(query, true)) && (browseAll || filters.matches(it, videoInfo[it.id])) }
        adapter.submit(filtered)
        searchButton.text = if (query.isEmpty()) "Search" else "Search ✓"
        root.findViewWithTag<Button>("browse-all")?.text = if (browseAll) "My guide" else "Browse all"
        val selection = if (favoritesOnly) "Favorites" else group
        val profileLabel = if (browseAll) "Filters paused" else filters.summary().takeUnless { it == "All channels" && selection != null }
        filterInfo.text = "${filtered.size} channels · " + listOfNotNull(selection, profileLabel, query.takeIf { it.isNotEmpty() }?.let { "Search: ${it.take(40)}" }, if (hidden > 0) "$hidden unavailable hidden" else null).joinToString(" · ")
        emptyInfo.visibility = if (filtered.isEmpty() && guide.channels.isNotEmpty()) View.VISIBLE else View.GONE
        emptyInfo.text = if (!browseAll && (filters.excludedFps.isNotEmpty() || filters.minHeight > 0)) "No verified channels match yet. Choose Browse all, then play channels to verify their quality. Unknown quality stays hidden in My guide."
            else if (hidden > 0) "Unavailable channels are temporarily hidden. Retry or restore them in Settings → Unavailable channels." else "No channels match. Try another category, clear Search, or choose Browse all."
        updateGroups()
    }
    private fun updateGroups() {
        if (groupsForChannels !== guide.channels) { providerGroups = CategoryOrder.sorted(guide.channels.map { it.group }); groupsForChannels = guide.channels }
        val visibleGroups = providerGroups.filter { browseAll || filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection(if (browseAll) "All channels" else "My guide", "all", !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", favoritesOnly)) + visibleGroups.map { GuideSection(it.ifBlank { "Uncategorized" }, "group:$it", !favoritesOnly && group == it && (group != null)) })
    }
    private fun updateTimes() { times.invalidate() }
    private fun showGuide(show: Boolean) {
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideNowPlaying); nowPlaying.visibility = View.GONE
        val preview = show && store.smallPreview
        video.layoutParams = if (preview) FrameLayout.LayoutParams(dp(216), dp(100), Gravity.TOP or Gravity.END).apply { topMargin = dp(62); marginEnd = dp(18) } else FrameLayout.LayoutParams(-1, -1)
        val inset = if (preview) dp(230) else 0
        channelInfo.setPadding(0, 0, inset, 0); programmeInfo.setPadding(0, 0, inset, 0); status.setPadding(0, 0, inset, 0)
        programmeInfo.layoutParams = LinearLayout.LayoutParams(-1, dp(if (preview) 56 else 38))
        if (preview) root.bringChildToFront(video) else root.bringChildToFront(guidePanel)
        listOf(previewCaption, nowPlaying, loadingPanel, playbackLoading, retryPanel).forEach(root::bringChildToFront)
        previewCaption.visibility = if (preview) View.VISIBLE else View.GONE
        previewCaption.text = if (current == null) "Select a channel" else "Live · ${current?.name}"
        if (show) focusChannel() else showNowPlaying()
        scheduleEpg()
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
        val choices = mutableListOf("Guide video", "Guide filters", "Audio track", "Subtitles", "Refresh playlist & guide", "About Spare", "Open-source licenses", "Exit Spare")
        if (source?.kind == "xtream") choices.add(1, "Stream format")
        if (unavailable.snapshot().isNotEmpty()) choices.add(1, "Unavailable channels")
        AlertDialog.Builder(this).setTitle("Settings").setItems(choices.toTypedArray()) { _, item ->
            when (choices[item]) {
                "Guide video" -> AlertDialog.Builder(this).setTitle("Video while browsing the guide").setSingleChoiceItems(arrayOf("Transparent overlay", "Small live preview"), if (store.smallPreview) 1 else 0) { dialog, which -> store.smallPreview = which == 1; dialog.dismiss(); showGuide(true) }.setNegativeButton("Cancel", null).show()
                "Guide filters" -> filterMenu()
                "Unavailable channels" -> unavailableMenu()
                "Stream format" -> streamFormatMenu()
                "Audio track" -> tracks(C.TRACK_TYPE_AUDIO)
                "Subtitles" -> tracks(C.TRACK_TYPE_TEXT)
                "Refresh playlist & guide" -> source?.let(::load)
                "Exit Spare" -> finish()
                "About Spare" -> notice("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player.\nMIT License · github.com/mot1us/spare\n\nOK: guide · Up/Down: channel\nHold OK on a guide channel: favorite\nLeft: categories · Hold Back: full screen\n\nQuality filters use observed playback, never channel names. Unknown channels stay hidden when quality filters are enabled; Browse all lets you verify them. No background stream scanning.\n\nNo app accounts, ads, or analytics. Credentials and guide cache stay in encrypted app storage.")
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
        AlertDialog.Builder(this).setTitle("My guide · ${filters.summary()}").setItems(arrayOf("Minimum resolution", "Frame rates · uncheck rates to hide", "Choose categories", "Reset filters")) { _, item ->
            when (item) {
                0 -> {
                    val heights = listOf(0, 720, 1080, 2160)
                    AlertDialog.Builder(this).setTitle("Minimum resolution").setSingleChoiceItems(arrayOf("Any", "HD · 720p or better", "FHD · 1080p or better", "4K · 2160p or better"), heights.indexOf(filters.minHeight)) { dialog, which -> saveFilters(filters.copy(minHeight = heights[which])); dialog.dismiss() }.setNegativeButton("Cancel", null).show()
                }
                1 -> {
                    val rates = FrameRateFilter.choices.keys.toList(); val excluded = filters.excludedFps.toMutableSet()
                    AlertDialog.Builder(this).setTitle("Frame rates · uncheck to hide").setMultiChoiceItems(FrameRateFilter.choices.values.toTypedArray(), rates.map { it !in excluded }.toBooleanArray()) { _, which, checked -> if (checked) excluded.remove(rates[which]) else excluded.add(rates[which]) }
                        .setPositiveButton("Save") { _, _ -> saveFilters(filters.copy(excludedFps = excluded)) }.setNeutralButton("All rates") { _, _ -> saveFilters(filters.copy(excludedFps = emptySet())) }.setNegativeButton("Cancel", null).show()
                }
                2 -> {
                    val chosen = filters.groups.toMutableSet()
                    AlertDialog.Builder(this).setTitle("Categories · none selected means all").setMultiChoiceItems(providerGroups.map { it.ifBlank { "Uncategorized" } }.toTypedArray(), providerGroups.map { it in chosen }.toBooleanArray()) { _, which, checked -> if (checked) chosen.add(providerGroups[which]) else chosen.remove(providerGroups[which]) }
                        .setPositiveButton("Save") { _, _ -> saveFilters(filters.copy(groups = chosen)) }.setNeutralButton("All") { _, _ -> saveFilters(filters.copy(groups = emptySet())) }.setNegativeButton("Cancel", null).show()
                }
                3 -> saveFilters(GuideFilters())
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
    @SuppressLint("GestureBackNavigation") // TV-only activity opts out of predictive back in the manifest so hold-Back receives key-down/up.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { backLongPressed = false; handler.postDelayed(backHold, ViewConfiguration.getLongPressTimeout().toLong()) }
            if (event.action == KeyEvent.ACTION_UP) { handler.removeCallbacks(backHold); if (!backLongPressed && !event.isCanceled) navigateBack() }
            return true
        }
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
    @Deprecated("TV hardware-back fallback")
    @SuppressLint("GestureBackNavigation") // The TV activity opts out of predictive back for remote hold detection.
    override fun onBackPressed() = navigateBack()
    private fun navigateBack() {
        if (retryPanel.isVisible) { retryPanel.visibility = View.GONE; showGuide(true) }
        else if (!guideVisible) showGuide(true)
        else if (list.hasFocus()) focusSidebar()
        else focusChannel()
    }
    override fun onStart() { super.onStart(); started = true; if (::player.isInitialized && current != null) playAttempt(if (hasPlayedChannel) PlaybackPhase.RECONNECTING else PlaybackPhase.OPENING); scheduleEpg() }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.resume() }
    override fun onStop() { started = false; firstFrame = false; expectedMediaId = ""; handler.removeCallbacks(backHold); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg); cancelPlaybackCallbacks(); if (::player.isInitialized) player.stop(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); epgWorkers.shutdownNow(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun nowWindow() = System.currentTimeMillis() / 1800000L * 1800000L
}

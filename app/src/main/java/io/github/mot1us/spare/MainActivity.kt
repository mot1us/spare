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
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Button
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
    private val cacheWrites = Executors.newSingleThreadExecutor()
    private val cadence = FrameCadence()
    private var cachedStartup = false
    private var availableUpdate: String? = null
    private var importTask: java.util.concurrent.Future<*>? = null
    private val epgWorkers = Executors.newFixedThreadPool(2)
    private var epgActive = 0
    private var epgWanted = emptyList<Channel>()
    private val epgInFlight = mutableSetOf<String>()
    private val epgCheckedAt = mutableMapOf<String, Long>()
    private val epgErrors = mutableMapOf<String, String>()
    private val fetchVisibleEpg = Runnable { collectVisibleEpg() }
    private val cacheEpg = Runnable {
        val selected = source; val snapshot = guide
        if (selected != null && snapshot.channels.isNotEmpty()) cacheWrites.execute { runCatching { store.cache(selected, snapshot) } }
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
    private lateinit var playbackLoading: ProgressBar
    private lateinit var voiceInput: NativeVoiceInput
    private var voiceVolume = 1f
    private var focusEpoch = 0
    private lateinit var playbackError: TextView
    private lateinit var searchButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var updateBadge: TextView
    private lateinit var headerInfo: LinearLayout
    private lateinit var guideSearch: GuideSearchBar
    private lateinit var details: PlaybackDetails
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
    private var groupsForOrder: List<String>? = null
    private var voiceSearchPending = false
    private val filterSearch = Runnable { query = guideSearch.query(); applyFilter() }
    private val hideDetails = Runnable { closeDetails() }
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
        override fun run() { if (!closed) { if (unavailable.prune(System.currentTimeMillis())) { saveUnavailable(); applyFilter() }; if (guideVisible) { adapter.notifyItemRangeChanged(0, adapter.itemCount); (focused ?: current)?.let(::showInfo) }; if (details.isVisible) refreshDetails(); handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        store = SecureStore(this)
        GuideTheme.select(store.theme)
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        providerFactory = DataSource.Factory { ProviderDataSource { source?.allowHttp == true } }
        mediaFactory = DefaultMediaSourceFactory(providerFactory, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)).setLoadErrorHandlingPolicy(ProviderLoadErrors())
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(50000, 60000, 1500, 8000)
            .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setMediaSourceFactory(mediaFactory).setLoadControl(loadControl)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        video.player = player
        frameRates = FrameRates(this, video.videoSurfaceView as SurfaceView)
        voiceInput = NativeVoiceInput(this, { guideSearch.message(it) }, { words ->
            if (guideSearch.isVisible && started) { guideSearch.setQuery(words); finishSearch(false) }
        }, { listening ->
            if (listening) { voiceVolume = player.volume; player.volume = 0f } else player.volume = voiceVolume
        })
        applyCaptionPreference()
        player.setVideoFrameMetadataListener { pts, _, format, codec ->
            val token = playbackToken
            val measured = cadence.observe(token, pts)
            val codecRate = runCatching { codec?.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: runCatching { codec?.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)?.toFloat() }.getOrNull()
            val fps = format.frameRate.takeIf { it.isFinite() && it in 10f..120f }
                ?: measured.takeIf { it > 0 } ?: codecRate?.takeIf { it.isFinite() && it in 10f..120f } ?: 0f
            frameRates.request(fps)
            if (firstFrame && (lastVideoInfo == null || (fps > 0 && lastVideoInfo?.fps != fps))) {
                handler.post {
                    if (firstFrame && token == playbackToken) {
                        val size = player.videoSize
                        val width = format.width.takeIf { it > 0 } ?: size.width
                        val height = format.height.takeIf { it > 0 } ?: size.height
                        if (width > 0 && height > 0) recordVideo(VideoInfo(width, height, fps.takeIf { it > 0 } ?: lastVideoInfo?.fps ?: 0f))
                    }
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
                current?.let { channel -> source?.let { store.setLast(it, channel) }; workingUrls[channel.id] = playbackUrl; if (unavailable.restore(channel.id)) { saveUnavailable(); applyFilter() } }
                cancelPlaybackCallbacks(); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
                player.videoFormat?.let { format ->
                    if (format.width > 0 && format.height > 0) recordVideo(VideoInfo(format.width, format.height, format.frameRate.takeIf { it.isFinite() && it > 0 } ?: lastVideoInfo?.fps ?: 0f))
                }
            }
        })
        updater = Updater(this, store) { release -> availableUpdate = release?.version; updateBadge.visibility = if (release == null) View.GONE else View.VISIBLE; settingsButton.contentDescription = if (release == null) "Settings" else "Settings · update available" }
        sources = runCatching { store.sources() }.getOrElse {
            notice("Source storage unavailable", "Android could not unlock saved credentials. Add your source again; no credentials have been sent elsewhere.")
            emptyList()
        }
        val selected = sources.firstOrNull { it.id == store.active } ?: sources.firstOrNull()
        if (selected == null) { status.text = "Add a playlist in Settings to start watching"; handler.post { editSource(null) } }
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
        background = GuideTheme.background(resources.displayMetrics.density, Color.rgb(30, 30, 30))
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
        guideSearch = GuideSearchBar(this, ::button, {
            handler.removeCallbacks(filterSearch); handler.postDelayed(filterSearch, 150)
        }, ::voiceSearch, { finishSearch(false) }, { finishSearch(true) })
        guidePanel.addView(guideSearch, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guidePanel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "guide-toolbar" }
        fun icon(label: String, resource: Int, click: () -> Unit) = ImageButton(this).apply {
            contentDescription = label; isFocusableInTouchMode = true; setImageResource(resource); setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE))
            fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(5).toFloat() }
            background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(), shape(Color.TRANSPARENT)) }
            background = GuideTheme.background(resources.displayMetrics.density)
            setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
        }
        searchButton = icon("Search", R.drawable.ic_search, ::search).apply { tag = "search" }; toolbar.addView(searchButton)
        settingsButton = icon("Settings", R.drawable.ic_settings, ::playbackMenu).apply { tag = "settings" }
        val settingsSlot = FrameLayout(this); settingsSlot.addView(settingsButton, FrameLayout.LayoutParams(dp(40), dp(40)))
        updateBadge = text("•", 18f).apply { visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        settingsSlot.addView(updateBadge, FrameLayout.LayoutParams(dp(12), dp(20), Gravity.TOP or Gravity.END))
        toolbar.addView(settingsSlot, LinearLayout.LayoutParams(dp(40), dp(40)))
        navigation.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))
        sidebar = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; tag = "guide-categories" }
        sidebarAdapter = SidebarAdapter(this, { section ->
            favoritesOnly = section.key == "favorites"
            group = if (section.key.startsWith("group:")) section.key.removePrefix("group:") else null
            applyFilter(); focusChannel()
        }, { focusChannel() })
        sidebar.adapter = sidebarAdapter
        navigation.addView(sidebar, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(navigation, LinearLayout.LayoutParams(dp(160), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        headerInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelInfo = text("", 20f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        headerInfo.addView(channelInfo, LinearLayout.LayoutParams(-1, -2))
        programmeInfo = text("Add a playlist in Settings to start watching.", 12f).apply { maxLines = 5; setTextColor(Color.LTGRAY) }
        headerInfo.addView(programmeInfo, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        status = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 2 }
        headerInfo.addView(status, LinearLayout.LayoutParams(-1, -2))
        content.addView(headerInfo, LinearLayout.LayoutParams(-1, dp(188)))
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
            { guideSearch.hideKeyboard(); if (it.id != current?.id || !hasPlayedChannel || player.playerError != null) tune(it) else showGuide(false) }, { channel ->
                source?.let { store.toggleFavorite(it, channel); favorites = store.favorites(it); applyFilter() }
            }, { channel -> focused = channel; showInfo(channel); scheduleEpg() }, { moveTime(it) }, { focusSidebar() }, { id, direction ->
                val index = adapter.channels.indexOfFirst { it.id == id }; if (index >= 0) focusChannelAt((index + direction).coerceIn(0, adapter.itemCount - 1))
            })
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleEpg() }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("← Categories     OK Preview / expand     Hold OK Favorite     Hold Back Full screen", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) })
        previewCaption = text("Select a channel", 10f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(6), dp(2), dp(6), dp(2)); tag = "preview-caption"; isFocusableInTouchMode = true; isClickable = true; setOnClickListener { if (current != null) showGuide(false) }; setOnFocusChangeListener { _, focused -> setTextColor(if (focused) Color.BLACK else Color.WHITE); setBackgroundColor(if (focused) GuideTheme.current.accent else 0xCC0B1018.toInt()) }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(previewCaption, FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178); marginEnd = dp(18) })
        nowPlaying = text("", 15f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE }
        root.addView(nowPlaying, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(28); bottomMargin = dp(24) })
        playbackLoading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE; tag = "playback-status"
            indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        }
        root.addView(playbackLoading, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
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
        details = PlaybackDetails(this, ::button, { showGuide(true) }, ::recallPrevious, {
            current?.let { channel -> source?.let { selected -> store.toggleFavorite(selected, channel); favorites = store.favorites(selected); applyFilter(); refreshDetails() } }
        })
        root.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        applyTheme(); updateTimes(); showGuide(true)
    }
    private fun moveTime(amount: Int) {
        windowStart = (windowStart + amount * 1800000L).coerceIn(nowWindow(), nowWindow() + 2 * 86400000L)
        updateTimes(); adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }
    private fun focusChannel() {
        val index = adapter.channels.indexOfFirst { it.id == (focused ?: current)?.id }.takeIf { it >= 0 }
            ?: adapter.channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        focusChannelAt(index)
    }
    private fun focusChannelAt(position: Int) {
        if (position !in adapter.channels.indices) return
        val epoch = ++focusEpoch
        list.scrollToPosition(position)
        fun request(remaining: Int) {
            if (epoch != focusEpoch || !guideVisible) return
            val row = list.findViewHolderForAdapterPosition(position)?.itemView
            if (row != null) row.requestFocus()
            else if (remaining > 0) list.postOnAnimation { request(remaining - 1) }
        }
        list.post { request(8) }
    }
    private fun focusSidebar() {
        ++focusEpoch
        val position = sidebarAdapter.activePosition(); sidebar.scrollToPosition(position)
        sidebar.post { sidebar.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: searchButton.requestFocus() }
    }
    private fun importProgress(epoch: Int, stage: String, done: Long = 0, total: Long = -1) = ui(epoch) {
        if (cachedStartup) return@ui
        loadingPanel.visibility = View.VISIBLE; loadingBar.isIndeterminate = total <= 0
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
        if (percent != null) loadingBar.progress = percent
        loadingText.text = stage + (percent?.let { " · $it%" } ?: "…")
    }

    private fun load(selected: Source, force: Boolean = false) {
        cacheEpg.run(); cachedStartup = false
        val epoch = ++generation
        if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; handler.removeCallbacks(filterSearch); guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE; guideSearch.setQuery(""); closeDetails()
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
            if (cached != null) {
                ui(epoch) { cachedStartup = true; loadingPanel.visibility = View.GONE; accept(cached, resume = true); status.text = "" }
                if (!force && System.currentTimeMillis() - store.playlistRefreshed(selected) in 0 until 6 * 3600000L) return@submit
            }
            val loaded = runCatching {
                val lineup = if (selected.kind == "xtream") Importers.xtream(selected) { stage, done, total -> importProgress(epoch, stage, done, total) }
                else Importers.m3u(Network.get(selected.url, selected.allowHttp, progress = { done, total -> importProgress(epoch, "Downloading channels", done, total) }).toString(Charsets.UTF_8), selected.allowHttp)
                Guide(lineup.channels, cached?.programmes.orEmpty(), lineup.categoryOrder).also { initial -> ui(epoch) { accept(initial, resume = cached == null) } } to
                    selected.epgUrl.ifBlank { if (selected.kind == "xtream") "" else lineup.epgUrl }
            }
            loaded.onSuccess { (initial, epg) ->
                val programmes = if (epg.isNotBlank()) runCatching {
                    val bytes = Network.get(epg, selected.allowHttp, 64 * 1024 * 1024) { done, total -> importProgress(epoch, "Downloading guide", done, total) }
                    importProgress(epoch, "Reading guide")
                    Importers.xmltv(bytes, initial.channels.map { it.guideId }.filter { it.isNotEmpty() }.toSet())
                } else Result.success(emptyMap())
                val fresh = Guide(initial.channels, programmes.getOrDefault(cached?.programmes.orEmpty()), initial.categoryOrder)
                ui(epoch) { loadingPanel.visibility = View.GONE; accept(fresh, resume = false); if (programmes.isSuccess) store.markPlaylistRefreshed(selected); handler.removeCallbacks(cacheEpg); cacheEpg.run(); status.text = if (programmes.isFailure) "Guide refresh failed · ${SourceErrors.message(programmes.exceptionOrNull()!!)}" else if (epg.isNotBlank() && fresh.programmes.isEmpty()) "Guide downloaded, but no current programmes matched the channel IDs." else "" }
            }.onFailure { failure -> ui(epoch) {
                loadingPanel.visibility = View.GONE
                val detail = SourceErrors.message(failure)
                status.text = if (cached != null) "Using saved guide · $detail" else detail
                if (cached == null && !isFinishing) AlertDialog.Builder(this).setTitle("Could not load playlist").setMessage(detail)
                    .setPositiveButton("Edit playlist") { _, _ -> editSource(selected) }
                    .setNeutralButton("Retry") { _, _ -> load(selected) }.setNegativeButton("Close", null).show()
            } }
        }
    }
    private fun ui(epoch: Int, task: () -> Unit) { handler.post { if (!closed && epoch == generation) task() } }
    private fun accept(next: Guide, resume: Boolean) {
        guide = if (source?.kind == "xtream" && source?.epgUrl.isNullOrBlank()) Guide(next.channels, next.programmes + guide.programmes, next.categoryOrder) else next; updateGroups(); applyFilter()
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
        closeDetails(); cancelPlaybackCallbacks(); playbackToken++
        hasPlayedChannel = false; startupFailures = StartupFailures(); reconnects = ReconnectBudget(); firstFrame = false; alternateUsed = false; lastVideoInfo = null; retryPanel.visibility = View.GONE
        current = channel; previewCaption.text = "Full screen · ${channel.name}"; showInfo(channel)
        playbackUrl = if (selected.kind == "xtream") when (store.streamFormat(selected)) { "ts" -> StreamUrls.format(channel.url, "ts") ?: channel.url; "hls" -> StreamUrls.format(channel.url, "m3u8") ?: channel.url; else -> channel.url } else channel.url
        if (selected.kind == "xtream" && store.streamFormat(selected) == "auto") playbackUrl = workingUrls[channel.id] ?: playbackUrl
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        applyCaptionPreference()
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
            HlsMediaSource.Factory(providerFactory).setAllowChunklessPreparation(!store.captions).setLoadErrorHandlingPolicy(ProviderLoadErrors()).setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
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
        playbackPhase = phase; playbackLoading.contentDescription = phase.label
        positionPlaybackLoading()
        playbackLoading.visibility = View.VISIBLE
    }
    private fun positionPlaybackLoading() {
        val preview = guideVisible && store.smallPreview
        playbackLoading.layoutParams = if (preview) FrameLayout.LayoutParams(dp(320), dp(3), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(175) + if (guideSearch.isVisible) dp(60) else 0; marginEnd = dp(18)
        } else FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM)
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
        AlertDialog.Builder(this).setTitle("Temporarily hidden channels").setItems(channels.map { it.name }.toTypedArray()) { _, which ->
            val channel = channels[which]; unavailable.restore(channel.id); saveUnavailable(); applyFilter(); tune(channel); showGuide(false)
        }.setNeutralButton("Why hidden?") { _, _ -> notice("Temporarily hidden channels", "Only channels whose startup addresses returned 404 or 410 are hidden. They return after 15 minutes. Select a channel to retry immediately, or restore all. Network and account errors never hide channels.") }.setPositiveButton("Restore all") { _, _ -> unavailable.clear(); saveUnavailable(); applyFilter() }.setNegativeButton("Close", null).show()
    }
    private fun recordVideo(info: VideoInfo) {
        val selected = source ?: return; val channel = current ?: return
        lastVideoInfo = info
        if (videoInfo[channel.id] == info) return
        videoInfo[channel.id] = info; store.saveVideoInfo(selected, videoInfo)
        applyFilter(); if (!guideVisible || focused?.id == channel.id) showInfo(channel); if (details.isVisible) refreshDetails()
    }
    private fun scheduleEpg() { handler.removeCallbacks(fetchVisibleEpg); handler.postDelayed(fetchVisibleEpg, 250) }
    private fun collectVisibleEpg() {
        val selected = source ?: return
        if (selected.kind != "xtream" || selected.epgUrl.isNotBlank() || !started) return
        val channels = adapter.channels
        val layout = list.layoutManager as LinearLayoutManager
        val first = if (guideVisible) layout.findFirstVisibleItemPosition().coerceAtLeast(0) else channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        val last = if (guideVisible) layout.findLastVisibleItemPosition().coerceAtLeast(first) else first
        val visible = channels.subList(first.coerceAtMost(channels.size), minOf(last + 1, first + 20, channels.size))
        val nearby = channels.subList((first - 12).coerceIn(0, channels.size), minOf(last + 33, channels.size))
        val favoritesToWarm = guide.channels.asSequence().filter { it.id in favorites }.take(24).toList()
        epgWanted = (listOfNotNull(current, focused?.takeIf { guideVisible }) + visible + nearby + favoritesToWarm).distinctBy { it.id }.take(80)
        pumpEpg()
    }
    private fun pumpEpg() {
        val selected = source ?: return
        if (closed || !started || selected.kind != "xtream" || selected.epgUrl.isNotBlank()) return
        val epoch = generation; val now = System.currentTimeMillis()
        while (epgActive < 2) {
            val channel = epgWanted.firstOrNull { "$epoch:${it.id}" !in epgInFlight && (guide.at(it, now) == null || guide.programmes[guide.key(it)].orEmpty().maxOfOrNull { programme -> programme.end }?.let { end -> end < now + 30 * 60000L } != false) && now - (epgCheckedAt[it.id] ?: 0) >= 5 * 60000L } ?: return
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
                                guide = Guide(guide.channels, programmes, guide.categoryOrder); epgErrors.remove(channel.id)
                                handler.removeCallbacks(cacheEpg); handler.postDelayed(cacheEpg, 1500)
                            } else epgErrors[channel.id] = "Provider supplied no current programme data."
                        }.onFailure { epgErrors[channel.id] = "Guide unavailable · ${SourceErrors.message(it)}" }
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                        if (details.isVisible) refreshDetails()
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
        if (guideVisible) {
            if (focused != null && filtered.none { it.id == focused?.id }) focused = filtered.firstOrNull()
            (focused ?: current)?.let(::showInfo)
            scheduleEpg()
        }
        searchButton.contentDescription = if (query.isEmpty()) "Search" else "Search · filter active"
        val selection = if (favoritesOnly) "Favorites" else group
        val profileLabel = if (browseAll) "Filters paused" else filters.summary().takeUnless { it == "All channels" && selection != null }
        filterInfo.text = "${filtered.size} channels · " + listOfNotNull(selection, profileLabel, query.takeIf { it.isNotEmpty() }?.let { "Search: ${it.take(40)}" }, if (hidden > 0) "$hidden unavailable hidden" else null).joinToString(" · ")
        emptyInfo.visibility = if (filtered.isEmpty() && guide.channels.isNotEmpty()) View.VISIBLE else View.GONE
        emptyInfo.text = if (!browseAll && (filters.excludedFps.isNotEmpty() || filters.minHeight > 0)) "No verified channels match yet. In Settings, choose Show unverified channels to play and verify them, or adjust Guide filters."
            else if (hidden > 0) "Unavailable channels are temporarily hidden. Retry or restore them in Settings → Temporarily hidden channels." else "No channels match. Try another category, clear Search, or adjust Guide filters in Settings."
        updateGroups()
    }
    private fun updateGroups() {
        if (groupsForChannels !== guide.channels || groupsForOrder !== guide.categoryOrder) {
            val provider = CategoryOrder.provider(guide.channels.map { it.group }, guide.categoryOrder)
            providerGroups = if (source?.let(store::usaFirst) == true) CategoryOrder.usaFirst(provider) else provider
            groupsForChannels = guide.channels; groupsForOrder = guide.categoryOrder
        }
        val visibleGroups = providerGroups.filter { browseAll || filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection("All channels", "all", !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", favoritesOnly)) + visibleGroups.map { GuideSection(it.ifBlank { "Uncategorized" }, "group:$it", !favoritesOnly && group == it && (group != null)) })
    }
    private fun updateTimes() { times.invalidate() }
    private fun showGuide(show: Boolean) {
        closeDetails()
        if (!show) { guideSearch.hideKeyboard(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false }
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideNowPlaying); nowPlaying.visibility = View.GONE
        val preview = show && store.smallPreview
        val searchInset = if (guideSearch.isVisible) dp(60) else 0
        video.layoutParams = if (preview) FrameLayout.LayoutParams(dp(320), dp(180), Gravity.TOP or Gravity.END).apply { topMargin = dp(18) + searchInset; marginEnd = dp(18) } else FrameLayout.LayoutParams(-1, -1)
        headerInfo.setPadding(0, 0, if (preview) dp(336) else 0, 0)
        headerInfo.layoutParams = LinearLayout.LayoutParams(-1, dp(if (preview) 188 else 104))
        previewCaption.layoutParams = FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178) + searchInset; marginEnd = dp(18) }
        if (preview) root.bringChildToFront(video) else root.bringChildToFront(guidePanel)
        listOf(previewCaption, nowPlaying, loadingPanel, playbackLoading, retryPanel).forEach(root::bringChildToFront)
        previewCaption.visibility = if (preview) View.VISIBLE else View.GONE
        previewCaption.text = if (current == null) "Select a channel" else "Full screen · ${current?.name}"
        positionPlaybackLoading()
        if (show) focusChannel() else { ++focusEpoch; showNowPlaying() }
        scheduleEpg()
    }
    private fun showNowPlaying() {
        val channel = current ?: return
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.text = channel.name + (guide.at(channel, System.currentTimeMillis())?.let { "  ·  ${it.title}" } ?: "") + "\nOK Details    ← Previous    Back Guide"
        nowPlaying.visibility = View.VISIBLE
        handler.postDelayed(hideNowPlaying, 1800)
    }
    private fun search() {
        showGuide(true); group = null; favoritesOnly = false; applyFilter()
        guideSearch.setQuery(query); guideSearch.visibility = View.VISIBLE; showGuide(true); guideSearch.voice.requestFocus()
        voiceSearch()
    }
    private fun finishSearch(close: Boolean) {
        voiceInput.stop(); voiceSearchPending = false
        handler.removeCallbacks(filterSearch); query = guideSearch.query(); applyFilter(); guideSearch.hideKeyboard()
        if (close) { guideSearch.visibility = View.GONE; showGuide(true) }
        focusChannel()
    }
    private fun voiceSearch() {
        if (!guideSearch.isVisible) return
        guideSearch.hideKeyboard(); ++focusEpoch
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (voiceSearchPending) return
            voiceSearchPending = true
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
        } else voiceInput.start()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001 || !voiceSearchPending) return
        voiceSearchPending = false
        if (!guideSearch.isVisible || !started) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) voiceInput.start()
        else guideSearch.message("Microphone permission is off. Select the field to type, or enable it in Android app permissions.")
    }
    private fun previousChannel(): Channel? {
        val selected = source ?: return null
        val last = store.last(selected)
        val id = if (current?.id != last) last else store.previous(selected)
        return guide.channels.firstOrNull { it.id == id && it.id != current?.id && !unavailable.contains(it.id, System.currentTimeMillis()) }
    }
    private fun recallPrevious() {
        val prior = previousChannel()
        if (prior == null) { toast("No previous available channel yet"); return }
        tune(prior); showGuide(false)
    }
    private fun refreshDetails() {
        val channel = current ?: return
        val now = System.currentTimeMillis(); val programme = guide.at(channel, now)
        val next = guide.programmes[guide.key(channel)]?.firstOrNull { it.start >= (programme?.end ?: now) }
        details.update(channel, programme, next, videoInfo[channel.id]?.label() ?: "Quality unverified", channel.id in favorites, previousChannel() != null)
    }
    private fun showDetails() {
        if (current == null) return
        handler.removeCallbacks(hideNowPlaying); nowPlaying.visibility = View.GONE
        refreshDetails(); root.bringChildToFront(details); details.visibility = View.VISIBLE; details.guideButton.requestFocus()
        handler.removeCallbacks(hideDetails); handler.postDelayed(hideDetails, 10000)
    }
    private fun closeDetails() {
        handler.removeCallbacks(hideDetails)
        if (::details.isInitialized) details.visibility = View.GONE
    }
    private fun showSources() {
        val builder = AlertDialog.Builder(this).setTitle("Playlists")
            .setSingleChoiceItems(sources.map { it.name }.toTypedArray(), sources.indexOfFirst { it.id == source?.id }) { dialog, which -> dialog.dismiss(); load(sources[which]) }
            .setPositiveButton("Add") { _, _ -> editSource(null) }.setNegativeButton("Close", null)
        if (source != null) builder.setNeutralButton("Edit current") { _, _ -> editSource(source) }
        builder.show()
    }
    private fun editSource(existing: Source?) = SourceDialog.show(this, existing, { selected ->
        runCatching { val next = sources.filterNot { it.id == selected.id } + selected; store.saveSources(next); sources = next }
            .onSuccess { load(selected, force = true) }.onFailure { notice("Could not save", "Android could not securely save this source. Try again.") }
    }, { removed ->
        runCatching { val next = sources.filterNot { it.id == removed.id }; store.saveSources(next); cacheWrites.execute { store.removeCache(removed) }; sources = next }
            .onSuccess {
                sources.firstOrNull()?.let { load(it) } ?: run {
                    ++generation; importTask?.cancel(true); handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); playbackToken++; player.stop(); frameRates.clear(); loadingPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE; source = null; current = null; filters = GuideFilters(); videoInfo.clear(); guide = Guide.EMPTY; updateGroups(); applyFilter(); showGuide(true); status.text = "Add a playlist in Settings to start watching"
                }
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    private fun playbackMenu() {
        val choices = mutableListOf("Playlists", availableUpdate?.let { "Update $it" } ?: "Updates", if (browseAll) "Use guide filters" else "Show unverified channels", "Guide video", "Guide filters", "Categories", "Category order", "Theme", "Audio track", "Subtitles", "Refresh playlist & guide", "About Spare", "Open-source licenses", "Exit Spare")
        if (source?.kind == "xtream") choices.add(1, "Stream format")
        if (unavailable.snapshot().isNotEmpty()) choices.add(1, "Temporarily hidden channels")
        AlertDialog.Builder(this).setTitle("Settings").setItems(choices.toTypedArray()) { _, item ->
            when (choices[item]) {
                "Playlists" -> showSources()
                "Updates", availableUpdate?.let { "Update $it" } -> updater.offer()
                "Show unverified channels", "Use guide filters" -> { browseAll = !browseAll; group = null; favoritesOnly = false; applyFilter(); focusChannel() }
                "Guide video" -> AlertDialog.Builder(this).setTitle("Video while browsing the guide").setSingleChoiceItems(arrayOf("Transparent overlay", "Small live preview"), if (store.smallPreview) 1 else 0) { dialog, which -> store.smallPreview = which == 1; dialog.dismiss(); showGuide(true) }.setNegativeButton("Cancel", null).show()
                "Guide filters" -> filterMenu()
                "Categories" -> categoryMenu()
                "Theme" -> themeMenu()
                "Category order" -> {
                    val selected = source
                    if (selected == null) toast("Add a source first") else AlertDialog.Builder(this).setTitle("Category order")
                        .setSingleChoiceItems(arrayOf("Provider order", "USA first · keep provider order within each region"), if (store.usaFirst(selected)) 1 else 0) { dialog, which ->
                            store.setUsaFirst(selected, which == 1); groupsForChannels = null; updateGroups(); dialog.dismiss()
                        }.setNegativeButton("Cancel", null).show()
                }
                "Temporarily hidden channels" -> unavailableMenu()
                "Stream format" -> streamFormatMenu()
                "Audio track" -> tracks(C.TRACK_TYPE_AUDIO)
                "Subtitles" -> tracks(C.TRACK_TYPE_TEXT)
                "Refresh playlist & guide" -> source?.let { load(it, force = true) }
                "Exit Spare" -> finish()
                "About Spare" -> notice("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player.\nMIT License · github.com/mot1us/spare\n\nWatching: OK details · Left previous · Back guide\nUp/Down: channel · Hold Back: full screen\nGuide: Left categories · Hold OK favorite\nSearch: voice or inline text\n\nQuality filters use observed playback, never channel names. Unknown channels stay hidden when quality filters are enabled; Settings → Show unverified channels lets you verify them. No background stream scanning.\n\nNo app accounts, ads, or analytics. Credentials and guide cache stay in encrypted app storage.")
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
        AlertDialog.Builder(this).setTitle("Guide filters · ${filters.summary()}").setItems(arrayOf("Minimum resolution", "Frame rates · uncheck rates to hide", "Choose categories", "Reset filters")) { _, item ->
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
                2 -> categoryMenu()
                3 -> saveFilters(GuideFilters())
            }
        }.setNegativeButton("Close", null).show()
    }
    private fun categoryMenu() {
        if (source == null) { toast("Add a playlist first"); return }
        AlertDialog.Builder(this).setTitle("Visible categories").setItems(CategoryPresets.names.toTypedArray()) { _, which ->
            when (which) {
                0 -> saveFilters(filters.copy(groups = emptySet()))
                4 -> editCategories()
                else -> {
                    val chosen = CategoryPresets.select(providerGroups, which)
                    if (chosen.isEmpty()) notice("No matching labels", "This provider has no category labels matching that preset. Choose categories manually instead. Your selection has been kept.")
                    else saveFilters(filters.copy(groups = chosen))
                }
            }
        }.setNeutralButton("About presets") { _, _ -> notice("Category presets", "Presets match country/language labels supplied by your playlist. They do not inspect the audio language. You can fine-tune the selection in Choose categories. Provider ordering is preserved.") }.setNegativeButton("Close", null).show()
    }
    private fun editCategories() {
        val chosen = if (filters.groups.isEmpty()) providerGroups.toMutableSet() else filters.groups.toMutableSet()
        val dialog = AlertDialog.Builder(this).setTitle("Choose visible categories")
            .setMultiChoiceItems(providerGroups.map { it.ifBlank { "Uncategorized" } }.toTypedArray(), providerGroups.map { it in chosen }.toBooleanArray()) { _, which, checked -> if (checked) chosen.add(providerGroups[which]) else chosen.remove(providerGroups[which]) }
            .setPositiveButton("Save", null).setNeutralButton("Show all") { _, _ -> saveFilters(filters.copy(groups = emptySet())) }.setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (chosen.isEmpty()) toast("Select at least one category, or choose Show all")
                else { saveFilters(filters.copy(groups = if (chosen.size == providerGroups.size) emptySet() else chosen.toSet())); dialog.dismiss() }
            }
        }
        dialog.show()
    }
    private fun themeMenu() {
        AlertDialog.Builder(this).setTitle("Theme").setSingleChoiceItems(GuideTheme.palettes.map { it.name }.toTypedArray(), GuideTheme.palettes.indexOf(GuideTheme.current)) { dialog, which ->
            store.theme = GuideTheme.palettes[which].name; GuideTheme.select(store.theme); applyTheme(); dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun applyTheme() {
        val base = GuideTheme.current.background
        guidePanel.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(base or 0xF2000000.toInt(), base or 0xC9000000.toInt()))
        fun visit(view: View) {
            if (view is Button) { view.background = GuideTheme.background(resources.displayMetrics.density, Color.rgb(30, 30, 30)); view.setTextColor(GuideTheme.ink()) }
            else if (view is ImageButton) view.background = GuideTheme.background(resources.displayMetrics.density)
            if (view is ViewGroup && view !is RecyclerView) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root); playbackLoading.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        sidebarAdapter.notifyItemRangeChanged(0, sidebarAdapter.itemCount); list.invalidate(); for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
    }
    private fun streamFormatMenu() {
        val selected = source ?: return
        val values = listOf("auto", "ts", "hls")
        AlertDialog.Builder(this).setTitle("Provider stream format").setSingleChoiceItems(arrayOf("Auto · retry alternate format", "MPEG-TS", "HLS"), values.indexOf(store.streamFormat(selected))) { dialog, which ->
            store.setStreamFormat(selected, values[which]); dialog.dismiss(); current?.let(::tune)
        }.setNegativeButton("Cancel", null).show()
    }
    private fun applyCaptionPreference() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !store.captions)
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true).build()
    }
    private fun tracks(type: Int) {
        val text = type == C.TRACK_TYPE_TEXT
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        val names = if (text) arrayListOf("Off", "Automatic") else arrayListOf("Automatic")
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSupported(i)) {
                val f = g.getTrackFormat(i); choices.add(g to i)
                names.add(listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }, f.sampleMimeType?.substringAfterLast('/'), f.accessibilityChannel.takeIf { it > 0 }?.let { "CC $it" }).distinct().joinToString(" · ").ifBlank { "Track ${choices.size}" })
            }
        }
        val base = if (text) 2 else 1
        if (text && choices.isEmpty()) names.add("No subtitle tracks detected · help")
        val selected = if (text && !store.captions) 0 else choices.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.takeIf { it >= 0 }?.plus(base) ?: if (text) 1 else 0
        AlertDialog.Builder(this).setTitle(if (text) "Subtitles" else "Audio").setSingleChoiceItems(names.toTypedArray(), selected) { dialog, index ->
            if (text && index >= base && choices.isEmpty()) { dialog.dismiss(); notice("Subtitles", "This stream has not supplied a supported subtitle track. Automatic keeps captions enabled for channels that provide them and checks HLS segments for undeclared captions. Spare cannot create captions missing from the provider."); return@setSingleChoiceItems }
            val wasEnabled = store.captions
            val selection = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, text && index == 0)
            if (text) { store.captions = index != 0; selection.setPreferredTextLanguage(java.util.Locale.getDefault().language).setSelectUndeterminedTextLanguage(true) }
            if (index >= base) choices[index - base].let { (g, track) -> selection.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, track)) }
            player.trackSelectionParameters = selection.build(); dialog.dismiss()
            // Segment inspection is only needed when captions are requested. Keep fast chunkless startup otherwise.
            if (text && !wasEnabled && store.captions && choices.isEmpty() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) playAttempt()
        }.setNegativeButton("Close", null).show()
    }
    @SuppressLint("GestureBackNavigation") // TV-only activity opts out of predictive back in the manifest so hold-Back receives key-down/up.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) search(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { backLongPressed = false; handler.postDelayed(backHold, ViewConfiguration.getLongPressTimeout().toLong()) }
            if (event.action == KeyEvent.ACTION_UP) { handler.removeCallbacks(backHold); if (!backLongPressed && !event.isCanceled) navigateBack() }
            return true
        }
        if (!guideVisible && retryPanel.visibility != View.VISIBLE) {
            if (details.isVisible) {
                if (event.action == KeyEvent.ACTION_DOWN) { handler.removeCallbacks(hideDetails); handler.postDelayed(hideDetails, 10000) }
                return super.dispatchKeyEvent(event)
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) recallPrevious(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_INFO) { if (event.action == KeyEvent.ACTION_UP) showDetails(); return true }
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
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) showDetails()
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
        if (details.isVisible) closeDetails()
        else if (guideVisible && guideSearch.isVisible) { finishSearch(true); searchButton.requestFocus() }
        else if (retryPanel.isVisible) { retryPanel.visibility = View.GONE; showGuide(true) }
        else if (!guideVisible) showGuide(true)
        else if (list.hasFocus()) focusSidebar()
        else focusChannel()
    }
    override fun onStart() { super.onStart(); started = true; if (::player.isInitialized && current != null) playAttempt(if (hasPlayedChannel) PlaybackPhase.RECONNECTING else PlaybackPhase.OPENING); scheduleEpg() }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.resume() }
    override fun onStop() { if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; started = false; firstFrame = false; expectedMediaId = ""; closeDetails(); handler.removeCallbacks(cacheEpg); cacheEpg.run(); handler.removeCallbacks(backHold); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg); cancelPlaybackCallbacks(); if (::player.isInitialized) player.stop(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { if (::voiceInput.isInitialized) voiceInput.stop(); closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); epgWorkers.shutdownNow(); cacheWrites.shutdown(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun nowWindow() = System.currentTimeMillis() / 1800000L * 1800000L
}

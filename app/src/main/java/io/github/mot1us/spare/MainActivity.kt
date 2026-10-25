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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
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
    private val epgDepth = mutableMapOf<String, Int>()
    private var sportsRefreshing = false
    private var sportsRefreshMessage = ""
    private val epgErrors = mutableMapOf<String, String>()
    private val fetchVisibleEpg = Runnable { collectVisibleEpg() }
    private val cacheEpg = Runnable {
        val selected = source; val snapshot = guide
        if (selected != null && snapshot.channels.isNotEmpty()) cacheWrites.execute { runCatching { store.cache(selected, snapshot) } }
    }
    private val sportsWorker = Executors.newSingleThreadExecutor()
    private var sportsTask: java.util.concurrent.Future<*>? = null
    private var sportsEpoch = 0
    private var sportsVisible = false
    private var sportsTeam: String? = null
    private var sportsRenderPending = false
    private var sportsComputing = false
    private var sportsDirty = false
    private var sportsChannels = emptyList<Channel>()
    private var selectedGame: SportsGame? = null
    private var sportsBackoffUntil = 0L
    private var sportsFailures = 0
    private val sportsForcedEpg = mutableSetOf<String>()
    private var sportsWarmSignature: List<Any?>? = null
    private val renderSports = Runnable { sportsRenderPending = false; refreshSports() }
    private lateinit var sportsPanel: SportsPanel
    private var settingsPanel: SettingsPanel? = null
    private var settingsReturnGuide = true
    private lateinit var timeline: LinearLayout
    private lateinit var nowButton: Button
    private lateinit var guideMode: Button
    private lateinit var sportsMode: Button
    private lateinit var categoryHeading: TextView
    private lateinit var favoriteFeedback: TextView
    private val hideFavoriteFeedback = Runnable { favoriteFeedback.animate().alpha(0f).setDuration(150).withEndAction { favoriteFeedback.visibility = View.GONE }.start() }
    private val guideTimeline = GuideTimeline()
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
    private lateinit var nowPlaying: NowPlayingBanner
    private lateinit var list: RecyclerView
    private lateinit var adapter: GuideAdapter
    private lateinit var sidebar: RecyclerView
    private lateinit var sidebarAdapter: SidebarAdapter
    private lateinit var filterInfo: TextView
    private lateinit var remoteHint: TextView
    private lateinit var emptyInfo: TextView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var playbackLoading: ProgressBar
    private lateinit var voiceInput: NativeVoiceInput
    private var voiceVolume = 1f
    private var focusEpoch = 0
    private var pendingFocusList: RecyclerView? = null
    private var pendingFocusEpoch = -1
    private var captionSawCue = false
    private var captionScanCount = 0
    private var captionProbeScheduled = false
    private val probeCaptions = Runnable {
        captionProbeScheduled = false
        if (::player.isInitialized && player.currentCues.cues.isNotEmpty()) { captionSawCue = true; return@Runnable }
        if (started && firstFrame && subtitleMode() == CaptionTracks.AUTO && !captionSawCue && captionScanCount < 6) {
            val choices = CaptionTracks.choices(player.currentTracks)
            val fallback = choices.filter { it.compatibility }
            if (fallback.size > 1 && fallback.size == choices.size) {
                val selected = fallback.indexOfFirst { it.group.isTrackSelected(it.index) }.coerceAtLeast(0)
                captionScanCount++; selectCaption(fallback[(selected + 1) % fallback.size])
            }
        }
    }
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
    private val hideNowPlaying = Runnable { nowPlaying.hide() }
    private val minuteTick = object : Runnable {
        override fun run() { if (!closed) { if (unavailable.prune(System.currentTimeMillis())) { saveUnavailable(); applyFilter() }; if (guideVisible) { adapter.refreshRows(); if (sportsVisible) scheduleSports() else (focused ?: current)?.let(::showInfo) }; if (details.isVisible) refreshDetails(); handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        store = SecureStore(this)
        GuideTheme.select(store.theme); GuideClock.use24Hour = store.clock24; sportsTeam = store.sportsTeam.takeIf { it in SportsCatalog.byId }
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        providerFactory = DataSource.Factory { ProviderDataSource { source?.allowHttp == true } }
        mediaFactory = DefaultMediaSourceFactory(providerFactory, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags).setTsSubtitleFormats(CaptionTracks.compatibility)).setLoadErrorHandlingPolicy(ProviderLoadErrors())
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
            override fun onTracksChanged(tracks: Tracks) { reconcileCaptions() }
            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isNotEmpty()) { captionSawCue = true; cancelCaptionProbe() }
            }
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
                cancelPlaybackCallbacks(); reconcileCaptions(); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
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
            setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pendingFocusList = null } }
            setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
        }
        searchButton = icon("Search", R.drawable.ic_search, ::search).apply { tag = "search"; id = View.generateViewId() }; toolbar.addView(searchButton)
        settingsButton = icon("Settings", R.drawable.ic_settings, ::playbackMenu).apply { tag = "settings"; id = View.generateViewId() }
        searchButton.nextFocusRightId = settingsButton.id; settingsButton.nextFocusLeftId = searchButton.id
        listOf(searchButton, settingsButton).forEach { control -> control.setOnKeyListener { _, key, event ->
            when {
                control === searchButton && key == KeyEvent.KEYCODE_DPAD_LEFT -> true
                key == KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { ++focusEpoch; if (sportsVisible) sportsMode.requestFocus() else focusSidebar(remember = true) }; true }
                control === settingsButton && key == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusChannel(); true }
                else -> false
            }
        } }
        val settingsSlot = FrameLayout(this); settingsSlot.addView(settingsButton, FrameLayout.LayoutParams(dp(40), dp(40)))
        updateBadge = text("•", 18f).apply { visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        settingsSlot.addView(updateBadge, FrameLayout.LayoutParams(dp(12), dp(20), Gravity.TOP or Gravity.END))
        toolbar.addView(settingsSlot, LinearLayout.LayoutParams(dp(40), dp(40)))
        navigation.addView(toolbar, LinearLayout.LayoutParams(-1, dp(48)))
        val modes = LinearLayout(this).apply { tag = "guide-modes"; setPadding(0, 0, 0, dp(8)) }
        guideMode = button("Guide") { switchMode(false) }.apply { tag = "guide-mode"; id = View.generateViewId(); setPadding(dp(7), 0, dp(7), 0) }
        sportsMode = button("Sports") { switchMode(true) }.apply { tag = "sports-mode"; id = View.generateViewId(); setPadding(dp(7), 0, dp(7), 0) }
        modes.addView(guideMode, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(6) }); modes.addView(sportsMode, LinearLayout.LayoutParams(0, dp(32), 1f))
        guideMode.nextFocusRightId = sportsMode.id; sportsMode.nextFocusLeftId = guideMode.id
        listOf(guideMode, sportsMode).forEach { it.setOnFocusChangeListener { _, active -> if (active) { ++focusEpoch; pendingFocusList = null } } }
        listOf(guideMode, sportsMode).forEach { control -> control.setOnKeyListener { _, key, event ->
            when {
                key == KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { ++focusEpoch; searchButton.requestFocus() }; true }
                key == KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) { if (sportsVisible) focusChannel() else focusSidebar(remember = true) }; true }
                control === guideMode && key == KeyEvent.KEYCODE_DPAD_LEFT -> true
                control === sportsMode && key == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) focusChannel(); true }
                else -> false
            }
        } }
        navigation.addView(modes, LinearLayout.LayoutParams(-1, dp(40)))
        categoryHeading = text("CATEGORIES", 9f).apply { setTextColor(0xFF929FAF.toInt()); letterSpacing = .12f; setPadding(dp(10), dp(3), 0, dp(7)) }
        navigation.addView(categoryHeading)
        sidebar = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; setHasFixedSize(true); overScrollMode = View.OVER_SCROLL_NEVER; tag = "guide-categories" }
        sidebarAdapter = SidebarAdapter(this, { section ->
            val hadSearch = guideSearch.isVisible
            clearGuideSearch(); sportsVisible = false
            favoritesOnly = section.key == "favorites"
            group = if (section.key.startsWith("group:")) section.key.removePrefix("group:") else null
            focused = null; applyFilter()
            if (hadSearch) showGuide(true)
            focusChannelAt(0)
        }, { focusChannel() }, { ++focusEpoch; searchButton.requestFocus() }, { ++focusEpoch; guideMode.requestFocus() }, ::focusCategoryAt)
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
        timeline = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filterInfo = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        timeline.addView(filterInfo, LinearLayout.LayoutParams(0, dp(30), 1f))
        val earlier = button("‹") { moveTime(focused ?: current, -1) }.apply { contentDescription = "Earlier on this channel"; tag = "guide-earlier" }
        nowButton = button("Now") { resetTime() }.apply { tag = "guide-now"; contentDescription = "Return this channel to live guide" }
        val later = button("›") { moveTime(focused ?: current, 1) }.apply { contentDescription = "Later on this channel"; tag = "guide-later" }
        listOf(earlier, nowButton, later).forEach { control ->
            control.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusChannel(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { ++focusEpoch; guideMode.requestFocus() }; true }
                else -> false
            } }
            timeline.addView(control)
        }
        content.addView(timeline, LinearLayout.LayoutParams(-1, dp(38)))
        times = TimeRuler(this) { guideTimeline.start((focused ?: current)?.id) }; content.addView(times, LinearLayout.LayoutParams(-1, dp(24)))
        emptyInfo = text("", 13f).apply { setPadding(dp(12), dp(24), dp(12), dp(24)); visibility = View.GONE }
        content.addView(emptyInfo)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); itemAnimator = null; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; tag = "channel-guide" }
        adapter = GuideAdapter(this, logos, { source }, { guide }, { favorites }, { guideTimeline.start(it.id) },
            { guideSearch.hideKeyboard(); if (it.id != current?.id || !hasPlayedChannel || player.playerError != null) tune(it) else showGuide(false) }, { channel ->
                toggleFavorite(channel)
            }, { channel -> focused = channel; showInfo(channel); updateTimes(); scheduleEpg() }, { channel, direction -> moveTime(channel, direction) }, { focusSidebar() }, { id, direction ->
                val index = adapter.channels.indexOfFirst { it.id == id }
                if (index == 0 && direction < 0) { ++focusEpoch; nowButton.requestFocus() }
                else if (index >= 0 && index + direction in adapter.channels.indices) focusChannelAt(index + direction)
            })
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleEpg() }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        sportsPanel = SportsPanel(this, ::button, ::sportsTeams, { team ->
            sportsTeam = team; store.sportsTeam = team.orEmpty(); sportsPanel.loading("Checking live games…"); scheduleSports()
        }, ::refreshSportsGuide, ::selectGame, ::showGameInfo, { playbackMenu(); settingsPanel?.selectSetting("Guide filters") }, { focusSidebar() })
        content.addView(sportsPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        remoteHint = text("← Earlier / categories    → Later on this channel    Back Live now    ↑ From first row: Now controls    Hold OK Favorite", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) }
        content.addView(remoteHint)
        previewCaption = text("Select a channel", 10f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(6), dp(2), dp(6), dp(2)); tag = "preview-caption"; isFocusableInTouchMode = true; isClickable = true; setOnClickListener { if (current != null) showGuide(false) }; setOnFocusChangeListener { _, focused -> setTextColor(if (focused) Color.BLACK else Color.WHITE); setBackgroundColor(if (focused) GuideTheme.current.accent else 0xCC0B1018.toInt()) }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(previewCaption, FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178); marginEnd = dp(18) })
        nowPlaying = NowPlayingBanner(this)
        root.addView(nowPlaying, FrameLayout.LayoutParams(dp(480), -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(24); bottomMargin = dp(24) })
        favoriteFeedback = text("", 12f).apply { tag = "favorite-feedback"; visibility = View.GONE; background = GuideTheme.glass(resources.displayMetrics.density); setPadding(dp(16), dp(10), dp(16), dp(10)); elevation = dp(10).toFloat(); clipToOutline = true }
        root.addView(favoriteFeedback, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
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
            current?.let { channel -> toggleFavorite(channel); refreshDetails() }
        }, { tracks(C.TRACK_TYPE_TEXT) })
        root.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        applyTheme(); updateTimes(); showGuide(true)
    }
    private fun toggleFavorite(channel: Channel) {
        val selected = source ?: return
        store.toggleFavorite(selected, channel); favorites = store.favorites(selected)
        applyFilter(); adapter.refreshRows()
        favoriteFeedback.animate().cancel(); handler.removeCallbacks(hideFavoriteFeedback)
        favoriteFeedback.text = if (channel.id in favorites) "Added to Favorites · ${channel.name}" else "Removed from Favorites · ${channel.name}"
        favoriteFeedback.alpha = 1f; favoriteFeedback.visibility = View.VISIBLE; root.bringChildToFront(favoriteFeedback)
        favoriteFeedback.announceForAccessibility(favoriteFeedback.text)
        handler.postDelayed(hideFavoriteFeedback, 2200)
    }
    private fun clearGuideSearch() {
        handler.removeCallbacks(filterSearch); query = ""; guideSearch.setQuery(""); guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE
        if (::voiceInput.isInitialized) voiceInput.stop()
        voiceSearchPending = false
    }
    private fun switchMode(sports: Boolean) {
        clearGuideSearch(); sportsVisible = sports; if (sports) sportsPanel.loading("Finding live games…"); applyFilter(); showGuide(true)
    }
    private fun moveTime(channel: Channel?, amount: Int) {
        channel ?: return
        guideTimeline.move(channel.id, amount); focused = channel; updateTimes(); adapter.refreshRows(); showInfo(channel); scheduleEpg()
    }
    private fun resetTime() {
        guideTimeline.reset((focused ?: current)?.id); updateTimes(); adapter.refreshRows(); (focused ?: current)?.let(::showInfo); scheduleEpg()
    }
    private fun focusChannel() {
        if (sportsVisible) { ++focusEpoch; sportsPanel.focusGames(); return }
        val index = adapter.channels.indexOfFirst { it.id == (focused ?: current)?.id }.takeIf { it >= 0 }
            ?: adapter.channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        focusChannelAt(index)
    }
    private fun focusChannelAt(position: Int) {
        if (position !in adapter.channels.indices) return
        focusListRow(list, position)
    }
    private fun focusSidebar(remember: Boolean = false) {
        if (sportsVisible) { ++focusEpoch; sportsMode.requestFocus(); return }
        focusCategoryAt(if (remember) sidebarAdapter.rememberedPosition() else sidebarAdapter.activePosition())
    }
    private fun focusCategoryAt(position: Int) {
        if (position !in 0 until sidebarAdapter.itemCount) return
        focusListRow(sidebar, position)
    }
    private fun focusListRow(target: RecyclerView, position: Int) {
        if (pendingFocusList === target && pendingFocusEpoch == focusEpoch) return
        val row = target.findViewHolderForAdapterPosition(position)?.itemView
        if (row?.hasFocus() == true) return
        val epoch = ++focusEpoch
        if (row != null && !target.isComputingLayout) { row.requestFocus(); return }
        pendingFocusList = target; pendingFocusEpoch = epoch
        target.stopScroll(); target.scrollToPosition(position)
        fun request(remaining: Int) {
            if (epoch != focusEpoch || !guideVisible || (settingsPanel?.isShown == true)) return
            val item = target.findViewHolderForAdapterPosition(position)?.itemView
            if (item != null && !target.isComputingLayout) { pendingFocusList = null; item.requestFocus() }
            else if (remaining > 0) target.postOnAnimation { request(remaining - 1) }
            else { pendingFocusList = null; target.getChildAt(0)?.requestFocus() }
        }
        request(30)
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
        val epoch = ++generation; guideTimeline.clear()
        sportsVisible = false; sportsChannels = emptyList(); selectedGame = null; sportsWarmSignature = null; sportsBackoffUntil = 0; sportsFailures = 0; sportsForcedEpg.clear(); sportsRefreshing = false; sportsRefreshMessage = ""
        ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); handler.removeCallbacks(renderSports); sportsRenderPending = false
        if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; handler.removeCallbacks(filterSearch); guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE; guideSearch.setQuery(""); closeDetails()
        epgWanted = emptyList(); epgCheckedAt.clear(); epgDepth.clear(); epgErrors.clear(); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg)
        importTask?.cancel(true)
        firstFrame = false; expectedMediaId = ""; playbackUrl = ""
        cancelPlaybackCallbacks(); playbackToken++; player.stop(); frameRates.clear(); current = null; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        source = selected; focused = null; store.active = selected.id; favorites = store.favorites(selected)
        filters = store.filters(selected); videoInfo = store.videoInfo(selected); unavailable = store.unavailable(selected); unavailable.prune(System.currentTimeMillis()); workingUrls.clear(); lastWorkingChannel = null; browseAll = false
        group = null; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; importProgress(epoch, "Opening playlist"); showGuide(true)
        importTask = imports.submit {
            val cached = runCatching { store.cached(selected)?.let { if (selected.kind == "xtream" && selected.epgUrl.isBlank()) it.streamGuide() else it } }.getOrNull()
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
        if (guideVisible && sportsVisible) scheduleSports() else (if (guideVisible) focused ?: current else current)?.let(::showInfo)
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
        firstFrame = false; lastVideoInfo = null; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); showPlaybackLoading(phase)
        val item = MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId(expectedMediaId).setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()).build()
        val media = if (playbackUrl.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(providerFactory).setAllowChunklessPreparation(!captionsEnabled()).setLoadErrorHandlingPolicy(ProviderLoadErrors()).setExtractorFactory(CaptionHlsExtractors(tsFlags)).createMediaSource(item)
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
    private fun recordVideo(info: VideoInfo) {
        val selected = source ?: return; val channel = current ?: return
        val observed = info.retainingKnownRate(videoInfo[channel.id])
        lastVideoInfo = observed
        if (videoInfo[channel.id] == observed) return
        videoInfo[channel.id] = observed; store.saveVideoInfo(selected, videoInfo)
        applyFilter(); if (!guideVisible || focused?.id == channel.id) showInfo(channel); if (details.isVisible) refreshDetails()
    }
    private fun scheduleEpg() { handler.removeCallbacks(fetchVisibleEpg); handler.postDelayed(fetchVisibleEpg, 250) }
    private fun collectVisibleEpg() {
        val selected = source ?: return
        if (selected.kind != "xtream" || selected.epgUrl.isNotBlank() || !started) return
        if (sportsVisible && guideVisible) {
            epgWanted = (listOfNotNull(current) + sportsChannels).distinctBy { guide.key(it) }.take(SportsGuide.MAX_WARM_CHANNELS + 1)
            pumpEpg(); return
        }
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
        if (sportsVisible && guideVisible && now < sportsBackoffUntil) return
        fun depth(channel: Channel) = if (sportsVisible && guideVisible) 4 else if (guideVisible && guideTimeline.ahead(channel.id)) 72 else 24
        fun needs(channel: Channel): Boolean {
            val target = if (sportsVisible && guideVisible) now else maxOf(now, guideTimeline.start(channel.id)) + 30 * 60000L
            val missing = guide.at(channel, now) == null || (!(sportsVisible && guideVisible) && (guide.programmes[guide.key(channel)].orEmpty().maxOfOrNull { it.end } ?: 0) < target)
            return (channel.id in sportsForcedEpg || missing) && (now - (epgCheckedAt[channel.id] ?: 0) >= 5 * 60000L || depth(channel) > (epgDepth[channel.id] ?: 0))
        }
        while (epgActive < 2) {
            val channel = epgWanted.firstOrNull { "$epoch:${it.id}" !in epgInFlight && needs(it) } ?: return
            val requestedDepth = depth(channel)
            val request = "$epoch:${channel.id}"; epgInFlight.add(request); epgCheckedAt[channel.id] = now; epgDepth[channel.id] = requestedDepth; sportsForcedEpg.remove(channel.id); epgActive++
            epgWorkers.execute {
                val result = runCatching { XtreamEpg.load(selected, channel, requestedDepth) }
                handler.post {
                    epgActive--; epgInFlight.remove(request)
                    if (!closed && epoch == generation) {
                        result.onSuccess { entries ->
                            sportsFailures = 0
                            if (entries.isNotEmpty()) {
                                val programmes = LinkedHashMap(guide.programmes); programmes[guide.key(channel)] = (programmes[guide.key(channel)].orEmpty() + entries).associateBy { it.start }.values.filter { it.end > now - 3600000L }.sortedBy { it.start }.take(72)
                                while (programmes.size > 5000) programmes.remove(programmes.keys.first())
                                guide = Guide(guide.channels, programmes, guide.categoryOrder); epgErrors.remove(channel.id)
                                handler.removeCallbacks(cacheEpg); handler.postDelayed(cacheEpg, 1500)
                            } else epgErrors[channel.id] = "Provider supplied no current programme data."
                        }.onFailure { failure ->
                            epgErrors[channel.id] = "Guide unavailable · ${SourceErrors.message(failure)}"
                            if (sportsVisible && guideVisible) {
                                sportsFailures++
                                if ((failure as? HttpFailure)?.status in setOf(401, 403, 429) || sportsFailures >= 3) sportsBackoffUntil = System.currentTimeMillis() + 5 * 60000L
                            }
                        }
                        adapter.refreshRows()
                        if (guideVisible && sportsVisible) scheduleSports() else (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                        if (details.isVisible) refreshDetails()
                    }
                    if (!closed) pumpEpg()
                }
            }
        }
    }
    private fun showInfo(channel: Channel) {
        if (sportsVisible && guideVisible && selectedGame != null) { showGameInfo(selectedGame!!); return }
        val programme = guide.at(channel, if (guideVisible) maxOf(System.currentTimeMillis(), guideTimeline.start(channel.id)) else System.currentTimeMillis())
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
        if (::sportsPanel.isInitialized) {
            guideMode.isSelected = !sportsVisible; sportsMode.isSelected = sportsVisible
            sidebar.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            categoryHeading.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            sportsPanel.visibility = if (sportsVisible) View.VISIBLE else View.GONE
            timeline.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            times.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            list.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            remoteHint.text = if (sportsVisible) "← Navigation · ↑ Search / Settings     OK Choose channel     Hold Back Full screen"
                else "← Earlier / categories    → Later on this channel    Back Live now    ↑ From first row: Now controls    Hold OK Favorite"
            if (sportsVisible) { emptyInfo.visibility = View.GONE; scheduleSports() }
        }
    }
    private fun updateGroups() {
        if (groupsForChannels !== guide.channels || groupsForOrder !== guide.categoryOrder) {
            val provider = CategoryOrder.provider(guide.channels.map { it.group }, guide.categoryOrder)
            providerGroups = if (source?.let(store::usaFirst) == true) CategoryOrder.usaFirst(provider) else provider
            groupsForChannels = guide.channels; groupsForOrder = guide.categoryOrder
        }
        val visibleGroups = providerGroups.filter { browseAll || filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection("All channels", "all", !sportsVisible && !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", !sportsVisible && favoritesOnly)) + visibleGroups.map { GuideSection(if (it in setOf("Sports", "Favorites", "All channels")) "Category · $it" else it.ifBlank { "Uncategorized" }, "group:$it", !sportsVisible && !favoritesOnly && group == it && (group != null)) })
    }
    private fun updateTimes() { times.invalidate(); nowButton.isSelected = guideTimeline.ahead((focused ?: current)?.id) }
    private fun showGuide(show: Boolean) {
        closeDetails()
        if (!show) { guideSearch.hideKeyboard(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false }
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        val preview = show && store.smallPreview
        val searchInset = if (guideSearch.isVisible) dp(60) else 0
        video.layoutParams = if (preview) FrameLayout.LayoutParams(dp(320), dp(180), Gravity.TOP or Gravity.END).apply { topMargin = dp(18) + searchInset; marginEnd = dp(18) } else FrameLayout.LayoutParams(-1, -1)
        headerInfo.setPadding(0, 0, if (preview) dp(336) else 0, 0)
        headerInfo.layoutParams = LinearLayout.LayoutParams(-1, dp(if (preview) 188 else 104))
        previewCaption.layoutParams = FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178) + searchInset; marginEnd = dp(18) }
        if (preview) root.bringChildToFront(video) else root.bringChildToFront(guidePanel)
        listOf(previewCaption, nowPlaying, loadingPanel, playbackLoading, retryPanel, favoriteFeedback).forEach(root::bringChildToFront)
        previewCaption.visibility = if (preview) View.VISIBLE else View.GONE
        previewCaption.text = if (current == null) "Select a channel" else "Full screen · ${current?.name}"
        positionPlaybackLoading()
        if (show) { focusChannel(); if (sportsVisible) scheduleSports() } else { ++focusEpoch; showNowPlaying() }
        scheduleEpg()
    }
    private fun showNowPlaying() {
        val channel = current ?: return
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.update(channel, guide.at(channel, System.currentTimeMillis()), videoInfo[channel.id]); nowPlaying.reveal()
        handler.postDelayed(hideNowPlaying, 2800)
    }
    private fun search() {
        sportsVisible = false
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
    private fun scheduleSports() {
        if (!sportsVisible || !guideVisible || closed) return
        if (!sportsRenderPending) { sportsRenderPending = true; handler.postDelayed(renderSports, 120) }
    }
    private fun refreshSports() {
        if (!sportsVisible || !guideVisible || !started || closed) return
        if (sportsComputing) { sportsDirty = true; return }
        sportsComputing = true
        val epoch = ++sportsEpoch; val sourceEpoch = generation; val snapshot = guide
        val followed = store.followedTeams; val selectedTeam = sportsTeam; val favoriteSnapshot = favorites
        val profile = if (browseAll) GuideFilters() else filters
        val quality = videoInfo.toMap(); val now = System.currentTimeMillis(); val hidden = unavailable.snapshot().filterValues { it > now }.keys
        val signature = listOf(snapshot.channels, profile.groups, favoriteSnapshot, followed, selectedTeam, hidden)
        val knownWarm = sportsChannels.takeIf { sportsWarmSignature == signature }
        sportsTask = sportsWorker.submit {
            val eligible = snapshot.channels.filter { it.id !in hidden && profile.matches(it, quality[it.id]) }
            val warm = knownWarm ?: SportsGuide.warmChannels(snapshot.channels.filter { profile.groups.isEmpty() || it.group in profile.groups }, favoriteSnapshot, selectedTeam?.let { setOf(it) } ?: followed)
            val rawGames = SportsGuide.find(snapshot, selectedTeam?.let { setOf(it) } ?: emptySet(), favoriteSnapshot, now, liveOnly = true)
            val eligibleIds = eligible.mapTo(hashSetOf()) { it.id }
            val games = rawGames.mapNotNull { game -> game.copy(broadcasts = game.broadcasts.filter { it.channel.id in eligibleIds }).takeIf { it.broadcasts.isNotEmpty() } }
            val guideChannels = warm.count { snapshot.at(it, now) != null }
            handler.post {
                if (epoch == sportsEpoch) sportsComputing = false
                if (closed || sourceEpoch != generation || epoch != sportsEpoch || !sportsVisible) return@post
                sportsChannels = warm; sportsWarmSignature = signature
                selectedGame = games.firstOrNull { it.key == selectedGame?.key } ?: games.firstOrNull()
                val checked = warm.count { guide.at(it, now) != null || (epgCheckedAt.containsKey(it.id) && "$generation:${it.id}" !in epgInFlight && it.id !in sportsForcedEpg) }
                val scanning = sportsRefreshing || (source?.kind == "xtream" && source?.epgUrl.isNullOrBlank() && checked < warm.size && now >= sportsBackoffUntil)
                val progress = when {
                    sportsRefreshing -> "Refreshing live guide…"
                    now < sportsBackoffUntil -> "Provider requests paused · showing cached live listings. Try again in a few minutes."
                    scanning -> "Checking live guide · $checked / ${warm.size} channels"
                    sportsRefreshMessage.isNotEmpty() -> sportsRefreshMessage
                    else -> "Live now · ${games.size} ${if (games.size == 1) "game" else "games"} · provider guide"
                }
                sportsPanel.submit(games, followed, selectedTeam, progress, scanning, rawGames.size - games.size, guideChannels)
                selectedGame?.let(::showGameInfo) ?: run {
                    channelInfo.text = "Sports"; programmeInfo.text = "Live MLB and NFL games from your provider’s guide. Pick a team above, or manage your teams."
                }
                scheduleEpg()
                if (sportsDirty) { sportsDirty = false; scheduleSports() }
            }
        }
    }
    private fun refreshSportsGuide() {
        val selected = source ?: return
        if (sportsRefreshing) return
        if (selected.kind == "xtream" && selected.epgUrl.isBlank()) {
            if (System.currentTimeMillis() < sportsBackoffUntil) { toast("Provider guide requests are paused. Try again in a few minutes."); return }
            sportsRefreshMessage = ""; sportsPanel.loading("Refreshing live guide…")
            sportsChannels.filter { "$generation:${it.id}" !in epgInFlight }.forEach { epgCheckedAt.remove(it.id); sportsForcedEpg.add(it.id) }
            scheduleSports(); scheduleEpg(); return
        }
        val epoch = generation; val snapshot = guide
        sportsRefreshing = true; sportsRefreshMessage = ""; scheduleSports()
        imports.execute {
            val result = runCatching {
                val url = selected.epgUrl.ifBlank {
                    Importers.m3u(Network.get(selected.url, selected.allowHttp).toString(Charsets.UTF_8), selected.allowHttp).epgUrl
                }
                if (url.isBlank()) throw SourceFailure(SourceProblem.GUIDE_RESPONSE)
                Importers.xmltv(Network.get(url, selected.allowHttp, 64 * 1024 * 1024), snapshot.channels.map { it.guideId }.filter { it.isNotEmpty() }.toSet())
            }
            ui(epoch) {
                sportsRefreshing = false
                result.onSuccess { programmes ->
                    guide = guide.copy(programmes = programmes); cacheEpg.run(); adapter.refreshRows()
                    sportsRefreshMessage = "Guide refreshed · ${programmes.size} channel listings · live now"
                }.onFailure { sportsRefreshMessage = "Guide refresh failed · ${SourceErrors.message(it)}" }
                scheduleSports()
            }
        }
    }
    private fun sportsTeams() {
        AlertDialog.Builder(this).setTitle("Follow teams").setItems(arrayOf("MLB · Baseball", "NFL · Football")) { _, leagueIndex ->
            val teams = SportsCatalog.teams.filter { it.league == if (leagueIndex == 0) "MLB" else "NFL" }.sortedBy { it.name }
            val chosen = store.followedTeams.toMutableSet()
            AlertDialog.Builder(this).setTitle("Follow ${teams.first().league} teams")
                .setMultiChoiceItems(teams.map { it.name }.toTypedArray(), teams.map { it.id in chosen }.toBooleanArray()) { _, which, checked ->
                    if (checked) chosen.add(teams[which].id) else chosen.remove(teams[which].id)
                }.setPositiveButton("Save") { _, _ -> store.followedTeams = chosen; if (sportsTeam != null && sportsTeam !in chosen) { sportsTeam = null; store.sportsTeam = "" }; scheduleSports() }
                .setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Close", null).show()
    }
    private fun showGameInfo(game: SportsGame) {
        selectedGame = game; channelInfo.text = game.title
        programmeInfo.text = "${game.teams.first().league} · ${if (game.live(System.currentTimeMillis())) "Live now" else SportsPanel.time(game.start)}\n" +
            game.broadcasts.take(3).joinToString(" · ") { it.channel.name } + "\nOK Choose channel · Hold Back Full screen"
    }
    private fun selectGame(game: SportsGame) {
        val now = System.currentTimeMillis()
        val broadcasts = game.broadcasts.filter { it.programme.end > now && !unavailable.contains(it.channel.id, now) }
        if (broadcasts.isEmpty()) { toast("These guide listings have ended or their channels are temporarily unavailable"); scheduleSports(); return }
        val names = broadcasts.map { b -> (if (b.channel.id in favorites) "★ " else "") + b.channel.name + " · " + (videoInfo[b.channel.id]?.label() ?: "Quality unverified") }
        AlertDialog.Builder(this).setTitle(if (game.live(now)) "Watch · ${game.title}" else "${SportsPanel.time(game.start)} · Watch channel now")
            .setItems(names.toTypedArray()) { _, which ->
                val channel = broadcasts[which].channel
                if (channel.id != current?.id || !hasPlayedChannel || player.playerError != null) tune(channel)
                showGameInfo(game) // Keep the game list and current preview; fullscreen remains explicit.
            }.setNegativeButton("Cancel", null).show()
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
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        refreshDetails(); root.bringChildToFront(details); details.visibility = View.VISIBLE; details.guideButton.requestFocus()
        handler.removeCallbacks(hideDetails); handler.postDelayed(hideDetails, 10000)
    }
    private fun closeDetails() {
        handler.removeCallbacks(hideDetails)
        if (::details.isInitialized) details.visibility = View.GONE
    }
    private fun editSource(existing: Source?) = SourceDialog.show(this, existing, { selected ->
        runCatching { val next = sources.filterNot { it.id == selected.id } + selected; store.saveSources(next); sources = next }
            .onSuccess { if (settingsPanel?.opened == true) closeSettings(); load(selected, force = true) }.onFailure { notice("Could not save", "Android could not securely save this source. Try again.") }
    }, { removed ->
        runCatching { val next = sources.filterNot { it.id == removed.id }; store.saveSources(next); cacheWrites.execute { store.removeCache(removed) }; sources = next }
            .onSuccess {
                if (settingsPanel?.opened == true) closeSettings()
                sources.firstOrNull()?.let { load(it) } ?: run {
                    ++generation; importTask?.cancel(true); handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); playbackToken++; player.stop(); frameRates.clear(); loadingPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE; source = null; current = null; filters = GuideFilters(); videoInfo.clear(); guide = Guide.EMPTY; sportsVisible = false; sportsChannels = emptyList(); ++sportsEpoch; updateGroups(); applyFilter(); showGuide(true); status.text = "Add a playlist in Settings to start watching"
                }
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    private fun playbackMenu() {
        val existing = settingsPanel
        if (existing?.opened == true) { existing.focusNavigation(); return }
        settingsReturnGuide = guideVisible; ++focusEpoch; handler.removeCallbacks(backHold)
        showGuide(false); nowPlaying.hide(immediate = true); previewCaption.visibility = View.GONE
        val panel = existing ?: SettingsPanel(this, ::renderSettingsPage, ::closeSettings).also { settingsPanel = it; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.bringChildToFront(panel)
        panel.open(listOf("Playlists", "Guide video", "Guide filters", "Categories", "Category order", "Theme", "Time format", "Audio track", "Subtitles", "Sports", "Updates", "Stream format", "Temporarily hidden channels", "Refresh playlist & guide", "About Spare", "Open-source licenses", "Exit Spare"))
    }
    private fun closeSettings() {
        settingsPanel?.visibility = View.GONE; showGuide(settingsReturnGuide)
        if (settingsReturnGuide) { ++focusEpoch; settingsButton.requestFocus() }
    }
    private fun renderSettingsPage(page: String) {
        val panel = settingsPanel ?: return
        fun refresh(focus: String? = null) = panel.refresh(focus)
        when (page) {
            "Playlists" -> {
                panel.page("Playlists", "Save several playlists; one plays at a time. Credentials stay encrypted on this TV.")
                sources.forEach { selected -> panel.action(selected.name, if (selected.id == source?.id) "Active playlist" else "Switch playlist", selected.id == source?.id) { closeSettings(); load(selected) } }
                panel.action("Add playlist") { editSource(null) }; if (source != null) panel.action("Edit current playlist") { editSource(source) }
            }
            "Guide video" -> {
                panel.page(page, "Choose how live video appears while browsing. Playback continues while you change settings.")
                listOf("Transparent overlay", "Small live preview").forEachIndexed { index, label -> panel.action(label, selected = store.smallPreview == (index == 1)) { store.smallPreview = index == 1; refresh(label) } }
            }
            "Guide filters" -> {
                panel.page(page, "${filters.summary()}. Resolution and FPS use measured playback; unverified channels stay hidden when those filters are enabled.")
                panel.action(if (browseAll) "Use guide filters" else "Show unverified channels", "Temporarily pause filters to play and verify a channel", browseAll) { browseAll = !browseAll; group = null; favoritesOnly = false; applyFilter(); refresh() }
                panel.note("Minimum resolution")
                listOf(0 to "Any resolution", 720 to "HD · 720p or better", 1080 to "FHD · 1080p or better", 2160 to "4K · 2160p or better").forEach { (height, label) -> panel.action(label, selected = filters.minHeight == height) { saveFilters(filters.copy(minHeight = height)); refresh(label) } }
                panel.note("Frame rates · select the rates you want to keep")
                FrameRateFilter.choices.forEach { (rate, label) -> panel.action(label, selected = rate !in filters.excludedFps) { val excluded = filters.excludedFps.toMutableSet(); if (!excluded.add(rate)) excluded.remove(rate); saveFilters(filters.copy(excludedFps = excluded)); refresh(label) } }
                panel.action("Reset filters") { saveFilters(GuideFilters()); refresh() }
            }
            "Categories" -> {
                panel.page(page, "Choose which provider categories appear in your guide. Presets use the provider’s country/language labels.")
                CategoryPresets.names.forEachIndexed { index, label -> panel.action(label) {
                    if (index == 4) { renderCategoryChoices(); panel.focusContent() }
                    else { val chosen = if (index == 0) emptySet() else CategoryPresets.select(providerGroups, index); if (index > 0 && chosen.isEmpty()) panel.note("No matching labels. Choose categories manually.") else { saveFilters(filters.copy(groups = chosen)); refresh(label) } }
                } }
            }
            "Category order" -> {
                panel.page(page, "Provider order is preserved within each region.")
                val selected = source
                if (selected == null) panel.note("Add a playlist first.") else listOf("Provider order", "USA first").forEachIndexed { index, label -> panel.action(label, selected = store.usaFirst(selected) == (index == 1)) { store.setUsaFirst(selected, index == 1); groupsForChannels = null; updateGroups(); refresh(label) } }
            }
            "Theme" -> {
                panel.page(page, "Guide colors. The current video keeps playing.")
                GuideTheme.palettes.forEach { palette -> panel.action(palette.name, selected = store.theme == palette.name) { store.theme = palette.name; GuideTheme.select(store.theme); applyTheme(); refresh(palette.name) } }
            }
            "Time format" -> {
                panel.page(page, "Use the same clock format throughout the guide, channel details, and Sports.")
                listOf("12 hour · 7:30 PM", "24 hour · 19:30").forEachIndexed { index, label -> panel.action(label, selected = store.clock24 == (index == 1)) {
                    store.clock24 = index == 1; GuideClock.use24Hour = store.clock24; updateTimes(); adapter.refreshRows(); if (details.isVisible) refreshDetails(); scheduleSports(); refresh(label)
                } }
            }
            "Audio track" -> {
                panel.page(page, current?.name ?: "Play a channel to choose its audio track.")
                panel.action("Automatic") { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build() }
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group -> for (index in 0 until group.length) if (group.isTrackSupported(index)) {
                    val format = group.getTrackFormat(index); val label = listOfNotNull(format.label, format.language, format.codecs).joinToString(" · ").ifBlank { "Track ${index + 1}" }
                    panel.action(label, selected = group.isTrackSelected(index)) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build(); refresh(label) }
                } }
            }
            "Subtitles" -> renderCaptionSettings()
            "Sports" -> { panel.page("Live sports", "Sports shows only MLB/NFL games playing now, from your provider’s current guide. Follow teams to add quick team selectors. Cached results appear first; a progress bar shows guide checks."); panel.action("Manage teams") { sportsTeams() }; panel.action("Open live sports") { closeSettings(); switchMode(true) } }
            "Updates" -> { panel.page(page, "Spare ${BuildConfig.VERSION_NAME}. Updates are downloaded from verified GitHub releases. Android may request installation permission or confirmation."); panel.action(availableUpdate?.let { "Update $it" } ?: "Check for updates") { updater.offer() } }
            "Stream format" -> {
                panel.page(page, "Auto tries the provider’s alternate transport format when a channel cannot open.")
                val selected = source
                if (selected?.kind != "xtream") panel.note("Available for Xtream playlists.") else listOf("auto" to "Auto", "ts" to "MPEG-TS", "hls" to "HLS").forEach { (format, label) -> panel.action(label, selected = store.streamFormat(selected) == format) { store.setStreamFormat(selected, format); current?.let(::tune); refresh(label) } }
            }
            "Temporarily hidden channels" -> { panel.page(page, "${unavailable.snapshot().size} channels are temporarily hidden after confirmed missing-stream responses. They return automatically after 15 minutes."); panel.action("Restore channels") { unavailable.clear(); saveUnavailable(); applyFilter(); refresh() } }
            "Refresh playlist & guide" -> { panel.page(page, "Refresh the active provider’s channels and programme guide."); panel.action("Refresh now") { val selected = source; closeSettings(); selected?.let { load(it, force = true) } } }
            "About Spare" -> panel.page("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player. MIT License · github.com/mot1us/spare\n\nWatching: OK details · Left previous · Back guide\nGuide: Right later · Left earlier / categories · Back live now\nHold OK favorite · Hold Back full screen\n\nNo app accounts, ads, or analytics. Your credentials and guide cache stay in encrypted app storage.")
            "Open-source licenses" -> { panel.page(page, ""); panel.note(listOf("NOTICES.txt", "MIT.txt", "Apache-2.0.txt").joinToString("\n\n") { assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() } }) }
            "Exit Spare" -> { panel.page(page, "Close the player."); panel.action("Exit Spare") { finish() } }
        }
    }
    private fun renderCategoryChoices() {
        val panel = settingsPanel ?: return
        panel.page("Visible categories", "Changes apply immediately. Keep at least one category selected, or choose Show all.")
        panel.action("Show all") { saveFilters(filters.copy(groups = emptySet())); renderCategoryChoices(); panel.focusOption("Show all") }
        panel.choices(providerGroups.map { it.ifBlank { "Uncategorized" } }, { index -> filters.groups.isEmpty() || providerGroups[index] in filters.groups }) { index ->
            val name = providerGroups[index]
            val chosen = if (filters.groups.isEmpty()) providerGroups.toMutableSet() else filters.groups.toMutableSet()
            if (!chosen.add(name)) chosen.remove(name)
            if (chosen.isEmpty()) toast("Keep at least one category selected") else saveFilters(filters.copy(groups = if (chosen.size == providerGroups.size) emptySet() else chosen))
        }
    }

    private fun renderCaptionSettings() {
        val panel = settingsPanel ?: return
        panel.page("Channel subtitles", current?.name?.let { "Saved for $it. App default applies to channels without an override." } ?: "Play a channel to choose its subtitles.")
        if (current != null && source != null) {
            val saved = store.channelCaptions(source!!, current!!)
            panel.action("Use app default", selected = saved == null) { setSubtitleMode(null); panel.refresh("Use app default") }
            panel.action("Off", selected = saved == CaptionTracks.OFF) { setSubtitleMode(CaptionTracks.OFF); panel.refresh("Off") }
            panel.action("Automatic", selected = saved == CaptionTracks.AUTO) { setSubtitleMode(CaptionTracks.AUTO); panel.refresh("Automatic") }
            val tracks = CaptionTracks.choices(player.currentTracks)
            tracks.forEach { choice -> panel.action(choice.label(), selected = saved == choice.key) { setSubtitleMode(choice.key); panel.refresh(choice.label()) } }
            if (tracks.isEmpty() || tracks.all { it.compatibility }) panel.note("Automatic tries common embedded caption services. This programme must supply captions for them to appear.")
        }
        panel.action("App default") { renderDefaultCaptionSettings(); panel.focusContent() }
    }
    private fun renderDefaultCaptionSettings() {
        val panel = settingsPanel ?: return
        panel.page("Default subtitles · all channels", "Channels with their own subtitle preference keep that preference.")
        listOf("Off", "Automatic").forEachIndexed { index, label -> panel.action(label, selected = store.captions == (index == 1)) {
            val wasEnabled = captionsEnabled(); store.captions = index == 1; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); applyCaptionPreference(); reconcileCaptions()
            if (!wasEnabled && captionsEnabled() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) playAttempt()
            renderDefaultCaptionSettings(); panel.focusOption(label)
        } }
    }
    private fun saveFilters(next: GuideFilters) {
        source?.let { store.setFilters(it, next) }; filters = next; browseAll = false; group = null; applyFilter()
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
        if (::sportsPanel.isInitialized) sportsPanel.refreshTheme()
        sidebarAdapter.notifyItemRangeChanged(0, sidebarAdapter.itemCount); adapter.refreshTheme()
    }
    private fun subtitleMode(): String = source?.let { selected -> current?.let { channel -> store.channelCaptions(selected, channel) } }
        ?: if (store.captions) CaptionTracks.AUTO else CaptionTracks.OFF
    private fun captionsEnabled() = subtitleMode() != CaptionTracks.OFF
    private fun applyCaptionPreference() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsEnabled())
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true).setSelectTextByDefault(captionsEnabled()).build()
        if (!captionsEnabled()) cancelCaptionProbe()
    }
    private fun selectCaption(choice: CaptionTracks.Choice) {
        if (choice.group.isTrackSelected(choice.index)) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index)).build()
    }
    private fun cancelCaptionProbe() { handler.removeCallbacks(probeCaptions); captionProbeScheduled = false }
    private fun reconcileCaptions() {
        if (!started || !captionsEnabled() || player.currentMediaItem?.mediaId != expectedMediaId) { cancelCaptionProbe(); return }
        val choices = CaptionTracks.choices(player.currentTracks)
        val mode = subtitleMode()
        if (mode.startsWith("track:")) choices.firstOrNull { it.key == mode }?.let { cancelCaptionProbe(); selectCaption(it); return }
        // Only rotate guessed compatibility services; never change a provider-declared language.
        if (mode == CaptionTracks.AUTO && !captionSawCue && captionScanCount < 6 && choices.size > 1 && choices.all { it.compatibility }) {
            if (!captionProbeScheduled) { captionProbeScheduled = true; handler.postDelayed(probeCaptions, 8000) }
        } else cancelCaptionProbe()
    }
    private fun setSubtitleMode(mode: String?) {
        val selected = source ?: return; val channel = current ?: return
        val wasEnabled = captionsEnabled()
        store.setChannelCaptions(selected, channel, mode); captionSawCue = false; captionScanCount = 0
        applyCaptionPreference(); reconcileCaptions()
        if (!wasEnabled && captionsEnabled() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) playAttempt()
    }
    private fun defaultSubtitles() {
        AlertDialog.Builder(this).setTitle("Default subtitles · all channels")
            .setSingleChoiceItems(arrayOf("Off", "Automatic"), if (store.captions) 1 else 0) { dialog, which ->
                val wasEnabled = captionsEnabled(); store.captions = which == 1; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); applyCaptionPreference(); reconcileCaptions(); dialog.dismiss()
                if (!wasEnabled && captionsEnabled() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) playAttempt()
            }.setNegativeButton("Close", null).show()
    }
    private fun subtitles() {
        val selected = source; val channel = current
        if (selected == null || channel == null) { defaultSubtitles(); return }
        val choices = CaptionTracks.choices(player.currentTracks)
        val names = arrayListOf("Use app default · ${if (store.captions) "Automatic" else "Off"}", "Off", "Automatic")
        names.addAll(choices.map { it.label() })
        if (choices.isEmpty() || choices.all { it.compatibility }) names.add("Caption availability · help")
        val saved = store.channelCaptions(selected, channel)
        val checked = when (saved) { null -> 0; CaptionTracks.OFF -> 1; CaptionTracks.AUTO -> 2; else -> choices.indexOfFirst { it.key == saved }.takeIf { it >= 0 }?.plus(3) ?: 2 }
        AlertDialog.Builder(this).setTitle("Subtitles · ${channel.name}").setSingleChoiceItems(names.toTypedArray(), checked) { dialog, index ->
            dialog.dismiss()
            when {
                index == 0 -> setSubtitleMode(null)
                index == 1 -> setSubtitleMode(CaptionTracks.OFF)
                index == 2 -> setSubtitleMode(CaptionTracks.AUTO)
                index - 3 in choices.indices -> setSubtitleMode(choices[index - 3].key)
                else -> notice("Caption availability", "This setting is saved for this channel only. Automatic tries the common CC1 and Digital 1 services when the provider omits caption labels. Compatibility services may be silent if this programme supplies no captions. Select a service directly to keep it for this channel, or choose Use app default to remove the override.")
            }
        }.setNeutralButton("App default") { _, _ -> defaultSubtitles() }.setNegativeButton("Close", null).show()
    }
    private fun tracks(type: Int) {
        if (type == C.TRACK_TYPE_TEXT) { subtitles(); return }
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        val names = arrayListOf("Automatic")
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) if (group.isTrackSupported(i)) {
                val format = group.getTrackFormat(i); choices.add(group to i)
                names.add(listOfNotNull(format.label, format.language, format.codecs).joinToString(" · ").ifBlank { "Track ${choices.size}" })
            }
        }
        AlertDialog.Builder(this).setTitle("Audio").setItems(names.toTypedArray()) { _, index ->
            val selection = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false)
            if (index > 0) choices[index - 1].let { (group, track) -> selection.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track)) }
            player.trackSelectionParameters = selection.build()
        }.setNegativeButton("Close", null).show()
    }
    @SuppressLint("GestureBackNavigation") // TV-only activity opts out of predictive back in the manifest so hold-Back receives key-down/up.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        settingsPanel?.takeIf { it.opened }?.let { panel ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { if (event.keyCode == KeyEvent.KEYCODE_MENU) closeSettings() else panel.back() }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) { closeSettings(); search() }; return true }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) playbackMenu(); return true }
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
        if (settingsPanel?.opened == true) { settingsPanel?.back(); return }
        if (details.isVisible) closeDetails()
        else if (guideVisible && guideSearch.isVisible) { finishSearch(true); searchButton.requestFocus() }
        else if (retryPanel.isVisible) { retryPanel.visibility = View.GONE; showGuide(true) }
        else if (!guideVisible) showGuide(true)
        else if (list.hasFocus() && guideTimeline.ahead((focused ?: current)?.id)) resetTime()
        else if (list.hasFocus() || (sportsVisible && sportsPanel.hasFocus())) focusSidebar()
        else focusChannel()
    }
    override fun onStart() { super.onStart(); started = true; if (::player.isInitialized && current != null) playAttempt(if (hasPlayedChannel) PlaybackPhase.RECONNECTING else PlaybackPhase.OPENING); scheduleEpg(); scheduleSports() }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.resume() }
    override fun onStop() { cancelCaptionProbe(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; started = false; firstFrame = false; expectedMediaId = ""; closeDetails(); handler.removeCallbacks(cacheEpg); cacheEpg.run(); handler.removeCallbacks(backHold); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg); handler.removeCallbacks(renderSports); sportsRenderPending = false; ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); cancelPlaybackCallbacks(); if (::player.isInitialized) player.stop(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { if (::voiceInput.isInitialized) voiceInput.stop(); closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); epgWorkers.shutdownNow(); sportsWorker.shutdownNow(); cacheWrites.shutdown(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

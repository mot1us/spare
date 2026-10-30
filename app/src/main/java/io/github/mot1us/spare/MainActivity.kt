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
import android.widget.ImageView
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
    private val imports = Executors.newFixedThreadPool(2) { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-import") }
    private val cacheWrites = Executors.newSingleThreadExecutor()
    private val cadence = FrameCadence()
    private lateinit var sourceVideoFrames: SourceVideoFrames
    private lateinit var reminders: GameReminders
    private lateinit var reminderBanner: GameReminderBanner
    private var reminderGame: SportsGame? = null
    private var reminderPending: Pair<SportsGame, Long>? = null
    private var reminderCheckedAt = 0L
    private var reminderChecking = false
    private var reminderReturnFocus: View? = null
    private val hideReminder = Runnable { dismissGameReminder() }
    private var cachedStartup = false
    private var availableUpdate: String? = null
    private var importTask: java.util.concurrent.Future<*>? = null
    private val epgWorkers = Executors.newFixedThreadPool(2)
    private var epgActive = 0
    private var epgWanted = emptyList<Channel>()
    private val epgInFlight = mutableSetOf<String>()
    private val epgCheckedAt = mutableMapOf<String, Long>()
    private val epgDepth = mutableMapOf<String, Int>()
    private val epgErrors = mutableMapOf<String, String>()
    private val fetchVisibleEpg = Runnable { collectVisibleEpg() }
    private val cacheEpg = Runnable {
        val selected = source; val snapshot = guide; val observedAt = System.currentTimeMillis()
        if (selected != null && snapshot.channels.isNotEmpty()) cacheWrites.execute { runCatching { store.cacheProgrammes(selected, snapshot.programmes, observedAt) } }
    }
    private lateinit var refreshBar: ProgressBar
    private var refreshRetryAt = 0L
    private val ownRefreshes = mutableSetOf<String>()
    private var mergeEpoch = 0
    private var deferredRefresh: PlaylistRefresh.Result? = null
    private val refreshTick = object : Runnable {
        override fun run() {
            if (closed) return
            sources.forEach { value -> settingsPanel?.findViewWithTag<TextView>("refresh-status:${value.id}")?.text = refreshSummary(value) }
            val selected = source
            checkGameReminders()
            if (selected != null) {
                val busy = PlaylistRefresh.running(selected.id)
                if (::refreshBar.isInitialized) refreshBar.isVisible = busy
                if (busy && guide.channels.isEmpty()) { loadingPanel.visibility = View.VISIBLE; loadingText.text = PlaylistRefresh.stage(selected.id) }
                if (!busy && started && System.currentTimeMillis() >= refreshRetryAt) autoRefresh(selected, false)
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val sportsWorker = Executors.newSingleThreadExecutor()
    private val sportsMatchWorker = Executors.newSingleThreadExecutor()
    private val sportsStreamCache = SportsStreamCache()
    private val sportsWarmWorker = Executors.newSingleThreadExecutor()
    private var sportsWarmTask: java.util.concurrent.Future<*>? = null
    private var sportsWarmActive: String? = null
    private var sportsWarmProgress = "Finding stream…"
    private var sportsWarmEpoch = 0
    private var sportsWarmGames = emptyList<SportsGame>()
    private var sportsWarmPriority: SportsGame? = null
    private val sportsWarmAttempted = mutableMapOf<String, Long>()
    private val sportsStreamSummaries = mutableMapOf<String, String>()
    private val warmSportsStreams = Runnable { prefetchSportsStream() }
    private data class GameStreams(val game: SportsGame, val channels: List<Channel>)
    private var sportsResults: GameStreams? = null
    private var watchingSports: GameStreams? = null
    private var sportsPlaybackPlan: SportsPlaybackPlan? = null
    private val sportsLookupWorkers = Executors.newFixedThreadPool(2)
    private var sportsMatchTask: java.util.concurrent.Future<*>? = null
    private var pendingSportsTune: String? = null
    private var sportsManager: SettingsPanel? = null
    private var sportsCalendar: SportsCalendarPanel? = null
    private var calendarSchedule: SportsCalendarLoader? = null
    private var calendarSnapshot = LiveSportsSnapshot.EMPTY
    internal var calendarSportsLoader: (java.time.LocalDate, Set<String>) -> LiveSportsSnapshot = { day, leagues -> SportsSchedule.load(emptySet(), requestedDate = day, enabledLeagues = leagues, calendarOnly = true) }
    private var sportsManagerReturnGuide = false
    private var sportsManagerReturnSettings = false
    private var sportsPreferencesChanged = false
    private var sportsTask: java.util.concurrent.Future<*>? = null
    private var sportsMatchEpoch = 0
    private var sportsEpoch = 0
    private var sportsVisible = false
    private var sportsTeam: String? = null
    private var sportsRenderPending = false
    private var sportsComputing = false
    private var sportsDirty = false
    private var sportsReplayGame: SportsGame? = null
    private val replayCache = linkedMapOf<String, Pair<Long, List<SportsReplay>>>()
    private var replayDiscovery: SportsReplayDiscovery? = null
    private var replayGames = emptyList<SportsGame>()
    private fun syncReplayDiscovery() {
        if (!::sportsPanel.isInitialized || !::player.isInitialized) return
        val active = !closed && started && sportsVisible && guideVisible && !sportsPanel.watching && !multiview.active && player.playWhenReady && player.playbackState != Player.STATE_BUFFERING
        if (!active) { replayDiscovery?.pause(); return }
        val discovery = replayDiscovery ?: SportsReplayDiscovery({ sourceId, epoch, game, choices, _ ->
            if (!closed && source?.id == sourceId && generation == epoch) {
                rememberSportsReplays(game, choices)
                replayDiscovery?.let { sportsPanel.updateReplayDiscovery(it.checkingKey, it.checkedKeys) }
                scheduleSports()
            }
        }, { checking -> if (!closed) sportsPanel.updateReplayDiscovery(checking, replayDiscovery?.checkedKeys.orEmpty()) }).also { replayDiscovery = it }
        discovery.update(source, generation, guide, replayGames, selectedGame, active)
    }

    private var sportsChannels = emptyList<Channel>()
    private var selectedGame: SportsGame? = null
    private var sportsBackoffUntil = 0L
    private var sportsFailures = 0
    private val sportsForcedEpg = mutableSetOf<String>()
    private val renderSports = Runnable { sportsRenderPending = false; refreshSports() }
    private var liveSports = LiveSportsSnapshot.EMPTY
    private var liveSportsChecking = false
    private var liveSportsAgain = false
    private var liveSportsAttempt = 0L
    private var liveSportsTask: java.util.concurrent.Future<*>? = null
    private val liveSportsWorker = Executors.newSingleThreadExecutor()
    private val reminderWorker = Executors.newSingleThreadExecutor()
    internal var liveSportsLoader: () -> LiveSportsSnapshot = {
        val day = SportsSchedule.selectedDate(sportsTeam); val file = "sports-schedule-${day ?: "default"}.enc"
        runCatching { (store.read(file) ?: if (day == null) store.read("sports-schedule.enc") else null)?.let { SportsSchedule.from(org.json.JSONObject(it.toString(Charsets.UTF_8))) } }.getOrNull()?.let { saved -> handler.post { if (!closed && SportsSchedule.selectedDate(sportsTeam) == day && liveSports.events.isEmpty()) { liveSports = saved; scheduleSports() } } }
        val followed = if (sportsTeam in setOf("today", "live")) emptySet() else store.followedTeams + listOfNotNull(sportsTeam?.takeIf { it in SportsCatalog.byId })
        // Publish complete cached/fresh snapshots, never one league at a time into the UI.
        SportsSchedule.load(followed, requestedDate = day, enabledLeagues = store.sportsLeagues).also { snapshot -> if (snapshot.leagues.isNotEmpty()) runCatching { store.write(file, SportsSchedule.json(snapshot).toString().toByteArray()) } }
    }

    private val pollLiveSports = Runnable { if (sportsVisible && guideVisible && started) { checkLiveSports(); scheduleSports() } }
    private lateinit var library: LibraryController
    private lateinit var libraryButton: Button
    private lateinit var demandControls: OnDemandControls
    private var onDemand: OnDemand? = null
    private var demandPosition = 0L
    private var demandPaused = false
    private var demandSavedAt = 0L
    private var demandSubtitleMode: String? = null
    private val hideDemandControls = Runnable { demandControls.visibility = View.GONE }
    private val demandTick = object : Runnable {
        override fun run() { if (!closed && onDemand != null && started) { if (demandControls.isShown) refreshDemandControls(); if (android.os.SystemClock.elapsedRealtime() - demandSavedAt >= 10000) { saveDemandProgress(); demandSavedAt = android.os.SystemClock.elapsedRealtime() }; handler.postDelayed(this, 1000) } }
    }
    private lateinit var sportsPanel: SportsPanel
    private var settingsPanel: SettingsPanel? = null
    private var themeEditor: ThemeEditor? = null
    private var localEditor: LocalChannelsEditor? = null
    private val updateAppearance = Runnable { if (!closed) applyTheme() }
    private var settingsReturnGuide = true
    private var settingsReturnLibrary = false
    private lateinit var timeline: LinearLayout
    private lateinit var nowButton: Button
    private lateinit var guideMode: Button
    private lateinit var sportsMode: Button
    private lateinit var categoryHeading: TextView
    private lateinit var favoriteFeedback: TextView
    private val hideFavoriteFeedback = Runnable { favoriteFeedback.animate().alpha(0f).setDuration(150).withEndAction { favoriteFeedback.visibility = View.GONE }.start() }
    private val guideTimeline = GuideTimeline()
    private val logos = LogoLoader()
    private lateinit var metadata: LiveMetadata
    private lateinit var store: SecureStore
    private lateinit var updater: Updater
    private lateinit var player: ExoPlayer
    private lateinit var multiview: Multiview
    private lateinit var video: PlayerView
    @Volatile private var measuredFrameRate = 0f
    @Volatile private var decoderFrameRate = 0f
    @Volatile private var scanEvidence = "unknown"
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
    private var voiceDucked = false
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
    private lateinit var dockHint: View
    private lateinit var categoryColumn: LinearLayout
    private lateinit var navigationRail: NavigationRail
    private lateinit var searchButton: Button
    private lateinit var settingsButton: Button
    private lateinit var updateBadge: TextView
    private lateinit var headerSurface: FrameLayout
    private lateinit var headerArtwork: ImageView
    private lateinit var programmeArtwork: ProgrammeArtwork
    private lateinit var headerInfo: LinearLayout
    private lateinit var guideSearch: GuideSearchBar
    private var vodWarmScheduled = false
    private val warmVodIndex = Runnable {
        vodWarmScheduled = false
        if (!closed && started && onDemand == null && !multiview.active) source?.let(playlistSearch::prefetch)
    }
    private fun scheduleVodIndex() {
        if (!vodWarmScheduled && started && source?.kind == "xtream") { vodWarmScheduled = true; handler.postDelayed(warmVodIndex, 2000) }
    }
    private lateinit var playlistSearch: PlaylistSearch
    private lateinit var searchPanel: PlaylistSearchPanel
    private var searchedDemand = emptyList<OnDemand>()
    private var searchLoading = false
    private var searchWarning = ""
    private var vodRequest: VodStart.Request? = null
    private lateinit var vodStartPanel: VodStartPanel
    private var vodMode = "movie"
    private var vodReturnGuide = false
    private lateinit var details: PlaybackDetails
    private lateinit var retryPanel: LinearLayout
    private var sources = emptyList<Source>()
    private var source: Source? = null
    private var guide = Guide.EMPTY
    private var current: Channel? = null
    private var focused: Channel? = null
    private var favorites = emptySet<String>()
    private var favoritesOnly = false
    private var localOnly = false
    private var localIds = emptySet<String>()
    private var localLineup: List<Channel>? = null
    private var localQuery = ""
    private var localLoading = false
    private var localSourceId = ""
    private lateinit var localProgress: ProgressBar
    private val localWorker = Executors.newSingleThreadExecutor()
    private var localTask: java.util.concurrent.Future<*>? = null
    private val localStations by lazy { LocalChannels(assets.open("local-stations.tsv").bufferedReader().use { it.readText() }) }
    private lateinit var guideTabs: LinearLayout
    private lateinit var allChannelsTab: Button
    private lateinit var localTab: Button
    private var filters = GuideFilters()
    private var sportsFilters = GuideFilters()
    private var sportsQualityChanged = false
    private var videoInfo = mutableMapOf<String, VideoInfo>()
    private var videoSavePending = false
    private val saveVideoReadings = Runnable {
        videoSavePending = false
        source?.let { selected -> val saved = videoInfo.toMap(); cacheWrites.execute { store.saveVideoInfo(selected, saved) } }
    }
    private fun flushVideoReadings() {
        if (videoSavePending) { handler.removeCallbacks(saveVideoReadings); saveVideoReadings.run() }
    }
    private var recentLookupChannels: List<Channel>? = null
    private var recentLookup = emptyMap<String, Channel>()
    private var providerGroups = emptyList<String>()
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
    private var playbackRebuffers = 0
    private var playbackReconnects = 0
    private var playbackLoadErrors = 0
    private var playbackPhase = PlaybackPhase.OPENING
    private var startupFailures = StartupFailures()
    private var reconnects = ReconnectBudget()
    private val playbackProgress = PlaybackProgress()
    @Volatile private var progressFrameToken = -1
    @Volatile private var progressFrameUs = Long.MIN_VALUE
    private var monitoringPlayback = false
    private val monitorPlayback = object : Runnable {
        override fun run() {
            monitoringPlayback = false
            if (!ownsPlayback()) { playbackProgress.reset(); return }
            val state = player.playbackState
            val active = firstFrame && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                player.playerError == null && !retryPanel.isVisible && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            val frame = if (!multiview.active && player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) progressFrameUs.takeIf { progressFrameToken == playbackToken } ?: Long.MIN_VALUE else null
            if (playbackProgress.stalled(playbackToken, android.os.SystemClock.elapsedRealtime(), player.currentPosition, frame, state == Player.STATE_BUFFERING, active)) {
                reconnectOrFail(if (onDemand != null) "Playback stopped advancing. Retry resumes this title from your saved position. (STREAM_STALLED)" else "Playback stopped advancing. Choose Retry to reconnect this channel. (STREAM_STALLED)")
                return
            }
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) { monitoringPlayback = true; handler.postDelayed(this, 1000) }
        }
    }
    private var unavailable = UnavailableChannels()
    private var lastWorkingChannel: Channel? = null
    private var groupsForChannels: List<Channel>? = null
    private var groupsForOrder: List<String>? = null
    private var voiceSearchPending = false
    private val filterSearch = Runnable { query = guideSearch.query(); applyFilter(); updatePlaylistSearch() }
    private val hideDetails = Runnable { closeDetails() }
    private val workingUrls = mutableMapOf<String, String>()
    private val retry = Runnable { if (started && (current != null || onDemand != null)) playAttempt(PlaybackPhase.RECONNECTING) }
    private val revealBuffering = Runnable { if (started && (current != null || onDemand != null) && player.playbackState == Player.STATE_BUFFERING) showPlaybackLoading(PlaybackPhase.BUFFERING) }
    private val startupTimeout = Runnable {
        if (started && (current != null || onDemand != null) && !firstFrame) {
            if (onDemand != null) { reconnectOrFail("This title did not resume. Retry continues from your saved position. (STREAM_TIMEOUT)"); return@Runnable }
            if (!hasPlayedChannel && tryAlternate()) return@Runnable
            if (onDemand == null && hasPlayedChannel) { reconnectOrFail("The channel did not resume. Choose Retry to reconnect. (STREAM_TIMEOUT)"); return@Runnable }
            showPlaybackFailure("No video arrived within 15 seconds. Try another channel or choose a stream format in Settings. (STREAM_TIMEOUT)")
        }
    }
    private var backLongPressed = false
    private val backHold = Runnable { if (guideVisible && settingsPanel?.opened != true && current != null) { backLongPressed = true; showGuide(false) } }
    private val hideNowPlaying = Runnable { nowPlaying.hide() }
    private val minuteTick = object : Runnable {
        override fun run() { if (!closed) { if (unavailable.prune(System.currentTimeMillis())) { saveUnavailable(); applyFilter() }; if (guideVisible) { updateTimes(); adapter.refreshRows(); if (sportsVisible) scheduleSports() else (focused ?: current)?.let(::showInfo) }; if (details.isVisible) refreshDetails(); handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        store = SecureStore(this)
        reminders = runCatching { store.read("game-reminders.enc")?.let { GameReminders.from(org.json.JSONObject(it.toString(Charsets.UTF_8))) } }.getOrNull() ?: GameReminders()
        metadata = LiveMetadata(store) { (if (guideVisible) focused ?: current else current)?.let(::showInfo) }
        configureAppearance(); GuideClock.use24Hour = store.clock24; sportsTeam = "today"
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        providerFactory = DataSource.Factory { if (onDemand == null) LiveProviderDataSource { source?.allowHttp == true } else ProviderDataSource { source?.allowHttp == true } }
        mediaFactory = DefaultMediaSourceFactory(providerFactory, DefaultExtractorsFactory().setTsExtractorFlags(tsFlags).setTsSubtitleFormats(CaptionTracks.compatibility)).setLoadErrorHandlingPolicy(ProviderLoadErrors())
        val loadControl = PlaybackLoadControl()
        sourceVideoFrames = SourceVideoFrames(androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory.getDefault(this)) { playbackToken }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun getCodecAdapterFactory() = sourceVideoFrames
        }.setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaFactory).setLoadControl(loadControl)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build()
        video.player = player
        multiview = Multiview(root, video, player, ::button, providerFactory, store, { channel -> tune(channel); showGuide(false) }, { showGuide(true) }).also { views ->
            views.fullScreenMain = { showGuide(false) }
            views.addGame = { closeDetails(); switchMode(true) }
            views.addChannel = { closeDetails(); switchMode(false) }
        }
        applySubtitleAppearance()
        frameRates = FrameRates(this, video.videoSurfaceView as SurfaceView).apply { enabled = store.frameRateMatching }
        voiceInput = NativeVoiceInput(this, { guideSearch.message(it) }, { words ->
            if (guideSearch.isVisible && started) { guideSearch.setQuery(words); finishSearch(false) }
        }, { listening ->
            if (listening && !voiceDucked) { voiceVolume = player.volume; voiceDucked = true; player.volume = 0f }
            else if (!listening && voiceDucked) { voiceDucked = false; player.volume = voiceVolume }
        })
        applyCaptionPreference()
        var qualityFormat: androidx.media3.common.Format? = null
        var qualityCodec: android.media.MediaFormat? = null
        var qualityHeader = AvcHeader.Info()
        var qualityCodecRate = 0f
        var qualityToken = -1
        var qualityPostedAt = 0L
        player.setVideoFrameMetadataListener { pts, _, format, codec ->
            val token = playbackToken
            progressFrameUs = pts; progressFrameToken = token
            val measured = cadence.observe(token, pts)
            measuredFrameRate = measured
            val now = android.os.SystemClock.elapsedRealtime()
            // Keep frame progress/cadence exact; defer format parsing, exceptions,
            // display requests and UI snapshots off the per-frame hot path.
            if (qualityToken == token && now - qualityPostedAt < 500) return@setVideoFrameMetadataListener
            if (qualityToken != token || qualityFormat !== format || qualityCodec !== codec) {
                qualityToken = token; qualityFormat = format; qualityCodec = codec
                qualityCodecRate = runCatching { codec?.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    ?: runCatching { codec?.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)?.toFloat() }.getOrNull() ?: 0f
                val buffers = format.initializationData.toMutableList()
                listOf("csd-0", "csd-1").forEach { key -> runCatching {
                    codec?.getByteBuffer(key)?.duplicate()?.let { data -> if (data.remaining() in 4..65536) buffers.add(ByteArray(data.remaining()).also(data::get)) }
                } }
                qualityHeader = if (format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_H264) AvcHeader.read(buffers) else AvcHeader.Info()
                scanEvidence = if (qualityHeader.known) (if (qualityHeader.interlaced) "SPS interlaced" else "SPS progressive") else if (VideoRate.interlaced(format)) "MPEG-2 interlaced" else "unknown"
            }
            decoderFrameRate = qualityCodecRate
            val info = VideoRate.observation(format, measured, qualityCodecRate, qualityHeader, sourceVideoFrames.rate(token))
            if (!multiview.active) frameRates.request(info.fps)
            if (now - qualityPostedAt >= 500) {
                qualityPostedAt = now
                // Player callbacks and persisted UI state have different threads.
                // Check first-frame/media ownership only after entering the UI thread.
                handler.post {
                    if (firstFrame && token == playbackToken) {
                        val size = player.videoSize
                        val width = info.width.takeIf { it > 0 } ?: size.width
                        val height = info.height.takeIf { it > 0 } ?: size.height
                        if (width > 0 && height > 0) recordVideo(info.copy(width = width, height = height))
                    }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlaybackMonitoring()
                if (onDemand != null) { refreshDemandControls(); handler.removeCallbacks(hideDemandControls); if (isPlaying && demandControls.isShown) handler.postDelayed(hideDemandControls, 8000) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { syncPlaybackMonitoring() }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { syncPlaybackMonitoring() }
            override fun onTracksChanged(tracks: Tracks) { reconcileCaptions() }
            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isNotEmpty()) { captionSawCue = true; cancelCaptionProbe() }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!started || (current == null && onDemand == null) || player.playerError !== error) return
                cancelPlaybackCallbacks()
                val http = PlaybackErrors.httpFailure(error)
                val statusCode = http?.responseCode
                if (statusCode == 404) onDemand?.takeIf { it.kind in setOf("movie", "episode") }?.let { item -> source?.let { library.repository.markMissing(it, item) } }
                if (!hasPlayedChannel && onDemand == null) {
                    startupFailures.record(statusCode, http?.dataSpec?.uri?.toString(), playbackUrl)
                    if (statusCode !in setOf(401, 403, 429) && tryAlternate()) return
                    if (startupFailures.allEndpointsMissing()) { hideUnavailable(); return }
                }
                if (statusCode in setOf(401, 403, 429)) sportsPlaybackPlan?.accepted = true
                if (!hasPlayedChannel && onDemand == null && sportsPlaybackPlan != null) { showPlaybackFailure(PlaybackErrors.message(error)); return }
                val hls = player.currentManifest is androidx.media3.exoplayer.hls.HlsManifest || playbackUrl.substringBefore('?').endsWith(".m3u8", true)
                if (onDemand == null && hasPlayedChannel && StartupFailures.expiredSegment(statusCode, http?.dataSpec?.uri?.toString(), playbackUrl, hls)) reconnectOrFail(PlaybackErrors.message(error))
                else if (statusCode != null && statusCode in 400..499 && statusCode !in setOf(408, 429)) showPlaybackFailure(PlaybackErrors.message(error))
                else if (statusCode == 429) showPlaybackFailure(PlaybackErrors.message(error))
                else reconnectOrFail(PlaybackErrors.message(error))
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != player.playbackState) return
                if (state == Player.STATE_BUFFERING && hasPlayedChannel && player.playWhenReady) playbackRebuffers++
                if (state == Player.STATE_BUFFERING) {
                    stopSportsPrefetch()
                    playlistSearch.pausePrefetch()
                }
                if (state == Player.STATE_READY && onDemand == null) scheduleVodIndex()
                if (state == Player.STATE_READY && sportsVisible && guideVisible && !sportsPanel.watching) queueSportsPrefetch()
                syncPlaybackMonitoring(); syncReplayDiscovery()
                if (state == Player.STATE_ENDED && started && onDemand != null) { saveDemandProgress(ended = true); showDemandControls() }
                else if (state == Player.STATE_ENDED && started && current != null) reconnectOrFail("The provider ended this stream. Try another channel or Retry. (STREAM_ENDED)")
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                if (!wasCanceled && !eventTime.timeline.isEmpty && eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == expectedMediaId) playbackLoadErrors++
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.timeline.isEmpty || eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != expectedMediaId) return
                firstFrame = true; hasPlayedChannel = true
                sportsPlaybackPlan?.takeIf { it.channel?.id == current?.id }?.let { plan ->
                    // A working stream is accepted immediately; quality never triggers another connection.
                    plan.accepted = true
                }
                if (pendingSportsTune == current?.id && pendingSportsTune != null) { pendingSportsTune = null; sportsPanel.finishTuning(); if (sportsVisible && guideVisible) showGuide(false) }
                if (onDemand == null) lastWorkingChannel = current
                if (onDemand == null) current?.let { channel -> source?.let { store.setLast(it, channel) }; workingUrls[channel.id] = playbackUrl; if (unavailable.restore(channel.id)) { saveUnavailable(); applyFilter() } }
                cancelPlaybackCallbacks(); reconcileCaptions(); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
                syncPlaybackMonitoring()
                player.videoFormat?.let { format ->
                    if (format.width > 0 && format.height > 0) recordVideo(VideoInfo(format.width, format.height, VideoRate.resolve(format.frameRate, 0f, interlaced = VideoRate.interlaced(format)), VideoRate.interlaced(format)))
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
        PlaylistRefresh.listen(this) { selectedSource, result ->
            if (!closed && source == selectedSource && selectedSource.id !in ownRefreshes) {
                if (started) mergeRefresh(result) else deferredRefresh = result
            }
        }
        RefreshJobs.configure(this, sources); handler.post(refreshTick)
        handler.post(minuteTick)
        updater.check()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; typeface = AppFont.face(); setTextColor(Color.WHITE) }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        isFocusableInTouchMode = true; text = value; textSize = 12f; isAllCaps = false; setPadding(dp(14), 0, dp(14), 0)
        minWidth = 0; minimumWidth = 0; minHeight = dp(34); minimumHeight = dp(34); typeface = AppFont.face(true)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(GuideTheme.focusText, Color.WHITE)))
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() }
        background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE)); addState(intArrayOf(), shape(Color.rgb(30, 30, 30))) }
        background = GuideTheme.background(resources.displayMetrics.density, Color.rgb(30, 30, 30))
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) }
        setOnClickListener { click() }
    }
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }; setContentView(root)
        var lastSportsFocus: java.lang.ref.WeakReference<View>? = null
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
            val waiting = pendingFocusList
            if (waiting != null && next != null && generateSequence(next) { it.parent as? View }.none { it === waiting }) {
                ++focusEpoch; pendingFocusList = null
            }
            if (::navigationRail.isInitialized && navigationRail.isVisible && next != null) {
                val inSports = ::sportsPanel.isInitialized && generateSequence(next) { it.parent as? View }.any { it === sportsPanel }
                val inRail = generateSequence(next) { it.parent as? View }.any { it === navigationRail }
                if (inSports) lastSportsFocus = java.lang.ref.WeakReference(next)
                if (inRail && guideVisible && sportsVisible && !navigationRail.deliberateFocus) {
                    // Android can restore a dock button directly when Sports replaces or
                    // disables a focused view. Keep the dock closed and restore content.
                    navigationRail.contentHasFocus = true
                    navigationRail.focusChanged(false)
                    root.post {
                        if (started && guideVisible && sportsVisible && !navigationRail.deliberateFocus && navigationRail.hasFocus()) {
                            val previous = lastSportsFocus?.get()
                            if (previous?.isShown != true || !previous.isEnabled || !previous.requestFocus()) sportsPanel.focusGames()
                        }
                    }
                    return@addOnGlobalFocusChangeListener
                }
                navigationRail.contentHasFocus = inSports
                navigationRail.focusChanged(inRail)
            }
        }
        video = PlayerView(this).apply {
            useController = false; isFocusable = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(true); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); contentDescription = "Current channel video"
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        guidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(104), dp(18), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF20B1018.toInt(), 0xC90B1018.toInt()))
        }
        root.addView(guidePanel, FrameLayout.LayoutParams(-1, -1))
        root.removeView(video); root.addView(video, FrameLayout.LayoutParams(-1, -1))
        guideSearch = GuideSearchBar(this, ::button, {
            handler.removeCallbacks(filterSearch); handler.postDelayed(filterSearch, 150)
        }, ::voiceSearch, { finishSearch(false) }, { finishSearch(true) })
        guidePanel.addView(guideSearch, LinearLayout.LayoutParams(-1, -2))
        guideTabs = LinearLayout(this).apply { tag = "guide-tabs" }
        allChannelsTab = button("All channels") { localOnly = false; group = null; favoritesOnly = false; applyFilter(); focusChannelAt(0) }.apply { tag = "guide-all" }
        localTab = button("Local") {
            if (store.localLocation.isBlank()) { playbackMenu(); settingsPanel?.selectSetting("Local channels") }
            else { localOnly = true; group = null; favoritesOnly = false; applyFilter(); if (localLoading && adapter.itemCount == 0) localTab.requestFocus() else focusChannelAt(0) }
        }.apply { tag = "guide-local" }
        guideTabs.addView(allChannelsTab); guideTabs.addView(localTab)
        localProgress = ProgressBar(this).apply { tag = "local-matching-progress"; isIndeterminate = true; visibility = View.GONE; contentDescription = "Finding local channels" }
        guideTabs.addView(localProgress, LinearLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8) })
        listOf(allChannelsTab, localTab).forEach { tab -> tab.setOnKeyListener { _, key, event -> when (key) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusChannel(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) openNavigation(); true }
            else -> false
        } } }
        guidePanel.addView(guideTabs, LinearLayout.LayoutParams(-1, dp(38)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guidePanel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        navigationRail = NavigationRail(this, ::search, { openSection(false) }, { openSection(true) }, { openLibrary() }, ::playbackMenu,
            ::enterNavigationContent, { ++focusEpoch; pendingFocusList = null })
        searchButton = navigationRail.search; settingsButton = navigationRail.settings; guideMode = navigationRail.guide
        sportsMode = navigationRail.sports; libraryButton = navigationRail.library; updateBadge = navigationRail.badge
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0); tag = "category-column" }; categoryColumn = navigation
        categoryHeading = text("CATEGORIES", 9f).apply { setTextColor(0xFF929FAF.toInt()); letterSpacing = .12f; setPadding(dp(10), dp(3), 0, dp(7)) }
        navigation.addView(categoryHeading)
        sidebar = RecyclerView(this).apply { layoutManager = TvFocus.Layout(this@MainActivity); preserveFocusAfterLayout = false; itemAnimator = null; setHasFixedSize(true); overScrollMode = View.OVER_SCROLL_NEVER; tag = "guide-categories" }
        sidebarAdapter = SidebarAdapter(this, { section ->
            val hadSearch = guideSearch.isVisible
            clearGuideSearch(); sportsVisible = false
            localOnly = false
            favoritesOnly = section.key == "favorites"
            group = if (section.key.startsWith("group:")) section.key.removePrefix("group:") else null
            focused = null; applyFilter()
            if (hadSearch) showGuide(true)
            focusChannelAt(0)
        }, { focusChannel() }, { openNavigation() }, { if (localOnly) localTab.requestFocus() else allChannelsTab.requestFocus() }, ::focusCategoryAt)
        sidebar.adapter = sidebarAdapter
        navigation.addView(sidebar, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(navigation, LinearLayout.LayoutParams(dp(160), -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        headerSurface = FrameLayout(this)
        headerArtwork = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .7f; tag = "programme-artwork"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE }
        programmeArtwork = ProgrammeArtwork(headerArtwork)
        headerSurface.addView(headerArtwork, FrameLayout.LayoutParams(-1, -1))
        headerSurface.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF50B1018.toInt(), 0x700B1018)) }, FrameLayout.LayoutParams(-1, -1))
        headerInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "guide-header" }
        channelInfo = text("", 20f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = AppFont.face(true) }
        headerInfo.addView(channelInfo, LinearLayout.LayoutParams(-1, -2))
        programmeInfo = text("Add a playlist in Settings to start watching.", 12f).apply { maxLines = 5; setTextColor(Color.LTGRAY) }
        headerInfo.addView(programmeInfo, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        status = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 2 }
        headerInfo.addView(status, LinearLayout.LayoutParams(-1, -2))
        refreshBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE; tag = "background-refresh" }; headerInfo.addView(refreshBar, LinearLayout.LayoutParams(-1, dp(2)))
        headerSurface.addView(headerInfo, FrameLayout.LayoutParams(-1, -1))
        content.addView(headerSurface, LinearLayout.LayoutParams(-1, dp(188)))
        timeline = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        filterInfo = text("", 11f).apply { setTextColor(Color.LTGRAY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        timeline.addView(filterInfo, LinearLayout.LayoutParams(0, dp(30), 1f))
        val earlier = button("‹") { moveTime(focused ?: current, -1) }.apply { contentDescription = "Earlier on this channel"; tag = "guide-earlier" }
        nowButton = button("Now") { resetTime() }.apply { tag = "guide-now"; contentDescription = "Return this channel to live guide" }
        val later = button("›") { moveTime(focused ?: current, 1) }.apply { contentDescription = "Later on this channel"; tag = "guide-later" }
        listOf(earlier, nowButton, later).forEach { control ->
            control.setOnKeyListener { _, key, event -> when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) focusChannel(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { ++focusEpoch; if (localOnly) localTab.requestFocus() else allChannelsTab.requestFocus() }; true }
                else -> false
            } }
            timeline.addView(control)
        }
        content.addView(timeline, LinearLayout.LayoutParams(-1, dp(38)))
        times = TimeRuler(this) { guideTimeline.start((focused ?: current)?.id) }; content.addView(times, LinearLayout.LayoutParams(-1, dp(24)))
        emptyInfo = text("", 13f).apply { setPadding(dp(12), dp(24), dp(12), dp(24)); visibility = View.GONE }
        content.addView(emptyInfo)
        list = RecyclerView(this).apply { layoutManager = TvFocus.Layout(this@MainActivity); preserveFocusAfterLayout = false; itemAnimator = null; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; tag = "channel-guide" }
        adapter = GuideAdapter(this, logos, { source }, { guide }, { favorites }, { guideTimeline.start(it.id) },
            { guideSearch.hideKeyboard(); if (::multiview.isInitialized && multiview.browsing) startMultiview(it) else { if (it.id != current?.id || !hasPlayedChannel || player.playerError != null) tune(it); showGuide(false) } }, { channel ->
                toggleFavorite(channel)
            }, { channel -> focused = channel; showInfo(channel); updateTimes(); scheduleEpg() }, { channel, direction -> moveTime(channel, direction) }, { focusSidebar() }, { id, direction ->
                val index = adapter.channels.indexOfFirst { it.id == id }
                if (index == 0 && direction < 0 && guideTimeline.ahead(id)) { ++focusEpoch; nowButton.requestFocus() }
                else if (index >= 0 && adapter.channels.isNotEmpty()) focusChannelAt(Math.floorMod(index + direction, adapter.channels.size))
            })
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleEpg() }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        sportsPanel = SportsPanel(this, ::button, ::sportsTeams, { team ->
            val previousDate = SportsSchedule.selectedDate(sportsTeam); sportsTeam = team; store.sportsTeam = team.orEmpty(); if (team in SportsCatalog.byId || SportsSchedule.selectedDate(team) != previousDate) checkLiveSports(force = true); sportsPanel.loading("Checking live games…"); scheduleSports()
        }, ::selectGame, ::showGameInfo, ::openSportsQuality, { focusSidebar() })
        sportsPanel.chooseDate = ::openSportsCalendar
        sportsPanel.watchChannel = { channel ->
            if (::multiview.isInitialized && multiview.browsing) startMultiview(channel) else {
                val game = sportsResults?.takeIf { result -> result.channels.any { it.id == channel.id && it.url == channel.url } }
                clearSportsPlayback()
                if (channel.id != current?.id || !hasPlayedChannel || player.playbackState != Player.STATE_READY || player.playerError != null) tune(channel)
                watchingSports = game; showGuide(false)
            }
        }
        sportsPanel.secondChannel = ::startMultiview
        sportsPanel.hasReminder = reminders::has
        sportsPanel.toggleReminder = { game ->
            runCatching { reminders.toggle(game) }.onSuccess { enabled -> saveGameReminders(); reminderCheckedAt = 0; toast(if (enabled) "Reminder set · ${GuideClock.sportsDate(game.start)}" else "Reminder removed") }.onFailure { toast("Up to 64 upcoming game reminders can be saved") }
        }
        sportsPanel.watchBest = { selectGame(it, autoWatch = true) }
        sportsPanel.playOnSelection = { store.sportsAutoPlay && !(::multiview.isInitialized && multiview.browsing) }
        sportsPanel.findRedZone = {
            sportsResults = null
            val snapshot = guide; val sourceEpoch = generation; val quality = videoInfo.toMap(); val profile = sportsStreamFilters()
            sportsMatchTask?.cancel(true); val epoch = ++sportsMatchEpoch
            sportsPanel.matchingRedZone()
            sportsMatchTask = sportsMatchWorker.submit {
                val now = System.currentTimeMillis()
                val channels = SportsDestinations.redZone(snapshot.channels, profile, quality) { !unavailable.contains(it.id, now) }
                handler.post { if (!closed && started && sourceEpoch == generation && epoch == sportsMatchEpoch && sportsVisible) {
                    sportsChannels = channels.take(4)
                    sportsPanel.showRedZoneStreams(channels) { it.name + " · " + (it.quality(videoInfo[it.id])?.label() ?: "Quality unverified") }
                } }
            }
        }
        sportsPanel.setSpoilers(store.hideCompletedScores)
        sportsPanel.watchReplay = ::playSportsReplay
        sportsPanel.presentationChanged = { if (guideVisible && sportsVisible) layoutGuideSurface() }
        content.addView(sportsPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        remoteHint = text("← Earlier / categories    → Later on this channel    Back Live now    Left categories, then Left main controls    Hold OK Favorite", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) }
        content.addView(remoteHint)
        previewCaption = text("Select a channel", 10f).apply { setBackgroundColor(0xCC0B1018.toInt()); setPadding(dp(6), dp(2), dp(6), dp(2)); tag = "preview-caption"; isFocusableInTouchMode = true; isClickable = true; setOnClickListener { if (current != null) showGuide(false) }; setOnFocusChangeListener { _, focused -> setTextColor(if (focused) GuideTheme.focusText else Color.WHITE); setBackgroundColor(if (focused) GuideTheme.current.accent else 0xCC0B1018.toInt()) }; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(previewCaption, FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178); marginEnd = dp(18) })
        nowPlaying = NowPlayingBanner(this)
        root.addView(nowPlaying, FrameLayout.LayoutParams(dp(480), -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(24); bottomMargin = dp(24) })
        favoriteFeedback = text("", 12f).apply { tag = "favorite-feedback"; visibility = View.GONE; background = GuideTheme.glass(resources.displayMetrics.density); setPadding(dp(16), dp(10), dp(16), dp(10)); elevation = dp(10).toFloat(); clipToOutline = true }
        root.addView(favoriteFeedback, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        reminderBanner = GameReminderBanner(this, ::button, ::watchGameReminder, ::dismissGameReminder)
        root.addView(reminderBanner, FrameLayout.LayoutParams(dp(420), -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(24); bottomMargin = dp(24) })
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
        retryActions.addView(button("Retry") { if (onDemand != null) playAttempt() else current?.let { tune(it) } })
        retryActions.addView(button("Other game streams") { showSportsAlternatives("Choose another stream for this game") }.apply { tag = "sports-other-streams"; visibility = View.GONE })
        retryActions.addView(button("Guide") { retryPanel.visibility = View.GONE; returnToLive() }); retryPanel.addView(retryActions)
        root.addView(retryPanel, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER))
        details = PlaybackDetails(this, ::button, { closeDetails(); showGuide(true) }, ::recallPrevious, {
            current?.let { channel -> toggleFavorite(channel); refreshDetails() }
        }, { tracks(C.TRACK_TYPE_TEXT) }, { current?.let { openLibrary(it) } }, { closeDetails(); current?.let(::startMultiview) }, { channel -> closeDetails(); tune(channel); showGuide(false) }, { channel -> closeDetails(); startMultiview(channel) }, { current?.let(::tune) }, ::showOtherGameStreams, ::watchVodFromBeginning)
        root.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        demandControls = OnDemandControls(this, ::button, { if (player.playbackState == Player.STATE_ENDED) { demandPosition = 0; demandPaused = false; playAttempt() } else { demandPaused = player.playWhenReady; player.playWhenReady = !demandPaused }; showDemandControls() },
            { position -> seekDemand(position, absolute = true) }, { seekDemand(0, absolute = true) }, { tracks(C.TRACK_TYPE_TEXT) }, { tracks(C.TRACK_TYPE_AUDIO) },
            { onDemand?.let { library.next(it) }?.let { playOnDemand(it, 0) } }, { reopenLibrary() }, ::returnToLive, ::stopDemandToLibrary)
        root.addView(demandControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        library = LibraryController(this, store, ::button, { source }, { guide }, ::playOnDemand, ::closeLibrary)
        library.panel.navigation = { ++focusEpoch; libraryButton.requestFocus() }
        library.catchupClosed = ::returnToLiveChannel
        library.panel.globalSearch = ::searchWithQuery
        library.catalogChanged = { if (details.isVisible) refreshDetails() }
        library.refreshCatalogs = { mode -> source?.let { playlistSearch.refresh(it, mode) } }
        guidePanel.removeView(guideSearch)
        searchPanel = PlaylistSearchPanel(this, guideSearch, ::button, ::openNavigation).apply { backgroundContent = guidePanel }
        root.addView(searchPanel, FrameLayout.LayoutParams(-1, -1))
        vodStartPanel = VodStartPanel(this, ::button, { closeVodStart() }, {
            closeVodStart(restore = false); openLibrary()
        }, { vodMode = "series"; requestVodStart() }, { requestVodStart() }).apply { backgroundContent = guidePanel }
        root.addView(vodStartPanel, FrameLayout.LayoutParams(-1, -1))
        playlistSearch = PlaylistSearch(store, library.repository) { items, busy, warning ->
            if (started && vodRequest != null && vodStartPanel.isVisible) renderVodStart(items, busy, warning)
            else if (started && searchPanel.isVisible) { searchedDemand = items; searchLoading = busy; searchWarning = warning; renderPlaylistSearch() }
        }
        library.archiveLoaded = { selected, channel, entries ->
            if (source == selected) {
                replayDiscovery?.remember(selected, generation, guide, mapOf(channel.id to entries))
                val key = guide.key(channel)
                // A provider archive belongs to one stream. Do not spread it to shared XMLTV variants.
                if (key == channel.id || guide.channels.count { guide.key(it) == key } == 1) {
                    guide = guide.copy(programmes = guide.programmes + (key to GuideHistory.merge(guide.programmes[key].orEmpty(), entries, channel.catchupDays, System.currentTimeMillis())))
                    handler.removeCallbacks(cacheEpg); handler.postDelayed(cacheEpg, 1500)
                    adapter.refreshRows(); scheduleSports()
                }
            }
        }
        library.panel.setPadding(dp(104), dp(18), dp(24), dp(18))
        root.addView(library.panel, FrameLayout.LayoutParams(-1, -1))
        root.addView(navigationRail, FrameLayout.LayoutParams(dp(76), -1, Gravity.START).apply { marginStart = dp(14); topMargin = dp(18); bottomMargin = dp(14) })
        dockHint = View(this).apply {
            tag = "navigation-handle"; contentDescription = "Left for navigation"; isClickable = true
            background = GradientDrawable().apply { setColor(0x80FFFFFF.toInt()); cornerRadius = dp(3).toFloat() }
            setOnClickListener { openNavigation() }
        }
        root.addView(dockHint, FrameLayout.LayoutParams(dp(4), dp(34), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(5) })
        navigationRail.expansionChanged = ::updateDockHint
        applyDockPreference(); applyTheme(); updateTimes(); showGuide(true)
    }
    private fun openLibrary(channel: Channel? = null) {
        showGuide(false); nowPlaying.hide(immediate = true); demandControls.visibility = View.GONE
        root.bringChildToFront(library.panel)
        if (settingsPanel?.opened == true) settingsPanel?.visibility = View.GONE
        if (channel != null) library.openCatchup(channel, direct = true) else library.open()
        showNavigation("library")
    }
    private fun reopenLibrary() {
        saveDemandProgress(); player.pause(); demandPaused = true; demandControls.visibility = View.GONE; library.reopen(onDemand); root.bringChildToFront(library.panel); showNavigation("library")
    }
    private fun closeLibrary() {
        library.dismiss(); retryPanel.visibility = View.GONE
        // Browsing must never replace or reopen the stream that is already playing.
        if (player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE) {
            if (onDemand != null) player.playWhenReady = !demandPaused
            showGuide(false); if (onDemand != null) showDemandControls()
        } else showGuide(true)
    }
    private fun stopDemandToLibrary() {
        if (onDemand?.kind == "catchup") { returnToLiveChannel(); return }
        val item = onDemand ?: return
        saveDemandProgress(); cancelPlaybackCallbacks(); clearSportsPlayback()
        onDemand = null; current = null; hasPlayedChannel = false; firstFrame = false; demandSubtitleMode = null
        ++playbackToken; expectedMediaId = ""; playbackUrl = ""
        handler.removeCallbacks(demandTick); handler.removeCallbacks(hideDemandControls)
        player.stop(); player.clearMediaItems(); frameRates.clear()
        playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE; demandControls.visibility = View.GONE
        sportsVisible = false; applyFilter(); showGuide(false); nowPlaying.hide(immediate = true)
        library.reopen(item); root.bringChildToFront(library.panel); showNavigation("library")
    }
    private fun playOnDemand(item: OnDemand, position: Long) {
        voiceInput.stop(); if (multiview.active) multiview.close()
        clearSportsPlayback(); stopSportsPrefetch(); watchingSports = null
        sportsReplayGame = null
        val selected = source ?: return
        if (!Network.allowed(item.url, selected.allowHttp)) { toast("This title needs an allowed HTTP or HTTPS address"); return }
        handler.removeCallbacks(warmVodIndex); vodWarmScheduled = false; playlistSearch.pause(cancel = true)
        saveDemandProgress(); closeDetails(); cancelPlaybackCallbacks()
        onDemand = item; demandPosition = position; demandPaused = false; demandSubtitleMode = null
        hasPlayedChannel = false; firstFrame = false; lastVideoInfo = null; alternateUsed = false; reconnects = ReconnectBudget()
        playbackUrl = item.url; retryPanel.visibility = View.GONE
        player.volume = 1f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build(); applyCaptionPreference()
        showGuide(false); if (started) playAttempt(); showDemandControls()
        handler.removeCallbacks(demandTick); handler.postDelayed(demandTick, 1000)
    }
    private fun saveDemandProgress(ended: Boolean = false) {
        val item = onDemand ?: return; val selected = source ?: return
        if (!::player.isInitialized || player.currentMediaItem?.mediaId?.startsWith("${item.id}:") != true) return
        if (player.currentMediaItem?.mediaId == expectedMediaId && player.playbackState != Player.STATE_IDLE) demandPosition = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        if (ended || duration > 0 && demandPosition >= maxOf(10000L, duration - 30000L)) library.completed(item)
        if (item.kind in setOf("movie", "episode")) { val position = demandPosition; cacheWrites.execute { runCatching { store.saveWatchProgress(selected, item, position, duration, ended) } } }
    }
    private fun seekDemand(position: Long, absolute: Boolean = false) {
        if (onDemand == null || !player.isCurrentMediaItemSeekable) return
        val target = (if (absolute) position else player.currentPosition + position).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target); demandPosition = target; showDemandControls()
    }
    private fun refreshDemandControls() {
        val item = onDemand ?: return
        demandControls.update(item, player.currentPosition, player.duration, player.isPlaying, player.isCurrentMediaItemSeekable, library.next(item) != null, player.playbackState == Player.STATE_ENDED)
    }
    private fun showDemandControls() {
        if (onDemand == null || library.opened || settingsPanel?.opened == true) return
        val hadFocus = demandControls.hasFocus()
        refreshDemandControls(); demandControls.visibility = View.VISIBLE; root.bringChildToFront(demandControls); if (!hadFocus) demandControls.pause.requestFocus()
        handler.removeCallbacks(hideDemandControls)
        if (player.isPlaying) handler.postDelayed(hideDemandControls, 8000)
    }
    private fun returnToLive() {
        sportsReplayGame = null
        sportsVisible = false; applyFilter()
        library.dismiss(); retryPanel.visibility = View.GONE
        val channel = current ?: source?.let { selected -> guide.channels.firstOrNull { it.id == store.last(selected) } } ?: adapter.channels.firstOrNull()
        if (channel != null) tune(channel) else { saveDemandProgress(); onDemand = null; player.stop(); demandControls.visibility = View.GONE; handler.removeCallbacks(demandTick) }
        showGuide(true)
    }
    private fun returnToLiveChannel() {
        library.dismiss(); retryPanel.visibility = View.GONE
        if (onDemand != null) returnToLive()
        showGuide(false)
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
        if (::searchPanel.isInitialized) searchPanel.dismiss()
        if (::playlistSearch.isInitialized) playlistSearch.pause()
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
    private fun showNavigation(key: String) {
        navigationRail.contentHasFocus = key == "sports-mode" && guideVisible && sportsVisible
        navigationRail.visibility = View.VISIBLE; navigationRail.activate(key); root.bringChildToFront(navigationRail); navigationRail.focusChanged(navigationRail.hasFocus()); updateDockHint()
    }
    private fun openSection(sports: Boolean) {
        settingsPanel?.visibility = View.GONE
        if (library.opened) library.dismiss()
        if (onDemand != null) returnToLive()
        if (sports) { sportsTeam = "today"; sportsPanel.home() }
        switchMode(sports)
    }
    private fun enterNavigationContent() {
        // Right returns to the current screen. Only OK activates a highlighted destination.
        when {
            ::searchPanel.isInitialized && searchPanel.isVisible -> searchPanel.focusResults()
            settingsPanel?.opened == true -> settingsPanel?.focusNavigation()
            library.opened -> library.panel.focusToolbar()
            sportsVisible -> sportsPanel.focusHero()
            else -> focusChannel()
        }
    }
    private fun applyDockPreference() {
        val inset = if (store.hiddenDock) 24 else 104
        guidePanel.setPadding(dp(inset), dp(18), dp(18), dp(14))
        library.panel.setPadding(dp(inset), dp(18), dp(24), dp(18))
        settingsPanel?.setPadding(dp(if (store.hiddenDock) 28 else 104), dp(24), dp(24), dp(24))
        navigationRail.configure(store.hiddenDock); updateDockHint()
    }
    private fun updateDockHint() {
        if (!::dockHint.isInitialized) return
        val inset = if (!store.hiddenDock || navigationRail.expanded || navigationRail.moving) 104 else 24
        guidePanel.setPadding(dp(inset), dp(18), dp(18), dp(14))
        if (::library.isInitialized) library.panel.setPadding(dp(inset), dp(18), dp(24), dp(18))
        dockHint.isVisible = navigationRail.isVisible && store.hiddenDock && !navigationRail.expanded && !navigationRail.moving
        if (dockHint.isVisible) root.bringChildToFront(dockHint)
    }
    private fun openNavigation() { ++focusEpoch; pendingFocusList = null; root.bringChildToFront(navigationRail); navigationRail.focusCurrent() }
    private fun focusSidebar(remember: Boolean = true) {
        if (sportsVisible) { ++focusEpoch; navigationRail.focusCurrent(); return }
        focusCategoryAt(if (remember) sidebarAdapter.rememberedPosition() else sidebarAdapter.activePosition())
    }
    private fun focusCategoryAt(position: Int) {
        if (position !in 0 until sidebarAdapter.itemCount) return
        focusListRow(sidebar, position)
    }
    private fun focusListRow(target: RecyclerView, position: Int) {
        if ((::searchPanel.isInitialized && searchPanel.isVisible) || (::vodStartPanel.isInitialized && vodStartPanel.isVisible)) return
        if (pendingFocusList === target && pendingFocusEpoch == focusEpoch) return
        val row = target.findViewHolderForAdapterPosition(position)?.itemView
        if (row?.hasFocus() == true) return
        val epoch = ++focusEpoch
        if (row != null && !target.isComputingLayout) { row.requestFocus(); TvFocus.center(target, row); return }
        pendingFocusList = target; pendingFocusEpoch = epoch
        // Keep focus inside the list while an offscreen/wrapped row is attached.
        // Otherwise Android can move it to a toolbar button as the old row is recycled.
        target.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        target.isFocusableInTouchMode = true; target.requestFocus()
        target.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        target.stopScroll(); (target.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, target.height / 2 - dp(if (target === sidebar) 19 else 16))
        fun request(remaining: Int) {
            if (epoch != focusEpoch || !guideVisible || (settingsPanel?.isShown == true) || searchPanel.isVisible || vodStartPanel.isVisible) return
            val item = target.findViewHolderForAdapterPosition(position)?.itemView
            if (item != null && !target.isComputingLayout) { pendingFocusList = null; item.requestFocus(); TvFocus.center(target, item) }
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
        clearGuideSearch()
        if (::multiview.isInitialized) multiview.close()
        if (::library.isInitialized) library.dismiss()
        saveDemandProgress(); onDemand = null; demandSubtitleMode = null; handler.removeCallbacks(demandTick); if (::demandControls.isInitialized) demandControls.visibility = View.GONE
        flushVideoReadings(); cacheEpg.run(); cachedStartup = false; deferredRefresh = null; refreshRetryAt = 0L
        stopSportsPrefetch(clear = true); clearSportsPlayback()
        val epoch = ++generation; guideTimeline.clear()
        sportsVisible = false; sportsChannels = emptyList(); selectedGame = null; sportsResults = null; watchingSports = null; replayDiscovery?.pause(); replayGames = emptyList(); sportsBackoffUntil = 0; sportsFailures = 0; sportsForcedEpg.clear()
        ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); handler.removeCallbacks(renderSports); sportsRenderPending = false
        if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; handler.removeCallbacks(filterSearch); guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE; guideSearch.setQuery(""); closeDetails()
        epgWanted = emptyList(); epgCheckedAt.clear(); epgDepth.clear(); epgErrors.clear(); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg)
        importTask?.cancel(true)
        firstFrame = false; expectedMediaId = ""; playbackUrl = ""
        cancelPlaybackCallbacks(); playbackToken++; player.stop(); frameRates.clear(); current = null; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        source = selected; playlistSearch.prepare(selected); focused = null; store.active = selected.id; favorites = store.favorites(selected)
        filters = store.filters(selected); sportsFilters = store.sportsFilters(selected).copy(minHeight = 0); videoInfo = store.videoInfo(selected); unavailable = store.unavailable(selected); unavailable.prune(System.currentTimeMillis()); workingUrls.clear(); lastWorkingChannel = null
        group = null; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; importProgress(epoch, "Opening playlist"); showGuide(true)
        importTask = imports.submit {
            val saved = if (force) null else runCatching { store.cachedLineup(selected) }.getOrNull()
            if (saved != null && saved.channels.isNotEmpty()) {
                ui(epoch) { cachedStartup = true; loadingPanel.visibility = View.GONE; if (guide.channels.isEmpty()) accept(saved, resume = true); status.text = "Loading saved programme guide…" }
                val programmes = runCatching { store.cachedProgrammes(selected) }.getOrDefault(emptyMap())
                val savedReplays = runCatching { SportsReplayHistory.decode(store.read("sports-replays-${selected.id}.enc"), saved, System.currentTimeMillis()) }.getOrDefault(emptyMap())
                ui(epoch) {
                    savedReplays.forEach { (game, choices) -> replayCache["${selected.id}:$epoch:$game"] = System.currentTimeMillis() to choices }
                    mergeRefresh(PlaylistRefresh.Result(guide.copy(programmes = programmes)))
                    status.text = store.refreshError(selected).takeIf { it.isNotBlank() }?.let { "Using saved guide · $it" }.orEmpty()
                    autoRefresh(selected, true)
                }
            } else ui(epoch) {
                refreshCurrent(selected, true, false) {
                    if (selected.kind != "xtream" || selected.epgUrl.isNotBlank()) refreshCurrent(selected, false, true)
                }
            }
        }
    }
    private fun autoRefresh(selected: Source, launch: Boolean) {
        val policy = store.refreshPolicy(selected); val now = System.currentTimeMillis()
        val playlist = !store.archiveMetadataReady(selected) || policy.playlistDue(store.playlistRefreshed(selected), now, launch)
        val epg = policy.epgDue(store.epgRefreshed(selected), now, launch)
        if ((playlist || epg) && !PlaylistRefresh.running(selected.id)) refreshCurrent(selected, playlist, epg)
    }
    private fun refreshCurrent(selected: Source, playlist: Boolean = true, epg: Boolean = true, after: (() -> Unit)? = null) {
        val epoch = generation
        refreshBar.isVisible = true; ownRefreshes.add(selected.id)
        PlaylistRefresh.request(this, selected, playlist, epg) { result ->
            ownRefreshes.remove(selected.id)
            if (closed || epoch != generation || source != selected) return@request
            refreshBar.isVisible = false; loadingPanel.visibility = View.GONE
            if (!started) { deferredRefresh = result; if (result.error.isBlank()) after?.invoke(); return@request }
            if (result.guide != null) mergeRefresh(result, if (result.error.isBlank()) after else null)
            status.text = if (result.error.isBlank()) "" else "Using saved guide · ${result.error}"
            refreshRetryAt = System.currentTimeMillis() + if (result.error.isBlank()) 30000 else 15 * 60000
            if (result.guide == null && guide.channels.isEmpty()) notice("Could not load playlist", result.error.ifBlank { "Check playlist settings and Retry." })
        }
    }
    private fun mergeRefresh(result: PlaylistRefresh.Result, applied: (() -> Unit)? = null) {
        val next = result.guide ?: return
        val before = guide; val sourceEpoch = generation; val token = ++mergeEpoch
        imports.execute {
            val sameLineup = next.channels == before.channels && next.categoryOrder == before.categoryOrder
            val history = next.channels.filter { it.catchupDays > 0 }.groupBy { next.key(it) }.mapValues { (_, channels) -> channels.maxOf { it.catchupDays } }
            val now = System.currentTimeMillis()
            val programmes = (before.programmes.keys + next.programmes.keys).associateWith { key ->
                (before.programmes[key].orEmpty() + next.programmes[key].orEmpty()).associateBy { it.start }.values
                    .filter { it.end > now - maxOf(1, history[key] ?: 0) * 86400000L }.sortedBy { it.start }.takeLast(500)
            }
            handler.post {
                if (closed || sourceEpoch != generation || token != mergeEpoch) return@post
                val latest = programmes.toMutableMap()
                // Preserve short-EPG responses that arrived during the background merge.
                guide.programmes.forEach { (key, entries) -> if (entries !== before.programmes[key]) latest[key] = (latest[key].orEmpty() + entries).associateBy { it.start }.values.sortedBy { it.start }.takeLast(500) }
                val oldFocus = focused?.id; val focusInGuide = list.hasFocus()
                if (sameLineup) { guide = guide.copy(programmes = latest); adapter.refreshRows(); if (sportsVisible) scheduleSports() else (focused ?: current)?.let(::showInfo) }
                else {
                    if (group != null && next.channels.none { it.group == group }) group = null
                    guide = next.copy(programmes = latest); current = next.channels.firstOrNull { it.id == current?.id } ?: current
                    focused = next.channels.firstOrNull { it.id == oldFocus } ?: focused
                    updateGroups(); applyFilter()
                    if (focusInGuide) list.post { if (!closed && guideVisible && !sportsVisible && settingsPanel?.opened != true) focusChannel() }
                    if (current == null && started) accept(guide, resume = true)
                }
                scheduleEpg(); applied?.invoke()
            }
        }
    }

    private fun ui(epoch: Int, task: () -> Unit) { handler.post { if (!closed && epoch == generation) task() } }
    private fun accept(next: Guide, resume: Boolean) {
        guide = if (source?.kind == "xtream" && source?.epgUrl.isNullOrBlank()) Guide(next.channels, next.programmes + guide.programmes, next.categoryOrder) else next; updateGroups(); applyFilter()
        if (resume && current == null) {
            val lastId = source?.let(store::last)
            val last = adapter.channels.firstOrNull { it.id == lastId } ?: adapter.channels.firstOrNull()
            if (last != null) { tune(last); showGuide(false) }
        } else current = next.channels.firstOrNull { it.id == current?.id } ?: current
        if (guideVisible && sportsVisible) scheduleSports() else (if (guideVisible) focused ?: current else current)?.let(::showInfo)
        scheduleEpg()
    }
    private fun tune(channel: Channel) {
        watchingSports = watchingSports?.takeIf { result -> result.channels.any { it.id == channel.id && it.url == channel.url } }
        sportsReplayGame = null
        stopSportsPrefetch(); ++sportsMatchEpoch; sportsMatchTask?.cancel(true); sportsMatchTask = null
        clearSportsPlayback()
        val selected = source ?: return
        if (!Network.allowed(channel.url, selected.allowHttp)) { showPlaybackFailure("This channel needs an allowed HTTP or HTTPS address. Check this source’s HTTP setting."); return }
        saveDemandProgress(); onDemand = null; demandSubtitleMode = null; demandControls.visibility = View.GONE; handler.removeCallbacks(demandTick); handler.removeCallbacks(hideDemandControls)
        closeDetails(); cancelPlaybackCallbacks(); playbackToken++
        hasPlayedChannel = false; playbackRebuffers = 0; playbackReconnects = 0; playbackLoadErrors = 0
        startupFailures = StartupFailures(); reconnects = ReconnectBudget(); firstFrame = false; alternateUsed = false; lastVideoInfo = null; retryPanel.visibility = View.GONE
        current = channel; previewCaption.text = "Full screen · ${channel.name}"; showInfo(channel)
        playbackUrl = if (selected.kind == "xtream") when (store.streamFormat(selected)) { "ts" -> StreamUrls.format(channel.url, "ts") ?: channel.url; "hls" -> StreamUrls.format(channel.url, "m3u8") ?: channel.url; else -> channel.url } else channel.url
        if (selected.kind == "xtream" && store.streamFormat(selected) == "auto") playbackUrl = workingUrls[channel.id] ?: playbackUrl
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        applyCaptionPreference()
        if (started) { saveDemandProgress(); playAttempt() }
        if (!guideVisible) showNowPlaying()
        scheduleEpg()
    }
    private fun playAttempt(phase: PlaybackPhase = PlaybackPhase.OPENING) {
        if (playbackUrl.isBlank() || pausedPlayback != null) return
        cancelPlaybackCallbacks()
        playbackToken++; expectedMediaId = "${onDemand?.id ?: current?.id}:$playbackToken"
        firstFrame = false; lastVideoInfo = null; measuredFrameRate = 0f; decoderFrameRate = 0f; scanEvidence = "unknown"; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); showPlaybackLoading(phase)
        val itemBuilder = MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId(expectedMediaId)
        if (onDemand?.kind in setOf("movie", "episode")) itemBuilder.setTag(PlaybackLoadControl.VOD)
        if (onDemand == null) itemBuilder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        val item = itemBuilder.build()
        val media = if (playbackUrl.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(providerFactory).setAllowChunklessPreparation(!captionsEnabled()).setLoadErrorHandlingPolicy(ProviderLoadErrors()).setExtractorFactory(CaptionHlsExtractors(tsFlags)).createMediaSource(item)
        } else mediaFactory.createMediaSource(item)
        if (onDemand == null) player.setMediaSource(media) else player.setMediaSource(media, demandPosition.coerceAtLeast(0))
        player.prepare(); player.playWhenReady = onDemand == null || !demandPaused
        handler.postDelayed(startupTimeout, if (onDemand != null) 30000 else 15000)
    }
    private fun tryAlternate(): Boolean {
        val selected = source ?: return false
        if (onDemand != null || hasPlayedChannel || selected.kind != "xtream" || store.streamFormat(selected) != "auto" || alternateUsed) return false
        val alternate = StreamUrls.alternate(playbackUrl) ?: return false
        alternateUsed = true; playbackUrl = alternate; playAttempt(PlaybackPhase.ALTERNATE)
        return true
    }
    private fun showPlaybackFailure(detail: String) {
        val plan = sportsPlaybackPlan
        if (plan != null && !plan.accepted && plan.channel?.id == current?.id) {
            if (playNextSportsCandidate("Stream failed · trying another match")) return
            cancelPlaybackCallbacks(); player.stop(); showSportsAlternatives("The automatic attempts could not open this game. Choose another stream. $detail"); return
        }
        pendingSportsTune = null; if (::sportsPanel.isInitialized) sportsPanel.finishTuning()
        cancelPlaybackCallbacks()
        saveDemandProgress(); player.stop(); playbackLoading.visibility = View.GONE; playbackError.text = detail; retryPanel.visibility = View.VISIBLE
        retryPanel.findViewWithTag<View>("sports-other-streams")?.isVisible = playbackGame() != null || plan != null
        if (!library.opened) (retryPanel.getChildAt(1) as ViewGroup).getChildAt(0).requestFocus()
    }
    private fun cancelPlaybackCallbacks() {
        handler.removeCallbacks(retry); handler.removeCallbacks(startupTimeout); handler.removeCallbacks(revealBuffering)
        handler.removeCallbacks(monitorPlayback); monitoringPlayback = false; playbackProgress.reset()
    }
    private fun ownsPlayback() = !closed && started && pausedPlayback == null && (current != null || onDemand != null) &&
        player.currentMediaItem?.mediaId == expectedMediaId
    private fun syncPlaybackMonitoring() {
        if (!::player.isInitialized) return
        handler.removeCallbacks(revealBuffering)
        val active = started && pausedPlayback == null && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (active && firstFrame && hasPlayedChannel && player.playbackState == Player.STATE_BUFFERING) {
            handler.postDelayed(revealBuffering, 750)
        } else if (player.playbackState == Player.STATE_READY && firstFrame) playbackLoading.visibility = View.GONE
        if (ownsPlayback()) {
            if (!monitoringPlayback) { monitoringPlayback = true; handler.post(monitorPlayback) }
        } else { handler.removeCallbacks(monitorPlayback); monitoringPlayback = false; playbackProgress.reset() }
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
        playbackReconnects++
        saveDemandProgress(); player.stop(); showPlaybackLoading(PlaybackPhase.RECONNECTING); handler.postDelayed(retry, 1500)
    }
    private fun saveUnavailable() { sportsStreamSummaries.clear(); source?.let { store.saveUnavailable(it, unavailable) } }
    private fun hideUnavailable() {
        val failed = current ?: return
        unavailable.hide(failed.id, System.currentTimeMillis()); saveUnavailable()
        if (sportsPlaybackPlan?.channel?.id == failed.id) {
            if (playNextSportsCandidate("Stream unavailable · trying another match")) return
            cancelPlaybackCallbacks(); player.stop(); showSportsAlternatives("Those game streams are unavailable. Choose another result."); return
        }
        cancelPlaybackCallbacks(); expectedMediaId = ""; firstFrame = false; player.stop(); playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        current = null; applyFilter(); showGuide(true)
        status.text = "${failed.name} is unavailable · hidden for 15 minutes. Retry in Settings."
        toast("Channel unavailable · hidden temporarily")
        lastWorkingChannel?.takeIf { it.id != failed.id && guide.channels.any { next -> next.id == it.id } && !unavailable.contains(it.id, System.currentTimeMillis()) }?.let { tune(it) }
    }
    private fun recordVideo(info: VideoInfo) {
        if (onDemand != null) { lastVideoInfo = info; return }
        if (source == null) return
        val channel = current ?: return
        val previous = videoInfo[channel.id]
        val observed = info.retainingKnownRate(previous)
        lastVideoInfo = observed
        if (previous == observed) return
        videoInfo[channel.id] = observed
        // Coverage is independent of measured quality; cached matches are ranked using fresh readings.
        if (SportsStreams.preferenceRank(channel, previous, sportsFilters) != SportsStreams.preferenceRank(channel, observed, sportsFilters) || FrameRateFilter.bucket(previous?.fps ?: 0f) != FrameRateFilter.bucket(observed.fps) || channel.quality(previous)?.height != channel.quality(observed)?.height) {
            sportsStreamSummaries.clear()
            if (guideVisible && sportsVisible && !sportsPanel.watching) queueSportsPrefetch()
        }
        if (::sportsPanel.isInitialized) sportsPanel.refreshStreamLabels()
        if (!videoSavePending) { videoSavePending = true; handler.postDelayed(saveVideoReadings, 5000) }
        adapter.refreshRows()
        if (!guideVisible || focused?.id == channel.id) showInfo(channel); if (details.isVisible) refreshDetails()
    }
    private fun scheduleEpg() { handler.removeCallbacks(fetchVisibleEpg); handler.postDelayed(fetchVisibleEpg, 250) }
    private fun collectVisibleEpg() {
        val selected = source ?: return
        if (selected.kind != "xtream" || selected.epgUrl.isNotBlank() || !started) return
        if (sportsVisible && guideVisible) {
            epgWanted = (listOfNotNull(current) + sportsChannels).distinctBy { guide.key(it) }.take(5)
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
                                val key = guide.key(channel)
                                val days = guide.channels.asSequence().filter { guide.key(it) == key }.maxOfOrNull { it.catchupDays } ?: 0
                                val programmes = LinkedHashMap(guide.programmes); programmes[key] = GuideHistory.merge(programmes[key].orEmpty(), entries, days, now)
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
        val quality = channel.quality(videoInfo[channel.id])?.let { it.label() + (if ((videoInfo[channel.id]?.fps ?: 0f) > 0) "" else " · Provider reported") } ?: "Quality unverified"
        val enriched = programme?.let { metadata.get(it) }
        val art = programme?.artwork.orEmpty().ifBlank { enriched?.backdrop.orEmpty().ifBlank { enriched?.poster.orEmpty() } }.ifBlank { channel.logo }
        headerArtwork.scaleType = if (art == channel.logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        programmeArtwork.show(if (guideVisible && !sportsVisible) art else "", source?.allowHttp == true)
        programmeInfo.text = channel.name + "  ·  " + quality + (programme?.let { "  ·  ${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}\n${enriched?.description?.ifBlank { it.description } ?: it.description}" } ?: "\n${epgErrors[channel.id] ?: if (epgInFlight.any { it == "$generation:${channel.id}" }) "Loading programme guide…" else "No programme information"}")
    }
    private fun applyFilter() {
        requestLocalMatches()
        val now = System.currentTimeMillis()
        val hidden = guide.channels.count { unavailable.contains(it.id, now) }
        val searchTerm = ChannelSearch.key(query)
        val filtered = guide.channels.filter { channel ->
            val eligible = !channel.media && !unavailable.contains(channel.id, now) && (!localOnly || channel.id in localIds) && (!favoritesOnly || channel.id in favorites) && (group == null || channel.group == group) &&
                (searchTerm.isEmpty() || channel.searchKey.contains(searchTerm))
            eligible && (filters.groups.isEmpty() || channel.group in filters.groups)
        }
        adapter.submit(filtered)
        if (guideVisible) {
            if (focused != null && filtered.none { it.id == focused?.id }) focused = filtered.firstOrNull()
            (focused ?: current)?.let(::showInfo)
            scheduleEpg()
        }
        searchButton.contentDescription = if (query.isEmpty()) "Search" else "Search · filter active"
        val selection = if (localOnly) "Local · ${store.localLocation}" else if (favoritesOnly) "Favorites" else group
        val profileLabel = filters.summary().takeUnless { it == "All channels" && selection != null }
        filterInfo.text = "${filtered.size} channels · " + listOfNotNull(selection, profileLabel, query.takeIf { it.isNotEmpty() }?.let { "Search: ${it.take(40)}" }, if (hidden > 0) "$hidden unavailable hidden" else null).joinToString(" · ")
        emptyInfo.visibility = if (filtered.isEmpty() && guide.channels.isNotEmpty()) View.VISIBLE else View.GONE
        emptyInfo.text = if (hidden > 0) "Unavailable channels are temporarily hidden. Retry or restore them in Settings → Temporarily hidden channels." else "No channels match. Try another category, clear Search, or choose Categories in Settings."
        if (localOnly) emptyInfo.text = if (localLoading) "Finding local channels…" else "No local stations matched. Check your city/state in Settings → Local channels and visible categories. Matches use your existing playlist."
        if (::guideTabs.isInitialized) { guideTabs.isVisible = !sportsVisible; allChannelsTab.isSelected = !localOnly; localTab.isSelected = localOnly; localProgress.isVisible = localOnly && localLoading }
        if (localOnly && localLoading) filterInfo.text = "Finding local channels… · ${store.localLocation}"
        updateGroups()
        if (::sportsPanel.isInitialized) {
            if (guideVisible) navigationRail.activate(if (guideSearch.isVisible) "search" else if (sportsVisible) "sports-mode" else "guide-mode")
            categoryColumn.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            sidebar.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            categoryHeading.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            sportsPanel.visibility = if (sportsVisible) View.VISIBLE else View.GONE
            timeline.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            times.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            list.visibility = if (sportsVisible) View.GONE else View.VISIBLE
            remoteHint.text = if (sportsVisible) "← Main navigation    OK Select game    Hold Back Full screen"
                else "← Earlier / categories    → Later on this channel    Back Live now    Left categories, then Left main controls    Hold OK Favorite"
            if (sportsVisible) { emptyInfo.visibility = View.GONE; scheduleSports() }
        }
    }
    private fun updateGroups() {
        if (groupsForChannels !== guide.channels || groupsForOrder !== guide.categoryOrder) {
            val provider = CategoryOrder.provider(guide.channels.filter { !it.media }.map { it.group }, guide.categoryOrder)
            providerGroups = if (source?.let(store::usaFirst) == true) CategoryOrder.usaFirst(provider) else provider
            groupsForChannels = guide.channels; groupsForOrder = guide.categoryOrder
        }
        val visibleGroups = providerGroups.filter { filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection("All channels", "all", !sportsVisible && !localOnly && !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", !sportsVisible && favoritesOnly)) + visibleGroups.map { GuideSection(if (it in setOf("Sports", "Favorites", "All channels")) "Category · $it" else it.ifBlank { "Uncategorized" }, "group:$it", !sportsVisible && !favoritesOnly && group == it && (group != null)) })
    }
    private fun requestLocalMatches() {
        val lineup = guide.channels; val location = store.localLocation; val selected = source
        if (localLineup === lineup && localQuery == location && localSourceId == selected?.id.orEmpty()) return
        localTask?.cancel(true); localLineup = lineup; localQuery = location; localSourceId = selected?.id.orEmpty(); localIds = emptySet()
        localLoading = location.isNotBlank() && lineup.isNotEmpty() && selected != null
        localEditor?.updateMatches(0, localLoading)
        if (!localLoading || selected == null) return
        localTask = localWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val matches = runCatching {
                val fingerprint = LocalMatchCache.fingerprint(lineup, location)
                val file = "local-matches-${selected.id}.enc"
                LocalMatchCache.decode(runCatching { store.read(file) }.getOrNull(), fingerprint) ?: localStations.find(lineup, location).also {
                    if (!Thread.currentThread().isInterrupted) runCatching { store.write(file, LocalMatchCache.encode(fingerprint, it)) }
                }
            }.getOrDefault(emptySet())
            if (Thread.currentThread().isInterrupted) return@submit
            handler.post { if (!closed && source?.id == selected.id && localLineup === lineup && localQuery == location) {
                localIds = matches; localLoading = false; localEditor?.updateMatches(matches.size, false)
                if (localOnly) { applyFilter(); if (localTab.hasFocus() && adapter.itemCount > 0) focusChannelAt(0) }
            } }
        }
    }
    private fun updateTimes() { times.invalidate(); nowButton.isSelected = guideTimeline.ahead((focused ?: current)?.id) }
    private fun showGuide(show: Boolean) {
        closeDetails()
        if (!show && ::searchPanel.isInitialized && searchPanel.isVisible) { searchPanel.dismiss(); playlistSearch.pause() }
        if (!show) { guideSearch.hideKeyboard(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false }
        if (!show || !sportsVisible) stopSportsPrefetch()
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show && sportsVisible) sportsPanel.resumeImages() else sportsPanel.pauseImages()
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        layoutGuideSurface()
        if (show) { showNavigation(if (guideSearch.isVisible) "search" else if (sportsVisible) "sports-mode" else "guide-mode"); focusChannel(); if (sportsVisible) scheduleSports() }
        else { navigationRail.visibility = View.GONE; updateDockHint(); ++focusEpoch; showNowPlaying() }
        scheduleEpg(); syncReplayDiscovery()
        if (show && sportsQualityChanged) {
            sportsQualityChanged = false
            if (sportsVisible && sportsPanel.watching) selectedGame?.takeUnless { it.state == "post" }?.let { selectGame(it) }
        }
    }
    private fun layoutGuideSurface() {
        val preview = guideVisible && store.smallPreview && !(sportsVisible && !sportsPanel.watching)
        val searchInset = if (guideSearch.isVisible) dp(60) else 0
        if (!::multiview.isInitialized || !multiview.active || guideVisible) video.layoutParams = if (preview) FrameLayout.LayoutParams(dp(320), dp(180), Gravity.TOP or Gravity.END).apply { topMargin = dp(18) + searchInset; marginEnd = dp(18) } else FrameLayout.LayoutParams(-1, -1)
        headerSurface.visibility = if (sportsVisible) View.GONE else View.VISIBLE
        sportsPanel.previewMode(preview && sportsVisible)
        headerInfo.setPadding(dp(12), dp(6), dp(if (preview) 348 else 12), dp(6))
        headerSurface.layoutParams = LinearLayout.LayoutParams(-1, dp(if (preview) 188 else 104))
        previewCaption.layoutParams = FrameLayout.LayoutParams(dp(320), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(178) + searchInset; marginEnd = dp(18) }
        if (preview) root.bringChildToFront(video) else root.bringChildToFront(guidePanel)
        listOf(previewCaption, nowPlaying, loadingPanel, playbackLoading, retryPanel, favoriteFeedback).forEach(root::bringChildToFront)
        previewCaption.visibility = if (preview) View.VISIBLE else View.GONE
        previewCaption.text = if (current == null) "Select a channel" else "Full screen · ${current?.name}"
        positionPlaybackLoading()
    }
    private fun showNowPlaying() {
        if (onDemand != null) return
        val channel = current ?: return
        handler.removeCallbacks(hideNowPlaying)
        nowPlaying.update(channel, guide.at(channel, System.currentTimeMillis()), videoInfo[channel.id]); nowPlaying.reveal()
        handler.postDelayed(hideNowPlaying, 2800)
    }
    private fun search() = searchWithQuery(query)
    private fun searchWithQuery(initial: String) { if (vodRequest != null) closeVodStart(restore = false); openPlaylistSearch(initial, true) }
    private fun openPlaylistSearch(initial: String, useVoice: Boolean) {
        settingsPanel?.visibility = View.GONE
        if (::library.isInitialized && library.opened) library.dismiss()
        if (onDemand != null) returnToLive()
        sportsVisible = false
        showGuide(true); group = null; favoritesOnly = false; localOnly = false; applyFilter()
        query = initial.take(128); guideSearch.setQuery(query); guideSearch.visibility = View.VISIBLE; showGuide(true)
        searchedDemand = emptyList(); searchLoading = false; searchWarning = ""
        handler.removeCallbacks(filterSearch); ++focusEpoch; pendingFocusList = null
        searchPanel.show(query); root.bringChildToFront(searchPanel); updatePlaylistSearch(); guideSearch.voice.requestFocus()
        if (useVoice) voiceSearch() else guideSearch.message("Finding this programme in your VOD library…")
    }
    private fun watchVodFromBeginning() {
        val programme = current?.let { guide.at(it, System.currentTimeMillis()) } ?: return
        val request = VodStart.request(programme) ?: return
        vodReturnGuide = guideVisible
        clearGuideSearch(); closeDetails(); showGuide(false); ++focusEpoch; pendingFocusList = null
        vodRequest = request; vodMode = if (request.episode == null) "movie" else "series"
        val matches = source?.let { library.repository.matches(it, request) }.orEmpty()
        if (matches.isEmpty()) { vodRequest = null; toast("No matching VOD title is saved for this programme yet"); return }
        vodStartPanel.open(request); root.bringChildToFront(vodStartPanel); renderVodStart(matches, false, "")
    }
    private fun requestVodStart() {
        val request = vodRequest ?: return; val selected = source ?: return
        renderVodStart(library.repository.matches(selected, request), false, "")
    }
    private fun renderVodStart(items: List<OnDemand>, busy: Boolean, warning: String) {
        val request = vodRequest ?: return
        val found = VodStart.best(request, items)
        if (found != null) { playVodStart(found); return }
        vodStartPanel.results(VodStart.choices(request, items), busy, warning, vodMode, ::playVodStart)
    }
    private fun closeVodStart(restore: Boolean = true) {
        vodRequest = null; vodStartPanel.dismiss(); playlistSearch.pause()
        if (restore) showGuide(vodReturnGuide)
    }
    private fun playVodStart(item: OnDemand) {
        val request = vodRequest ?: return
        closeVodStart(restore = false)
        if (item.kind == "movie") playOnDemand(item, 0L)
        else { showGuide(false); nowPlaying.hide(immediate = true); library.openSearchResult(item, restart = true, season = request.season, episode = request.episode); showNavigation("library") }
    }
    private fun updatePlaylistSearch() {
        if (!::searchPanel.isInitialized || !searchPanel.isVisible) return
        searchedDemand = emptyList(); searchLoading = source?.kind == "xtream" && query.isNotBlank(); searchWarning = ""
        renderPlaylistSearch()
        source?.let { playlistSearch.search(it, query, library.knownTitles(it)) }
    }
    private fun renderPlaylistSearch() {
        val term = ChannelSearch.key(query)
        val live = if (term.isBlank()) emptyList() else guide.channels.asSequence().filter { !it.media && it.searchKey.contains(term) }.take(40).map { channel ->
            PlaylistSearchRow("live:${channel.id}", channel.name, "Live TV · ${channel.group}", "live") {
                clearGuideSearch(); if (current?.id != channel.id || !hasPlayedChannel || player.playerError != null) tune(channel); showGuide(false)
            }
        }.toList()
        val media = searchedDemand + if (source?.kind == "m3u" && term.isNotBlank()) guide.channels.asSequence().filter { it.media && it.searchKey.contains(term) }.take(40).map { OnDemand("m3u:${it.id}", it.name, it.group, it.url) }.toList() else emptyList()
        val titles = media.map { item -> PlaylistSearchRow(item.id, item.title, when (item.kind) { "series" -> "Series"; "episode" -> "Episode · Season ${item.season} · Episode ${item.episode}"; else -> "Movie" }, item.kind) {
            clearGuideSearch(); showGuide(false); nowPlaying.hide(immediate = true); library.openSearchResult(item); showNavigation("library")
        } }
        searchPanel.submit(query, live + titles, searchLoading, searchWarning)
    }
    private fun finishSearch(close: Boolean) {
        voiceInput.stop(); voiceSearchPending = false
        handler.removeCallbacks(filterSearch); query = guideSearch.query(); applyFilter(); guideSearch.hideKeyboard()
        if (close) { searchPanel.dismiss(); playlistSearch.pause(); showGuide(true); focusChannel() }
        else { updatePlaylistSearch(); searchPanel.focusResults() }
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
    private fun saveGameReminders() {
        val bytes = reminders.json().toString().toByteArray()
        cacheWrites.execute { runCatching { store.write("game-reminders.enc", bytes) } }
    }
    private fun checkGameReminders() {
        if (!started || !::reminders.isInitialized || !::reminderBanner.isInitialized) return
        val now = System.currentTimeMillis()
        reminderPending?.let { (game, checked) ->
            if (!reminders.has(game) || now - checked > 120000L) reminderPending = null
            else if (reminderGame == null && !guideVisible && !library.opened && settingsPanel?.opened != true && sportsManager?.opened != true && sportsCalendar?.opened != true && !details.isVisible && player.isPlaying) {
                reminderGame = game; reminderReturnFocus = currentFocus
                reminders.delivered(game); saveGameReminders(); root.bringChildToFront(reminderBanner); reminderBanner.show(game)
                handler.removeCallbacks(hideReminder); handler.postDelayed(hideReminder, 30000)
                reminderPending = null
            }
        }
        if (reminderChecking || now - reminderCheckedAt in 0 until 30000L) return
        val stored = reminders.json().toString(); reminders.update(emptyList(), now)
        if (stored != reminders.json().toString()) saveGameReminders()
        val targets = reminders.targets(now); reminderCheckedAt = now
        if (targets.isEmpty()) return
        reminderChecking = true
        reminderWorker.execute {
            val events = runCatching { SportsSchedule.reminderEvents(targets) }.getOrDefault(emptyList())
            handler.post {
                reminderChecking = false
                if (!closed) {
                    val before = reminders.json().toString()
                    val ready = reminders.update(events, System.currentTimeMillis())
                    if (before != reminders.json().toString()) saveGameReminders()
                    if (reminderPending == null && reminderGame == null) ready.firstOrNull()?.let { reminderPending = it to System.currentTimeMillis() }
                }
            }
        }
    }
    private fun dismissGameReminder() {
        handler.removeCallbacks(hideReminder)
        if (::reminderBanner.isInitialized) reminderBanner.visibility = View.GONE
        reminderGame = null
        reminderReturnFocus?.takeIf { it.isShown }?.requestFocus(); reminderReturnFocus = null
    }
    private fun watchGameReminder() {
        val game = reminderGame ?: return
        val now = System.currentTimeMillis()
        val event = LiveEvent(game.eventId, game.teams.map { it.id }.toSet(), game.start, "in", game.networks, game.scores, game.logos, game.status, league = game.leagueId)
        liveSports = LiveSportsSnapshot((liveSports.events.filterNot { it.id == event.id && it.league == event.league } + event), now, liveSports.leagues + game.leagueId)
        selectedGame = game; sportsTeam = "today"; liveSportsAttempt = now
        dismissGameReminder(); multiview.close(); sportsVisible = true; clearGuideSearch(); showGuide(true)
        selectGame(game, autoWatch = true)
    }
    private fun checkLiveSports(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (liveSportsChecking) { if (force) liveSportsAgain = true; return }
        if (!force && now - liveSportsAttempt < 60000) return
        liveSportsChecking = true; liveSportsAttempt = now; val requestedDay = SportsSchedule.selectedDate(sportsTeam)
        liveSportsTask = liveSportsWorker.submit {
            val result = runCatching { liveSportsLoader() }.getOrDefault(LiveSportsSnapshot.EMPTY)
            handler.post { if (!closed) {
                liveSportsChecking = false; if (SportsSchedule.selectedDate(sportsTeam) == requestedDay && (result.events.isNotEmpty() || result.leagues.isNotEmpty())) liveSports = result
                if (liveSportsAgain) { liveSportsAgain = false; checkLiveSports(force = true) }
                scheduleSports()
            } }
        }
    }
    private fun scheduleSports() {
        if (!sportsVisible || !guideVisible || closed) return
        if (!sportsRenderPending) { sportsRenderPending = true; handler.postDelayed(renderSports, 120) }
    }
    private fun refreshSports() {
        if (!sportsVisible || !guideVisible || !started || closed) return
        if (sportsComputing) { sportsDirty = true; return }
        checkLiveSports(); handler.removeCallbacks(pollLiveSports); handler.postDelayed(pollLiveSports, 60000)
        sportsComputing = true
        val liveSnapshot = liveSports; val checkingSnapshot = liveSportsChecking
        val epoch = ++sportsEpoch; val sourceEpoch = generation; val snapshot = guide
        val followed = store.followedTeams; val selectedTeam = sportsTeam; val favoriteSnapshot = favorites; val qualitySnapshot = videoInfo.toMap()
        val replaySnapshot = replayCache.toMap(); val replayChecked = replayDiscovery?.checkedKeys.orEmpty(); val selectedSourceId = source?.id
        val now = System.currentTimeMillis()
        sportsTask = sportsWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val archiveDays = snapshot.channels.maxOfOrNull { if (Catchup.supported(it)) it.catchupDays else 0 } ?: 0
            val teamGames = followed.flatMap { team -> SportsSchedule.games(liveSnapshot, snapshot, team, favoriteSnapshot, now, matchChannels = false) }
            val rawGames = if (selectedTeam == "followed") teamGames.distinctBy { it.key } else {
                val scheduled = SportsSchedule.games(liveSnapshot, snapshot, selectedTeam, favoriteSnapshot, now, matchChannels = false)
                (scheduled + teamGames.filter { selectedTeam == null || selectedTeam == "league:${it.leagueId}" }).distinctBy { it.key }
            }
            val games = SportsPanel.ordered(rawGames.filter { it.leagueId in store.sportsLeagues && (selectedTeam == "live" || !store.sportsFollowedOnly || it.teams.any { team -> team.id in followed }) }, followed, now)
            val archiveGuide = snapshot.copy(channels = Catchup.channels(snapshot))
            val archiveChannels = archiveGuide.channels.associateBy { it.id }
            val directReplays = games.filter { SportsReplays.eligible(it, archiveDays, now) }.take(256).mapNotNull { game ->
                val cached = SportsReplays.retained(replaySnapshot["$selectedSourceId:$sourceEpoch:${game.key}"]?.second.orEmpty(), archiveChannels, now)
                SportsReplays.best(cached.ifEmpty { if (game.key in replayChecked) emptyList() else SportsReplays.find(game, archiveGuide, now) }, quality = qualitySnapshot, favorites = favoriteSnapshot)?.let { game.key to it }
            }.toMap()
            handler.post {
                if (epoch == sportsEpoch) sportsComputing = false
                if (closed || sourceEpoch != generation || epoch != sportsEpoch || !sportsVisible) return@post
                if (liveSnapshot !== liveSports) { sportsDirty = false; scheduleSports(); return@post }
                selectedGame = games.firstOrNull { it.key == selectedGame?.key } ?: games.firstOrNull()
                val scanning = checkingSnapshot
                val progress = when {
                    scanning -> "Updating sports…"
                    !liveSports.fresh(now) || liveSports.leagues.isEmpty() -> "Schedule unavailable · Saved games are shown · Retrying automatically"
                    else -> "${if (selectedTeam == "today") "Today · Your leagues" else if (selectedTeam == "live") "Live now · Your leagues" else if (selectedTeam == null) "All games" else "Team / league scoreboard"} · ${games.size} ${if (games.size == 1) "game" else "games"} · ESPN schedule"
                }
                sportsPanel.archiveWindowDays = archiveDays
                sportsPanel.updateCardReplays(directReplays)
                sportsPanel.setSpoilers(store.hideCompletedScores); sportsPanel.setLeagues(store.sportsLeagues); sportsPanel.submit(games, followed, selectedTeam, progress, scanning)
                selectedGame?.let(::showGameInfo) ?: run {
                    channelInfo.text = "Sports"; programmeInfo.text = "Today shows games across every supported league, whether or not you follow the teams. Select a poster to find playlist coverage. Live, upcoming and final status come from the scoreboard."
                }
                queueSportsPrefetch(games)
                replayGames = games.filter { SportsReplays.eligible(it, archiveDays, now) }; syncReplayDiscovery()
                scheduleEpg()
                if (sportsDirty) { sportsDirty = false; scheduleSports() }
            }
        }
    }
    private fun openSportsCalendar() {
        showGuide(false); nowPlaying.hide(immediate = true)
        val panel = sportsCalendar ?: SportsCalendarPanel(this, ::button, { day ->
            sportsTeam = "date:$day"; store.sportsTeam = sportsTeam.orEmpty(); sportsPanel.home()
            closeSportsCalendar(); sportsPanel.loading("Loading games for $day…"); checkLiveSports(force = true); scheduleSports()
        }, ::previewCalendarDate, ::selectCalendarGame, ::closeSportsCalendar).also { sportsCalendar = it; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        if (calendarSchedule == null) calendarSchedule = SportsCalendarLoader(store, { day, leagues -> calendarSportsLoader(day, leagues) }) { day, snapshot, loading, failed ->
            if (started && sportsCalendar?.opened == true && sportsCalendar?.activeDate == day) {
                calendarSnapshot = snapshot
                sportsCalendar?.submit(day, calendarGames(snapshot, day), loading, failed, store.hideCompletedScores)
            }
        }
        root.bringChildToFront(panel); panel.open(SportsSchedule.selectedDate(sportsTeam) ?: java.time.LocalDate.now())
    }
    private fun calendarGames(snapshot: LiveSportsSnapshot, day: java.time.LocalDate) = SportsSchedule.games(snapshot, Guide.EMPTY, "date:$day", emptySet(), System.currentTimeMillis(), matchChannels = false)
        .filter { it.leagueId in store.sportsLeagues && (!store.sportsFollowedOnly || it.teams.any { team -> team.id in store.followedTeams }) }
    private fun previewCalendarDate(day: java.time.LocalDate, force: Boolean) { calendarSchedule?.request(day, store.sportsLeagues, liveSports, force) }
    private fun selectCalendarGame(day: java.time.LocalDate, game: SportsGame) {
        sportsTeam = "date:$day"; store.sportsTeam = sportsTeam.orEmpty()
        ++sportsEpoch; sportsTask?.cancel(true); sportsComputing = false; sportsDirty = false
        handler.removeCallbacks(renderSports); sportsRenderPending = false
        liveSports = calendarSnapshot
        sportsPanel.submit(calendarGames(calendarSnapshot, day), store.followedTeams, sportsTeam, "Games · ${day}", false)
        closeSportsCalendar(); showGameInfo(game); sportsPanel.openGameActions(game)
    }
    private fun closeSportsCalendar() {
        calendarSchedule?.cancel(); sportsCalendar?.visibility = View.GONE
        showGuide(true); sportsPanel.focusCalendar()
    }
    private fun sportsTeams() {
        sportsManagerReturnGuide = guideVisible; sportsManagerReturnSettings = settingsPanel?.opened == true
        if (sportsManagerReturnSettings) settingsPanel?.visibility = View.GONE
        showGuide(false); nowPlaying.hide(immediate = true); closeDetails()
        val panel = sportsManager ?: SettingsPanel(this, ::renderSportsManager, ::closeSportsManager, "Sports").also {
            sportsManager = it; it.tag = "sports-manager"; it.leaveNavigation = ::closeSportsManager
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        root.bringChildToFront(panel); panel.open(listOf("Preferences", "Stream quality", "Leagues") + SportsLeagues.all.map { it.name })
    }
    private fun openSportsQuality() {
        if (sportsManager?.opened != true) sportsTeams()
        sportsManager?.selectSetting("Stream quality")
    }
    private fun renderSportsManager(page: String) {
        val panel = sportsManager ?: return
        if (page == "Stream quality") { renderSportsQuality(panel); return }
        if (page == "Preferences") {
            panel.page("Your sports", "Choose leagues and teams on the left. Enable automatic playback to watch a live game directly from its card. Upcoming and completed games keep their reminder and replay options.")
            panel.action("Play best stream on selection", "Play the highest-resolution matching stream. Known FPS breaks resolution ties. Failed starts can try another match, up to three attempts.", selected = store.sportsAutoPlay) { store.sportsAutoPlay = !store.sportsAutoPlay; panel.refresh("Play best stream on selection") }
            panel.action("Show only my teams", selected = store.sportsFollowedOnly) { store.sportsFollowedOnly = !store.sportsFollowedOnly; sportsPreferencesChanged = true; panel.refresh("Show only my teams") }
            panel.action("Hide completed-game scores", selected = store.hideCompletedScores) { store.hideCompletedScores = !store.hideCompletedScores; sportsPanel.setSpoilers(store.hideCompletedScores); panel.refresh("Hide completed-game scores") }
            panel.note("NFL RedZone appears whenever NFL is enabled. Its availability follows your playlist provider; it is not a scheduled matchup.")
            panel.action("Reset Sports settings", "Clear teams and reminders, enable all leagues and restore Sports defaults") { confirmSportsReset() }
            panel.note("Saved reminders")
            if (reminders.events.isEmpty()) panel.note("Choose an upcoming game to set a reminder.")
            reminders.events.forEach { event ->
                val name = event.teams.mapNotNull { SportsCatalog.byId[it]?.name }.joinToString(" vs ")
                panel.action("Remove · $name", GuideClock.sportsDate(event.start)) { reminders.remove("${event.league}:${event.id}"); saveGameReminders(); panel.refresh() }
            }
        } else if (page == "Leagues") {
            panel.page("Your leagues", "Choose the leagues shown in Sports. Changes save automatically.")
            panel.action("All leagues", selected = store.sportsLeagues == SportsLeagues.byId.keys) { store.sportsLeagues = SportsLeagues.byId.keys; sportsPreferencesChanged = true; panel.refresh("All leagues") }
            SportsLeagues.all.forEach { league -> panel.action(league.name, selected = league.id in store.sportsLeagues) {
                val chosen = store.sportsLeagues.toMutableSet(); if (!chosen.add(league.id)) chosen.remove(league.id)
                if (chosen.isEmpty()) toast("Keep at least one league selected") else {
                    store.sportsLeagues = chosen; sportsPreferencesChanged = true
                    if (sportsTeam?.startsWith("league:") == true && sportsTeam?.removePrefix("league:") !in chosen) sportsTeam = "today"
                    panel.refresh(league.name)
                }
            } }
        } else {
            val league = SportsLeagues.all.firstOrNull { it.name == page } ?: return
            val teams = SportsCatalog.teams.filter { league.id in it.competitions }.sortedBy { it.name }
            panel.page("${league.name} teams", "Choose your teams. Changes save automatically.")
            panel.choices(teams.map { it.name }, { teams[it].id in store.followedTeams }) { index ->
                val chosen = store.followedTeams.toMutableSet(); if (!chosen.add(teams[index].id)) chosen.remove(teams[index].id)
                store.followedTeams = chosen; sportsPreferencesChanged = true
            }
        }
    }
    private fun confirmSportsReset() {
        val panel = sportsManager ?: return
        panel.page("Reset Sports settings?", "This clears followed teams and saved reminders, enables every league, turns off My teams only and automatic playback, resets this playlist’s stream quality, and hides completed scores. Sports will open on Today.")
        panel.action("Cancel") { panel.refresh("Reset Sports settings") }
        panel.action("Reset Sports") {
            store.followedTeams = emptySet(); store.sportsLeagues = SportsLeagues.byId.keys; store.sportsFollowedOnly = false; store.sportsAutoPlay = false; store.hideCompletedScores = true; store.sportsTeam = "today"
            saveSportsFilters(GuideFilters())
            sportsTeam = "today"; reminders = GameReminders(); saveGameReminders(); reminderPending = null; dismissGameReminder()
            sportsPanel.setSpoilers(true); sportsPanel.home(); sportsPreferencesChanged = true
            panel.refresh("Reset Sports settings"); toast("Sports settings reset")
        }
        panel.focusOption("Cancel")
    }
    private fun closeSportsManager() {
        sportsManager?.visibility = View.GONE
        if (sportsPreferencesChanged) { sportsPreferencesChanged = false; liveSportsAttempt = 0; checkLiveSports(force = true); scheduleSports() }
        if (sportsManagerReturnSettings) { settingsPanel?.visibility = View.VISIBLE; settingsPanel?.let { root.bringChildToFront(it); it.selectSetting("Sports") } }
        else { showGuide(sportsManagerReturnGuide); if (sportsVisible && guideVisible) sportsPanel.teamsButton.requestFocus() }
    }
    private fun showGameInfo(game: SportsGame) {
        selectedGame = game; syncReplayDiscovery(); channelInfo.text = game.title
        programmeInfo.text = "${sportsPanel.scoreText(game)}\n${game.teams.first().league} · ${sportsPanel.statusText(game)}\n" +
            (game.networks.joinToString(" · ").ifBlank { "Broadcaster to be announced" }) + "\n" +
            (if (game.broadcasts.isNotEmpty() || game.titleMatches.isNotEmpty()) "${game.broadcasts.size + game.titleMatches.size} EPG/title matches · OK Choose channel" else if (game.candidates.isNotEmpty()) "${game.candidates.size} possible network channels · OK Review" else "Channel not matched yet")
        sportsChannels = emptyList()
        if (game.live(System.currentTimeMillis()) && sportsVisible && guideVisible && !sportsPanel.watching) { sportsWarmPriority = game; queueSportsPrefetch() }
    }
    private fun rememberSportsReplays(game: SportsGame, choices: List<SportsReplay>) {
        val selected = source ?: return
        if (choices.isEmpty()) return
        val key = "${selected.id}:$generation:${game.key}"
        if (replayCache[key]?.second == choices) return
        val now = System.currentTimeMillis()
        replayCache[key] = now to choices
        while (replayCache.size > 256) replayCache.remove(replayCache.keys.first())
        val prefix = "${selected.id}:$generation:"
        val saved = replayCache.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }.mapValues { it.value.second }
        cacheWrites.execute { runCatching {
            if (store.sources().any { it == selected }) store.write("sports-replays-${selected.id}.enc", SportsReplayHistory.encode(saved, now))
        } }
    }
    private fun findSportsReplay(game: SportsGame) {
        stopSportsPrefetch(); clearSportsPlayback()
        val selected = source ?: run { toast("Add a playlist to find replays"); return }
        pendingSportsTune = null; sportsMatchTask?.cancel(true)
        val snapshot = guide; val sourceEpoch = generation; val epoch = ++sportsMatchEpoch; val favoriteSnapshot = favorites; val quality = videoInfo.toMap()
        showGameInfo(game); sportsPanel.matching(game)
        val key = "${selected.id}:$sourceEpoch:${game.key}"
        val now = System.currentTimeMillis()
        val saved = SportsReplays.retained(replayCache[key]?.second.orEmpty(), snapshot.channels.associateBy { it.id }, now)
        if (saved.isNotEmpty()) { sportsPanel.showReplays(game, saved); return }
        val savedArchives = replayDiscovery?.cachedListings(selected, sourceEpoch).orEmpty()
        sportsMatchTask = sportsMatchWorker.submit {
            val listings = savedArchives.toMutableMap()
            var choices = SportsReplays.rank(SportsReplays.find(game, snapshot, System.currentTimeMillis()) + SportsReplays.findListings(game, snapshot, listings, System.currentTimeMillis()))
            if (choices.isEmpty() && selected.kind == "xtream" && !Thread.currentThread().isInterrupted) {
                val targets = SportsReplays.candidates(game, snapshot, favoriteSnapshot, System.currentTimeMillis(), limit = Importers.MAX_CHANNELS).filter { it.id !in listings }.take(8)
                val requests = targets.associateWith { channel -> sportsLookupWorkers.submit<List<Programme>> { Catchup.load(selected, channel, 1200) } }
                val deadline = System.nanoTime() + 4_000_000_000L
                try {
                    requests.entries.forEachIndexed { index, (channel, task) ->
                        if (Thread.currentThread().isInterrupted) return@submit
                        val left = deadline - System.nanoTime()
                        if (left > 0 || task.isDone) runCatching { task.get(maxOf(1L, left), java.util.concurrent.TimeUnit.NANOSECONDS) }.getOrNull()?.let { listings[channel.id] = it }
                        handler.post { if (!closed && started && epoch == sportsMatchEpoch && sportsVisible) sportsPanel.matchingProgress(game, "Checking provider archives · ${index + 1} / ${targets.size}", index + 1, targets.size) }
                    }
                } finally { requests.values.forEach { it.cancel(true) } }
                choices = SportsReplays.findListings(game, snapshot, listings, System.currentTimeMillis())
            }
            val result = SportsReplays.rank(choices, quality, favoriteSnapshot)
            handler.post {
                if (closed || !started || generation != sourceEpoch || source?.id != selected.id || epoch != sportsMatchEpoch || !sportsVisible || selectedGame?.key != game.key) return@post
                replayDiscovery?.remember(selected, sourceEpoch, snapshot, listings)
                rememberSportsReplays(game, result)
                sportsPanel.showReplays(game, result)
                scheduleSports()
            }
        }
    }
    private fun playSportsReplay(replay: SportsReplay) {
        val selected = source ?: return
        val game = selectedGame?.takeIf { it.state == "post" } ?: return
        if (guide.channels.none { it.id == replay.channel.id && it.url == replay.channel.url }) { toast("Playlist changed · find this replay again"); return }
        val item = replay.item(game, selected.allowHttp, System.currentTimeMillis()) ?: run { toast("This replay has expired or is unavailable"); findSportsReplay(game); return }
        if (::multiview.isInitialized) multiview.close()
        library.dismiss(); playOnDemand(item, 0); sportsReplayGame = game
    }
    private fun returnToSportsReplay() {
        if (sportsReplayGame == null) return
        returnToLive(); switchMode(true); sportsPanel.back(); sportsPanel.focusGames()
    }
    private fun stopSportsPrefetch(clear: Boolean = false) {
        ++sportsWarmEpoch; handler.removeCallbacks(warmSportsStreams); sportsWarmTask?.cancel(true); sportsWarmTask = null
        sportsWarmActive?.let { key ->
            sportsWarmAttempted.remove(key)
            if (sportsStreamSummaries[key]?.startsWith("Finding stream") == true) sportsStreamSummaries[key] = "Select to find stream"
        }
        sportsWarmActive = null
        if (clear) { sportsStreamCache.clear(); sportsWarmAttempted.clear(); sportsWarmGames = emptyList(); sportsWarmPriority = null; sportsStreamSummaries.clear(); if (::sportsPanel.isInitialized) sportsPanel.updateStreamSummaries(emptyMap()) }
        else if (::sportsPanel.isInitialized) sportsPanel.updateStreamSummaries(sportsStreamSummaries)
    }
    private fun queueSportsPrefetch(games: List<SportsGame>? = null) {
        if (games != null) {
            sportsWarmGames = games.filter { it.live(System.currentTimeMillis()) }.take(12)
            sportsStreamSummaries.keys.retainAll(sportsWarmGames.map { it.key }.toSet() + listOfNotNull(sportsWarmPriority?.key))
        }
        if (!started || closed || !sportsVisible || !guideVisible || sportsPanel.watching) return
        val selected = source ?: return
        val token = sportsStreamCache.use(selected.id, generation, guide, favorites)
        (listOfNotNull(sportsWarmPriority) + sportsWarmGames).distinctBy { it.key }.forEach { game ->
            val match = sportsStreamCache.get(token, game, System.currentTimeMillis(), forAuto = false, allowStale = true)
            sportsStreamSummaries[game.key] = when {
                match != null -> sportsStreamSummaries[game.key]?.takeUnless { it.startsWith("Finding stream") || it.startsWith("Select to") } ?: sportsStreamSummary(match)
                sportsWarmActive == game.key -> sportsWarmProgress
                sportsWarmAttempted.containsKey(game.key) -> "Select to finish stream search"
                else -> "Select to find stream"
            }
        }
        sportsPanel.updateStreamSummaries(sportsStreamSummaries)
        handler.removeCallbacks(warmSportsStreams); handler.postDelayed(warmSportsStreams, 300)
    }
    private fun sportsStreamSummary(match: SportsStreamCache.Match): String {
        val evidence = guide.copy(programmes = guide.programmes + match.warmed)
        val now = System.currentTimeMillis()
        return SportsStreams.cardSummary(match.game, sportsStreamFilters(), videoInfo, evidence, now) { !unavailable.contains(it.id, now) }
    }
    private fun prefetchSportsStream() {
        if (!started || closed || !sportsVisible || !guideVisible || sportsPanel.watching || multiview.active || sportsWarmActive != null) return
        if (player.playbackState == Player.STATE_BUFFERING || current != null && (!firstFrame || player.playbackState != Player.STATE_READY)) { handler.postDelayed(warmSportsStreams, 1000); return }
        val selected = source ?: return
        val now = System.currentTimeMillis(); val snapshot = guide; val token = sportsStreamCache.use(selected.id, generation, snapshot, favorites)
        val game = (listOfNotNull(sportsWarmPriority) + sportsWarmGames).distinctBy { it.key }.firstOrNull {
            it.live(now) && sportsStreamCache.get(token, it, now, forAuto = false) == null && now - (sportsWarmAttempted[it.key] ?: 0L) >= 30000
        } ?: return
        sportsWarmAttempted[game.key] = now
        val showProgress = sportsStreamCache.get(token, game, now, forAuto = false, allowStale = true) == null
        sportsWarmActive = game.key; sportsWarmProgress = "Finding stream…"
        if (showProgress) { sportsStreamSummaries[game.key] = sportsWarmProgress; sportsPanel.updateStreamSummaries(sportsStreamSummaries) }
        if (sportsWarmAttempted.size > 64) sportsWarmAttempted.keys.retainAll(sportsWarmGames.map { it.key }.toSet() + game.key)
        val epoch = ++sportsWarmEpoch; val favoriteSnapshot = favorites; val profile = sportsStreamFilters(); val quality = videoInfo.toMap()
        sportsWarmTask = sportsWarmWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // Background cards use saved data only. Provider requests belong to an explicit selection.
            val result = runCatching { resolveSportsMatch(selected, game, snapshot, favoriteSnapshot, profile, quality, false, { _, checked, total ->
                handler.post {
                    if (showProgress && !closed && epoch == sportsWarmEpoch && sportsWarmActive == game.key) {
                        sportsWarmProgress = "Finding stream · $checked / $total"; sportsStreamSummaries[game.key] = sportsWarmProgress
                        sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                    }
                }
            }, background = true) }.getOrNull()
            val summary = result?.let { SportsStreams.cardSummary(it.game, profile, quality, snapshot.copy(programmes = snapshot.programmes + it.warmed), System.currentTimeMillis()) }
            handler.post {
                if (closed || epoch != sportsWarmEpoch) return@post
                sportsWarmTask = null; sportsWarmActive = null
                val completedAt = System.currentTimeMillis()
                val latest = sportsStreamCache.use(selected.id, generation, guide, favorites)
                val retained = result?.takeIf { sportsStreamCache.put(token, it, completedAt) }
                    ?: sportsStreamCache.get(latest, game, completedAt, forAuto = false, allowStale = true)
                if (retained != null) {
                    sportsStreamSummaries[game.key] = if (retained === result && summary != null) summary else sportsStreamSummary(retained)
                } else {
                    sportsStreamSummaries[game.key] = "Select to finish stream search"
                    if (result != null && result.game.coverageComplete) sportsWarmAttempted.remove(game.key)
                }
                sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                if (started && sportsVisible && guideVisible && !sportsPanel.watching) handler.postDelayed(warmSportsStreams, 250)
            }
        }
    }
    private fun clearSportsPlayback() { sportsPlaybackPlan = null; pendingSportsTune = null }
    private fun playNextSportsCandidate(reason: String): Boolean {
        val plan = sportsPlaybackPlan ?: return false
        val selected = source ?: return false
        val next = plan.next { channel -> !unavailable.contains(channel.id, System.currentTimeMillis()) && Network.allowed(channel.url, selected.allowHttp) &&
            guide.channels.any { it.id == channel.id && it.url == channel.url } } ?: return false
        tune(next); sportsPlaybackPlan = plan; pendingSportsTune = next.id
        watchingSports = sportsResults?.takeIf { it.game.key == plan.game.key } ?: GameStreams(plan.game, plan.candidates)
        val exact = plan.game.broadcasts.any { it.channel.id == next.id && System.currentTimeMillis() in it.programme.start until it.programme.end } || plan.game.titleMatches.any { it.id == next.id }
        if (!exact) sportsPanel.showMatchNotice("Trying broadcaster match · game coverage unconfirmed")
        sportsPanel.matchingProgress(plan.game, "$reason · ${next.name}"); toast("$reason · ${next.name}")
        return true
    }
    private fun playbackGame(): GameStreams? {
        if (onDemand != null) return null
        val channel = current ?: return null
        val now = System.currentTimeMillis()
        watchingSports?.takeIf { now in it.game.start until maxOf(it.game.end, it.game.start + 6 * 3600000L) && it.channels.any { stream -> stream.id == channel.id && stream.url == channel.url } }?.let { return it }
        // Channels opened from the guide can identify a matchup from their current EPG or title.
        val programme = guide.at(channel, now)
        val title = Programme(guide.key(channel), channel.name, now, now + 3 * 3600000L)
        val listing = programme?.takeIf { SportsGuide.teams(it, channel.group) != null } ?: title
        val teams = SportsGuide.teams(listing, channel.group) ?: return null
        return GameStreams(SportsGame(teams, listing.start, listing.end, listOf(SportsBroadcast(channel, listing))), listOf(channel))
    }
    private fun showOtherGameStreams() {
        val context = playbackGame() ?: return
        showGameStreamChoices(context, "Choose another stream for ${context.game.title}")
    }
    private fun showSportsAlternatives(message: String) {
        val context = playbackGame() ?: sportsPlaybackPlan?.let { GameStreams(it.game, it.candidates) } ?: return
        showGameStreamChoices(context, message)
    }
    private fun showGameStreamChoices(context: GameStreams, message: String) {
        val selected = source ?: return
        val now = System.currentTimeMillis(); val lineup = guide.channels.associateBy { it.id }
        val choices = context.channels.mapNotNull { lineup[it.id] }.distinctBy { it.id }
            .filter { !unavailable.contains(it.id, now) && Network.allowed(it.url, selected.allowHttp) }
        stopSportsPrefetch(); clearSportsPlayback(); closeDetails()
        ++sportsMatchEpoch; sportsMatchTask?.cancel(true); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
        sportsVisible = true; showGameInfo(context.game); applyFilter(); showGuide(true)
        if (choices.none { it.id != current?.id }) { selectGame(context.game); return }
        sportsResults = GameStreams(context.game, choices); sportsChannels = choices.take(4)
        sportsPanel.matching(context.game)
        sportsPanel.showStreams(context.game, choices, message) { channel -> (if (channel.id == current?.id) "Current · " else "") + gameStreamLabel(context.game, channel) }
    }
    private fun gameStreamLabel(game: SportsGame, channel: Channel): String =
        (if (channel.id in favorites) "★ " else "") + channel.name + " · " + channel.qualityLabel(videoInfo[channel.id]) +
            if (game.broadcasts.any { it.channel.id == channel.id }) " · EPG matched" else if (game.titleMatches.any { it.id == channel.id }) " · Channel title matched" else " · Possible coverage"
    private fun resolveSportsMatch(selected: Source, game: SportsGame, snapshot: Guide, favoritesSnapshot: Set<String>,
        profile: GuideFilters, quality: Map<String, VideoInfo>, confirm: Boolean, progress: (String, Int, Int) -> Unit, cached: SportsStreamCache.Match? = null, background: Boolean = false): SportsStreamCache.Match {
        var lastProgress = 0L
        // A finite local pass can finish on a large lineup at background priority. Tuning and
        // lifecycle changes still interrupt it; only explicit foreground searches have a deadline.
        val deadline = if (background) Long.MAX_VALUE else System.nanoTime() + 1_500_000_000L
        var result = cached?.game ?: SportsSchedule.match(game, snapshot, favoritesSnapshot, System.currentTimeMillis(), deadline, quality = quality) { checked, total ->
            val now = System.nanoTime()
            if (now - lastProgress > 100_000_000L || checked == total) { lastProgress = now; progress("Checking playlist · $checked / $total", checked, total) }
        }
        val warmed = cached?.warmed.orEmpty().toMutableMap()
        val needsProvider = selected.kind == "xtream" && selected.epgUrl.isBlank()
        val coverageNow = System.currentTimeMillis()
        val locallyConfirmed = result.broadcasts.any { coverageNow in it.programme.start until it.programme.end } || result.titleMatches.isNotEmpty()
        if (confirm && needsProvider && !Thread.currentThread().isInterrupted) {
            val choices = SportsStreams.rank(result, profile.copy(excludedFps = emptySet()), quality)
            val bestHeight = (result.broadcasts.map { it.channel } + result.titleMatches).maxOfOrNull { it.quality(quality[it.id])?.height ?: 0 } ?: 0
            val targets = SportsStreams.confirmationTargets(game, snapshot, choices, favoritesSnapshot, quality, System.currentTimeMillis())
                .filter { !locallyConfirmed || (it.quality(quality[it.id])?.height ?: 0) > bestHeight }
            if (targets.isNotEmpty()) {
                val highestTarget = targets.maxOf { it.quality(quality[it.id])?.height ?: 0 }
                progress("Confirming current coverage · ${targets.size} channels", 0, 0)
                val replies = SportsCoverageLookup.load(targets, sportsLookupWorkers,
                    get = { channel, timeout -> XtreamEpg.load(selected, channel, 6, timeout) },
                    confirmed = { channel, entries ->
                        (channel.quality(quality[channel.id])?.height ?: 0) >= highestTarget &&
                            SportsSchedule.match(game, Guide(listOf(channel), mapOf(snapshot.key(channel) to entries)), favoritesSnapshot, System.currentTimeMillis()).broadcasts.isNotEmpty()
                    }, progress = { checked, total -> progress("Confirming current coverage · $checked / $total", checked, total) })
                replies.forEach { (channel, entries) -> warmed[snapshot.key(channel)] = entries }
                if (warmed.isNotEmpty()) {
                    val fresh = SportsSchedule.match(game, Guide(targets, warmed), favoritesSnapshot, System.currentTimeMillis())
                    val broadcasts = (result.broadcasts + fresh.broadcasts).distinctBy { it.channel.id }; val ids = broadcasts.map { it.channel.id }.toSet()
                    result = result.copy(broadcasts = broadcasts, candidates = result.candidates.filter { it.id !in ids }, titleMatches = result.titleMatches.filter { it.id !in ids })
                }
            }
        }
        return SportsStreamCache.Match(result, warmed, confirm || !needsProvider || locallyConfirmed)
    }
    private fun selectGame(game: SportsGame, autoWatch: Boolean = false) {
        if (game.state == "post") { findSportsReplay(game); return }
        stopSportsPrefetch(); clearSportsPlayback()
        pendingSportsTune = null; sportsMatchTask?.cancel(true)
        val selected = source ?: run { toast("Add a playlist in Settings to find streams"); return }; val snapshot = guide; val favoritesSnapshot = favorites
        val profile = sportsStreamFilters(); val quality = videoInfo.toMap(); val sourceEpoch = generation
        val cacheContext = sportsStreamCache.use(selected.id, sourceEpoch, snapshot, favoritesSnapshot)
        val cached = sportsStreamCache.get(cacheContext, game, System.currentTimeMillis(), forAuto = autoWatch)
        showGameInfo(game); val matchEpoch = ++sportsMatchEpoch
        sportsPanel.matching(game)
        fun present(prepared: SportsStreamCache.Match) {
            val warmed = prepared.warmed; val matched = prepared.game
            if (closed || !started || generation != sourceEpoch || source?.id != selected.id || !sportsVisible || matchEpoch != sportsMatchEpoch || selectedGame?.key != game.key) return
            val now = System.currentTimeMillis()
            if (prepared.needsRefresh(snapshot, guide, now)) { selectGame(game, autoWatch); return }
            val (mergedGuide, retained) = prepared.mergeGuide(guide, now)
            guide = mergedGuide
            sportsStreamCache.put(cacheContext, retained, now)
            if (warmed.isNotEmpty()) {
                handler.removeCallbacks(cacheEpg); handler.postDelayed(cacheEpg, 1500)
            }
            val latestQuality = videoInfo.toMap()
            val alternatives = SportsStreams.automaticChoices(matched, profile, latestQuality, guide, now) { !unavailable.contains(it.id, now) && Network.allowed(it.url, selected.allowHttp) }
            val channels = (alternatives + SportsStreams.rank(matched, profile.copy(minHeight = 0, excludedFps = emptySet()), latestQuality) {
                !unavailable.contains(it.id, now) && Network.allowed(it.url, selected.allowHttp)
            }).distinctBy { it.id }.filterNot { SportsStreams.conflictingCoverage(matched, it, guide, now) }
            sportsResults = GameStreams(matched, channels)
            val streamLabel: (Channel) -> String = { (if (it.id == current?.id) "Current · " else "") + gameStreamLabel(matched, it) }
            if (autoWatch) {
                if (alternatives.isNotEmpty()) {
                    showGameInfo(matched); sportsChannels = channels.take(4)
                    sportsPanel.showStreams(matched, channels, null, streamLabel)
                    multiview.close(); sportsPlaybackPlan = SportsPlaybackPlan(matched, alternatives)
                    if (!playNextSportsCandidate("Opening best game stream")) showSportsAlternatives("The available streams changed. Choose another result.")
                    return
                }
            }
            showGameInfo(matched); sportsChannels = channels.take(4); scheduleEpg()
            sportsPanel.showStreams(matched, channels, if (autoWatch) SportsStreams.unavailableReason(matched, channels, latestQuality, now) else null, streamLabel)
        }
        if (cached != null && cached.autoReady) { present(cached); return }
        sportsMatchTask = sportsMatchWorker.submit {
            fun progress(message: String, checked: Int, total: Int) { handler.post { if (!closed && started && matchEpoch == sportsMatchEpoch && sportsVisible) sportsPanel.matchingProgress(game, message, checked, total) } }
            val prepared = resolveSportsMatch(selected, game, snapshot, favoritesSnapshot, profile, quality, true, ::progress, cached)
            handler.post { present(prepared) }
        }
    }

    private fun startMultiview(channel: Channel) {
        clearSportsPlayback(); stopSportsPrefetch()
        val selected = source ?: return
        if (current == null || !hasPlayedChannel) { tune(channel); toast("Start a live channel, then open Multiview"); return }
        if (channel.id == current?.id) {
            showGuide(false); closeDetails(); nowPlaying.hide(immediate = true); frameRates.clear()
            multiview.open(current!!.name, current!!.id); return
        }
        if (!Network.allowed(channel.url, selected.allowHttp)) { toast("This stream needs HTTP permission in playlist settings"); return }
        showGuide(false); nowPlaying.hide(immediate = true); frameRates.clear()
        val url = when (store.streamFormat(selected)) { "ts" -> StreamUrls.format(channel.url, "ts"); "hls" -> StreamUrls.format(channel.url, "m3u8"); else -> workingUrls[channel.id] } ?: channel.url
        multiview.start(channel, url, current?.name.orEmpty(), current?.id.orEmpty())
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
        val format = player.videoFormat
        fun rate(value: Float) = if (value.isFinite() && value > 0) "%.2f".format(java.util.Locale.US, value) else "unknown"
        val evidence = if (format == null) "" else "\n${format.sampleMimeType?.removePrefix("video/").orEmpty()} · Input ${rate(sourceVideoFrames.rate(playbackToken))} · Declared ${rate(format.frameRate)} · Decoder ${rate(decoderFrameRate)} · Display samples ${rate(measuredFrameRate)} fps · $scanEvidence · ${videoInfo[channel.id]?.evidence.orEmpty()}"
        val transport = if (player.currentManifest is androidx.media3.exoplayer.hls.HlsManifest) "HLS" else if (playbackUrl.substringBefore('?').endsWith(".ts", true)) "TS" else "Stream"
        val health = "\n$transport · Buffer ${"%.1f".format(java.util.Locale.US, player.totalBufferedDuration.coerceAtLeast(0) / 1000.0)} s · $playbackRebuffers stalls · $playbackLoadErrors load errors · $playbackReconnects reconnects"
        val recentIds = source?.let(store::recent).orEmpty().filter { it != channel.id }
        if (recentLookupChannels !== guide.channels) { recentLookupChannels = guide.channels; recentLookup = guide.channels.associateBy { it.id } }
        details.update(channel, programme, next, (videoInfo[channel.id]?.label() ?: "Quality unverified") + evidence + health, channel.id in favorites, previousChannel() != null, Catchup.supported(channel), recentIds.mapNotNull(recentLookup::get), source?.allowHttp == true, playbackGame()?.game?.title, source?.let { selected -> programme?.let(VodStart::request)?.let { library.repository.matches(selected, it).isNotEmpty() } } == true)
    }
    private fun showDetails() {
        if (onDemand != null) { showDemandControls(); return }
        if (current == null) return
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        details.resumeImages(); refreshDetails(); root.bringChildToFront(details); details.visibility = View.VISIBLE; (if (details.gameStreamsButton.isVisible) details.gameStreamsButton else details.guideButton).requestFocus()
        handler.removeCallbacks(hideDetails); handler.postDelayed(hideDetails, 10000)
    }
    private fun closeDetails() {
        handler.removeCallbacks(hideDetails)
        if (::details.isInitialized) { details.pauseImages(); details.visibility = View.GONE }
    }
    private fun editSource(existing: Source?) = SourceDialog.show(this, existing, { selected ->
        runCatching { val next = sources.filterNot { it.id == selected.id } + selected; store.saveSources(next); sources = next; RefreshJobs.configure(this, sources) }
            .onSuccess { if (settingsPanel?.opened == true) closeSettings(); load(selected, force = true) }.onFailure { notice("Could not save", "Android could not securely save this source. Try again.") }
    }, { removed ->
        if (source?.id == removed.id) { handler.removeCallbacks(saveVideoReadings); videoSavePending = false }
        runCatching { val next = sources.filterNot { it.id == removed.id }; store.saveSources(next); cacheWrites.execute { store.removeCache(removed) }; sources = next; RefreshJobs.configure(this, sources) }
            .onSuccess {
                if (settingsPanel?.opened == true) closeSettings()
                if (source?.id == removed.id) sources.firstOrNull()?.let { load(it) } ?: clearRemovedPlaylist()
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    /** Deleting the last source must also clear VOD/resume and every source-owned callback. */
    private fun clearRemovedPlaylist() {
        stopSportsPrefetch(clear = true); clearSportsPlayback()
        ++generation; ++mergeEpoch; deferredRefresh = null; refreshRetryAt = 0L
        handler.removeCallbacks(saveVideoReadings); videoSavePending = false; recentLookupChannels = null; recentLookup = emptyMap()
        importTask?.cancel(true); cancelPlaybackCallbacks(); handler.removeCallbacks(cacheEpg); handler.removeCallbacks(fetchVisibleEpg)
        handler.removeCallbacks(demandTick); handler.removeCallbacks(hideDemandControls); handler.removeCallbacks(filterSearch)
        ++sportsMatchEpoch; sportsMatchTask?.cancel(true); pendingSportsTune = null
        ++sportsEpoch; sportsTask?.cancel(true); sportsComputing = false; sportsDirty = false
        handler.removeCallbacks(renderSports); handler.removeCallbacks(pollLiveSports); sportsRenderPending = false
        library.dismiss(); multiview.close(); voiceInput.stop(); voiceSearchPending = false; closeDetails(); dismissGameReminder()
        onDemand = null; demandPosition = 0; demandPaused = false; demandSubtitleMode = null; demandControls.visibility = View.GONE
        playbackToken++; firstFrame = false; hasPlayedChannel = false; expectedMediaId = ""; playbackUrl = ""; pausedPlayback = null
        player.stop(); player.clearMediaItems(); frameRates.clear()
        source = null; store.active = ""; current = null; focused = null; lastWorkingChannel = null; lastVideoInfo = null
        favorites = emptySet(); favoritesOnly = false; group = null; query = ""
        filters = GuideFilters(); sportsFilters = GuideFilters(); videoInfo.clear(); unavailable = UnavailableChannels(); workingUrls.clear(); guideTimeline.clear()
        guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE; guideSearch.setQuery("")
        epgWanted = emptyList(); epgCheckedAt.clear(); epgDepth.clear(); epgErrors.clear()
        guide = Guide.EMPTY; sportsVisible = false; sportsChannels = emptyList(); selectedGame = null; sportsResults = null; watchingSports = null; sportsReplayGame = null; replayCache.clear(); sportsForcedEpg.clear()
        listOf(loadingPanel, playbackLoading, retryPanel, refreshBar).forEach { it.visibility = View.GONE }
        updateGroups(); applyFilter(); showGuide(true); status.text = "Add a playlist in Settings to start watching"
    }
    private fun playbackMenu() {
        clearGuideSearch()
        clearSportsPlayback()
        if (::demandControls.isInitialized) demandControls.visibility = View.GONE
        val existing = settingsPanel
        if (existing?.opened == true) { existing.focusNavigation(); return }
        settingsReturnGuide = guideVisible; settingsReturnLibrary = library.opened; ++focusEpoch; handler.removeCallbacks(backHold)
        showGuide(false); nowPlaying.hide(immediate = true); previewCaption.visibility = View.GONE
        val panel = existing ?: SettingsPanel(this, ::renderSettingsPage, ::closeSettings).also { settingsPanel = it; it.setPadding(dp(if (store.hiddenDock) 28 else 104), dp(24), dp(24), dp(24)); it.leaveNavigation = { settingsButton.requestFocus() }; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.bringChildToFront(panel)
        showNavigation("settings")
        panel.open(listOf("Playlists", "Local channels", "Playback", "Theme", "Guide", "Categories", "Sports", "Metadata", "Updates", "About Spare", "Open-source licenses", "Exit Spare"))
    }
    private fun closeSettings() {
        settingsPanel?.visibility = View.GONE
        if (settingsReturnLibrary && library.opened) { root.bringChildToFront(library.panel); showNavigation("library"); library.panel.focusCategories() } else showGuide(settingsReturnGuide)
        if (settingsReturnGuide) { ++focusEpoch; if (store.hiddenDock) focusChannel() else settingsButton.requestFocus() }
    }
    private fun renderSettingsPage(page: String) {
        val panel = settingsPanel ?: return
        if (page != "Theme") themeEditor = null
        if (page != "Local channels") localEditor = null
        val parent = when (page) {
            "Frame-rate matching", "Audio track", "Subtitles", "Stream format", "Temporarily hidden channels" -> "Playback"
            "Navigation dock", "Time format" -> "Guide"
            "Category order" -> "Categories"
            else -> null
        }
        panel.onDetailBack(parent?.let { { renderSettingsPage(it); panel.focusOption(page) } })
        fun refresh(focus: String? = null) {
            if (parent == null) panel.refresh(focus) else { renderSettingsPage(page); focus?.let(panel::focusOption) }
        }
        when (page) {
            "Playlists" -> {
                panel.page("Playlists", "Save several playlists; one plays at a time. Credentials stay encrypted on this TV.")
                sources.forEach { selected -> panel.action(selected.name, if (selected.id == source?.id) "Active playlist" else "Switch playlist", selected.id == source?.id) { closeSettings(); load(selected) } }
                sources.forEach { selected -> panel.action("Refresh settings · ${selected.name}") { renderRefreshSettings(selected); panel.focusContent() } }
                panel.action("Add playlist") { editSource(null) }; if (source != null) panel.action("Edit current playlist") { editSource(source) }
            }
            "Local channels" -> {
                panel.page(page, "Enter a U.S. city or state. Matches update automatically from your playlist; location stays on this device.")
                val editor = LocalChannelsEditor(this, store.localLocation, { value ->
                    store.localLocation = value; localLineup = null; requestLocalMatches(); if (localOnly) applyFilter()
                }, { localOnly = true; favoritesOnly = false; group = null; sportsVisible = false; closeSettings(); applyFilter(); showGuide(true) })
                localEditor = editor; panel.custom(editor, 390); requestLocalMatches(); editor.updateMatches(localIds.size, localLoading)
            }
            "Playback" -> {
                panel.page(page, "Player controls for live TV, catch-up, movies and series. Changes keep this settings surface open.")
                listOf("Frame-rate matching", "Audio track", "Subtitles", "Stream format", "Temporarily hidden channels").forEach { label ->
                    panel.action(label, when (label) { "Frame-rate matching" -> if (store.frameRateMatching) "On" else "Off"; "Subtitles" -> "Channel tracks, app defaults, size and style"; "Stream format" -> source?.let(store::streamFormat).orEmpty(); else -> "" }) { renderSettingsPage(label); panel.focusContent() }
                }
                panel.note("Live buffering adapts to available data. Sustained stalls reconnect automatically; OK offers other streams for recognized games.")
            }
            "Guide" -> {
                panel.page(page, "Guide navigation and clock. Appearance, fonts, spacing, opacity and preview mode are in Theme.")
                listOf("Navigation dock", "Time format").forEach { label -> panel.action(label) { renderSettingsPage(label); panel.focusContent() } }
                panel.action("Theme editor") { panel.selectSetting("Theme") }
            }
            "Navigation dock" -> {
                panel.page(page, "Hidden dock slides in when you move Left from categories. Right returns to your current screen; OK opens an item.")
                listOf("Hidden dock", "Always open").forEachIndexed { index, label -> panel.action(label, selected = store.hiddenDock == (index == 0)) {
                    store.hiddenDock = index == 0; applyDockPreference(); refresh(label)
                } }
            }
            "Frame-rate matching" -> {
                panel.page(page, "Match the TV refresh rate to the current stream. Your TV may briefly go black when changing modes. Off leaves display timing to Android.")
                listOf("On", "Off").forEachIndexed { index, label -> panel.action(label, selected = store.frameRateMatching == (index == 0)) {
                    store.frameRateMatching = index == 0; frameRates.enabled = store.frameRateMatching
                    if (store.frameRateMatching && !multiview.active) videoInfo[current?.id]?.fps?.let(frameRates::request)
                    refresh(label)
                } }
            }
            "Categories" -> {
                panel.page(page, "Choose which provider categories appear in your guide. Presets use the provider’s country/language labels.")
                panel.action("Category order") { renderSettingsPage("Category order"); panel.focusContent() }
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
                panel.page("Theme editor", "Live preview · changes save automatically · Left/Right changes a value")
                val editor = ThemeEditor(this, store, ::appearanceChanged, { panel.focusNavigation() })
                themeEditor = editor; panel.custom(editor, 390)
            }
            "Metadata" -> {
                panel.page("Metadata", "Provider artwork and descriptions are used by default. Optional AIO Metadata enrichment uses your configured Stremio addon URL. IPTV credentials and playback URLs stay in Spare. Enrichment is cached and never blocks playback.")
                panel.action("Refresh metadata artwork", "Retry missing movie and series posters without reloading your playlist") { metadata.clear(); library.clearMetadata(); panel.note("Metadata cache cleared · Missing posters will load as you browse.") }
                panel.action("Provider metadata") { imports.execute { store.saveMetadataAddon(""); handler.post { if (!closed) { metadata.clear(); library.clearMetadata(); panel.note("Provider metadata selected.") } } } }
                panel.action("Configure AIO Metadata", "Enter the configured manifest.json URL from your existing setup") { configureMetadata() }
                panel.note("Movies and series use provider IDs or an exact, unambiguous title/year match. Identifiable live programmes can also show enriched descriptions. Ambiguous matches retain provider data.")
            }
            "Time format" -> {
                panel.page(page, "Use the same clock format throughout the guide, channel details, and Sports.")
                listOf("12 hour · 7:30 PM", "24 hour · 19:30").forEachIndexed { index, label -> panel.action(label, selected = store.clock24 == (index == 1)) {
                    store.clock24 = index == 1; GuideClock.use24Hour = store.clock24; updateTimes(); adapter.refreshRows(); if (details.isVisible) refreshDetails(); scheduleSports(); refresh(label)
                } }
            }
            "Audio track" -> {
                panel.page(page, onDemand?.title ?: current?.name ?: "Play a channel to choose its audio track.")
                panel.action("Automatic") { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build() }
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group -> for (index in 0 until group.length) if (group.isTrackSupported(index)) {
                    val format = group.getTrackFormat(index); val label = listOfNotNull(format.label, format.language, format.codecs).joinToString(" · ").ifBlank { "Track ${index + 1}" }
                    panel.action(label, selected = group.isTrackSelected(index)) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build(); refresh(label) }
                } }
            }
            "Subtitles" -> renderCaptionSettings()
            "Sports" -> {
                panel.page("Sports", "Mark upcoming games with Remind me. Reminders appear over playback while Spare is open, once the scoreboard confirms the game is live. Watch best prefers the highest-resolution matching game stream. Known FPS breaks resolution ties. Broadcaster matches are labelled when game coverage is unconfirmed. All qualities remain playable.")
                panel.action("Play best stream on selection", "Skip live-game actions and play the highest-resolution match. Known FPS breaks resolution ties. Failed starts can try another match, up to three attempts. FPS is learned from normal playback.", selected = store.sportsAutoPlay) { store.sportsAutoPlay = !store.sportsAutoPlay; refresh("Play best stream on selection") }
                panel.action("Hide completed-game scores", selected = store.hideCompletedScores) { store.hideCompletedScores = !store.hideCompletedScores; sportsPanel.setSpoilers(store.hideCompletedScores); selectedGame?.let(::showGameInfo); refresh("Hide completed-game scores") }
                panel.action("Stream quality", "Resolution and FPS preferences apply to Sports only") { openSportsQuality() }
                panel.action("Sports settings") { sportsTeams() }; panel.action("Open live sports") { closeSettings(); switchMode(true) }
                panel.note("Saved reminders")
                if (reminders.events.isEmpty()) panel.note("No reminders. Choose an upcoming game in Sports.")
                reminders.events.forEach { event ->
                    val label = event.teams.mapNotNull { SportsCatalog.byId[it]?.name }.joinToString(" vs ")
                    panel.action("Remove · $label", GuideClock.sportsDate(event.start)) { reminders.remove("${event.league}:${event.id}"); saveGameReminders(); refresh() }
                }
            }
            "Updates" -> { panel.page(page, "Spare ${BuildConfig.VERSION_NAME}. Updates are downloaded from verified GitHub releases. Android may request installation permission or confirmation."); panel.action(availableUpdate?.let { "Update $it" } ?: "Check for updates") { updater.offer() } }
            "Stream format" -> {
                panel.page(page, "Auto tries the provider’s alternate transport format when a channel cannot open.")
                val selected = source
                if (selected?.kind != "xtream") panel.note("Available for Xtream playlists.") else listOf("auto" to "Auto", "ts" to "MPEG-TS", "hls" to "HLS").forEach { (format, label) -> panel.action(label, selected = store.streamFormat(selected) == format) { store.setStreamFormat(selected, format); if (onDemand == null) current?.let(::tune); refresh(label) } }
            }
            "Temporarily hidden channels" -> { panel.page(page, "${unavailable.snapshot().size} channels are temporarily hidden after confirmed missing-stream responses. They return automatically after 15 minutes."); panel.action("Restore channels") { unavailable.clear(); saveUnavailable(); applyFilter(); refresh() } }
            "Refresh playlist & guide" -> { source?.let { renderRefreshSettings(it) } ?: panel.page(page, "Add a playlist first.") }
            "About Spare" -> panel.page("Spare ${BuildConfig.VERSION_NAME}", "A minimal, free, open-source IPTV player. MIT License · github.com/mot1us/spare\n\nWatching: OK details · Left previous · Back guide\nGuide: Right later · Left earlier / categories · Back live now\nHold OK favorite · Hold Back full screen\n\nNo app accounts, ads, or analytics. Your credentials and guide cache stay in encrypted app storage.")
            "Open-source licenses" -> { panel.page(page, ""); panel.note(listOf("NOTICES.txt", "MIT.txt", "Apache-2.0.txt").joinToString("\n\n") { assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() } }) }
            "Exit Spare" -> { panel.page(page, "Close the player."); panel.action("Exit Spare") { finish() } }
        }
    }
    private fun refreshSummary(selected: Source): String {
        fun saved(value: Long) = if (value <= 0) "Not refreshed yet" else GuideClock.sportsDate(value)
        return (if (PlaylistRefresh.running(selected.id)) PlaylistRefresh.stage(selected.id) + "\n" else "") + "Playlist: ${saved(store.playlistRefreshed(selected))}\nEPG refresh: ${saved(store.epgRefreshed(selected))}" + store.refreshError(selected).takeIf { it.isNotBlank() }?.let { "\n$it · Saved data is kept." }.orEmpty()
    }
    private fun renderRefreshSettings(selected: Source, focus: String? = null) {
        val panel = settingsPanel ?: return; val policy = store.refreshPolicy(selected)
        panel.page("Refresh · ${selected.name}", "Saved channels open immediately. Refresh runs in the background and keeps playback and guide position. Android schedules intervals around sleep and network availability.")
        panel.note(refreshSummary(selected), "refresh-status:${selected.id}")
        fun set(value: RefreshPolicy, label: String) { store.setRefreshPolicy(selected, value); RefreshJobs.configure(this, sources); renderRefreshSettings(selected, label) }
        panel.action("Playlist on app launch", selected = policy.playlistOnLaunch) { set(policy.copy(playlistOnLaunch = !policy.playlistOnLaunch), "Playlist on app launch") }
        panel.action("EPG on app launch", selected = policy.epgOnLaunch) { set(policy.copy(epgOnLaunch = !policy.epgOnLaunch), "EPG on app launch") }
        fun interval(epg: Boolean) {
            val title = if (epg) "EPG interval" else "Playlist interval"
            panel.page(title, "Choose a background schedule for ${selected.name}. Manual still allows Refresh now.")
            RefreshPolicy.intervals.forEach { hours -> val label = RefreshPolicy.label(hours); panel.action(label, selected = hours == if (epg) policy.epgHours else policy.playlistHours) { set(if (epg) policy.copy(epgHours = hours) else policy.copy(playlistHours = hours), title) } }; panel.focusContent()
        }
        panel.action("Playlist interval", RefreshPolicy.label(policy.playlistHours)) { interval(false) }
        panel.action("EPG interval", RefreshPolicy.label(policy.epgHours)) { interval(true) }
        panel.action("Refresh now / Retry") { refreshCurrent(selected); renderRefreshSettings(selected, "Refresh now / Retry") }
        panel.action("Refresh playlist only") { refreshCurrent(selected, true, false); renderRefreshSettings(selected, "Refresh playlist only") }
        panel.action("Refresh EPG only") { refreshCurrent(selected, false, true); renderRefreshSettings(selected, "Refresh EPG only") }
        focus?.let(panel::focusOption)
    }
    private fun configureMetadata() {
        val input = android.widget.EditText(this).apply { hint = "https://…/manifest.json"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("AIO Metadata addon").setMessage("Paste your configured addon manifest URL. It is saved encrypted on this TV.").setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val value = input.text.toString()
                imports.execute {
                    val result = runCatching { val url = MetadataAddon.validate(value); MetadataAddon.manifest(url); store.saveMetadataAddon(url) }
                    handler.post { if (!closed) { result.onSuccess { metadata.clear(); library.clearMetadata(); settingsPanel?.note("AIO Metadata connected · Missing posters load as you browse Movies and Series."); toast("AIO Metadata connected") }.onFailure { notice("Could not connect metadata", "Use a valid configured HTTPS manifest.json URL. The addon must be reachable. Your playlist is unchanged.") } } }
                }
            }.setNegativeButton("Cancel", null).show()
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
        panel.onDetailBack { renderSettingsPage("Playback"); panel.focusOption("Subtitles") }
        fun refresh(label: String) { renderCaptionSettings(); panel.focusOption(label) }
        if (onDemand != null) {
            panel.page("Subtitles", "For ${onDemand?.title}. The app default applies to other on-demand titles.")
            panel.action("Subtitle appearance", "${store.subtitleSize} · ${store.subtitleStyle}") { renderSubtitleAppearance(); panel.focusContent() }
            listOf(null to "Use app default", CaptionTracks.OFF to "Off", CaptionTracks.AUTO to "Automatic").forEach { (mode, label) -> panel.action(label, selected = demandSubtitleMode == mode) { setSubtitleMode(mode); refresh(label) } }
            CaptionTracks.choices(player.currentTracks).forEach { choice -> panel.action(choice.label(), selected = demandSubtitleMode == choice.key) { setSubtitleMode(choice.key); refresh(choice.label()) } }
            return
        }
        panel.page("Channel subtitles", current?.name?.let { "Saved for $it. App default applies to channels without an override." } ?: "Play a channel to choose its subtitles.")
        panel.action("Subtitle appearance", "${store.subtitleSize} · ${store.subtitleStyle}") { renderSubtitleAppearance(); panel.focusContent() }
        if (current != null && source != null) {
            val saved = store.channelCaptions(source!!, current!!)
            panel.action("Use app default", selected = saved == null) { setSubtitleMode(null); refresh("Use app default") }
            panel.action("Off", selected = saved == CaptionTracks.OFF) { setSubtitleMode(CaptionTracks.OFF); refresh("Off") }
            panel.action("Automatic", selected = saved == CaptionTracks.AUTO) { setSubtitleMode(CaptionTracks.AUTO); refresh("Automatic") }
            val tracks = CaptionTracks.choices(player.currentTracks)
            tracks.forEach { choice -> panel.action(choice.label(), selected = saved == choice.key) { setSubtitleMode(choice.key); refresh(choice.label()) } }
            if (tracks.isEmpty() || tracks.all { it.compatibility }) panel.note("Automatic tries common embedded caption services. This programme must supply captions for them to appear.")
        }
        panel.action("App default") { renderDefaultCaptionSettings(); panel.focusContent() }
    }
    private fun applySubtitleAppearance() { SubtitleAppearance.apply(video, store.subtitleSize, store.subtitleStyle); if (::multiview.isInitialized) multiview.appearance() }
    private fun renderSubtitleAppearance() {
        val panel = settingsPanel ?: return
        panel.onDetailBack { renderCaptionSettings(); panel.focusOption("Subtitle appearance") }
        panel.page("Subtitle appearance", "Applies to live TV, movies and series. Embedded subtitle font sizes are overridden.")
        panel.note("Size")
        SubtitleAppearance.sizes.keys.forEach { size -> panel.action(size, selected = store.subtitleSize == size) { store.subtitleSize = size; applySubtitleAppearance(); renderSubtitleAppearance(); panel.focusOption(size) } }
        panel.note("Style")
        SubtitleAppearance.styles.forEach { style -> panel.action(style, selected = store.subtitleStyle == style) { store.subtitleStyle = style; applySubtitleAppearance(); renderSubtitleAppearance(); panel.focusOption(style) } }
    }
    private fun renderDefaultCaptionSettings() {
        val panel = settingsPanel ?: return
        panel.onDetailBack { renderCaptionSettings(); panel.focusOption("App default") }
        panel.page("Default subtitles · all channels", "Channels with their own subtitle preference keep that preference.")
        listOf("Off", "Automatic").forEachIndexed { index, label -> panel.action(label, selected = store.captions == (index == 1)) {
            val wasEnabled = captionsEnabled(); store.captions = index == 1; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); applyCaptionPreference(); reconcileCaptions()
            if (!wasEnabled && captionsEnabled() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) { saveDemandProgress(); playAttempt() }
            renderDefaultCaptionSettings(); panel.focusOption(label)
        } }
    }
    private fun saveFilters(next: GuideFilters) {
        source?.let { store.setFilters(it, next) }; filters = GuideFilters(groups = next.groups); group = null; applyFilter()
    }
    private fun sportsStreamFilters() = sportsFilters.copy(minHeight = 0, groups = filters.groups)
    private fun saveSportsFilters(next: GuideFilters) {
        sportsFilters = next.copy(minHeight = 0, groups = emptySet()); source?.let { store.setSportsFilters(it, sportsFilters) }
        sportsQualityChanged = true
        stopSportsPrefetch(clear = true); scheduleSports()
    }
    private fun renderSportsQuality(panel: SettingsPanel) {
        panel.page("Sports stream quality", "Watch Best ranks matching streams by resolution first. FPS preferences break ties at the same resolution. All qualities remain playable; playback readings take priority over provider metadata.")
        if (source == null) { panel.note("Add a playlist in Settings to save Sports quality preferences."); return }
        panel.action("Prefer 60 fps", "Prefer 59.94/60+ FPS among streams at the same resolution") { saveSportsFilters(GuideFilters(excludedFps = FrameRateFilter.sixtyOrBetter)); panel.refresh("Prefer 60 fps") }
        val profile = sportsStreamFilters()
        val channels = guide.channels.filter { !it.media && (profile.groups.isEmpty() || it.group in profile.groups) }
        val known = channels.count { (it.quality(videoInfo[it.id])?.fps ?: 0f).let { rate -> rate.isFinite() && rate > 0f } }
        val resolutionKnown = channels.count { (it.quality(videoInfo[it.id])?.height ?: 0) > 0 }
        panel.note("${channels.size} channels · $resolutionKnown with known resolution · $known with known FPS", "quality-data-summary")
        panel.note("Resolution first: 4K, FHD, HD, then other available streams. FPS comes from provider metadata or normal playback; Spare never opens extra streams to check it. Missing FPS does not block Watch Best.")
        panel.note("Frame-rate tie-breaker · select the rates you prefer at the same resolution")
        FrameRateFilter.choices.forEach { (rate, label) -> panel.action(label, selected = rate !in sportsFilters.excludedFps) {
            val excluded = sportsFilters.excludedFps.toMutableSet(); if (!excluded.add(rate)) excluded.remove(rate)
            saveSportsFilters(sportsFilters.copy(excludedFps = excluded)); panel.refresh(label)
        } }
        panel.action("Reset stream quality") { saveSportsFilters(GuideFilters()); panel.refresh("Reset stream quality") }
    }
    private fun configureAppearance() {
        GuideTheme.select(store.theme); GuideTheme.selectStyle(store.themeStyle)
        GuideTheme.configure(store.customAccent, store.customBackground, store.cornerRadius, store.borderWidth, store.guideRowHeight, store.guideRowGap, store.guideTextScale)
        AppFont.select(store.font)
    }
    private fun appearanceChanged() {
        configureAppearance(); themeEditor?.refreshPreview()
        handler.removeCallbacks(updateAppearance); handler.postDelayed(updateAppearance, 80)
    }
    private fun applyTheme() {
        configureAppearance()

        guidePanel.background = GuideTheme.guideBackground(store.guideOpacity)
        fun visit(view: View) {
            if (view is Button) { view.background = GuideTheme.background(resources.displayMetrics.density, Color.rgb(30, 30, 30)); view.setTextColor(GuideTheme.ink()) }
            else if (view is ImageButton) view.background = GuideTheme.background(resources.displayMetrics.density)
            else if (view.tag?.toString()?.startsWith("settings-nav:") == true) { view.background = GuideTheme.background(resources.displayMetrics.density); (view as? TextView)?.setTextColor(GuideTheme.ink()) }
            else if (view.tag?.toString()?.startsWith("settings-option:") == true) view.background = GuideTheme.background(resources.displayMetrics.density, 0x66323D4B)
            if (view is ViewGroup && view !is RecyclerView) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root); navigationRail.appearance(); AppFont.apply(root); if (::library.isInitialized) library.panel.appearance(); playbackLoading.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        if (::sportsPanel.isInitialized) sportsPanel.refreshTheme()
        sidebarAdapter.notifyItemRangeChanged(0, sidebarAdapter.itemCount); adapter.refreshTheme()
    }
    private fun subtitleMode(): String = (if (onDemand != null) demandSubtitleMode else source?.let { selected -> current?.let { channel -> store.channelCaptions(selected, channel) } })
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
        val selected = source ?: return; val channel = current
        val wasEnabled = captionsEnabled()
        if (onDemand != null) demandSubtitleMode = mode else if (channel != null) store.setChannelCaptions(selected, channel, mode); captionSawCue = false; captionScanCount = 0
        applyCaptionPreference(); reconcileCaptions()
        if (!wasEnabled && captionsEnabled() && playbackUrl.substringBefore('?').endsWith(".m3u8", true)) { saveDemandProgress(); playAttempt() }
    }
    private fun tracks(type: Int) {
        playbackMenu(); settingsPanel?.selectSetting("Playback")
        renderSettingsPage(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio track"); settingsPanel?.focusContent()
    }
    @SuppressLint("GestureBackNavigation") // TV-only activity opts out of predictive back in the manifest so hold-Back receives key-down/up.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::vodStartPanel.isInitialized && vodStartPanel.isVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeVodStart(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (::searchPanel.isInitialized && searchPanel.isVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishSearch(true); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) voiceSearch(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (sportsCalendar?.opened == true) return sportsCalendar!!.dispatchKeyEvent(event)
        sportsManager?.takeIf { it.opened }?.let { panel ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) { if (event.keyCode == KeyEvent.KEYCODE_MENU) closeSportsManager() else panel.back() }; return true }
            return super.dispatchKeyEvent(event)
        }
        if (::reminderBanner.isInitialized && reminderBanner.isShown) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) dismissGameReminder(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (::multiview.isInitialized && multiview.visible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { multiview.close(); showGuide(true) }; return true }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) { multiview.close(); playbackMenu() }; return true }
            return super.dispatchKeyEvent(event)
        }
        settingsPanel?.takeIf { it.opened }?.let { panel ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) { if (event.keyCode == KeyEvent.KEYCODE_MENU) closeSettings() else panel.back() }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) { closeSettings(); search() }; return true }
            return super.dispatchKeyEvent(event)
        }
        if (::library.isInitialized && library.opened) {
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) search(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) library.back(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) playbackMenu(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (guideVisible && pendingFocusList != null && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) playbackMenu(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) search(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { backLongPressed = false; handler.postDelayed(backHold, ViewConfiguration.getLongPressTimeout().toLong()) }
            if (event.action == KeyEvent.ACTION_UP) { handler.removeCallbacks(backHold); if (!backLongPressed && !event.isCanceled) navigateBack() }
            return true
        }
        if (onDemand != null && !guideVisible && retryPanel.visibility != View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) { if (event.action == KeyEvent.ACTION_UP) stopDemandToLibrary(); return true }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                if (event.action == KeyEvent.ACTION_UP) { player.playWhenReady = when (event.keyCode) { KeyEvent.KEYCODE_MEDIA_PLAY -> true; KeyEvent.KEYCODE_MEDIA_PAUSE -> false; else -> !player.playWhenReady }; demandPaused = !player.playWhenReady; showDemandControls() }; return true
            }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) { if (event.action == KeyEvent.ACTION_DOWN) seekDemand(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -30000 else 30000); return true }
            if (!demandControls.isShown && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO)) {
                if (event.action == KeyEvent.ACTION_UP) showDemandControls(); return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) handler.removeCallbacks(hideDemandControls)
            return super.dispatchKeyEvent(event)
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
        if (::vodStartPanel.isInitialized && vodStartPanel.isVisible) { closeVodStart(); return }
        if (::searchPanel.isInitialized && searchPanel.isVisible) { finishSearch(true); searchButton.requestFocus(); return }
        if (sportsCalendar?.opened == true) { closeSportsCalendar(); return }
        if (sportsManager?.opened == true) { sportsManager?.back(); return }
        if (sportsVisible && sportsPanel.watching) { clearSportsPlayback(); sportsMatchTask?.cancel(true); ++sportsMatchEpoch }

        if (::multiview.isInitialized && multiview.active) {
            if (multiview.browsing) { showGuide(false); multiview.present() } else { multiview.close(); showGuide(true) }; return
        }
        if (settingsPanel?.opened == true) { settingsPanel?.back(); return }
        if (library.opened) { library.back(); return }
        if (onDemand != null) { if (sportsReplayGame != null) returnToSportsReplay() else if (onDemand?.kind == "catchup") returnToLiveChannel() else stopDemandToLibrary(); return }
        if (details.isVisible) closeDetails()
        else if (guideVisible && guideSearch.isVisible) { finishSearch(true); searchButton.requestFocus() }
        else if (retryPanel.isVisible) { retryPanel.visibility = View.GONE; showGuide(true) }
        else if (!guideVisible) showGuide(true)
        else if (list.hasFocus() && guideTimeline.ahead((focused ?: current)?.id)) resetTime()
        else if (sportsVisible && sportsPanel.back()) return
        else if (list.hasFocus() || (sportsVisible && sportsPanel.hasFocus())) focusSidebar()
        else focusChannel()
    }
    override fun onStart() { super.onStart(); started = true; if (::searchPanel.isInitialized && searchPanel.isVisible) updatePlaylistSearch(); sportsCalendar?.takeIf { it.opened }?.let { previewCalendarDate(it.activeDate, false) }; handler.removeCallbacks(refreshTick); handler.post(refreshTick); deferredRefresh?.let { deferredRefresh = null; mergeRefresh(it) }; if (::programmeArtwork.isInitialized) programmeArtwork.resume(); if (::player.isInitialized && (current != null || onDemand != null)) playAttempt(if (hasPlayedChannel) PlaybackPhase.RECONNECTING else PlaybackPhase.OPENING); scheduleEpg(); scheduleSports(); if (onDemand != null) { handler.removeCallbacks(demandTick); handler.postDelayed(demandTick, 1000) } }
    private var pausedPlayback: Boolean? = null
    override fun onResume() {
        super.onResume()
        val resume = pausedPlayback; pausedPlayback = null
        if (resume != null && ::player.isInitialized) {
            if (resume && player.playbackState == Player.STATE_IDLE) playAttempt(PlaybackPhase.RECONNECTING) else player.playWhenReady = resume
            if (resume && !firstFrame && player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)) { handler.removeCallbacks(startupTimeout); handler.postDelayed(startupTimeout, if (onDemand != null) 30000 else 15000) }
        }
        syncPlaybackMonitoring()
        if (::sportsPanel.isInitialized && guideVisible && sportsVisible) sportsPanel.resumeImages()
        if (::updater.isInitialized) updater.resume()
    }
    override fun onPause() {
        // Stop audio before any cache, image or lifecycle cleanup. A paused TV app
        // must never continue playing over the launcher or another app.
        if (::player.isInitialized) { pausedPlayback = player.playWhenReady; if (onDemand != null) demandPaused = !player.playWhenReady; player.pause(); saveDemandProgress() }
        cancelPlaybackCallbacks(); stopSportsPrefetch(); clearSportsPlayback(); replayDiscovery?.pause()
        if (::multiview.isInitialized) multiview.close()
        if (::sportsPanel.isInitialized) sportsPanel.pauseImages()
        super.onPause()
    }
    override fun onStop() { handler.removeCallbacks(warmVodIndex); vodWarmScheduled = false; if (vodRequest != null) closeVodStart(restore = false); if (::playlistSearch.isInitialized) playlistSearch.pause(cancel = true); replayDiscovery?.pause(); if (::player.isInitialized) player.stop(); calendarSchedule?.cancel(); flushVideoReadings(); pausedPlayback = null; ++sportsMatchEpoch; sportsMatchTask?.cancel(true); pendingSportsTune = null; dismissGameReminder(); handler.removeCallbacks(refreshTick); if (::programmeArtwork.isInitialized) programmeArtwork.pause(); if (::multiview.isInitialized) multiview.close(); saveDemandProgress(); handler.removeCallbacks(demandTick); handler.removeCallbacks(pollLiveSports); handler.removeCallbacks(hideDemandControls); cancelCaptionProbe(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; started = false; firstFrame = false; expectedMediaId = ""; closeDetails(); handler.removeCallbacks(cacheEpg); cacheEpg.run(); handler.removeCallbacks(backHold); handler.removeCallbacks(fetchVisibleEpg); handler.removeCallbacks(cacheEpg); handler.removeCallbacks(renderSports); sportsRenderPending = false; ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); cancelPlaybackCallbacks(); if (::frameRates.isInitialized) frameRates.clear(); super.onStop() }
    override fun onDestroy() { if (::playlistSearch.isInitialized) playlistSearch.close(); replayDiscovery?.close(); localTask?.cancel(true); localWorker.shutdownNow(); calendarSchedule?.close(); flushVideoReadings(); PlaylistRefresh.unlisten(this); if (::programmeArtwork.isInitialized) programmeArtwork.close(); if (::multiview.isInitialized) multiview.close(); metadata.close(); details.closeImages(); library.destroy(); sportsPanel.closeImages(); liveSportsTask?.cancel(true); liveSportsWorker.shutdownNow(); reminderWorker.shutdownNow(); if (::voiceInput.isInitialized) voiceInput.stop(); closed = true; ++generation; handler.removeCallbacksAndMessages(null); imports.shutdownNow(); epgWorkers.shutdownNow(); sportsWorker.shutdownNow(); sportsMatchWorker.shutdownNow(); sportsWarmWorker.shutdownNow(); sportsStreamCache.clear(); sportsLookupWorkers.shutdownNow(); cacheWrites.shutdown(); logos.close(); if (::updater.isInitialized) updater.close(); if (::player.isInitialized) { video.player = null; player.release() }; super.onDestroy() }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

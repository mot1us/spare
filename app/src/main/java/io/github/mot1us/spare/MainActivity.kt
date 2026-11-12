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


/** Channels whose short EPG is fetched ahead of the visible guide rows. */
private const val EPG_READ_AHEAD_LIMIT = 200
private const val EPG_BACKGROUND_LIMIT = 4000
private const val EPG_BACKGROUND_GAP_MS = 120L

@UnstableApi
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val workers = ActivityWorkers()
    private val imports = workers.pool("import", threads = 2, background = true)
    private val cacheWrites = workers.pool("cache", background = true, drain = true)
    private data class EpgSave(val guide: Guide, val catalog: PlaylistCatalog, val owners: List<Source>, val at: Long)
    private val epgWriter by lazy { LatestTask<EpgSave>(cacheWrites) { save -> save.owners.forEach { selected -> runCatching { store.cacheProgrammes(selected, save.catalog.extract(selected, save.guide).programmes, save.at) } } } }
    private var lastInputAt = 0L
    private val cadence = FrameCadence()
    private lateinit var sourceVideoFrames: SourceVideoFrames
    private lateinit var reminders: GameReminders
    private lateinit var reminderBanner: GameReminderBanner
    private lateinit var gameWatchPanel: GameWatchPanel
    private var reminderWatchReturnSports = false
    private var reminderGame: SportsGame? = null
    private var reminderPending: Pair<SportsGame, Long>? = null
    private var reminderCheckedAt = 0L
    private var reminderChecking = false
    private var reminderReturnFocus: View? = null
    private val hideReminder = Runnable { dismissGameReminder() }
    private var cachedStartup = false
    private var startupPlaybackPending = false
    private var startupInputAt = 0L
    private var additionalRestoreStarted = false
    private val restoringProgrammes = mutableSetOf<String>()
    private var availableUpdate: String? = null
    private var importTask: java.util.concurrent.Future<*>? = null
    private val epgWorkers = workers.pool("epg", threads = 3, background = true)
    // Ranking a very large lineup must never queue ahead of saved-guide or Sports work on the import thread.
    private val epgOrderWorker = workers.pool("epg-order", threads = 1, background = true)
    private var epgActive = 0
    private var epgBackgroundFor: List<Channel>? = null
    private var epgBackgroundOrder = emptyList<Channel>()
    private var epgBackgroundOrdering: List<Channel>? = null
    private var epgBackgroundCursor = 0
    private var epgBackgroundRequests = 0
    private var epgBackgroundFailures = 0
    private var epgBackgroundNotBefore = 0L
    private var epgSavePending = false
    private var epgSaveQuiet = false
    private fun cancelEpgSave() { handler.removeCallbacks(cacheEpg); epgSavePending = false }
    private val fetchBackgroundEpg = Runnable { pumpEpg() }
    private val prewarmLibrary = Runnable { if (started && !closed && ::library.isInitialized) enabledSources().firstOrNull { it.kind == "xtream" }?.let(library::prewarm) }
    private var epgWanted = emptyList<Channel>()
    /** Rows needed on screen now; the rest of epgWanted is background read-ahead. */
    private var epgUrgent = 0
    private var epgScrollDown = true
    private var epgAheadPausedUntil = 0L
    private val epgInFlight = mutableSetOf<String>()
    private val epgCheckedAt = mutableMapOf<String, Long>()
    private val epgDepth = mutableMapOf<String, Int>()
    private val epgErrors = mutableMapOf<String, String>()
    private val fetchVisibleEpg = Runnable { collectVisibleEpg() }
    private val cacheEpg = Runnable {
        epgSavePending = false
        val snapshot = guide; val observedAt = System.currentTimeMillis()
        // Another playlist can already have listings while this owner's saved EPG
        // is still being read. Never replace its disk cache with that partial view.
        val owners = enabledSources().filter { it.id in providerViews && it.id !in restoringProgrammes }
        if (::playlists.isInitialized && snapshot.channels.isNotEmpty() && snapshot.programmes.isNotEmpty()) epgWriter.submit(EpgSave(snapshot, playlists, owners, observedAt))
    }
    private data class EpgUpdate(val channel: Channel, val entries: List<Programme>, val at: Long, val background: Boolean = false)
    private val pendingEpg = linkedMapOf<String, EpgUpdate>()
    private var epgApplying = false
    private var epgApplyScheduled = false
    private val applyEpgUpdates = Runnable {
        epgApplyScheduled = false
        if (!closed && !epgApplying && pendingEpg.isNotEmpty()) {
            epgApplying = true
            val updates = pendingEpg.toMap(); pendingEpg.clear()
            val before = guide; val epoch = generation
            imports.execute {
                val programmes = LinkedHashMap(before.programmes)
                updates.values.forEach { update -> val key = before.key(update.channel); programmes[key] = GuideHistory.merge(programmes[key].orEmpty(), update.entries, update.channel.catchupDays, update.at) }
                handler.post {
                    epgApplying = false
                    if (!closed && epoch == generation) {
                        if (guide.programmes === before.programmes) {
                            guide = guide.copy(programmes = programmes)
                            // Save within 30 s of the first unsaved update you asked for. Background fill rewrites the whole
                            // encrypted guide, so it saves at most every 5 minutes (and when Spare stops) to keep playback smooth.
                            val quiet = updates.values.all { it.background }
                            if (epgSavePending && epgSaveQuiet && !quiet) cancelEpgSave()
                            if (!epgSavePending) { epgSavePending = true; epgSaveQuiet = quiet; handler.postDelayed(cacheEpg, if (quiet) 300000 else 30000) }
                            if (guideVisible) adapter.refreshRows()
                            if (guideVisible && sportsVisible) scheduleSports() else (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                            if (details.isVisible) refreshDetails()
                        } else updates.forEach { (id, update) -> if (id !in pendingEpg) pendingEpg[id] = update }
                    }
                    scheduleEpgApply()
                }
            }
        }
    }
    private var epgApplyAt = 0L
    /** Visible rows apply within 120 ms; background fills batch for a few seconds to keep the guide smooth. */
    private fun scheduleEpgApply(delay: Long = 120) {
        if (closed || pendingEpg.isEmpty()) return
        val at = android.os.SystemClock.uptimeMillis() + delay
        if (epgApplyScheduled && epgApplyAt <= at) return
        handler.removeCallbacks(applyEpgUpdates); epgApplyScheduled = true; epgApplyAt = at
        handler.postDelayed(applyEpgUpdates, delay)
    }
    private lateinit var refreshBar: ProgressBar
    private var refreshRetryAt = 0L
    private var launchRefreshPending = false
    private val playbackWarmup = PlaybackWarmup()
    private fun backgroundPlaybackReady(): Boolean = (current == null && onDemand == null) || playbackWarmup.ready(
        playbackToken, android.os.SystemClock.elapsedRealtime(), started && firstFrame && player.isPlaying, player.totalBufferedDuration)
    private fun bulkRefreshReady(): Boolean {
        if (onDemand != null || multiview.active || searchPanel.isVisible || library.opened || guideVisible && sportsVisible) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastInputAt < 3000) return false
        return current == null || playbackWarmup.ready(playbackToken, now, started && firstFrame && player.isPlaying, player.totalBufferedDuration, 30000, 15000, 60000)
    }
    private fun updateRefreshIndicator() { if (::refreshBar.isInitialized) refreshBar.isVisible = guide.channels.isEmpty() && (playlistLoading.isNotEmpty() || enabledSources().any { PlaylistRefresh.running(it.id) }) }

    private val ownRefreshes = mutableSetOf<Source>()
    private var mergeEpoch = 0
    private var deferredRefresh: PlaylistRefresh.Result? = null
    private val refreshTick = object : Runnable {
        override fun run() {
            if (closed) return
            resumeStartupPlayback()
            if (settingsPanel?.opened == true) sources.forEach { value -> settingsPanel?.findViewWithTag<TextView>("refresh-status:${value.id}")?.text = refreshSummary(value) }
            val selected = source
            checkGameReminders()
            syncScoreStrip()
            if (sportsVisible && guideVisible) syncReplayDiscovery()
            enabledSources().filter { it.id != selected?.id }.forEach { refreshAdditional(it) }
            if (selected != null) {
                val busy = PlaylistRefresh.running(selected.id)
                updateRefreshIndicator()
                if (busy && guide.channels.isEmpty()) { loadingPanel.visibility = View.VISIBLE; loadingText.text = PlaylistRefresh.stage(selected.id) }
                if (!busy && started && System.currentTimeMillis() >= refreshRetryAt) autoRefresh(selected, false)
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val sportsWorker = workers.pool("sports")
    private val sportsDestinationWorker = workers.pool("destinations", background = true)
    private val sportsScheduleDisk = workers.pool("schedule-cache", background = true)
    private var sportsDestinationTask: java.util.concurrent.Future<*>? = null
    internal var redZoneLoader: (Guide, GuideFilters, Map<String, VideoInfo>, Long) -> List<Channel> = { snapshot, profile, quality, now -> redZoneCache.find(snapshot, profile, quality, now) }
    private val sportsMatchWorker = workers.pool("sports-match")
    private val sportsStreamCache = SportsStreamCache()
    private val redZoneCache = SportsDestinations.RedZoneCache()
    private val sportsWarmWorker = workers.pool("sports-warm")
    private val sportsLocalWarmWorkers = workers.pool("sports-local-warm", threads = 2, background = true)
    private var sportsWarmTask: java.util.concurrent.Future<*>? = null
    private var sportsWarmActive = emptySet<String>()
    private var sportsWarmPending = false
    private var sportsWarmEpoch = 0
    private var sportsWarmGames = emptyList<SportsGame>()
    private var sportsUpgradeKeys = emptySet<String>()
    private var sportsWarmPriority: SportsGame? = null
    private val sportsWarmAttempted = mutableMapOf<String, Long>()
    private val sportsStreamSummaries = mutableMapOf<String, String>()
    private val warmSportsStreams = Runnable { prefetchSportsStream() }
    private data class GameStreams(val game: SportsGame, val channels: List<Channel>)
    private var sportsResults: GameStreams? = null
    private lateinit var scoreStrip: SportsScoreStrip
    private var scoreStripOpen = false
    private var scoreStripEntryPressed = false
    private var scoreStripLoading: String? = null
    private var scoreStripSnapshot: LiveSportsSnapshot? = null
    private var scoreStripPreferences = ""
    private var scoreStripRefreshAt = 0L
    private var watchingRedZone = false
    private var redZoneAutoPending = false
    private var watchingSports: GameStreams? = null
    private var sportsPlaybackPlan: SportsPlaybackPlan? = null
    private val sportsLookupWorkers = workers.pool("sports-lookup", threads = 2)
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
    private var sportsBrowseLeague: String? = null
    private var sportsLeaguePicker: SportsLeaguePicker? = null
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
                SportsReplays.best(choices, videoInfo, favorites)?.let { sportsPanel.confirmReplay(game, it) }
                replayDiscovery?.let { sportsPanel.updateReplayDiscovery(it.checkingKey, it.checkedKeys) }
                if (choices.isNotEmpty()) scheduleSports()
            }
        }, { checking -> if (!closed) sportsPanel.updateReplayDiscovery(checking, replayDiscovery?.checkedKeys.orEmpty()) }, load = { fallback, channel, timeout -> Catchup.load(owner(channel) ?: fallback, channel, timeout) }, canLoad = { _, channel -> owner(channel)?.let { it.enabled && it.kind == "xtream" } == true }, knownFeeds = { game -> source?.let { sportsStreamCache.replayFeeds(it.id, generation, game) }.orEmpty() }).also { replayDiscovery = it }
        discovery.update(source, generation, guide, SportsBrowseWork.targets(replayGames, sportsPanel.visibleGameKeys(), selectedGame, store.followedTeams), selectedGame, active)
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
    private var liveSportsRequest = 0
    private var liveSportsDay: java.time.LocalDate? = null
    private var liveSportsTeamScope: String? = "today"
    private var liveSportsTask: java.util.concurrent.Future<*>? = null
    private val liveSportsWorker = workers.pool("live-sports")
    private val delayedScores = SportsScoreDelay()
    private var observedScores: LiveSportsSnapshot? = null
    private var scoreDelayPlayingKey: String? = null
    private fun rememberScores(snapshot: LiveSportsSnapshot) {
        if (observedScores !== snapshot && snapshot.fresh(System.currentTimeMillis())) {
            observedScores = snapshot; delayedScores.observe(snapshot, android.os.SystemClock.elapsedRealtime())
        }
    }
    private fun scoreDelayKey(channel: Channel) = "${owner(channel)?.id.orEmpty()}:${channel.nativeId}"
    private fun presentScore(game: SportsGame): SportsGame {
        val playing = watchingSports?.game?.key ?: scoreDelayPlayingKey
        val seconds = if (game.key == playing) current?.let { store.channelScoreDelay(scoreDelayKey(it)) } ?: store.sportsScoreDelay else store.sportsScoreDelay
        return delayedScores.display(game, seconds, android.os.SystemClock.elapsedRealtime())
    }
    private val scoreDelayTick = object : Runnable {
        override fun run() {
            if (!started || closed) return
            scoreDelayPlayingKey = if (onDemand == null) playbackGame()?.game?.key else null
            if (sportsVisible && guideVisible) { sportsPanel.refreshScores(); selectedGame?.let(::renderGameInfo) }
            if (::scoreStrip.isInitialized && scoreStrip.isVisible) scoreStrip.refreshScores()
            sportsCalendar?.takeIf { it.opened }?.refreshScores()
            scheduleScoreDelay()
        }
    }
    private fun scheduleScoreDelay() {
        handler.removeCallbacks(scoreDelayTick)
        if (!started || closed || store.sportsScoreDelay == 0 && (current?.let { store.channelScoreDelay(scoreDelayKey(it)) } ?: 0) == 0) return
        scoreDelayPlayingKey = if (onDemand == null) playbackGame()?.game?.key else null
        if (sportsVisible && guideVisible || ::scoreStrip.isInitialized && scoreStrip.isVisible || sportsCalendar?.opened == true) handler.postDelayed(scoreDelayTick, 1000)
    }
    private fun scoreDelayChanged() {
        scoreStripPreferences = ""; scoreDelayPlayingKey = if (onDemand == null) playbackGame()?.game?.key else null
        sportsPanel.refreshScores(); if (::scoreStrip.isInitialized) scoreStrip.refreshScores(); sportsCalendar?.refreshScores()
        if (sportsVisible && guideVisible) selectedGame?.let(::renderGameInfo)
        scheduleScoreDelay()
    }
    private val reminderWorker = workers.pool("reminder")
    private fun publishSportsSchedule(snapshot: LiveSportsSnapshot, day: java.time.LocalDate?, request: Int, cached: Boolean = false) {
        if (snapshot.events.isEmpty() && snapshot.leagues.isEmpty()) return
        handler.post {
            if (!closed && request == liveSportsRequest && liveSportsDay == day && (!cached || liveSports.leagues.isEmpty())) {
                liveSports = liveSports.mergeRefresh(snapshot); rememberScores(liveSports); scheduleSports(); updateScoreStrip()
            }
        }
    }
    internal var liveSportsLoader: () -> LiveSportsSnapshot = {
        val day = liveSportsDay; val request = liveSportsRequest; val file = "sports-schedule-${day ?: "default"}.enc"
        // Disk restore and persistence must never hold up the first scoreboard reply.
        sportsScheduleDisk.execute {
            runCatching { (store.read(file) ?: if (day == null) store.read("sports-schedule.enc") else null)?.let { SportsSchedule.from(org.json.JSONObject(it.toString(Charsets.UTF_8))) } }.getOrNull()
                ?.let { publishSportsSchedule(it, day, request, cached = true) }
        }
        val followed = if (liveSportsTeamScope in setOf("today", "live")) emptySet() else store.followedTeams + listOfNotNull(liveSportsTeamScope?.takeIf { it in SportsCatalog.byId })
        SportsSchedule.load(followed, requestedDate = day, enabledLeagues = store.sportsLeagues, priorityLeague = sportsBrowseLeague,
            partial = { publishSportsSchedule(it, day, request) }).also { snapshot ->
            if (snapshot.leagues.isNotEmpty() && !closed) sportsScheduleDisk.execute { runCatching { store.write(file, SportsSchedule.json(snapshot).toString().toByteArray()) } }
        }
    }

    private val pollLiveSports = Runnable { if (started && (sportsVisible && guideVisible || ::scoreStrip.isInitialized && scoreStrip.isVisible)) { checkLiveSports(); scheduleSports() } }
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
    private lateinit var logos: LogoLoader
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
    private var pendingCategory: GuideSection? = null
    private val previewCategory = Runnable {
        val section = pendingCategory
        pendingCategory = null
        if (section != null && store.guideAutoCategories && guideVisible && !sportsVisible && started &&
            settingsPanel?.opened != true && !library.opened && !searchPanel.isVisible && sidebar.findFocus()?.tag == section.key)
            selectGuideCategory(section, enter = false)
    }
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
    private lateinit var playlistSearch: PlaylistSearch
    private lateinit var searchPanel: PlaylistSearchPanel
    private var searchedDemand = emptyList<OnDemand>()
    private var searchLoading = false
    private var searchedLive = emptyList<PlaylistLiveSearch.Match>()
    private var searchedMedia = emptyList<OnDemand>()
    private var liveSearchBusy = false
    private var liveSearchEpoch = 0
    private var liveSearchTask: java.util.concurrent.Future<*>? = null
    private val liveSearchWorker = workers.pool("live-search", background = true)
    private var searchWarning = ""
    private var vodRequest: VodStart.Request? = null
    private lateinit var vodStartPanel: VodStartPanel
    private var vodMode = "movie"
    private var vodReturnGuide = false
    private var vodEpisodeResolving = false
    private var vodSeriesChoice: OnDemand? = null
    private lateinit var details: PlaybackDetails
    private val vodAvailability = VodAvailability()
    private val updateVodDetails = object : Runnable {
        override fun run() { if (!closed && started && details.isVisible) refreshDetails() }
    }
    private lateinit var retryPanel: LinearLayout
    private var sources = emptyList<Source>()
    private var source: Source? = null
    private lateinit var playlists: PlaylistCatalog
    private var browsePlaylist: String? = null
    private val playlistVersions = mutableMapOf<String, Int>()
    private val playlistMetadata = mutableSetOf<String>()
    private val playlistLoading = mutableSetOf<String>()
    private val additionalLaunch = mutableSetOf<String>()
    private val playlistFilters = mutableMapOf<String, GuideFilters>()
    private val providerOrders = mutableMapOf<String, List<String>>()
    private data class GroupCache(val channels: List<Channel>, val order: List<String>, val usa: Boolean, val groups: List<String>)
    private val playlistGroups = mutableMapOf<String, GroupCache>()
    private fun groupsFor(selected: Source): List<String> {
        val channels = providerViews[selected.id]?.channels.orEmpty(); val order = providerOrders[selected.id].orEmpty(); val usa = store.usaFirst(selected)
        playlistGroups[selected.id]?.takeIf { it.channels === channels && it.order == order && it.usa == usa }?.let { return it.groups }
        val groups = CategoryOrder.provider(channels.filter { !it.media }.map { it.group }, order).let { if (usa) CategoryOrder.usaFirst(it) else it }
        playlistGroups[selected.id] = GroupCache(channels, order, usa, groups); return groups
    }
    private val providerViews = mutableMapOf<String, Guide>()
    private fun playlistId(channel: Channel) = channel.playlistId.ifBlank { if (::playlists.isInitialized) playlists.legacySource else source?.id.orEmpty() }
    private fun owner(channel: Channel?): Source? = if (channel == null) source else sources.firstOrNull { it.id == playlistId(channel) }
    private fun owner(item: OnDemand): Source? = if (item.playlistId.isBlank()) source else sources.firstOrNull { it.id == item.playlistId }
    private fun enabledSources() = sources.filter { it.enabled }
    private fun playbackSource() = onDemand?.let(::owner) ?: owner(current)
    private fun channelLabel(channel: Channel) = channel.name + if (enabledSources().size > 1) " · ${owner(channel)?.name.orEmpty()}" else ""
    private fun allowed(channel: Channel) = owner(channel)?.let { it.enabled && Network.allowed(channel.url, it.allowHttp) } == true
    private val programmeIndexWorker = workers.pool("programme-index", background = true)
    private val programmeIndexBuilder = GuideProgrammeIndex.Builder()
    @Volatile private var programmeIndex: GuideProgrammeIndex? = null
    private val programmeIndexUpdates = LatestTask<Guide>(programmeIndexWorker) { snapshot ->
        val ready = programmeIndexBuilder.prepare(snapshot, System.currentTimeMillis())
        handler.post {
            if (!closed && ready.isFor(guide)) {
                programmeIndex = ready
                if (::searchPanel.isInitialized && searchPanel.isVisible && query.isNotBlank()) searchLivePlaylist()
            }
        }
    }
    private val prepareProgrammeIndex = Runnable { if (!closed) programmeIndexUpdates.submit(guide) }
    private var guide = Guide.EMPTY
        set(value) {
            val changed = field.channels !== value.channels || field.programmes !== value.programmes
            field = value
            if (changed) {
                handler.removeCallbacks(prepareProgrammeIndex)
                handler.postDelayed(prepareProgrammeIndex, 150)
            }
        }
    private var channelIndex = GuideChannelIndex()
    private data class SidebarPlaylistShape(val id: String, val name: String, val groups: List<String>, val visible: Set<String>, val collapsed: Boolean, val loading: Boolean)
    private var sidebarShape: Any? = null
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
    private val localWorker = workers.pool("local", background = true)
    private var localTask: java.util.concurrent.Future<*>? = null
    private val localStations by lazy { LocalChannels(assets.open("local-stations.tsv").bufferedReader().use { it.readText() }) }
    private lateinit var guideTabs: LinearLayout
    private lateinit var allChannelsTab: Button
    private lateinit var localTab: Button
    private lateinit var catchupTab: Button
    private var filters = GuideFilters()
    private var sportsFilters = GuideFilters()
    private var sportsQualityChanged = false
    private var videoInfo = mutableMapOf<String, VideoInfo>()
    private var videoSavePending = false
    private val saveVideoReadings = Runnable {
        videoSavePending = false
        val saved = videoInfo.toMap(); val owners = sources.toList()
        if (::playlists.isInitialized) cacheWrites.execute { owners.forEach { selected -> store.saveVideoInfo(selected, saved.filterKeys { playlists.belongs(selected, it) }.mapKeys { playlists.rawKey(selected.id, it.key) }) } }
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
    private val streamHealth = StreamHealth()
    private val playbackJournal = PlaybackJournal()
    private var performanceOverlay: PerformanceOverlay? = null
    private fun syncPerformanceOverlay() {
        if (!started || !store.performanceOverlay) {
            performanceOverlay?.close()
            performanceOverlay = null
        } else if (performanceOverlay == null) {
            performanceOverlay = PerformanceOverlay(this, player, video.videoSurfaceView!!, {
                PerformanceOverlay.VideoReading(expectedMediaId, frameRates.requestedRate, lastVideoInfo, firstFrame, playbackDroppedFrames)
            }, playbackJournal)
        }
    }
    private var journalSampleAt = 0L
    private fun journal(event: PlaybackJournal.Event, dropped: Int = 0, code: Int? = null) {
        val runtime = Runtime.getRuntime()
        playbackJournal.record(event, android.os.SystemClock.elapsedRealtime(), player.totalBufferedDuration,
            runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), if (multiview.active) multiview.streamCount else 1, dropped,
            code, player.currentLiveOffset.takeIf { onDemand == null && it >= 0 }, player.isLoading)
    }
    private var playbackRebuffers = 0
    private var playbackReconnects = 0
    private var playbackLoadErrors = 0
    private var playbackDroppedFrames = 0L
    private var playbackPhase = PlaybackPhase.OPENING
    private var startupFailures = StartupFailures()
    private var reconnects = ReconnectBudget()
    private val playbackProgress = PlaybackProgress()
    private val sportsRecovery = SportsBufferRecovery()
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
            val monotonic = android.os.SystemClock.elapsedRealtime()
            if (onDemand == null && !multiview.active) current?.let { streamHealth.observe(it.id, monotonic, active && state == Player.STATE_READY) }
            if (monotonic - journalSampleAt >= 5000) { journalSampleAt = monotonic; journal(PlaybackJournal.Event.SAMPLE) }
            val frame = if (!multiview.active && player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) progressFrameUs.takeIf { progressFrameToken == playbackToken } ?: Long.MIN_VALUE else null
            if (recoverBufferedSports(monotonic, state == Player.STATE_BUFFERING)) return
            if (playbackProgress.stalled(playbackToken, android.os.SystemClock.elapsedRealtime(), player.currentPosition, frame, state == Player.STATE_BUFFERING, active, bufferedMs = player.totalBufferedDuration)) {
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
    private val filterSearch = Runnable {
        query = guideSearch.query()
        if (searchPanel.isVisible) updatePlaylistSearch() else applyFilter()
    }
    private val hideDetails = object : Runnable {
        override fun run() { if (details.isVisible && details.checkingVod) handler.postDelayed(this, 1000) else closeDetails() }
    }
    private val workingUrls = mutableMapOf<String, String>()
    private val retry = Runnable { if (started && (current != null || onDemand != null)) playAttempt(PlaybackPhase.RECONNECTING) }
    private val revealBuffering = Runnable { if (started && (current != null || onDemand != null) && player.playbackState == Player.STATE_BUFFERING) showPlaybackLoading(PlaybackPhase.BUFFERING) }
    private val startupTimeout = Runnable {
        if (started && (current != null || onDemand != null) && !firstFrame) {
            performanceOverlay?.sports?.playing?.outcome("startup timeout")
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
        logos = LogoLoader(160, 12 * 1024 * 1024, GuideLogoFiles(this))
        configureAppearance(); GuideClock.use24Hour = store.clock24; sportsTeam = "today"
        buildUi()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        providerFactory = DataSource.Factory { if (onDemand == null) LiveProviderDataSource { playbackSource()?.allowHttp == true } else ProviderDataSource { playbackSource()?.allowHttp == true } }
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
        multiview = Multiview(root, video, player, ::button, providerFactory, store, { channel -> tune(channel); showGuide(false) }, { showGuide(true) }, sourceFor = { owner(it) }, currentChannel = { current }).also { views ->
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
            if (!multiview.active) frameRates.observe(info.fps, info.evidence in setOf("input timestamps", "SPS timing"))
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
                if (onDemand == null && !multiview.active) current?.let { streamHealth.observe(it.id, android.os.SystemClock.elapsedRealtime(), isPlaying && firstFrame) }
                syncPlaybackMonitoring()
                if (onDemand != null) { refreshDemandControls(); handler.removeCallbacks(hideDemandControls); if (isPlaying && demandControls.isShown) handler.postDelayed(hideDemandControls, 8000) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { syncPlaybackMonitoring() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (started && player.currentMediaItem?.mediaId == expectedMediaId) journal(PlaybackJournal.Event.DISCONTINUITY, code = reason)
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { syncPlaybackMonitoring() }
            override fun onTracksChanged(tracks: Tracks) { applySubtitleAppearance(); reconcileCaptions() }
            override fun onCues(cueGroup: CueGroup) {
                SubtitleAppearance.render(video, cueGroup.cues, store.subtitleSize)
                if (cueGroup.cues.isNotEmpty()) { captionSawCue = true; cancelCaptionProbe() }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!started || (current == null && onDemand == null) || player.playerError !== error) return
                cancelPlaybackCallbacks()
                val http = PlaybackErrors.httpFailure(error)
                val statusCode = http?.responseCode
                performanceOverlay?.sports?.playing?.playerError(statusCode, error.errorCode)
                if (onDemand == null && hasPlayedChannel && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    if (!reconnects.take(android.os.SystemClock.elapsedRealtime())) { showPlaybackFailure(PlaybackErrors.message(error)); return }
                    playbackReconnects++; journal(PlaybackJournal.Event.LIVE_WINDOW_RECOVERY)
                    showPlaybackLoading(PlaybackPhase.RECONNECTING); player.seekToDefaultPosition(); player.prepare(); syncPlaybackMonitoring(); return
                }
                if (StartupFailures.endpointMissing(statusCode, http?.dataSpec?.uri?.toString(), playbackUrl)) {
                    onDemand?.takeIf { it.kind in setOf("movie", "episode") }?.let { item -> owner(item)?.let { library.repository.markMissing(it, item) } }
                }
                if (!hasPlayedChannel && onDemand == null) {
                    startupFailures.record(statusCode, http?.dataSpec?.uri?.toString(), playbackUrl)
                    if (statusCode !in setOf(401, 403, 429) && tryAlternate()) return
                    if (startupFailures.allEndpointsMissing()) { hideUnavailable(); return }
                }
                if (statusCode in setOf(401, 403, 429)) sportsPlaybackPlan?.accepted = true
                if (!hasPlayedChannel && onDemand == null && sportsPlaybackPlan != null) { showPlaybackFailure(PlaybackErrors.message(error)); return }
                if (StartupFailures.retryMissingLive(statusCode, hasPlayedChannel, onDemand == null)) reconnectOrFail(PlaybackErrors.message(error))
                else if (statusCode != null && statusCode in 400..499 && statusCode !in setOf(408, 429)) showPlaybackFailure(PlaybackErrors.message(error))
                else if (statusCode == 429) showPlaybackFailure(PlaybackErrors.message(error))
                else reconnectOrFail(PlaybackErrors.message(error))
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != player.playbackState) return
                if (state == Player.STATE_BUFFERING && firstFrame && hasPlayedChannel && player.playWhenReady) {
                    playbackRebuffers++; journal(PlaybackJournal.Event.BUFFERING)
                    if (onDemand == null && !multiview.active) current?.let { streamHealth.problem(it.id, android.os.SystemClock.elapsedRealtime()) }
                }
                // Rebuffering only defers new Sports searches (prefetchSportsStream waits for READY).
                // Cancelling the one in flight restarted it on every stall, so slow devices never
                // finished matching a game.
                if (state == Player.STATE_BUFFERING) playbackWarmup.reset()
                if (state == Player.STATE_READY && sportsVisible && guideVisible && !sportsPanel.watching) queueSportsPrefetch()
                syncPlaybackMonitoring(); syncReplayDiscovery()
                if (state == Player.STATE_ENDED && started && onDemand != null) { saveDemandProgress(ended = true); showDemandControls() }
                else if (state == Player.STATE_ENDED && started && current != null) reconnectOrFail("The provider ended this stream. Try another channel or Retry. (STREAM_ENDED)")
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (started && player.currentMediaItem?.mediaId == expectedMediaId) {
                    playbackDroppedFrames += droppedFrames.coerceAtLeast(0)
                    journal(PlaybackJournal.Event.DROPPED_FRAMES, droppedFrames)
                }
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                if (!wasCanceled && !eventTime.timeline.isEmpty && eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId == expectedMediaId) {
                    playbackLoadErrors++; journal(PlaybackJournal.Event.LOAD_ERROR, code = PlaybackErrors.httpFailure(error)?.responseCode)
                }
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.timeline.isEmpty || eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != expectedMediaId) return
                performanceOverlay?.sports?.playing?.firstFrame(expectedMediaId)
                firstFrame = true; hasPlayedChannel = true; backgroundPlaybackReady(); journal(PlaybackJournal.Event.FIRST_FRAME)
                sportsPlaybackPlan?.takeIf { it.channel?.id == current?.id }?.let { plan ->
                    // A working stream is accepted immediately; quality never triggers another connection.
                    plan.accepted = true
                }
                if (pendingSportsTune == current?.id && pendingSportsTune != null) { pendingSportsTune = null; sportsPanel.finishTuning(); if (sportsVisible && guideVisible && sportsPlaybackPlan?.backgroundRecovery != true) showGuide(false) }
                if (onDemand == null) lastWorkingChannel = current
                if (onDemand == null) current?.let { channel -> owner(channel)?.let { store.setLast(it, channel) }; store.recentFeeds = listOf(channel.id) + store.recentFeeds; workingUrls[channel.id] = playbackUrl; if (unavailable.restore(channel.id)) { saveUnavailable(); applyFilter() } }
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
        val selected = sources.firstOrNull { it.enabled && it.id == store.active } ?: sources.firstOrNull { it.enabled }
        if (selected == null) { status.text = if (sources.isEmpty()) "Add a playlist in Settings to start watching" else "Enable a playlist in Settings to start watching"; if (sources.isEmpty()) handler.post { editSource(null) } }
        else load(selected)
        PlaylistRefresh.listen(this) { selectedSource, result ->
            if (!closed && selectedSource.enabled && sources.any { it == selectedSource } && selectedSource !in ownRefreshes) {
                if (started) result.guide?.let { receivePlaylist(selectedSource, it) } else if (source == selectedSource) deferredRefresh = result
            }
        }
        RefreshJobs.configure(this, sources); handler.post(refreshTick)
        handler.post(minuteTick)
        updater.check(startup = true)
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
        allChannelsTab = button("All channels") { browsePlaylist = null; localOnly = false; group = null; favoritesOnly = false; applyFilter(); focusChannelAt(0) }.apply { tag = "guide-all" }
        localTab = button("Local") {
            if (store.localLocation.isBlank()) { playbackMenu(); settingsPanel?.selectSetting("Local channels") }
            else { browsePlaylist = null; localOnly = true; group = null; favoritesOnly = false; applyFilter(); if (localLoading && adapter.itemCount == 0) localTab.requestFocus() else focusChannelAt(0) }
        }.apply { tag = "guide-local" }
        catchupTab = button("Catch-up") { openCatchup() }.apply { tag = "guide-catchup"; contentDescription = "Browse past programmes on catch-up channels"; visibility = View.GONE }
        guideTabs.addView(allChannelsTab); guideTabs.addView(localTab); guideTabs.addView(catchupTab)
        localProgress = ProgressBar(this).apply { tag = "local-matching-progress"; isIndeterminate = true; visibility = View.GONE; contentDescription = "Finding local channels" }
        guideTabs.addView(localProgress, LinearLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8) })
        listOf(allChannelsTab, localTab, catchupTab).forEach { tab -> tab.setOnKeyListener { _, key, event -> when (key) {
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
        sidebarAdapter = SidebarAdapter(this, { selectGuideCategory(it, enter = true) }, {
            if (store.guideAutoCategories) sidebarAdapter.focusedSection()?.takeUnless { it.header }?.let { selectGuideCategory(it, enter = false) }
            focusChannel()
        }, { openNavigation() }, { if (localOnly) localTab.requestFocus() else allChannelsTab.requestFocus() }, ::focusCategoryAt, ::highlightGuideCategory)
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
        adapter = GuideAdapter(this, logos, { owner(it) }, { guide }, { favorites }, { guideTimeline.start(it.id) },
            { guideSearch.hideKeyboard(); if (::multiview.isInitialized && multiview.browsing) startMultiview(it) else {
                val playing = it.id == current?.id && hasPlayedChannel && player.playerError == null
                if (!playing) tune(it)
                if (store.guideOpenFullscreen || playing) showGuide(false)
            } }, { channel ->
                toggleFavorite(channel)
            }, ::focusGuideRow, { channel, direction -> moveTime(channel, direction) }, { focusSidebar() }, { id, direction, repeating ->
                lastGuideRepeat = if (repeating) android.os.SystemClock.uptimeMillis() else -1L
                val index = adapter.positionOf(id)
                if (index == 0 && direction < 0 && guideTimeline.ahead(id)) { ++focusEpoch; nowButton.requestFocus() }
                else if (index >= 0 && adapter.channels.isNotEmpty()) focusChannelAt(if (store.guideLoopChannels) Math.floorMod(index + direction, adapter.channels.size) else (index + direction).coerceIn(adapter.channels.indices))
            })
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleEpg() }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { if (dy != 0) epgScrollDown = dy > 0 }
        })
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        sportsPanel = SportsPanel(this, ::button, ::sportsTeams, { team ->
            ++sportsMatchEpoch; sportsMatchTask?.cancel(true); clearSportsPlayback()
            val previousDate = SportsSchedule.selectedDate(sportsTeam); sportsTeam = team; store.sportsTeam = team.orEmpty(); if (team in SportsCatalog.byId || SportsSchedule.selectedDate(team) != previousDate) checkLiveSports(force = true); sportsPanel.loading("Checking live games…"); scheduleSports()
        }, ::selectGame, ::showGameInfo, ::openSportsQuality, { focusSidebar() })
        sportsPanel.chooseDate = ::openSportsCalendar
        sportsPanel.leagueSelected = ::selectSportsLeague
        sportsPanel.cardsReady = { queueSportsPrefetch(); syncReplayDiscovery() }
        sportsPanel.scorePresentation = ::presentScore
        sportsPanel.streamSource = ::owner; sportsPanel.streamFavorites = { favorites }
        sportsPanel.watchChannel = { channel ->
            if (::multiview.isInitialized && multiview.browsing) startMultiview(channel) else {
                val isRedZone = sportsPanel.redZoneResults
                val game = sportsResults?.takeIf { result -> result.channels.any { it.id == channel.id && it.url == channel.url } }
                val traces = performanceOverlay?.sports
                val trace = if (traces != null && game != null) traces.forGame(game.game.key) else null
                clearSportsPlayback()
                if (channel.id != current?.id || !hasPlayedChannel || player.playbackState != Player.STATE_READY || player.playerError != null) tune(channel, trace)
                else trace?.finish("already playing")
                watchingSports = game; watchingRedZone = isRedZone; showGuide(false)
            }
        }
        sportsPanel.secondChannel = ::startMultiview
        sportsPanel.hasReminder = reminders::has
        sportsPanel.toggleReminder = { game ->
            runCatching { reminders.toggle(game) }.onSuccess { enabled -> saveGameReminders(); reminderCheckedAt = 0; toast(if (enabled) "Reminder set · ${GuideClock.sportsDate(game.start)}" else "Reminder removed") }.onFailure { toast("Up to 64 upcoming game reminders can be saved") }
        }
        sportsPanel.watchBest = { selectGame(it, autoWatch = true) }
        sportsPanel.playOnSelection = { store.sportsAutoPlay && !(::multiview.isInitialized && multiview.browsing) }
        sportsPanel.findRedZone = { findRedZone(false) }
        sportsPanel.watchRedZone = { findRedZone(true) }
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
        gameWatchPanel = GameWatchPanel(this, ::button, ::cancelReminderWatch)
        root.addView(gameWatchPanel, FrameLayout.LayoutParams(-1, -1))
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
        }, { tracks(C.TRACK_TYPE_TEXT) }, { current?.let { openCatchup(it) } }, { closeDetails(); current?.let(::startMultiview) }, { channel -> closeDetails(); tune(channel); showGuide(false) }, { channel -> closeDetails(); startMultiview(channel) }, ::showOtherGameStreams, ::watchVodFromBeginning, ::openScoreStrip)
        root.addView(details, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        scoreStrip = SportsScoreStrip(this, ::button, ::selectOverlayGame,
            { cancelScoreTune(); scoreStripOpen = false; root.isFocusableInTouchMode = true; root.requestFocus(); syncScoreStrip() },
            { store.scoreOverlayPinned = false; cancelScoreTune(); scoreStripOpen = false; scoreStrip.suspend(); root.isFocusableInTouchMode = true; root.requestFocus() }).also { it.scorePresentation = ::presentScore }
        root.addView(scoreStrip, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(18); topMargin = dp(18) })
        demandControls = OnDemandControls(this, ::button, { if (player.playbackState == Player.STATE_ENDED) { demandPosition = 0; demandPaused = false; playAttempt() } else { demandPaused = player.playWhenReady; player.playWhenReady = !demandPaused }; showDemandControls() },
            { position -> seekDemand(position, absolute = true) }, { seekDemand(0, absolute = true) }, { tracks(C.TRACK_TYPE_TEXT) }, { tracks(C.TRACK_TYPE_AUDIO) },
            { onDemand?.let { library.next(it) }?.let { playOnDemand(it, 0) } }, { reopenLibrary() }, ::returnToLive, ::stopDemandToLibrary)
        root.addView(demandControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(16) })
        library = LibraryController(this, store, ::button, { sources.firstOrNull { it.id == browsePlaylist && it.enabled } ?: source }, { guide }, ::playOnDemand, ::closeLibrary, { id -> sources.firstOrNull { it.id == id && it.enabled } }, { channel -> owner(channel) })
        library.panel.navigation = { ++focusEpoch; (if (library.browsingCatchup) guideMode else libraryButton).requestFocus() }
        library.catchupClosed = ::returnToLiveChannel
        library.panel.globalSearch = ::searchWithQuery
        library.catalogChanged = { if (details.isVisible) refreshDetails() }
        library.refreshCatalogs = { mode -> library.currentSource?.let { playlistSearch.refresh(it, mode) } }
        guidePanel.removeView(guideSearch)
        searchPanel = PlaylistSearchPanel(this, guideSearch, ::button, ::openNavigation).apply {
            backgroundContent = guidePanel
            resultChosen = { chosen -> if (chosen.isNotBlank()) store.recentSearches = listOf(chosen) + store.recentSearches; recent = store.recentSearches }
        }
        root.addView(searchPanel, FrameLayout.LayoutParams(-1, -1))
        vodStartPanel = VodStartPanel(this, ::button, { closeVodStart() }, {
            val series = vodSeriesChoice
            closeVodStart(restore = false)
            if (series == null) openLibrary() else { library.openSearchResult(series); root.bringChildToFront(library.panel); showNavigation("library") }
        }, { vodMode = "series"; requestVodStart() }, { requestVodStart() }).apply { backgroundContent = guidePanel }
        root.addView(vodStartPanel, FrameLayout.LayoutParams(-1, -1))
        playlistSearch = PlaylistSearch(store, library.repository, ::enabledSources) { items, busy, warning ->
            if (started && vodRequest != null && vodStartPanel.isVisible) renderVodStart(items, busy, warning)
            else if (started && searchPanel.isVisible) { searchedDemand = items; searchLoading = busy; searchWarning = warning; renderPlaylistSearch() }
        }
        playlistSearch.stateChanged = { if (details.isVisible) refreshDetails() }
        library.archiveLoaded = { selected, channel, entries ->
            if (owner(channel)?.id == selected.id) {
                source?.let { replayDiscovery?.remember(it, generation, guide, mapOf(channel.id to entries)) }
                val key = guide.key(channel)
                // A provider archive belongs to one stream. Do not spread it to shared XMLTV variants.
                if (key == channel.id || guide.channels.count { guide.key(it) == key } == 1) {
                    guide = guide.copy(programmes = guide.programmes + (key to GuideHistory.merge(guide.programmes[key].orEmpty(), entries, channel.catchupDays, System.currentTimeMillis())))
                    cancelEpgSave(); handler.postDelayed(cacheEpg, 30000)
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
    private fun openLibrary() = openBrowser(false)
    private fun openCatchup(channel: Channel? = null) = openBrowser(true, channel)
    private fun openBrowser(archive: Boolean, channel: Channel? = null) {
        showGuide(false); nowPlaying.hide(immediate = true); demandControls.visibility = View.GONE
        root.bringChildToFront(library.panel)
        if (settingsPanel?.opened == true) settingsPanel?.visibility = View.GONE
        if (archive) { if (channel != null) library.openCatchup(channel, direct = true) else library.open("catchup") } else { library.selectPlaylist(sources.firstOrNull { it.id == browsePlaylist && it.enabled } ?: source); library.open(focusContent = true) }
        showNavigation(if (archive) "guide-mode" else "library")
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
        val selected = owner(item) ?: return
        if (!selected.enabled || !Network.allowed(item.url, selected.allowHttp)) { toast("This title needs an enabled playlist with HTTP or HTTPS playback allowed"); return }
        performanceOverlay?.sports?.tune(null)
        performanceOverlay?.tune()
        playlistSearch.pause(cancel = true)
        saveDemandProgress(); closeDetails(); cancelPlaybackCallbacks()
        watchingRedZone = false; onDemand = item.copy(playlistId = selected.id); demandPosition = position; demandPaused = false; demandSubtitleMode = null
        hasPlayedChannel = false; firstFrame = false; lastVideoInfo = null; alternateUsed = false; reconnects = ReconnectBudget()
        playbackUrl = item.url; retryPanel.visibility = View.GONE
        player.volume = 1f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build(); applyCaptionPreference(); applySubtitleAppearance()
        showGuide(false); if (started) playAttempt(); showDemandControls()
        handler.removeCallbacks(demandTick); handler.postDelayed(demandTick, 1000)
    }
    private fun saveDemandProgress(ended: Boolean = false) {
        val item = onDemand ?: return; val selected = owner(item) ?: return
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
        demandControls.update(item, player.currentPosition, player.duration, player.isPlaying, player.isCurrentMediaItemSeekable, library.next(item) != null, player.playbackState == Player.STATE_ENDED, lastVideoInfo?.label().orEmpty())
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
        val selected = owner(channel) ?: return
        store.toggleFavorite(selected, channel)
        favorites = if (channel.id in favorites) favorites - channel.id else favorites + channel.id
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
    private fun openScoreStrip() {
        if (current == null || onDemand != null || multiview.active) return
        closeDetails(); store.scoreOverlayPinned = true; scoreStripOpen = true; syncScoreStrip(); scoreStrip.enter()
    }
    private fun suspendSportsOverlay() {
        if (!::scoreStrip.isInitialized) return
        cancelScoreTune(); scoreStripOpen = false; scoreStripEntryPressed = false; scoreStrip.suspend()
    }
    private fun selectOverlayGame(game: SportsGame) {
        if (game.state == "post") {
            selectSportsLeague(game.leagueId)
            suspendSportsOverlay(); sportsVisible = true; clearGuideSearch(); applyFilter(); showGuide(true)
            showGameInfo(game); sportsPanel.openGameActions(game)
        } else {
            scoreStripLoading = game.key; scoreStrip.tuning(game.key, "Finding stream…"); selectSportsGame(game, true, fromScores = true)
        }
    }
    private fun cancelScoreTune() {
        if (scoreStripLoading != null) { ++sportsMatchEpoch; sportsMatchTask?.cancel(true); scoreStripLoading = null }
        if (::scoreStrip.isInitialized) scoreStrip.tuning(null)
    }
    private fun syncScoreStrip() {
        scheduleScoreDelay()
        if (!::scoreStrip.isInitialized || !::library.isInitialized) return
        val show = started && !closed && !guideVisible && current != null && onDemand == null && !multiview.active && !details.isVisible &&
            settingsPanel?.opened != true && sportsManager?.opened != true && sportsCalendar?.opened != true && !library.opened && !searchPanel.isVisible && !vodStartPanel.isVisible && !retryPanel.isVisible &&
            !reminderBanner.isVisible && !gameWatchPanel.isVisible && (scoreStripOpen || store.scoreOverlayPinned)
        if (!show) { if (scoreStrip.isVisible) { cancelScoreTune(); scoreStripOpen = false; scoreStrip.suspend() }; return }
        val wasVisible = scoreStrip.isVisible
        val gravity = if (store.scoreOverlayPosition == "Top") Gravity.TOP else Gravity.BOTTOM
        val layout = scoreStrip.layoutParams as FrameLayout.LayoutParams
        if (layout.gravity != gravity) { layout.gravity = gravity; scoreStrip.layoutParams = layout }
        scoreStrip.present()
        if (!wasVisible) root.bringChildToFront(scoreStrip)
        if (gravity == Gravity.BOTTOM) nowPlaying.hide(immediate = true)
        updateScoreStrip(); checkLiveSports()
    }
    private fun updateScoreStrip() {
        if (!::scoreStrip.isInitialized || !scoreStrip.isVisible) return
        rememberScores(liveSports)
        val now = System.currentTimeMillis(); val leagues = store.sportsLeagues; val followed = store.followedTeams
        val preferences = leagues.sorted().joinToString() + followed.sorted().joinToString() + watchingSports?.game?.key.orEmpty() + liveSportsChecking + liveSportsDay + store.hideCompletedScores
        if (scoreStripSnapshot === liveSports && scoreStripPreferences == preferences && now < scoreStripRefreshAt) return
        scoreStripSnapshot = liveSports; scoreStripPreferences = preferences; scoreStripRefreshAt = now + 30000
        val games = if (liveSportsDay == null) SportsScoreStrip.todayGames(liveSports, leagues, followed, now) else emptyList()
        scoreStrip.submit(games, watchingSports?.game?.key, liveSportsChecking || liveSportsDay != null, store.hideCompletedScores)
    }
    private fun switchMode(sports: Boolean) {
        clearGuideSearch(); sportsVisible = sports
        if (sports) { ensureSportsLeague(); sportsPanel.loading("Finding live games…") }
        applyFilter(); showGuide(true)
        if (sports && !store.sportsLeaguesChosen) openSportsLeaguePicker()
    }
    private fun moveTime(channel: Channel?, amount: Int) {
        channel ?: return
        guideTimeline.move(channel.id, amount); focused = channel; updateTimes(); adapter.refreshRows(); showInfo(channel); scheduleEpg()
    }
    private fun resetTime() {
        guideTimeline.reset((focused ?: current)?.id); updateTimes(); adapter.refreshRows(); (focused ?: current)?.let(::showInfo); scheduleEpg()
    }
    private var lastGuideRepeat = -1L
    private val settledGuideHeader = Runnable {
        if (started && guideVisible && !sportsVisible && list.hasFocus()) focused?.let(::showInfo)
    }
    private fun focusGuideRow(channel: Channel) {
        focused = channel
        handler.removeCallbacks(settledGuideHeader)
        val idle = android.os.SystemClock.uptimeMillis() - lastGuideRepeat
        if (lastGuideRepeat >= 0 && idle < 100) handler.postDelayed(settledGuideHeader, 100 - idle)
        else showInfo(channel)
        updateTimes()
        scheduleEpg()
    }
    private fun focusChannel() {
        if (sportsVisible) { ++focusEpoch; sportsPanel.focusGames(); return }
        val index = adapter.positionOf((focused ?: current)?.id).takeIf { it >= 0 }
            ?: adapter.positionOf(current?.id).coerceAtLeast(0)
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
    private fun cancelCategoryPreview() { handler.removeCallbacks(previewCategory); pendingCategory = null }
    private fun highlightGuideCategory(section: GuideSection, hasFocus: Boolean) {
        if (!hasFocus) { if (pendingCategory?.key == section.key) cancelCategoryPreview(); return }
        cancelCategoryPreview()
        // Playlist headers expand/collapse only on OK; browsing never changes playback.
        if (store.guideAutoCategories && !section.header) { pendingCategory = section; handler.postDelayed(previewCategory, 140) }
    }
    private fun selectGuideCategory(section: GuideSection, enter: Boolean) {
        cancelCategoryPreview()
        if (section.header) { if (enter) { store.setPlaylistCollapsed(section.playlist, !store.playlistCollapsed(section.playlist)); updateGroups() }; return }
        val playlist = section.playlist.takeIf { it.isNotBlank() }
        val category = section.category ?: section.key.takeIf { it.startsWith("group:") }?.removePrefix("group:")
        val favorite = section.key == "favorites"
        if (!enter && !localOnly && !sportsVisible && !guideSearch.isVisible && browsePlaylist == playlist && group == category && favoritesOnly == favorite) return
        val hadSearch = guideSearch.isVisible
        browsePlaylist = playlist; clearGuideSearch(); sportsVisible = false; localOnly = false; favoritesOnly = favorite; group = category
        focused = null; list.stopScroll(); (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        applyFilter(); focused = adapter.channels.firstOrNull(); focused?.let(::showInfo); updateTimes()
        if (hadSearch) showGuide(true)
        if (enter) focusChannelAt(0)
    }
    private fun openNavigation() { cancelCategoryPreview(); ++focusEpoch; pendingFocusList = null; root.bringChildToFront(navigationRail); navigationRail.focusCurrent() }
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

    private data class ProviderPreferences(val favorites: Set<String>, val quality: Map<String, VideoInfo>, val hidden: Map<String, Long>, val filters: GuideFilters)
    private fun receivePlaylist(selected: Source, raw: Guide, applied: (() -> Unit)? = null) {
        if (!selected.enabled || sources.none { it.id == selected.id && it.enabled }) return
        val epoch = generation; val version = (playlistVersions[selected.id] ?: 0) + 1; playlistVersions[selected.id] = version
        val before = guide; val needsMetadata = selected.id !in playlistMetadata; val previousView = providerViews[selected.id]
        imports.execute {
            val incoming = playlists.view(selected, raw)
            val scoped = if (previousView?.channels == incoming.channels) incoming.copy(channels = previousView.channels) else incoming
            val prefs = if (needsMetadata) ProviderPreferences(store.favorites(selected), store.videoInfo(selected), store.unavailable(selected).snapshot(), store.filters(selected)) else null
            val days = HashMap<String, Int>()
            scoped.channels.forEach { channel -> if (channel.catchupDays > 0) { val key = scoped.key(channel); days[key] = maxOf(days[key] ?: 0, channel.catchupDays) } }
            val at = System.currentTimeMillis()
            val merged = LinkedHashMap(before.programmes)
            scoped.programmes.forEach { (key, entries) -> merged[key] = GuideHistory.merge(before.programmes[key].orEmpty(), entries, days[key] ?: 0, at) }
            handler.post {
                if (closed || epoch != generation || playlistVersions[selected.id] != version || sources.none { it.id == selected.id && it.enabled && it.providerKey() == selected.providerKey() }) return@post
                if (prefs != null && playlistMetadata.add(selected.id)) {
                    favorites = favorites + prefs.favorites.map { playlists.key(selected.id, it) }
                    videoInfo.putAll(prefs.quality.mapKeys { playlists.key(selected.id, it.key) })
                    unavailable = UnavailableChannels((unavailable.snapshot() + prefs.hidden.mapKeys { playlists.key(selected.id, it.key) }).toMutableMap())
                    playlistFilters[selected.id] = prefs.filters
                }
                val same = providerViews[selected.id]?.channels === scoped.channels
                providerViews[selected.id] = scoped; providerOrders[selected.id] = raw.categoryOrder
                val channels = if (same) guide.channels else enabledSources().flatMap { providerViews[it.id]?.channels.orEmpty() }
                if (guide.programmes !== before.programmes) guide.programmes.forEach { (key, entries) -> if (entries !== before.programmes[key]) merged[key] = entries }
                val oldFocus = focused?.id
                guide = Guide(channels, merged, providerOrders[source?.id].orEmpty())
                if (!same) {
                    current = channels.firstOrNull { it.id == current?.id } ?: current
                    focused = channels.firstOrNull { it.id == oldFocus } ?: focused
                    val snapshot = channels; val legacy = playlists.legacySource
                    imports.execute { val prepared = GuideChannelIndex().apply { prepare(snapshot, legacy) }; handler.post { if (!closed && guide.channels === snapshot) channelIndex = prepared } }
                }
                playlistLoading.remove(selected.id); updateRefreshIndicator()
                loadingPanel.visibility = View.GONE
                if (same) { adapter.refreshRows(); updateGroups() } else { groupsForChannels = null; updateGroups(); applyFilter(); if (searchPanel.isVisible) updatePlaylistSearch() }
                resumeStartupPlayback()
                if (sportsVisible && guideVisible) scheduleSports() else (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                scheduleEpg(); applied?.invoke()
                if (selected.id == source?.id) restoreAdditionalPlaylists()
            }
        }
    }
    private fun resumeStartupPlayback() {
        if (!startupPlaybackPending || !started || pausedPlayback != null || current != null || onDemand != null || guide.channels.isEmpty()) return
        val selected = source ?: return
        if (selected.id in playlistLoading || library.opened || settingsPanel?.opened == true || searchPanel.isVisible || vodStartPanel.isVisible || sportsVisible) return
        val recent = store.recentFeeds.firstNotNullOfOrNull { id -> guide.channels.firstOrNull { it.id == id && !it.media } }
        val saved = playlists.key(selected.id, store.last(selected))
        val channel = recent ?: guide.channels.firstOrNull { it.id == saved && !it.media }
            ?: providerViews[selected.id]?.channels?.firstOrNull { !it.media } ?: guide.channels.firstOrNull { !it.media } ?: return
        val enterFullscreen = lastInputAt == startupInputAt
        startupPlaybackPending = false
        tune(channel)
        if (enterFullscreen) showGuide(false)
    }
    private fun restoreAdditionalPlaylists() {
        if (additionalRestoreStarted) return
        additionalRestoreStarted = true
        enabledSources().filter { it.id != source?.id }.forEach { loadAdditionalPlaylist(it) }
    }
    private fun receiveSavedProgrammes(selected: Source, saved: Map<String, List<Programme>>, applied: () -> Unit) {
        val epoch = generation; val before = guide; val catalog = playlists
        imports.execute {
            val scoped = catalog.view(selected, Guide(emptyList(), saved)).programmes
            val days = HashMap<String, Int>()
            before.channels.forEach { channel -> val key = before.key(channel); days[key] = maxOf(days[key] ?: 0, channel.catchupDays) }
            val at = System.currentTimeMillis(); val merged = LinkedHashMap(before.programmes)
            scoped.forEach { (key, entries) ->
                // Live replies received after startup began take precedence over
                // older disk entries sharing a programme's start time.
                merged[key] = GuideHistory.merge(entries, before.programmes[key].orEmpty(), days[key] ?: 0, at)
            }
            handler.post {
                if (closed || epoch != generation || sources.none { it.id == selected.id && it.enabled && it.providerKey() == selected.providerKey() }) return@post
                if (guide.programmes !== before.programmes) guide.programmes.forEach { (key, entries) -> if (entries !== before.programmes[key]) merged[key] = entries }
                // This is an EPG-only merge: a newer lineup or the user's focus,
                // category and playback state must survive disk restoration.
                guide = guide.copy(programmes = merged)
                restoringProgrammes.remove(selected.id)
                adapter.refreshRows()
                (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                if (details.isVisible) refreshDetails()
                applied()
            }
        }
    }
    private fun loadAdditionalPlaylist(selected: Source, force: Boolean = false) {
        if (!selected.enabled || selected.id == source?.id || !::playlists.isInitialized) return
        val epoch = generation; playlistLoading.add(selected.id)
        val changed = store.bindProvider(selected)
        if (changed) { playlistMetadata.remove(selected.id); library.invalidateProvider(selected.id) }
        imports.execute {
            if (changed) store.invalidateProviderFiles(selected)
            val saved = if (force) null else runCatching { store.cached(selected) }.getOrNull()
            handler.post {
                if (closed || epoch != generation || sources.none { it == selected && it.enabled }) return@post
                if (saved != null && saved.channels.isNotEmpty()) receivePlaylist(selected, saved) { refreshAdditional(selected, launch = true) }
                else refreshAdditional(selected, force = true)
            }
        }
    }
    private fun refreshAdditional(selected: Source, launch: Boolean = false, force: Boolean = false) {
        if (launch) additionalLaunch.add(selected.id)
        if (!selected.enabled || selected.id == source?.id || !started || (!force && !bulkRefreshReady()) || (!force && PlaylistRefresh.running(selected.id))) return
        val policy = store.refreshPolicy(selected); val now = System.currentTimeMillis(); val onLaunch = selected.id in additionalLaunch
        val playlist = force || !store.epgMappingReady(selected) || policy.playlistDue(store.playlistRefreshed(selected), now, onLaunch)
        val epg = !force && (!store.fullEpgAttempted(selected) || policy.epgDue(store.epgRefreshed(selected), now, onLaunch))
        additionalLaunch.remove(selected.id)
        if (!playlist && !epg) return
        val epoch = generation; ownRefreshes.add(selected)
        PlaylistRefresh.request(this, selected, playlist, epg) { result ->
            ownRefreshes.remove(selected)
            if (!closed && epoch == generation && sources.any { it == selected && it.enabled }) {
                if (result.guide != null) receivePlaylist(selected, result.guide)
                else { playlistLoading.remove(selected.id); updateRefreshIndicator(); updateGroups(); applyFilter() }
            }
        }
    }
    private fun load(selected: Source, force: Boolean = false) {
        flushVideoReadings(); cacheEpg.run()
        if (store.catalogRoot.isBlank() || sources.none { it.id == store.catalogRoot }) {
            val prefix = "playlist:${selected.id}:"
            store.recentFeeds = store.recentFeeds.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            store.catalogRoot = selected.id
        }
        if (!::playlists.isInitialized || playlists.legacySource != store.catalogRoot) playlists = PlaylistCatalog(store.catalogRoot)
        playlists.configure(sources); providerViews.clear(); providerOrders.clear(); playlistVersions.clear(); playlistMetadata.clear(); playlistLoading.clear(); playlistFilters.clear(); playlistGroups.clear()
        playlistLoading.add(selected.id)
        browsePlaylist = selected.id
        val providerChanged = store.bindProvider(selected)
        if (providerChanged) {
            library.invalidateProvider(selected.id)
            handler.removeCallbacks(saveVideoReadings); videoSavePending = false; cancelEpgSave()
        }
        streamHealth.clear()
        clearGuideSearch()
        if (::multiview.isInitialized) multiview.close()
        if (::library.isInitialized) library.dismiss()
        saveDemandProgress(); onDemand = null; demandSubtitleMode = null; handler.removeCallbacks(demandTick); if (::demandControls.isInitialized) demandControls.visibility = View.GONE
        cachedStartup = false; deferredRefresh = null; refreshRetryAt = 0L; launchRefreshPending = false
        startupPlaybackPending = true; startupInputAt = lastInputAt; additionalRestoreStarted = false; restoringProgrammes.clear()
        stopSportsPrefetch(clear = true); clearSportsPlayback()
        val epoch = ++generation; guideTimeline.clear()
        sportsVisible = false; sportsChannels = emptyList(); selectedGame = null; sportsResults = null; watchingSports = null; replayDiscovery?.pause(); replayGames = emptyList(); sportsBackoffUntil = 0; sportsFailures = 0; sportsForcedEpg.clear()
        ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); handler.removeCallbacks(renderSports); sportsRenderPending = false
        if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; handler.removeCallbacks(filterSearch); guideSearch.hideKeyboard(); guideSearch.visibility = View.GONE; guideSearch.setQuery(""); closeDetails()
        epgWanted = emptyList(); epgCheckedAt.clear(); epgDepth.clear(); epgErrors.clear(); handler.removeCallbacks(fetchVisibleEpg); cancelEpgSave()
        epgBackgroundFor = null; epgBackgroundOrdering = null; epgBackgroundRequests = 0; epgBackgroundFailures = 0; epgBackgroundNotBefore = android.os.SystemClock.elapsedRealtime() + 45000; handler.removeCallbacks(fetchBackgroundEpg)
        pendingEpg.clear(); handler.removeCallbacks(applyEpgUpdates); epgApplyScheduled = false
        importTask?.cancel(true)
        firstFrame = false; expectedMediaId = ""; playbackUrl = ""
        cancelPlaybackCallbacks(); playbackToken++; player.stop(); frameRates.clear(); current = null; playbackLoading.visibility = View.GONE; retryPanel.visibility = View.GONE
        playlistSearch.pause(cancel = true)
        source = selected; focused = null; store.active = selected.id; favorites = emptySet()
        filters = store.filters(selected); sportsFilters = store.sportsFilters(selected).copy(minHeight = 0); videoInfo = mutableMapOf(); unavailable = UnavailableChannels(); unavailable.prune(System.currentTimeMillis()); workingUrls.clear(); lastWorkingChannel = null
        group = null; query = ""; favoritesOnly = false; guide = Guide.EMPTY; updateGroups(); applyFilter()
        status.text = "Loading ${selected.name}…"; importProgress(epoch, "Opening playlist"); showGuide(true)
        val began = Timings.now()
        importTask = imports.submit {
            if (providerChanged) store.invalidateProviderFiles(selected)
            val saved = if (force) null else runCatching { store.cachedLineup(selected) }.getOrNull()
            if (saved != null && saved.channels.isNotEmpty()) {
                ui(epoch) {
                    restoringProgrammes.add(selected.id)
                    // Apply the playable lineup before reading its larger EPG. A
                    // second receive must not invalidate this fast first apply.
                    receivePlaylist(selected, saved) {
                        Timings.since("Channels", began)
                        cachedStartup = true
                        status.text = "Loading saved programme guide…"
                        imports.execute {
                            val programmes = runCatching { store.cachedProgrammes(selected) }.getOrDefault(emptyMap())
                            val savedReplays = runCatching { SportsReplayHistory.decode(store.read("sports-replays-${selected.id}.enc"), saved, System.currentTimeMillis()) }.getOrDefault(emptyMap())
                            ui(epoch) {
                                savedReplays.forEach { (game, choices) -> replayCache["${selected.id}:$epoch:$game"] = System.currentTimeMillis() to choices }
                                receiveSavedProgrammes(selected, programmes) {
                                    Timings.since("Saved guide", began)
                                    status.text = store.refreshError(selected).takeIf { it.isNotBlank() }?.let { "Using saved guide · $it" }.orEmpty()
                                    scheduleEpg(); autoRefresh(selected, true)
                                    handler.removeCallbacks(prewarmLibrary); handler.postDelayed(prewarmLibrary, 20000)
                                }
                            }
                        }
                    }
                }
            } else ui(epoch) {
                refreshCurrent(selected, true, false) {
                    if (selected.kind != "xtream" || selected.epgUrl.isNotBlank()) refreshCurrent(selected, false, true) else autoRefresh(selected, true)
                }
            }
        }
    }
    private fun autoRefresh(selected: Source, launch: Boolean) {
        launchRefreshPending = launchRefreshPending || launch
        if (!started || guide.channels.isEmpty() || selected.id in restoringProgrammes || !bulkRefreshReady()) return
        val refreshLaunch = launchRefreshPending; launchRefreshPending = false
        val policy = store.refreshPolicy(selected); val now = System.currentTimeMillis()
        val playlist = !store.archiveMetadataReady(selected) || !store.epgMappingReady(selected) || policy.playlistDue(store.playlistRefreshed(selected), now, refreshLaunch)
        val epg = !store.fullEpgAttempted(selected) || policy.epgDue(store.epgRefreshed(selected), now, refreshLaunch)
        if ((playlist || epg) && !PlaylistRefresh.running(selected.id)) refreshCurrent(selected, playlist, epg)
    }
    private fun refreshCurrent(selected: Source, playlist: Boolean = true, epg: Boolean = true, after: (() -> Unit)? = null) {
        val epoch = generation
        ownRefreshes.add(selected); updateRefreshIndicator()
        PlaylistRefresh.request(this, selected, playlist, epg) { result ->
            ownRefreshes.remove(selected)
            if (closed || epoch != generation || source != selected) return@request
            // A successful download is not yet a visible lineup: receivePlaylist
            // still scopes/indexes it off-thread before dismissing startup progress.
            if (result.guide == null) { playlistLoading.remove(selected.id); updateRefreshIndicator(); loadingPanel.visibility = View.GONE; restoreAdditionalPlaylists() }
            if (!started) { deferredRefresh = result; if (result.error.isBlank()) after?.invoke(); return@request }
            if (result.guide != null) mergeRefresh(result, if (result.error.isBlank()) after else null)
            status.text = if (result.error.isBlank()) "" else "Using saved guide · ${result.error}"
            refreshRetryAt = System.currentTimeMillis() + if (result.error.isBlank()) 30000 else 15 * 60000
            if (result.guide == null && guide.channels.isEmpty()) notice("Could not load playlist", result.error.ifBlank { "Check playlist settings and Retry." })
        }
    }
    private fun mergeRefresh(result: PlaylistRefresh.Result, applied: (() -> Unit)? = null) {
        val selected = source ?: return
        result.guide?.let { receivePlaylist(selected, it, applied = applied) }
    }
    private fun ui(epoch: Int, task: () -> Unit) { handler.post { if (!closed && epoch == generation) task() } }
    private fun tune(channel: Channel) = tune(channel, null)
    private fun tune(channel: Channel, trace: SportsClickTrace?) {
        clearSleepEnded()
        startupPlaybackPending = false
        watchingRedZone = false; redZoneAutoPending = false
        scoreStripLoading = null; if (::scoreStrip.isInitialized) scoreStrip.tuning(null)
        watchingSports = watchingSports?.takeIf { result -> result.channels.any { it.id == channel.id && it.url == channel.url } }
        sportsReplayGame = null
        stopSportsPrefetch(); ++sportsMatchEpoch; sportsMatchTask?.cancel(true); sportsMatchTask = null
        clearSportsPlayback()
        val selected = owner(channel) ?: return
        if (!selected.enabled || !Network.allowed(channel.url, selected.allowHttp)) { toast("This channel needs an enabled playlist with HTTP or HTTPS playback allowed."); return }
        performanceOverlay?.sports?.tune(trace, channel.id)
        performanceOverlay?.tune()
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
        performanceOverlay?.attempt()
        playbackToken++; playbackDroppedFrames = 0; expectedMediaId = "${onDemand?.id ?: current?.id}:$playbackToken"
        performanceOverlay?.sports?.playing?.attempt(expectedMediaId)
        firstFrame = false; lastVideoInfo = null; measuredFrameRate = 0f; decoderFrameRate = 0f; scanEvidence = "unknown"; captionSawCue = false; captionScanCount = 0; cancelCaptionProbe(); showPlaybackLoading(phase)
        val itemBuilder = MediaItem.Builder().setUri(playbackUrl.toUri()).setMediaId(expectedMediaId)
        if (onDemand?.kind in setOf("movie", "episode")) itemBuilder.setTag(PlaybackLoadControl.VOD)
        if (onDemand == null) itemBuilder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        val item = itemBuilder.build()
        val media = if (playbackUrl.substringBefore('?').endsWith(".m3u8", true)) {
            HlsMediaSource.Factory(providerFactory).setAllowChunklessPreparation(!captionsEnabled()).setLoadErrorHandlingPolicy(ProviderLoadErrors(liveHlsEndpoint = playbackUrl.takeIf { onDemand == null })).setExtractorFactory(CaptionHlsExtractors(tsFlags)).createMediaSource(item)
        } else mediaFactory.createMediaSource(item)
        if (onDemand == null) player.setMediaSource(media) else player.setMediaSource(media, demandPosition.coerceAtLeast(0))
        if (!multiview.active) frameRates.begin(if (onDemand == null) videoInfo[current?.id]?.fps ?: 0f else 0f)
        journal(PlaybackJournal.Event.OPEN)
        player.prepare(); player.playWhenReady = onDemand == null || !demandPaused
        handler.postDelayed(startupTimeout, if (onDemand != null) 30000 else 15000)
    }
    private fun tryAlternate(): Boolean {
        val selected = playbackSource() ?: return false
        if (onDemand != null || hasPlayedChannel || selected.kind != "xtream" || store.streamFormat(selected) != "auto" || alternateUsed) return false
        val alternate = StreamUrls.alternate(playbackUrl) ?: return false
        performanceOverlay?.sports?.playing?.outcome("trying alternate format")
        alternateUsed = true; playbackUrl = alternate; playAttempt(PlaybackPhase.ALTERNATE)
        return true
    }
    private fun showPlaybackFailure(detail: String) {
        val trace = performanceOverlay?.sports?.playing
        trace?.outcome("failed")
        journal(PlaybackJournal.Event.FAILED)
        if (onDemand == null && !multiview.active) current?.let { streamHealth.problem(it.id, android.os.SystemClock.elapsedRealtime(), true) }
        val plan = sportsPlaybackPlan
        if (plan != null && !plan.accepted && plan.channel?.id == current?.id) {
            if (playNextSportsCandidate("Stream failed · trying another match")) return
            trace?.finish("candidates exhausted")
            cancelPlaybackCallbacks(); player.stop(); showSportsAlternatives("The automatic attempts could not open this game. Choose another stream. $detail"); return
        }
        trace?.finish("playback failed")
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
        journal(PlaybackJournal.Event.RECONNECT)
        if (onDemand == null && !multiview.active) current?.let { streamHealth.problem(it.id, android.os.SystemClock.elapsedRealtime(), true) }
        cancelPlaybackCallbacks()
        if (!reconnects.take(android.os.SystemClock.elapsedRealtime())) { showPlaybackFailure(detail); return }
        playbackReconnects++
        saveDemandProgress(); player.stop(); showPlaybackLoading(PlaybackPhase.RECONNECTING); handler.postDelayed(retry, 1500)
    }
    private fun saveUnavailable() {
        sportsStreamSummaries.clear(); val hidden = unavailable.snapshot(); val owners = sources.toList()
        if (::playlists.isInitialized) cacheWrites.execute { owners.forEach { selected -> store.saveUnavailable(selected, UnavailableChannels(hidden.filterKeys { playlists.belongs(selected, it) }.mapKeys { playlists.rawKey(selected.id, it.key) }.toMutableMap())) } }
    }
    private fun hideUnavailable() {
        val failed = current ?: return
        val trace = performanceOverlay?.sports?.playing
        trace?.outcome("unavailable")
        unavailable.hide(failed.id, System.currentTimeMillis()); saveUnavailable()
        if (sportsPlaybackPlan?.channel?.id == failed.id) {
            if (playNextSportsCandidate("Stream unavailable · trying another match")) return
            trace?.finish("candidates exhausted")
            cancelPlaybackCallbacks(); player.stop(); showSportsAlternatives("Those game streams are unavailable. Choose another result."); return
        }
        trace?.finish("stream unavailable")
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
            // Keep the discovered channel visible while its quality label is refreshed off-thread.
            if (guideVisible && sportsVisible && !sportsPanel.watching) queueSportsPrefetch()
        }
        if (::sportsPanel.isInitialized) sportsPanel.refreshStreamLabels()
        if (!videoSavePending) { videoSavePending = true; handler.postDelayed(saveVideoReadings, 5000) }
        adapter.refreshRows()
        if (!guideVisible || focused?.id == channel.id) showInfo(channel); if (details.isVisible) refreshDetails()
    }
    private fun scheduleEpg() { handler.removeCallbacks(fetchVisibleEpg); handler.postDelayed(fetchVisibleEpg, 250) }
    private fun collectVisibleEpg() {
        if (!started || onDemand != null || multiview.active || enabledSources().none { it.kind == "xtream" && it.epgUrl.isBlank() }) return
        if (!guideVisible) { epgWanted = listOfNotNull(current); pumpEpg(); return }
        if (sportsVisible && guideVisible) {
            epgWanted = (listOfNotNull(current) + sportsChannels).distinctBy { guide.key(it) }.take(5)
            pumpEpg(); return
        }
        val channels = adapter.channels
        val layout = list.layoutManager as LinearLayoutManager
        val first = if (guideVisible) layout.findFirstVisibleItemPosition().coerceAtLeast(0) else channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        val last = if (guideVisible) layout.findLastVisibleItemPosition().coerceAtLeast(first) else first
        val visible = channels.subList(first.coerceAtMost(channels.size), minOf(last + 1, first + 20, channels.size))
        val urgent = (listOfNotNull(current, focused) + visible).distinctBy { it.id }
        // Read ahead in the scroll direction, then a little behind, then the rest of this
        // category, so rows are filled before the remote reaches them.
        val below = channels.subList(minOf(last + 1, channels.size), minOf(last + 61, channels.size))
        val above = channels.subList(maxOf(0, first - 20), first.coerceAtMost(channels.size)).asReversed()
        val ahead = if (epgScrollDown) below + above else above + below
        val rest = if (channels.size <= EPG_READ_AHEAD_LIMIT) channels else emptyList()
        epgWanted = (urgent + ahead + rest).distinctBy { it.id }.take(EPG_READ_AHEAD_LIMIT)
        epgUrgent = urgent.size
        pumpEpg()
    }
    private fun pumpEpg() {
        if (closed || !started || onDemand != null || multiview.active) return
        val epoch = generation; val now = System.currentTimeMillis()
        if (sportsVisible && guideVisible && now < sportsBackoffUntil) return
        fun depth(channel: Channel) = if (sportsVisible && guideVisible) 4 else if (guideVisible && guideTimeline.ahead(channel.id)) 72 else 24
        fun needs(channel: Channel): Boolean {
            val selected = owner(channel) ?: return false
            if (selected.id in restoringProgrammes) return false
            if (selected.kind != "xtream" || selected.epgUrl.isNotBlank()) return false
            val target = if (sportsVisible && guideVisible) now else maxOf(now, guideTimeline.start(channel.id)) + 30 * 60000L
            val missing = guide.at(channel, now) == null || (!(sportsVisible && guideVisible) && (guide.programmes[guide.key(channel)].orEmpty().maxOfOrNull { it.end } ?: 0) < target)
            return (channel.id in sportsForcedEpg || missing) && (now - (epgCheckedAt[channel.id] ?: 0) >= 5 * 60000L || depth(channel) > (epgDepth[channel.id] ?: 0))
        }
        while (epgActive < 3) {
            val readAhead = now >= epgAheadPausedUntil
            val candidates = if (readAhead) epgWanted else epgWanted.take(epgUrgent.coerceAtLeast(1))
            val index = candidates.indexOfFirst { "$epoch:${it.id}" !in epgInFlight && needs(it) }
            if (index < 0) { warmGuideInBackground(epoch, now); return }
            // Keep one worker free for rows the viewer can see right now.
            if (index >= epgUrgent && epgActive >= 2) return
            val channel = candidates[index]
            val selected = owner(channel) ?: return
            startEpgRequest(channel, selected, depth(channel), epoch, now, background = false)
        }
    }
    /**
     * Fill the whole guide quietly, one request at a time and only when nothing visible is
     * waiting, so a category opened later already has programmes (and they are saved for the
     * next launch). Providers whose XMLTV download works never need this: their channels are
     * already filled, so these checks skip them.
     */
    private fun warmGuideInBackground(epoch: Int, now: Long) {
        if (epgActive > 0 || now < epgAheadPausedUntil || epgBackgroundRequests >= EPG_BACKGROUND_LIMIT) return
        if (sportsVisible && guideVisible && now < sportsBackoffUntil) return
        // A game being matched to channels gets the provider to itself.
        if (sportsMatchTask?.isDone == false || sportsWarmActive.isNotEmpty()) { handler.removeCallbacks(fetchBackgroundEpg); handler.postDelayed(fetchBackgroundEpg, 5000); return }
        // Let startup playback and the first visible rows settle before quiet background work.
        val wait = epgBackgroundNotBefore - android.os.SystemClock.elapsedRealtime()
        if (wait > 0) { handler.removeCallbacks(fetchBackgroundEpg); handler.postDelayed(fetchBackgroundEpg, wait); return }
        if (epgBackgroundFor !== guide.channels) {
            // Ranking thousands of channels is CPU work: build the order off the UI thread.
            val channels = guide.channels
            if (epgBackgroundOrdering === channels) return
            epgBackgroundOrdering = channels
            val favoriteIds = favorites; val followed = store.followedTeams
            epgOrderWorker.execute {
                // Favorites, then likely game networks (so Sports matches from the saved guide instead of
                // asking the provider per game), then everything else in guide order.
                val all = channels.filter { !it.media }
                val sports = runCatching { SportsGuide.warmChannels(all, favoriteIds, followed) }.getOrDefault(emptyList())
                val order = (all.filter { it.id in favoriteIds } + sports + all).distinctBy { it.id }
                handler.post {
                    if (!closed && epgBackgroundOrdering === channels) {
                        epgBackgroundOrdering = null
                        if (guide.channels === channels) { epgBackgroundFor = channels; epgBackgroundOrder = order; epgBackgroundCursor = 0; pumpEpg() }
                    }
                }
            }
            return
        }
        val order = epgBackgroundOrder
        while (epgBackgroundCursor < order.size) {
            val channel = order[epgBackgroundCursor++]
            val selected = owner(channel) ?: continue
            if (!selected.enabled || selected.kind != "xtream" || selected.epgUrl.isNotBlank() || selected.id in restoringProgrammes) continue
            // A running full-guide refresh will fill these channels itself.
            if (PlaylistRefresh.running(selected.id)) { handler.removeCallbacks(fetchBackgroundEpg); handler.postDelayed(fetchBackgroundEpg, 15000); epgBackgroundCursor--; return }
            if (unavailable.contains(channel.id, now) || "$epoch:${channel.id}" in epgInFlight) continue
            val key = guide.key(channel)
            val ends = guide.programmes[key].orEmpty().maxOfOrNull { it.end } ?: 0
            if (guide.at(channel, now) != null && ends >= now + 3 * 3600000L) continue
            if (now - (epgCheckedAt[channel.id] ?: 0) < 30 * 60000L) continue
            epgBackgroundRequests++
            startEpgRequest(channel, selected, 24, epoch, now, background = true)
            return
        }
    }
    private fun startEpgRequest(channel: Channel, selected: Source, requestedDepth: Int, epoch: Int, now: Long, background: Boolean) {
        val request = "$epoch:${channel.id}"; epgInFlight.add(request); epgCheckedAt[channel.id] = now; epgDepth[channel.id] = requestedDepth; sportsForcedEpg.remove(channel.id); epgActive++
        epgWorkers.execute {
            val result = runCatching { XtreamEpg.load(selected, channel, requestedDepth) }
            handler.post {
                epgActive--; epgInFlight.remove(request)
                if (!closed && epoch == generation) {
                    if (background) {
                        // Quiet work never shows errors; repeated failures pause it instead of retrying the provider.
                        if (result.isSuccess) epgBackgroundFailures = 0
                        else if (++epgBackgroundFailures >= 5) { epgBackgroundFailures = 0; epgBackgroundNotBefore = android.os.SystemClock.elapsedRealtime() + 10 * 60000L }
                        if ((result.exceptionOrNull() as? HttpFailure)?.status in setOf(401, 403, 429, 503)) epgAheadPausedUntil = System.currentTimeMillis() + 10 * 60000L
                        result.getOrNull()?.takeIf { it.isNotEmpty() && owner(channel)?.enabled == true }?.let { entries -> pendingEpg[channel.id] = EpgUpdate(channel, entries, now, background = true); scheduleEpgApply(2500) }
                    } else result.onSuccess { entries ->
                        sportsFailures = 0
                        if (entries.isNotEmpty()) {
                            if (owner(channel)?.enabled == true) { pendingEpg[channel.id] = EpgUpdate(channel, entries, now); scheduleEpgApply() }
                            epgErrors.remove(channel.id)
                        } else epgErrors[channel.id] = "Provider supplied no current programme data."
                    }.onFailure { failure ->
                        epgErrors[channel.id] = "Guide unavailable · ${SourceErrors.message(failure)}"
                        // A busy or rate-limiting provider pauses read-ahead, never the visible rows.
                        if ((failure as? HttpFailure)?.status in setOf(403, 429, 503)) epgAheadPausedUntil = System.currentTimeMillis() + 2 * 60000L
                        if (sportsVisible && guideVisible) {
                            sportsFailures++
                            if ((failure as? HttpFailure)?.status in setOf(401, 403, 429) || sportsFailures >= 3) sportsBackoffUntil = System.currentTimeMillis() + 5 * 60000L
                        }
                    }
                    if (!background && (result.isFailure || result.getOrNull().isNullOrEmpty())) {
                        if (guideVisible) adapter.refreshRows()
                        (if (guideVisible) focused ?: current else current)?.let(::showInfo)
                    }
                }
                if (!closed) { if (background) handler.postDelayed(fetchBackgroundEpg, EPG_BACKGROUND_GAP_MS) else pumpEpg() }
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
        programmeArtwork.show(if (guideVisible && !sportsVisible) art else "", owner(channel)?.allowHttp == true)
        programmeInfo.text = channelLabel(channel) + "  ·  " + quality + (programme?.let { "  ·  ${GuideAdapter.time(it.start)}–${GuideAdapter.time(it.end)}\n${enriched?.description?.ifBlank { it.description } ?: it.description}" } ?: "\n${epgErrors[channel.id] ?: if (owner(channel)?.id in restoringProgrammes) "Loading saved programme guide…" else if (epgInFlight.any { it == "$generation:${channel.id}" }) "Loading programme guide…" else "No programme information"}")
    }
    private fun applyFilter() {
        requestLocalMatches()
        val now = System.currentTimeMillis()
        val hidden = unavailable.snapshot().keys.count { unavailable.contains(it, now) && channelIndex.contains(guide.channels, it) }
        val searchTerm = ChannelSearch.key(query)
        val filtered = channelIndex.candidates(guide.channels, browsePlaylist, group).filter { channel ->
            val eligible = !channel.media && !unavailable.contains(channel.id, now) && (!localOnly || channel.id in localIds) && (!favoritesOnly || channel.id in favorites) && (browsePlaylist == null || playlistId(channel) == browsePlaylist) && (group == null || channel.group == group) &&
                (searchTerm.isEmpty() || channel.searchKey.contains(searchTerm))
            val selectedFilter = playlistFilters[playlistId(channel)] ?: filters
            eligible && (selectedFilter.groups.isEmpty() || channel.group in selectedFilter.groups)
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
        filterInfo.text = "${counted(filtered.size, "channel")} · " + listOfNotNull(selection, profileLabel, query.takeIf { it.isNotEmpty() }?.let { "Search: ${it.take(40)}" }, if (hidden > 0) "$hidden unavailable hidden" else null).joinToString(" · ")
        val loadingSelection = if (browsePlaylist != null) browsePlaylist in playlistLoading else playlistLoading.isNotEmpty()
        if (filtered.isEmpty() && loadingSelection) filterInfo.text = "Loading channels…"
        emptyInfo.visibility = if (filtered.isEmpty() && guide.channels.isNotEmpty() && !loadingSelection) View.VISIBLE else View.GONE
        emptyInfo.text = if (hidden > 0) "Unavailable channels are temporarily hidden. Retry or restore them in Settings → Temporarily hidden channels." else "No channels match. Try another category, clear Search, or choose Categories in Settings."
        if (localOnly) emptyInfo.text = if (localLoading) "Finding local channels…" else "No local stations matched. Check your city/state in Settings → Local channels and visible categories. Matches use your existing playlist."
        if (::guideTabs.isInitialized) { guideTabs.isVisible = !sportsVisible; allChannelsTab.isSelected = !localOnly; localTab.isSelected = localOnly; catchupTab.isVisible = guide.channels.any(Catchup::supported); localProgress.isVisible = localOnly && localLoading }
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
            remoteHint.text = if (sportsVisible) "↑ Filters    OK Select game    Hold OK More options    Hold Back Full screen"
                else "← Earlier / categories    → Later on this channel    Back Categories, then main menu    Hold Back Full screen    Hold OK Favorite"
            if (sportsVisible) { emptyInfo.visibility = View.GONE; scheduleSports() }
        }
    }
    private fun updateGroups() {
        if (groupsForChannels !== guide.channels || groupsForOrder !== guide.categoryOrder) {
            val provider = CategoryOrder.provider((if (enabledSources().size > 1) providerViews[source?.id]?.channels.orEmpty() else guide.channels).filter { !it.media }.map { it.group }, guide.categoryOrder)
            providerGroups = if (source?.let(store::usaFirst) == true) CategoryOrder.usaFirst(provider) else provider
            groupsForChannels = guide.channels; groupsForOrder = guide.categoryOrder
        }
        val enabled = enabledSources()
        val activeKey = when {
            sportsVisible || localOnly -> null
            favoritesOnly -> "favorites"
            enabled.size > 1 && browsePlaylist != null -> if (group == null) "playlist-all:$browsePlaylist" else "playlist-group:$browsePlaylist:$group"
            group != null -> "group:$group"
            else -> "all"
        }
        val shape: Any = if (enabled.size > 1) enabled.map { selected -> SidebarPlaylistShape(selected.id, selected.name, groupsFor(selected), playlistFilters[selected.id]?.groups.orEmpty(), store.playlistCollapsed(selected.id), selected.id in playlistLoading) }
            else listOf(providerGroups, filters.groups)
        if (shape == sidebarShape) { sidebarAdapter.activate(activeKey); return }
        sidebarShape = shape
        if (enabled.size > 1) {
            val sections = mutableListOf(GuideSection("All channels", "all", browsePlaylist == null && group == null && !favoritesOnly), GuideSection("Favorites", "favorites", favoritesOnly))
            enabledSources().forEach { selected ->
                val collapsed = store.playlistCollapsed(selected.id)
                sections.add(GuideSection((if (collapsed) "▸ " else "▾ ") + selected.name + if (selected.id in playlistLoading) " …" else "", "playlist:${selected.id}", playlist = selected.id, header = true))
                if (!collapsed) {
                    sections.add(GuideSection("  All channels", "playlist-all:${selected.id}", browsePlaylist == selected.id && group == null && !favoritesOnly, playlist = selected.id))
                    val ordered = groupsFor(selected)
                    val allowed = playlistFilters[selected.id]?.groups.orEmpty()
                    ordered.filter { allowed.isEmpty() || it in allowed }.forEach { category -> sections.add(GuideSection("  " + category.ifBlank { "Uncategorized" }, "playlist-group:${selected.id}:$category", browsePlaylist == selected.id && group == category && !favoritesOnly, selected.id, category)) }
                }
            }
            sidebarAdapter.submit(sections); sidebarAdapter.activate(activeKey); return
        }
        val visibleGroups = providerGroups.filter { filters.groups.isEmpty() || it in filters.groups }
        sidebarAdapter.submit(listOf(GuideSection("All channels", "all", !sportsVisible && !localOnly && !favoritesOnly && (group == null)),
            GuideSection("Favorites", "favorites", !sportsVisible && favoritesOnly)) + visibleGroups.map { GuideSection(if (it in setOf("Sports", "Favorites", "All channels")) "Category · $it" else it.ifBlank { "Uncategorized" }, "group:$it", !sportsVisible && !favoritesOnly && group == it && (group != null)) })
        sidebarAdapter.activate(activeKey)
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
        val returningToGuide = show && !guideVisible
        if (returningToGuide) navigationRail.prepareForContent()
        closeDetails()
        if (!show && ::searchPanel.isInitialized && searchPanel.isVisible) { searchPanel.dismiss(); playlistSearch.pause() }
        if (!show) {
            handler.removeCallbacks(settledGuideHeader)
            lastGuideRepeat = -1L
            guideSearch.hideKeyboard(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false }
        if (!show || !sportsVisible) stopSportsPrefetch()
        guideVisible = show; guidePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show && sportsVisible) sportsPanel.resumeImages() else sportsPanel.pauseImages()
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        layoutGuideSurface()
        if (show) { if (returningToGuide) focusChannel(); showNavigation(if (guideSearch.isVisible) "search" else if (sportsVisible) "sports-mode" else "guide-mode"); if (!returningToGuide) focusChannel(); if (sportsVisible) scheduleSports() }
        else { navigationRail.visibility = View.GONE; navigationRail.prepareForContent(); updateDockHint(); ++focusEpoch; showNowPlaying() }
        scheduleEpg(); syncReplayDiscovery(); syncScoreStrip()
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
        if (::scoreStrip.isInitialized && scoreStrip.isVisible && store.scoreOverlayPosition == "Bottom") return
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
        searchPanel.recent = store.recentSearches
        searchPanel.show(query); root.bringChildToFront(searchPanel); showNavigation("search")
        updatePlaylistSearch(); guideSearch.voice.requestFocus()
        if (useVoice) voiceSearch() else guideSearch.message("Speak a title or channel name, or select the field to type.")
    }
    private fun watchVodFromBeginning() {
        val programme = current?.let { guide.at(it, System.currentTimeMillis()) } ?: return
        val request = VodStart.request(programme) ?: return
        vodReturnGuide = guideVisible
        library.dismiss(); vodEpisodeResolving = false; vodSeriesChoice = null
        clearGuideSearch(); closeDetails(); showGuide(false); ++focusEpoch; pendingFocusList = null
        vodRequest = request; vodMode = if (request.episode == null) "movie" else "series"
        val matches = enabledSources().flatMap { library.repository.matches(it, request) }
        if (matches.isEmpty()) { vodRequest = null; toast("No matching VOD title is saved for this programme yet"); return }
        vodStartPanel.open(request); root.bringChildToFront(vodStartPanel); renderVodStart(matches, false, "")
    }
    private fun requestVodStart() {
        val request = vodRequest ?: return
        renderVodStart(enabledSources().flatMap { library.repository.matches(it, request) }, false, "")
    }
    private fun renderVodStart(items: List<OnDemand>, busy: Boolean, warning: String) {
        if (vodEpisodeResolving) return
        val request = vodRequest ?: return
        val found = VodStart.best(request, items)
        if (found != null) { playVodStart(found); return }
        vodStartPanel.results(VodStart.choices(request, items), busy, warning, vodMode, ::playVodStart)
    }
    private fun closeVodStart(restore: Boolean = true) {
        library.cancelStart(); vodEpisodeResolving = false; vodSeriesChoice = null
        vodRequest = null; vodStartPanel.dismiss(); playlistSearch.pause()
        if (restore) showGuide(vodReturnGuide)
    }
    private fun playVodStart(item: OnDemand) {
        val request = vodRequest ?: return
        if (item.kind != "series") { closeVodStart(restore = false); playOnDemand(item, 0L); return }
        if (vodEpisodeResolving) return
        vodEpisodeResolving = true; vodSeriesChoice = item
        vodStartPanel.openingEpisode(request.season, request.episode)
        library.resolveEpisode(item, request.season, request.episode) { result ->
            if (!started || vodRequest !== request) return@resolveEpisode
            vodEpisodeResolving = false
            result.onSuccess { episode -> closeVodStart(restore = false); playOnDemand(episode, 0L) }
                .onFailure { failure -> vodStartPanel.results(emptyList(), false, failure.message.orEmpty().ifBlank { "This episode could not be opened. Retry or browse Library." }, "series", ::playVodStart) }
        }
    }
    private fun updatePlaylistSearch() {
        if (!::searchPanel.isInitialized || !searchPanel.isVisible) return
        searchedDemand = emptyList(); searchedLive = emptyList(); searchedMedia = emptyList(); searchLoading = enabledSources().any { it.kind == "xtream" } && query.isNotBlank(); searchWarning = ""
        searchLivePlaylist()
        source?.let { playlistSearch.search(it, query, enabledSources().flatMap { selected -> library.knownTitles(selected).map { item -> item.copy(playlistId = selected.id) } }) }
    }

    private fun searchLivePlaylist() {
        liveSearchTask?.cancel(true)
        val token = ++liveSearchEpoch
        val term = ChannelSearch.key(query)
        val snapshot = guide
        val index = programmeIndex
        val legacy = playlists.legacySource
        liveSearchBusy = term.isNotBlank()
        renderPlaylistSearch()
        if (term.isBlank()) return
        liveSearchTask = liveSearchWorker.submit {
            val matches = PlaylistLiveSearch.find(snapshot, index, term, System.currentTimeMillis(), legacy)
            val (media, live) = matches.partition { it.channel.media }
            val movies = media.map { (channel) -> OnDemand("m3u:${channel.id}", channel.name, channel.group, channel.url, playlistId = channel.playlistId.ifBlank { legacy }) }
            handler.post {
                if (!closed && token == liveSearchEpoch && ChannelSearch.key(query) == term && guide.channels === snapshot.channels && searchPanel.isVisible) {
                    searchedLive = live; searchedMedia = movies; liveSearchBusy = false; renderPlaylistSearch()
                }
            }
        }
    }

    private fun renderPlaylistSearch() {
        val now = System.currentTimeMillis()
        val showPlaylist = enabledSources().size > 1
        val live = searchedLive.map { (channel, programme) ->
            val provider = owner(channel)
            val playing = programme != null && now in programme.start until programme.end
            val status = when {
                playing -> "${((programme.end - now + 59_999) / 60_000).coerceAtLeast(1)}m left"
                programme != null && programme.start > now -> "Starts ${GuideClock.time(programme.start)}"
                else -> ""
            }
            val progress = if (playing) ((now - programme.start).toDouble() / (programme.end - programme.start) * 100).toInt().coerceIn(0, 100) else -1
            PlaylistSearchChannel(channel, provider?.name.orEmpty().takeIf { showPlaylist }.orEmpty(),
                programme?.title ?: "No programme information", status, progress, provider?.allowHttp == true) {
                clearGuideSearch(); if (current?.id != channel.id || !hasPlayedChannel || player.playerError != null) tune(channel); showGuide(false)
            }
        }
        val titles = (searchedDemand + searchedMedia).map { item ->
            val key = if (item.playlistId.isBlank() || item.playlistId == playlists.legacySource) item.id else "${item.playlistId}:${item.id}"
            val provider = owner(item)
            val subtitle = listOfNotNull(item.year, item.genre, if (item.kind == "episode") "Season ${item.season} · Episode ${item.episode}" else null,
                provider?.name?.takeIf { showPlaylist }).filter(String::isNotBlank).joinToString(" · ")
            val content = LibraryPosterTile.Content(item.title, subtitle, item.poster, item.kind.uppercase(java.util.Locale.ROOT))
            PlaylistSearchPoster(key, item.kind, content, provider?.allowHttp == true) {
                clearGuideSearch(); showGuide(false); nowPlaying.hide(immediate = true); library.openSearchResult(item); showNavigation("library")
            }
        }.distinctBy { it.key }
        searchPanel.submit(query, live, titles, searchLoading || liveSearchBusy, searchWarning)
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
        val due = reminders.due(now)
        if (reminderPending == null && reminderGame == null) due.firstOrNull()?.let { reminderPending = it to now }
        reminderPending?.let { (game, checked) ->
            if (!reminders.has(game) || due.none { it.key == game.key } || now - checked > 120000L) reminderPending = null
            else if (reminderGame == null && !guideVisible && !gameWatchPanel.isVisible && !library.opened && settingsPanel?.opened != true && sportsManager?.opened != true && sportsCalendar?.opened != true && !details.isVisible && player.playWhenReady && (current != null || onDemand != null)) {
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
                    if (reminderPending?.first?.key?.let { key -> ready.none { it.key == key } } == true) reminderPending = null
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
        val reminder = reminderGame ?: return
        // A scheduled reminder can precede the scoreboard's delayed live flag.
        // Search current EPG/title coverage without falsifying the saved scoreboard.
        val game = reminder.copy(state = "in")
        val now = System.currentTimeMillis()
        val event = LiveEvent(reminder.eventId, reminder.teams.map { it.id }.toSet(), reminder.start, reminder.state, reminder.networks, reminder.scores, reminder.logos, reminder.status, league = reminder.leagueId, records = reminder.records)
        liveSports = liveSports.copy(events = liveSports.events.filterNot { it.id == event.id && it.league == event.league } + event)
        selectedGame = game; sportsTeam = "today"
        reminderWatchReturnSports = sportsVisible
        dismissGameReminder(); multiview.close(); sportsVisible = true; clearGuideSearch(); showGuide(false)
        gameWatchPanel.open(game); root.bringChildToFront(gameWatchPanel)
        selectGame(game, autoWatch = true)
        checkLiveSports(force = true)
    }
    private fun cancelReminderWatch() {
        ++sportsMatchEpoch; sportsMatchTask?.cancel(true); sportsMatchTask = null
        gameWatchPanel.dismiss(); clearSportsPlayback(); sportsPanel.back(); sportsVisible = reminderWatchReturnSports
    }
    private fun checkLiveSports(force: Boolean = false) {
        if (!store.sportsLeaguesChosen) return
        val now = System.currentTimeMillis()
        val scope = if (sportsVisible && guideVisible) sportsTeam else "live"
        val day = SportsSchedule.selectedDate(scope)
        if (liveSportsChecking) { if (force || day != liveSportsDay) liveSportsAgain = true; return }
        if (!force && day == liveSportsDay && now - liveSportsAttempt < 60000) return
        if (day != liveSportsDay) liveSports = LiveSportsSnapshot.EMPTY
        liveSportsDay = day; liveSportsTeamScope = scope
        liveSportsChecking = true; liveSportsAttempt = now; ++liveSportsRequest; val requestedDay = day
        liveSportsTask = liveSportsWorker.submit {
            val result = runCatching { liveSportsLoader() }.getOrDefault(LiveSportsSnapshot.EMPTY)
            handler.post { if (!closed) {
                liveSportsChecking = false; if (liveSportsDay == requestedDay) { liveSports = liveSports.mergeRefresh(result, complete = true); rememberScores(liveSports) }
                if (liveSportsAgain) { liveSportsAgain = false; checkLiveSports(force = true) }
                scheduleSports(); updateScoreStrip()
            } }
        }
    }
    private fun scheduleSports() {
        if (!sportsVisible || !guideVisible || closed) return
        if (!sportsRenderPending) { sportsRenderPending = true; handler.postDelayed(renderSports, 120) }
    }
    private fun refreshSports() {
        if (!sportsVisible || !guideVisible || !started || closed || !store.sportsLeaguesChosen) return
        ensureSportsLeague()
        rememberScores(liveSports)
        if (sportsComputing) { sportsDirty = true; return }
        checkLiveSports(); handler.removeCallbacks(pollLiveSports); handler.postDelayed(pollLiveSports, 60000)
        sportsComputing = true
        val liveSnapshot = liveSports; val checkingSnapshot = liveSportsChecking
        val epoch = ++sportsEpoch; val sourceEpoch = generation; val snapshot = guide
        val followed = store.followedTeams; val selectedTeam = sportsTeam; val selectedLeague = sportsBrowseLeague; val leagues = store.sportsLeagues; val favoriteSnapshot = favorites; val qualitySnapshot = videoInfo.toMap()
        val replaySnapshot = replayCache.toMap(); val selectedSourceId = source?.id
        val redZoneProfile = sportsStreamFilters(); val hidden = unavailable.snapshot()
        val now = System.currentTimeMillis()
        sportsTask = sportsWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val teamGames = followed.flatMap { team -> SportsSchedule.games(liveSnapshot, snapshot, team, favoriteSnapshot, now, matchChannels = false) }
            val rawGames = if (selectedTeam == "followed") teamGames.distinctBy { it.key } else {
                val scheduled = SportsSchedule.games(liveSnapshot, snapshot, selectedTeam, favoriteSnapshot, now, matchChannels = false)
                (scheduled + teamGames.filter { selectedTeam == null || selectedTeam == "league:${it.leagueId}" }).distinctBy { it.key }
            }
            val games = SportsPanel.ordered(rawGames.filter { it.leagueId in leagues && (selectedLeague == null || it.leagueId == selectedLeague) && (selectedTeam == "live" || !store.sportsFollowedOnly || it.teams.any { team -> team.id in followed }) }, followed, now)
            handler.post {
                if (epoch == sportsEpoch) sportsComputing = false
                if (closed || sourceEpoch != generation || epoch != sportsEpoch || !sportsVisible) return@post
                if (liveSnapshot !== liveSports || selectedTeam != sportsTeam || selectedLeague != sportsBrowseLeague) { sportsDirty = false; scheduleSports(); return@post }
                val browsing = sportsPanel.browsingGames
                if (browsing) selectedGame = games.firstOrNull { it.key == selectedGame?.key } ?: games.firstOrNull()
                val scanning = checkingSnapshot && selectedLeague !in liveSnapshot.leagues
                val progress = when {
                    scanning -> "Updating sports…"
                    !liveSports.fresh(now) || liveSports.leagues.isEmpty() -> "Schedule unavailable · Saved games are shown · Retrying automatically"
                    else -> "${if (selectedTeam == "today") "Today · Your leagues" else if (selectedTeam == "live") "Live now · Your leagues" else if (selectedTeam == null) "All games" else "Team / league scoreboard"} · ${games.size} ${if (games.size == 1) "game" else "games"} · ESPN schedule"
                }
                sportsPanel.setSpoilers(store.hideCompletedScores); sportsPanel.setLeagues(selectedLeague?.let { setOf(it) } ?: leagues); sportsPanel.setLeagueFilter(selectedLeague); sportsPanel.submit(games, followed, selectedTeam, progress, scanning); scheduleScoreDelay()
                if (browsing) selectedGame?.let(::showGameInfo) ?: run {
                    channelInfo.text = "Sports"; programmeInfo.text = "Today shows games across every supported league, whether or not you follow the teams. Select a poster to find playlist coverage. Live, upcoming and final status come from the scoreboard."
                }
                queueSportsPrefetch(games)
                refreshSportsDestinations(snapshot, games, redZoneProfile, qualitySnapshot, favoriteSnapshot, hidden, replaySnapshot, selectedSourceId, sourceEpoch, epoch, now)
                scheduleEpg()
                if (sportsDirty) { sportsDirty = false; scheduleSports() }
            }
        }
    }
    private fun refreshSportsDestinations(snapshot: Guide, games: List<SportsGame>, profile: GuideFilters, quality: Map<String, VideoInfo>, favorites: Set<String>, hidden: Map<String, Long>, replays: Map<String, Pair<Long, List<SportsReplay>>>, selectedId: String?, sourceEpoch: Int, epoch: Int, now: Long) {
        sportsDestinationTask?.cancel(true)
        val nfl = "NFL" in store.sportsLeagues && sportsBrowseLeague in setOf(null, "NFL")
        sportsDestinationTask = sportsDestinationWorker.submit {
            val archiveChannels = Catchup.channels(snapshot).associateBy { it.id }
            val archiveDays = archiveChannels.values.maxOfOrNull { it.catchupDays } ?: 0
            val confirmed = games.filter { SportsReplays.eligible(it, archiveDays, now) }.mapNotNull { game ->
                val cached = SportsReplays.retained(replays["$selectedId:$sourceEpoch:${game.key}"]?.second.orEmpty(), archiveChannels, now)
                SportsReplays.best(cached, quality = quality, favorites = favorites)?.let { game.key to it }
            }.toMap()
            if (Thread.currentThread().isInterrupted) return@submit
            handler.post {
                if (!closed && generation == sourceEpoch && epoch == sportsEpoch && sportsVisible) {
                    val latest = confirmed.toMutableMap()
                    games.forEach { game ->
                        val ready = SportsReplays.retained(replayCache["$selectedId:$sourceEpoch:${game.key}"]?.second.orEmpty(), archiveChannels, System.currentTimeMillis())
                        SportsReplays.best(ready, quality, favorites)?.let { latest[game.key] = it }
                    }
                    sportsPanel.archiveWindowDays = archiveDays; sportsPanel.updateCardReplays(latest)
                    replayGames = games.filter { SportsReplays.eligible(it, archiveDays, now) }; syncReplayDiscovery()
                }
            }
            val redZone = if (nfl) redZoneLoader(snapshot, profile, quality, now).filter { (hidden[it.id] ?: 0) <= now } else emptyList()
            if (!Thread.currentThread().isInterrupted) handler.post { if (!closed && generation == sourceEpoch && epoch == sportsEpoch && sportsVisible) sportsPanel.updateRedZone(redZone, quality) }
        }
    }
    private fun ensureSportsLeague(): String {
        if (sportsBrowseLeague !in store.sportsLeagues) sportsBrowseLeague = store.sportsLastLeague
        sportsPanel.setAvailableLeagues(store.sportsLeagues)
        sportsPanel.setLeagueFilter(sportsBrowseLeague)
        return sportsBrowseLeague!!
    }
    private fun selectSportsLeague(league: String) {
        if (league !in store.sportsLeagues || league == sportsBrowseLeague) return
        // Browsing another league preserves the current stream's recovery plan.
        stopSportsPrefetch(); ++sportsMatchEpoch; sportsMatchTask?.cancel(true)
        sportsWarmGames = emptyList(); sportsWarmPriority = null; sportsUpgradeKeys = emptySet(); replayDiscovery?.pause()
        ++sportsEpoch; sportsTask?.cancel(true); sportsComputing = false; sportsDirty = false
        sportsBrowseLeague = league; store.sportsLastLeague = league
        sportsPanel.setLeagueFilter(league); scheduleSports()
        if (league !in liveSports.leagues) checkLiveSports(force = true)
    }
    private fun openSportsLeaguePicker() {
        val picker = sportsLeaguePicker ?: SportsLeaguePicker(this, ::button, { leagues ->
            store.sportsLeagues = leagues; sportsBrowseLeague = store.sportsLastLeague
            sportsLeaguePicker?.visibility = View.GONE; ensureSportsLeague()
            sportsPanel.focusLeagueFilter(); checkLiveSports(force = true); scheduleSports()
        }, ::closeSportsLeaguePicker).also { sportsLeaguePicker = it; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.bringChildToFront(picker); picker.open(if (store.sportsLeaguesChosen) store.sportsLeagues else emptySet())
    }
    private fun closeSportsLeaguePicker() { sportsLeaguePicker?.visibility = View.GONE; if (!store.sportsLeaguesChosen) switchMode(false) else sportsPanel.focusLeagueFilter() }
    private fun openSportsCalendar() {
        suspendSportsOverlay()
        showGuide(false); nowPlaying.hide(immediate = true)
        val panel = sportsCalendar ?: SportsCalendarPanel(this, ::button, { day ->
            sportsTeam = "date:$day"; store.sportsTeam = sportsTeam.orEmpty(); sportsPanel.home()
            closeSportsCalendar(); sportsPanel.loading("Loading games for $day…"); checkLiveSports(force = true); scheduleSports()
        }, ::previewCalendarDate, ::selectCalendarGame, ::closeSportsCalendar).also { sportsCalendar = it; it.scorePresentation = ::presentScore; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        if (calendarSchedule == null) calendarSchedule = SportsCalendarLoader(store, { day, leagues -> calendarSportsLoader(day, leagues) }) { day, snapshot, loading, failed ->
            if (started && sportsCalendar?.opened == true && sportsCalendar?.activeDate == day) {
                calendarSnapshot = snapshot; rememberScores(snapshot)
                sportsCalendar?.submit(day, calendarGames(snapshot, day), loading, failed, store.hideCompletedScores)
            }
        }
        panel.background = GuideTheme.guideBackground(store.guideOpacity)
        root.bringChildToFront(panel); panel.open(SportsSchedule.selectedDate(sportsTeam) ?: java.time.LocalDate.now()); syncScoreStrip()
    }
    private fun calendarGames(snapshot: LiveSportsSnapshot, day: java.time.LocalDate) = SportsSchedule.games(snapshot, Guide.EMPTY, "date:$day", emptySet(), System.currentTimeMillis(), matchChannels = false)
        .filter { it.leagueId in store.sportsLeagues && (sportsBrowseLeague == null || it.leagueId == sportsBrowseLeague) && (!store.sportsFollowedOnly || it.teams.any { team -> team.id in store.followedTeams }) }
    private fun previewCalendarDate(day: java.time.LocalDate, force: Boolean) { calendarSchedule?.request(day, setOf(ensureSportsLeague()), liveSports, force) }
    private fun selectCalendarGame(day: java.time.LocalDate, game: SportsGame) {
        sportsTeam = "date:$day"; store.sportsTeam = sportsTeam.orEmpty()
        ++sportsEpoch; sportsTask?.cancel(true); sportsComputing = false; sportsDirty = false
        handler.removeCallbacks(renderSports); sportsRenderPending = false
        liveSports = calendarSnapshot; liveSportsDay = day
        sportsPanel.setLeagues(sportsBrowseLeague?.let { setOf(it) } ?: store.sportsLeagues)
        sportsPanel.submit(calendarGames(calendarSnapshot, day), store.followedTeams, sportsTeam, "Games · ${day}", false)
        closeSportsCalendar(); showGameInfo(game); sportsPanel.openGameActions(game)
    }
    private fun closeSportsCalendar() {
        calendarSchedule?.cancel(); sportsCalendar?.visibility = View.GONE
        showGuide(true); sportsPanel.focusCalendar()
    }
    private fun sportsTeams() {
        suspendSportsOverlay()
        sportsManagerReturnGuide = guideVisible; sportsManagerReturnSettings = settingsPanel?.opened == true
        if (sportsManagerReturnSettings) settingsPanel?.visibility = View.GONE
        showGuide(false); nowPlaying.hide(immediate = true); closeDetails()
        val panel = sportsManager ?: SettingsPanel(this, ::renderSportsManager, ::closeSportsManager, "Sports").also {
            sportsManager = it; it.tag = "sports-manager"; it.leaveNavigation = ::closeSportsManager
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        panel.background = GuideTheme.guideBackground(store.guideOpacity)
        root.bringChildToFront(panel); panel.open(listOf("Preferences", "Stream quality", "Score delay", "Leagues") + SportsLeagues.all.map { it.name }); syncScoreStrip()
    }
    private fun openSportsQuality() {
        if (sportsManager?.opened != true) sportsTeams()
        sportsManager?.selectSetting("Stream quality")
    }
    private fun delayLabel(seconds: Int) = if (seconds == 0) "Off · immediate scores" else "$seconds seconds"
    private fun openSportsScoreDelay() { if (sportsManager?.opened != true) sportsTeams(); sportsManager?.selectSetting("Score delay") }
    private fun renderScoreDelay(panel: SettingsPanel, channel: Channel? = null) {
        val chosen = channel?.let { store.channelScoreDelay(scoreDelayKey(it)) } ?: store.sportsScoreDelay
        panel.page(if (channel == null) "Sports score delay" else "Score delay · ${channel.name}", "Delay scoreboard scores and game clocks to match your stream. Cards, hero, calendar and Sports overlay use the same delay. A new delay may need time to collect scores; game reminders and video playback keep their timing.")
        if (channel == null) {
            current?.let { playing -> panel.action("Current channel", store.channelScoreDelay(scoreDelayKey(playing))?.let(::delayLabel) ?: "Uses default · ${delayLabel(chosen)}") { renderScoreDelay(panel, playing); panel.focusContent() } }
            panel.note("Default for all games")
        } else {
            panel.onDetailBack { panel.selectSetting("Score delay") }
            panel.action("Use default delay", delayLabel(store.sportsScoreDelay), store.channelScoreDelay(scoreDelayKey(channel)) == null) { store.setChannelScoreDelay(scoreDelayKey(channel), null); scoreDelayChanged(); renderScoreDelay(panel, channel); panel.focusOption("Use default delay") }
        }
        fun choose(seconds: Int, focus: String) {
            if (channel == null) store.sportsScoreDelay = seconds else store.setChannelScoreDelay(scoreDelayKey(channel), seconds)
            scoreDelayChanged(); renderScoreDelay(panel, channel); panel.focusOption(focus)
        }
        listOf(0, 15, 30, 45, 60, 90, 120, 180, 300).forEach { seconds ->
            val title = delayLabel(seconds)
            panel.action(title, selected = chosen == seconds && (channel == null || store.channelScoreDelay(scoreDelayKey(channel)) != null)) { choose(seconds, title) }
        }
        panel.action("−5 seconds", "Fine tune · ${delayLabel(chosen)}") { choose((chosen - 5).coerceAtLeast(0), "−5 seconds") }
        panel.action("+5 seconds", "Fine tune · ${delayLabel(chosen)}") { choose((chosen + 5).coerceAtMost(SportsScoreDelay.MAX_SECONDS), "+5 seconds") }
    }
    private fun renderSportsManager(page: String) {
        val panel = sportsManager ?: return
        if (page == "Stream quality") { renderSportsQuality(panel); return }
        if (page == "Score delay") { renderScoreDelay(panel); return }
        if (page == "Preferences") {
            panel.page("Your sports", "Choose leagues and teams on the left. Enable automatic playback to watch a live game directly from its card. Upcoming and completed games keep their reminder and replay options.")
            panel.action("Play best stream on selection", "Play the highest-resolution matching stream. Known FPS breaks resolution ties. Failed starts can try another match, up to three attempts.", selected = store.sportsAutoPlay) { store.sportsAutoPlay = !store.sportsAutoPlay; panel.refresh("Play best stream on selection") }
            panel.action("Show only my teams", selected = store.sportsFollowedOnly) { store.sportsFollowedOnly = !store.sportsFollowedOnly; sportsPreferencesChanged = true; panel.refresh("Show only my teams") }
            panel.action("Hide completed-game scores", selected = store.hideCompletedScores) { store.hideCompletedScores = !store.hideCompletedScores; sportsPanel.setSpoilers(store.hideCompletedScores); panel.refresh("Hide completed-game scores") }
            panel.action("Score delay", delayLabel(store.sportsScoreDelay) + " default · Optional current-channel override") { panel.selectSetting("Score delay") }
            panel.note("NFL RedZone appears whenever NFL is enabled. Its availability follows your playlist provider; it is not a scheduled matchup.")
            panel.action("Reset Sports settings", "Clear teams and reminders, choose leagues again and restore Sports defaults") { confirmSportsReset() }
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
                    if (sportsBrowseLeague !in chosen) sportsBrowseLeague = null
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
        panel.page("Reset Sports settings?", "This clears followed teams and saved reminders, asks you to choose leagues again, turns off My teams only and automatic playback, resets this playlist’s stream quality, and hides completed scores. Sports will open on Today.")
        panel.action("Cancel") { panel.refresh("Reset Sports settings") }
        panel.action("Reset Sports") {
            store.scoreOverlayPinned = false; store.scoreOverlayPosition = "Bottom"; scoreStripOpen = false; cancelScoreTune(); scoreStrip.suspend()
            store.followedTeams = emptySet(); store.sportsLeagues = SportsLeagues.byId.keys; store.sportsFollowedOnly = false; store.sportsAutoPlay = false; store.hideCompletedScores = true; store.sportsTeam = "today"; store.resetScoreDelays(); scoreDelayChanged()
            saveSportsFilters(GuideFilters())
            store.resetSportsLeagues(); sportsBrowseLeague = null
            sportsTeam = "today"; reminders = GameReminders(); saveGameReminders(); reminderPending = null; dismissGameReminder()
            sportsPanel.setSpoilers(true); sportsPanel.resetLeagueBrowse(); sportsPanel.home(); sportsPreferencesChanged = true
            panel.refresh("Reset Sports settings"); toast("Sports settings reset")
        }
        panel.focusOption("Cancel")
    }
    private fun closeSportsManager() {
        sportsManager?.visibility = View.GONE
        if (sportsPreferencesChanged) { sportsPreferencesChanged = false; ensureSportsLeague(); liveSportsAttempt = 0; checkLiveSports(force = true); scheduleSports() }
        if (sportsManagerReturnSettings) { settingsPanel?.visibility = View.VISIBLE; settingsPanel?.let { root.bringChildToFront(it); it.selectSetting("Sports") } }
        else { showGuide(sportsManagerReturnGuide); if (sportsVisible && guideVisible) { if (!store.sportsLeaguesChosen) openSportsLeaguePicker() else sportsPanel.teamsButton.requestFocus() } }
    }
    private fun showGameInfo(game: SportsGame) {
        if (redZoneAutoPending && !sportsPanel.redZoneFocused) { redZoneAutoPending = false; ++sportsMatchEpoch; sportsMatchTask?.cancel(true); sportsPanel.finishTuning() }
        selectedGame = game; syncReplayDiscovery(); channelInfo.text = game.title
        renderGameInfo(game)
        sportsChannels = emptyList()
        if (game.live(System.currentTimeMillis()) && sportsVisible && guideVisible && !sportsPanel.watching) { sportsWarmPriority = game; queueSportsPrefetch() }
    }
    private fun renderGameInfo(game: SportsGame) {
        programmeInfo.text = "${sportsPanel.scoreText(game)}\n${SportsLeagues.label(game.leagueId)} · ${sportsPanel.statusText(game)}\n" +
            game.networks.joinToString(" · ").ifBlank { "Broadcaster to be announced" } + "\n" +
            (if (game.broadcasts.isNotEmpty() || game.titleMatches.isNotEmpty()) "${game.broadcasts.size + game.titleMatches.size} EPG/title matches · OK Choose channel" else if (game.candidates.isNotEmpty()) "${game.candidates.size} possible network channels · OK Review" else "Channel not matched yet")
    }
    private fun playRedZone(channels: List<Channel>): Boolean {
        val choices = channels.filter { allowed(it) && !unavailable.contains(it.id, System.currentTimeMillis()) }
            .sortedWith(SportsStreams.preferenceComparator(sportsStreamFilters(), videoInfo))
        val best = choices.firstOrNull() ?: return false
        redZoneAutoPending = false; sportsPanel.finishTuning()
        tune(best); watchingRedZone = true; showGuide(false); return true
    }
    private fun findRedZone(autoPlay: Boolean) {
        sportsResults = null
        redZoneAutoPending = autoPlay
        val snapshot = guide; val sourceEpoch = generation; val quality = videoInfo.toMap(); val profile = sportsStreamFilters(); val selected = source; val hidden = unavailable.snapshot()
        sportsMatchTask?.cancel(true); val epoch = ++sportsMatchEpoch
        if (autoPlay) {
            redZoneCache.peek(snapshot, profile, quality, System.currentTimeMillis())?.filter { (hidden[it.id] ?: 0) <= System.currentTimeMillis() && allowed(it) }?.takeIf { it.isNotEmpty() }?.let { playRedZone(it); return }
            sportsPanel.tuningRedZone("Finding the best RedZone feed… · Back cancels")
        } else sportsPanel.matchingRedZone()
        sportsMatchTask = sportsMatchWorker.submit {
            val now = System.currentTimeMillis()
            var evidence = snapshot
            val warmed = linkedMapOf<String, List<Programme>>()
            val archiveDays = mutableMapOf<String, Int>()
            val direct = redZoneCache.named(snapshot, profile, quality).filter { (hidden[it.id] ?: 0) <= now }
            if (direct.isNotEmpty()) handler.post {
                if (!closed && started && sourceEpoch == generation && epoch == sportsMatchEpoch && sportsVisible && (!autoPlay || redZoneAutoPending && sportsPanel.redZoneFocused)) {
                    sportsPanel.updateRedZone(direct, quality)
                    if (!autoPlay) sportsPanel.showRedZoneStreams(direct, checking = true) { channelLabel(it) + " · " + it.qualityLabel(videoInfo[it.id]) }
                }
            }
            val known = redZoneLoader(snapshot, profile, quality, now).filter { (hidden[it.id] ?: 0) <= now }
            if (known.isNotEmpty()) handler.post {
                if (!closed && started && sourceEpoch == generation && epoch == sportsMatchEpoch && sportsVisible && (!autoPlay || redZoneAutoPending && sportsPanel.redZoneFocused)) {
                    sportsPanel.updateRedZone(known, quality)
                    if (!autoPlay) sportsPanel.showRedZoneStreams(known, checking = true) { channelLabel(it) + " · " + it.qualityLabel(videoInfo[it.id]) }
                }
            }
            val height = known.maxOfOrNull { it.quality(quality[it.id])?.height ?: 0 } ?: 0
            val targets = SportsDestinations.guideTargets(snapshot, quality, now) { channel ->
                owner(channel)?.let { provider -> provider.enabled && provider.kind == "xtream" && provider.epgUrl.isBlank() } == true && (height == 0 || (channel.quality(quality[channel.id])?.height ?: 0) > height)
            }
            if (targets.isNotEmpty() && selected != null) {
                val highest = targets.maxOfOrNull { it.quality(quality[it.id])?.height ?: 0 } ?: 0
                val keys = targets.map(snapshot::key).toSet()
                snapshot.channels.forEach { channel -> val key = snapshot.key(channel); if (key in keys) archiveDays[key] = maxOf(archiveDays[key] ?: 0, channel.catchupDays) }
                val replies = SportsCoverageLookup.load(targets, sportsLookupWorkers,
                    get = { channel, timeout -> XtreamEpg.load(owner(channel) ?: selected, channel, 6, timeout) },
                    confirmed = { channel, entries -> (channel.quality(quality[channel.id])?.height ?: 0) >= highest && SportsDestinations.redZone(Guide(listOf(channel), mapOf(snapshot.key(channel) to entries)), profile, quality, now).isNotEmpty() })
                replies.forEach { (channel, entries) ->
                    val key = snapshot.key(channel); archiveDays[key] = maxOf(archiveDays[key] ?: 0, channel.catchupDays)
                    warmed[key] = GuideHistory.merge(snapshot.programmes[key].orEmpty(), entries, archiveDays.getValue(key), now)
                }
                if (warmed.isNotEmpty()) evidence = snapshot.copy(programmes = snapshot.programmes + warmed)
            }
            val channels = (if (warmed.isEmpty()) known else redZoneLoader(evidence, profile, quality, now)).filter { (hidden[it.id] ?: 0) <= now }
            handler.post { if (!closed && started && sourceEpoch == generation && epoch == sportsMatchEpoch && sportsVisible && (!autoPlay || redZoneAutoPending && sportsPanel.redZoneFocused)) {
                if (warmed.isNotEmpty()) { guide = guide.copy(programmes = guide.programmes + warmed.mapValues { (key, entries) -> GuideHistory.merge(guide.programmes[key].orEmpty(), entries, archiveDays[key] ?: 0, now) }); cancelEpgSave(); handler.postDelayed(cacheEpg, 30000) }
                sportsChannels = channels.take(4)
                sportsPanel.updateRedZone(channels, quality)
                redZoneAutoPending = false; sportsPanel.finishTuning()
                if (autoPlay && playRedZone(channels)) return@post
                if (autoPlay) sportsPanel.matchingRedZone()
                sportsPanel.showRedZoneStreams(channels) { channelLabel(it) + " · " + it.qualityLabel(videoInfo[it.id]) }
            } }
        }
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
            if (choices.isEmpty() && enabledSources().any { it.kind == "xtream" } && !Thread.currentThread().isInterrupted) {
                val known = sportsStreamCache.replayFeeds(selected.id, sourceEpoch, game)
                val targets = SportsReplays.candidates(game, snapshot, favoriteSnapshot, System.currentTimeMillis(), limit = Importers.MAX_CHANNELS, knownFeeds = known).filter { it.id !in listings && owner(it)?.kind == "xtream" }.take(8)
                val requests = targets.associateWith { channel -> sportsLookupWorkers.submit<List<Programme>> { Catchup.load(owner(channel) ?: selected, channel, 1200) } }
                val deadline = System.nanoTime() + 4_000_000_000L
                try {
                    requests.entries.forEachIndexed { index, (channel, task) ->
                        if (Thread.currentThread().isInterrupted) return@submit
                        val left = deadline - System.nanoTime()
                        if (left > 0 || task.isDone) runCatching { task.get(maxOf(1L, left), java.util.concurrent.TimeUnit.NANOSECONDS) }.getOrNull()?.let { listings[channel.id] = it }
                        handler.post { if (!closed && started && epoch == sportsMatchEpoch && sportsVisible) sportsPanel.matchingProgress(game, SportsPanel.CHECKING_ARCHIVES, index + 1, targets.size) }
                    }
                } finally { requests.values.forEach { it.cancel(true) } }
                choices = SportsReplays.findListings(game, snapshot, listings, System.currentTimeMillis())
            }
            val result = SportsReplays.rank(choices, quality, favoriteSnapshot)
            handler.post {
                if (closed || !started || generation != sourceEpoch || source?.id != selected.id || epoch != sportsMatchEpoch || !sportsVisible || !sportsPanel.showingStreams(game)) return@post
                replayDiscovery?.remember(selected, sourceEpoch, snapshot, listings)
                rememberSportsReplays(game, result)
                sportsPanel.showReplays(game, result)
                scheduleSports()
            }
        }
    }
    private fun playSportsReplay(replay: SportsReplay) {
        val selected = owner(replay.channel) ?: return
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
        sportsWarmActive.forEach { key ->
            sportsWarmAttempted.remove(key)
            if (sportsStreamSummaries[key]?.startsWith("Finding stream") == true) sportsStreamSummaries[key] = "Select to find stream"
        }
        sportsWarmActive = emptySet(); sportsWarmPending = false
        sportsStreamSummaries.replaceAll { _, value -> if (value.startsWith("Finding stream")) "Select to find stream" else value }
        if (clear) { sportsStreamCache.clear(); sportsWarmAttempted.clear(); sportsWarmGames = emptyList(); sportsWarmPriority = null; sportsStreamSummaries.clear(); if (::sportsPanel.isInitialized) sportsPanel.updateStreamSummaries(emptyMap()) }
        else if (::sportsPanel.isInitialized) sportsPanel.updateStreamSummaries(sportsStreamSummaries)
    }
    private fun queueSportsPrefetch(games: List<SportsGame>? = null) {
        if (games != null) {
            val now = System.currentTimeMillis()
            sportsWarmGames = games.filter { it.live(now) }
            sportsStreamSummaries.keys.retainAll(sportsWarmGames.map { it.key }.toSet() + listOfNotNull(sportsWarmPriority?.key))
        }
        if (!started || closed || !sportsVisible || !guideVisible || sportsPanel.watching) return
        sportsUpgradeKeys = sportsPanel.visibleGameKeys() + listOfNotNull(sportsWarmPriority?.key)
        // A remote focus callback only records intent. Cache validation and ranking
        // must never hold up the UI, even when a large EPG changed underneath it.
        sportsWarmGames.forEach { sportsStreamSummaries.putIfAbsent(it.key, "Select to find stream") }
        if (games != null) sportsPanel.updateStreamSummaries(sportsStreamSummaries)
        sportsWarmPending = true
        handler.removeCallbacks(warmSportsStreams); handler.postDelayed(warmSportsStreams, 300)
    }
    private fun sportsStreamSummary(match: SportsStreamCache.Match, snapshot: Guide, profile: GuideFilters,
        quality: Map<String, VideoInfo>, hidden: Map<String, Long>, now: Long): String {
        val evidence = if (match.warmed.isEmpty()) snapshot else snapshot.copy(programmes = object : AbstractMap<String, List<Programme>>() {
            override fun get(key: String) = match.warmed[key] ?: snapshot.programmes[key]
            override val entries: Set<Map.Entry<String, List<Programme>>> get() = (snapshot.programmes + match.warmed).entries
        })
        val available: (Channel) -> Boolean = { (hidden[it.id] ?: 0) <= now }
        if (match.game.live(now)) {
            val choices = SportsStreams.automaticChoices(match.game, profile, quality, evidence, now, available)
            streamHealth.order(choices, quality, android.os.SystemClock.elapsedRealtime()).firstOrNull()?.let { return channelLabel(it) + "\n" + it.qualityLabel(quality[it.id]) }
        }
        return SportsStreams.cardSummary(match.game, profile, quality, evidence, now) { match.game.state == "pre" || available(it) }
    }
    private fun prefetchSportsStream() {
        if (!started || closed || !sportsVisible || !guideVisible || sportsPanel.watching || multiview.active || sportsWarmActive.isNotEmpty()) return
        if (player.playbackState == Player.STATE_BUFFERING || current != null && (!firstFrame || player.playbackState != Player.STATE_READY)) { handler.postDelayed(warmSportsStreams, 1000); return }
        val selected = source ?: return
        val now = System.currentTimeMillis(); val snapshot = guide; val sourceEpoch = generation
        val games = SportsBrowseWork.targets(sportsWarmGames.filter { it.live(now) }, sportsUpgradeKeys, sportsWarmPriority, store.followedTeams)
        if (games.isEmpty()) { sportsWarmPending = false; return }
        val epoch = ++sportsWarmEpoch; val favoriteSnapshot = favorites; val profile = sportsStreamFilters()
        val quality = videoInfo.toMap(); val hidden = unavailable.snapshot(); val attempted = sportsWarmAttempted.toMap(); val upgrades = sportsUpgradeKeys
        sportsWarmPending = false; sportsWarmActive = setOf(games.first().key)
        sportsWarmTask = sportsWarmWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val token = sportsStreamCache.use(selected.id, sourceEpoch, snapshot, favoriteSnapshot)
                if (Thread.currentThread().isInterrupted) return@submit
                val summaries = linkedMapOf<String, String>()
                games.forEach { game ->
                    val cached = sportsStreamCache.get(token, game, now, forAuto = false, allowStale = true)
                    summaries[game.key] = cached?.let { sportsStreamSummary(it, snapshot, profile, quality, hidden, now) } ?: "Select to find stream"
                }
                val game = games.firstOrNull {
                    val saved = sportsStreamCache.get(token, it, now, forAuto = false)
                    it.key in upgrades && saved?.needsUpgrade(profile, quality, now) == true || saved == null && now - (attempted[it.key] ?: 0L) >= 30000
                }
                val finished = mutableSetOf<String>()
                val ready = programmeIndex?.takeIf { it.isFor(snapshot) }
                if (game != null) {
                    val batch = listOf(game) + if (ready == null) emptyList() else games.asSequence()
                        .filter { it.key != game.key && now - (attempted[it.key] ?: 0L) >= 30_000 }
                        .filter { sportsStreamCache.get(token, it, now, forAuto = false) == null }
                        .take(2).toList()
                    val pending = batch.filter { sportsStreamCache.get(token, it, now, forAuto = false, allowStale = true) == null }.mapTo(hashSetOf()) { it.key }
                    handler.post {
                        if (!closed && epoch == sportsWarmEpoch) {
                            sportsWarmActive = batch.mapTo(hashSetOf()) { it.key }
                            sportsWarmActive.forEach { sportsWarmAttempted[it] = now }
                            pending.forEach { sportsStreamSummaries[it] = "Finding stream…" }
                            if (pending.isNotEmpty()) sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                        }
                    }
                    // Other visible games share the read-only index. Only the priority game can confirm with a provider.
                    val tasks = batch.map { target -> java.util.concurrent.Callable {
                        val cachedMatch = sportsStreamCache.get(token, target, now, forAuto = false, allowStale = true)
                        val confirm = target == game && cachedMatch?.needsUpgrade(profile, quality, now) == true
                        val result = runCatching { resolveSportsMatch(selected, target, snapshot, favoriteSnapshot, profile, quality, confirm, { _, checked, total ->
                            if (target.key in pending) handler.post {
                                if (!closed && epoch == sportsWarmEpoch && target.key in sportsWarmActive) {
                                    sportsStreamSummaries[target.key] = "Finding stream · $checked / $total"
                                    sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                                }
                            }
                        }, cached = cachedMatch, preparedIndex = ready) }.getOrNull()
                        val at = System.currentTimeMillis()
                        val retained = result?.takeIf { sportsStreamCache.put(token, it, at) }
                            ?: sportsStreamCache.get(token, target, at, forAuto = false, allowStale = true)
                        val summary = retained?.let { sportsStreamSummary(it, snapshot, profile, quality, hidden, at) } ?: "Select to finish stream search"
                        Triple(target.key, summary, result?.game?.coverageComplete == true)
                    } }
                    sportsLocalWarmWorkers.invokeAll(tasks).forEach { task ->
                        val (key, summary, complete) = task.get()
                        summaries[key] = summary
                        if (complete && summary == "Select to finish stream search") finished.add(key)
                    }
                }
                if (Thread.currentThread().isInterrupted) return@submit
                handler.post {
                    if (closed || epoch != sportsWarmEpoch || generation != sourceEpoch) return@post
                    sportsWarmTask = null; sportsWarmActive = emptySet()
                    val visible = (sportsWarmGames + listOfNotNull(sportsWarmPriority)).map { it.key }.toSet()
                    sportsStreamSummaries.putAll(summaries.filterKeys { it in visible })
                    if (sportsWarmAttempted.size > 64) sportsWarmAttempted.keys.retainAll(visible)
                    finished.forEach(sportsWarmAttempted::remove)
                    sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                    if (started && sportsVisible && guideVisible && !sportsPanel.watching && (sportsWarmPending || game != null)) handler.postDelayed(warmSportsStreams, 250)
                }
            } catch (_: Exception) {
                if (!Thread.currentThread().isInterrupted) handler.post {
                    if (!closed && epoch == sportsWarmEpoch) {
                        sportsWarmTask = null; sportsWarmActive = emptySet(); sportsWarmPending = false
                        sportsStreamSummaries.replaceAll { _, value -> if (value.startsWith("Finding stream")) "Select to find stream" else value }
                        sportsPanel.updateStreamSummaries(sportsStreamSummaries)
                    }
                }
            }

        }
    }
    private fun clearSportsPlayback() { sportsPlaybackPlan = null; pendingSportsTune = null }
    private fun playNextSportsCandidate(reason: String): Boolean {
        val plan = sportsPlaybackPlan ?: return false
        val selected = source ?: return false
        val next = plan.next { channel -> !unavailable.contains(channel.id, System.currentTimeMillis()) && allowed(channel) && !SportsStreams.conflictingCoverage(plan.game, channel, guide, System.currentTimeMillis()) &&
            guide.channels.any { it.id == channel.id && it.url == channel.url } } ?: return false
        tune(next, plan.trace); sportsPlaybackPlan = plan; pendingSportsTune = next.id
        watchingSports = sportsResults?.takeIf { it.game.key == plan.game.key } ?: GameStreams(plan.game, plan.candidates)
        val exact = plan.game.broadcasts.any { it.channel.id == next.id && System.currentTimeMillis() in it.programme.start until it.programme.end } || plan.game.titleMatches.any { it.id == next.id }
        if (!exact) sportsPanel.showMatchNotice("Trying broadcaster match · game coverage unconfirmed")
        sportsPanel.matchingProgress(plan.game, "$reason · ${next.name}"); toast("$reason · ${next.name}")
        return true
    }
    private fun recoverBufferedSports(now: Long, buffering: Boolean): Boolean {
        val context = watchingSports?.takeIf { it.game.live(System.currentTimeMillis()) && System.currentTimeMillis() < it.game.end && it.channels.any { candidate -> candidate.id == current?.id } }
        val active = store.sportsAutoFailover && started && onDemand == null && !multiview.active && hasPlayedChannel && player.playerError == null && !retryPanel.isVisible && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        if (!sportsRecovery.due(context?.game?.key, current?.id, now, buffering, active, store.sportsFailoverSeconds * 1000L)) return false
        val game = context?.game ?: return false; val failed = current ?: return false
        sportsPlaybackPlan?.takeIf { it.game.key == game.key }?.attemptedIds?.let(sportsRecovery::remember)
        val choices = streamHealth.order(SportsStreams.automaticChoices(game, sportsStreamFilters(), videoInfo, guide, System.currentTimeMillis()) { channel ->
            channel.id != failed.id && sportsRecovery.allows(channel.id) && allowed(channel) && !unavailable.contains(channel.id, System.currentTimeMillis())
        }, videoInfo, now)
        val next = choices.firstOrNull() ?: return false
        streamHealth.problem(failed.id, now, true)
        val plan = SportsPlaybackPlan(game, choices, backgroundRecovery = true)
        sportsPlaybackPlan = plan
        journal(PlaybackJournal.Event.FAILOVER)
        if (!playNextSportsCandidate("Buffering · switching game stream")) return false
        sportsRecovery.switched(failed.id, next.id, now)
        watchingSports = context
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
        if (watchingRedZone) { sportsVisible = true; applyFilter(); showGuide(true); findRedZone(false); return }
        val context = playbackGame() ?: return
        showGameStreamChoices(context, "Choose another stream for ${context.game.title}")
    }
    private fun showSportsAlternatives(message: String) {
        val context = playbackGame() ?: sportsPlaybackPlan?.let { GameStreams(it.game, it.candidates) } ?: return
        showGameStreamChoices(context, message)
    }
    private fun showGameStreamChoices(context: GameStreams, message: String) {
        if (source == null) return
        val now = System.currentTimeMillis(); val lineup = guide.channels.associateBy { it.id }
        val known = SportsStreams.results(context.game, sportsStreamFilters().copy(minHeight = 0, excludedFps = emptySet()), videoInfo, guide, now) {
            !unavailable.contains(it.id, now) && allowed(it)
        }.mapNotNull { lineup[it.id] }
        val choices = streamHealth.order(known.distinctBy { it.id }, videoInfo, android.os.SystemClock.elapsedRealtime())
        stopSportsPrefetch(); clearSportsPlayback(); closeDetails()
        ++sportsMatchEpoch; sportsMatchTask?.cancel(true); retryPanel.visibility = View.GONE; playbackLoading.visibility = View.GONE
        sportsVisible = true; showGameInfo(context.game); applyFilter()
        sportsPanel.matching(context.game); showGuide(true)
        if (choices.none { it.id != current?.id }) { selectGame(context.game); return }
        sportsResults = GameStreams(context.game, choices); sportsChannels = choices.take(4)
        val note = if (choices.size == 1 && choices[0].id == current?.id && SportsStreams.coverage(context.game, choices[0], guide, now) != SportsStreams.Coverage.UNCONFIRMED) "This is the only confirmed game feed right now. Resolution labels alone do not confirm coverage or availability." else message
        sportsPanel.showStreams(context.game, choices, note) { channel -> (if (channel.id == current?.id) "Current · " else "") + gameStreamLabel(context.game, channel) }
    }
    private fun gameStreamLabel(game: SportsGame, channel: Channel): String {
        val now = System.currentTimeMillis()
        val coverage = when (SportsStreams.coverage(game, channel, guide, now)) {
            SportsStreams.Coverage.EPG -> if (game.state == "pre") "Scheduled EPG match" else "EPG matched"
            SportsStreams.Coverage.CHANNEL_TITLE -> "Channel title matched"
            SportsStreams.Coverage.UNCONFIRMED -> "Possible coverage · not confirmed"
        }
        val health = if (streamHealth.status(channel.id, android.os.SystemClock.elapsedRealtime()) > 0) " · Recent playback failure" else ""
        return (if (channel.id in favorites) "★ " else "") + channelLabel(channel) + " · " + channel.qualityLabel(videoInfo[channel.id]) + " · " + coverage + health
    }
    private fun resolveSportsMatch(selected: Source, game: SportsGame, snapshot: Guide, favoritesSnapshot: Set<String>,
        profile: GuideFilters, quality: Map<String, VideoInfo>, confirm: Boolean, progress: (String, Int, Int) -> Unit, cached: SportsStreamCache.Match? = null,
        trace: SportsClickTrace? = null, preparedIndex: GuideProgrammeIndex? = programmeIndex): SportsStreamCache.Match {
        var lastProgress = 0L
        // Finish the finite, cancellable local pass across every enabled provider. A timeout
        // here previously turned an incomplete scan into a false "no matches" result.
        var result = cached?.game ?: SportsSchedule.match(game, snapshot, favoritesSnapshot, System.currentTimeMillis(), quality = quality, trace = trace, index = preparedIndex) { checked, total ->
            val now = System.nanoTime()
            if (now - lastProgress > 100_000_000L || checked == total) { lastProgress = now; progress(SportsPanel.SEARCHING, checked, total) }
        }
        val warmed = cached?.warmed.orEmpty().toMutableMap()
        val needsProvider = enabledSources().any { it.kind == "xtream" && it.epgUrl.isBlank() }
        val coverageNow = System.currentTimeMillis()
        val locallyConfirmed = result.broadcasts.any { game.state == "pre" || coverageNow in it.programme.start until it.programme.end } || result.titleMatches.isNotEmpty()
        val confirmationFloor = SportsStreams.confirmationFloor(result, profile, quality, coverageNow)
        if (confirm && needsProvider && confirmationFloor != null && (game.state != "pre" || game.start < coverageNow + 3 * 86400000L) && !Thread.currentThread().isInterrupted) {
            val choices = SportsStreams.rank(result, profile.copy(excludedFps = emptySet()), quality)
            val targets = SportsStreams.confirmationTargets(game, snapshot, choices, favoritesSnapshot, quality, System.currentTimeMillis(), betterThanHeight = confirmationFloor)
                .filter { owner(it)?.let { source -> source.kind == "xtream" && source.epgUrl.isBlank() } == true }
            if (targets.isNotEmpty()) {
                val targetOrder = SportsStreams.preferenceComparator(profile, quality); val bestTarget = targets.minWith(targetOrder)
                val label = if (game.state == "pre") SportsPanel.CHECKING_SCHEDULES else SportsPanel.CONFIRMING
                progress(label, 0, targets.size)
                val replies = SportsCoverageLookup.load(targets, sportsLookupWorkers,
                    get = { channel, timeout -> XtreamEpg.load(owner(channel) ?: selected, channel, if (game.state == "pre") 72 else 6, timeout) },
                    confirmed = { channel, entries ->
                        targetOrder.compare(channel, bestTarget) <= 0 &&
                            SportsSchedule.match(game, Guide(listOf(channel), mapOf(snapshot.key(channel) to entries)), favoritesSnapshot, System.currentTimeMillis()).broadcasts.isNotEmpty()
                    }, trace = trace, progress = { checked, total -> progress(label, checked, total) })
                replies.forEach { (channel, entries) -> warmed[snapshot.key(channel)] = entries }
                if (warmed.isNotEmpty()) {
                    val fresh = SportsSchedule.match(game, Guide(targets, warmed), favoritesSnapshot, System.currentTimeMillis())
                    val broadcasts = (result.broadcasts + fresh.broadcasts).distinctBy { it.channel.id }; val ids = broadcasts.map { it.channel.id }.toSet()
                    result = result.copy(broadcasts = broadcasts, candidates = result.candidates.filter { it.id !in ids }, titleMatches = result.titleMatches.filter { it.id !in ids })
                }
            }
        }
        return SportsStreamCache.Match(result, warmed, confirm || !needsProvider || locallyConfirmed, upgradeChecked = confirm || !needsProvider || cached?.upgradeChecked == true)
    }
    private fun selectGame(game: SportsGame, autoWatch: Boolean = false) = selectSportsGame(game, autoWatch, false)
    private fun selectSportsGame(game: SportsGame, autoWatch: Boolean, fromScores: Boolean, trace: SportsClickTrace? = performanceOverlay?.sports?.begin(game.key, autoWatch)) {
        redZoneAutoPending = false
        if (game.state == "post") { trace?.finish("replay path"); findSportsReplay(game); return }
        stopSportsPrefetch(); clearSportsPlayback()
        pendingSportsTune = null; sportsMatchTask?.cancel(true)
        val selected = source ?: run { trace?.finish("no playlist"); toast("Add a playlist in Settings to find streams"); return }; val snapshot = guide; val favoritesSnapshot = favorites
        val profile = sportsStreamFilters(); val quality = videoInfo.toMap(); val sourceEpoch = generation
        val reminderWatch = gameWatchPanel.isVisible
        val fastCached = sportsStreamCache.peek(selected.id, sourceEpoch, snapshot, favoritesSnapshot, game, System.currentTimeMillis(), forAuto = autoWatch, trace = trace)
        if (fromScores) selectedGame = game else showGameInfo(game)
        val matchEpoch = ++sportsMatchEpoch
        if (!fromScores) sportsPanel.matching(game)
        fun targetActive() = if (fromScores) scoreStrip.isVisible && scoreStripLoading == game.key else sportsVisible && sportsPanel.showingStreams(game)
        fun present(prepared: SportsStreamCache.Match) {
            val warmed = prepared.warmed; val matched = prepared.game
            if (closed || !started || generation != sourceEpoch || source?.id != selected.id || !targetActive() || matchEpoch != sportsMatchEpoch) {
                trace?.finish("selection left"); return
            }
            val now = System.currentTimeMillis()
            if (prepared.needsRefresh(snapshot, guide, now, trace)) { selectSportsGame(game, autoWatch, fromScores, trace); return }
            val (mergedGuide, _) = prepared.mergeGuide(guide, now)
            guide = mergedGuide
            if (warmed.isNotEmpty()) {
                cancelEpgSave(); handler.postDelayed(cacheEpg, 30000)
            }
            val latestQuality = videoInfo.toMap()
            val alternatives = streamHealth.order(SportsStreams.automaticChoices(matched, profile, latestQuality, guide, now) { !unavailable.contains(it.id, now) && allowed(it) }, latestQuality, android.os.SystemClock.elapsedRealtime())
            val choices = SportsStreams.results(matched, profile.copy(minHeight = 0, excludedFps = emptySet()), latestQuality, guide, now) {
                (game.state == "pre" || !unavailable.contains(it.id, now)) && allowed(it)
            }
            val channels = streamHealth.order((alternatives + choices).distinctBy { it.id }
                .filter { game.state != "pre" || matched.broadcasts.any { broadcast -> broadcast.channel.id == it.id } }, latestQuality, android.os.SystemClock.elapsedRealtime())
            sportsResults = GameStreams(matched, channels)
            val streamLabel: (Channel) -> String = { (if (it.id == current?.id) "Current · " else "") + gameStreamLabel(matched, it) }
            if (fromScores) {
                scoreStripLoading = null
                if (alternatives.isEmpty()) { trace?.finish("no confirmed feed"); scoreStrip.tuning(game.key, "No confirmed feed · Try Sports"); return }
                scoreStrip.tuning(null)
                sportsPlaybackPlan = SportsPlaybackPlan(matched, alternatives, backgroundRecovery = true).also { it.trace = trace }
                if (playNextSportsCandidate("Opening game stream")) { scoreStrip.releaseFocus(); syncScoreStrip() }
                else { trace?.finish("no available feed"); scoreStrip.tuning(game.key, "No available feed · Try Sports") }
                return
            }
            if (autoWatch) {
                if (alternatives.isNotEmpty()) {
                    showGameInfo(matched); sportsChannels = channels.take(4)
                    sportsPanel.showStreams(matched, channels, null, streamLabel)
                    multiview.close(); sportsPlaybackPlan = SportsPlaybackPlan(matched, alternatives).also { it.trace = trace }
                    if (reminderWatch) gameWatchPanel.dismiss()
                    showGuide(false)
                    if (!playNextSportsCandidate("Opening best game stream")) {
                        trace?.choices(channels.size); showSportsAlternatives("The available streams changed. Choose another result.")
                    }
                    return
                }
            }
            showGameInfo(matched); sportsChannels = channels.take(4); scheduleEpg()
            if (channels.isEmpty()) trace?.finish("no results") else trace?.choices(channels.size)
            sportsPanel.showStreams(matched, channels, if (autoWatch) SportsStreams.unavailableReason(matched, channels, latestQuality, now) else null, streamLabel)
            if (reminderWatch) { gameWatchPanel.dismiss(); applyFilter(); showGuide(true) }
        }
        if (fastCached != null && fastCached.autoReady) { present(fastCached); return }
        sportsMatchTask = sportsMatchWorker.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val cacheContext = sportsStreamCache.use(selected.id, sourceEpoch, snapshot, favoritesSnapshot)
            val cached = sportsStreamCache.get(cacheContext, game, System.currentTimeMillis(), forAuto = autoWatch)
            trace?.workerCache(cached?.autoReady == true)
            if (cached != null && cached.autoReady) { handler.post { present(cached) }; return@submit }
            fun progress(message: String, checked: Int, total: Int) { handler.post { if (!closed && started && matchEpoch == sportsMatchEpoch && targetActive()) {
                if (fromScores) scoreStrip.tuning(game.key, message) else sportsPanel.matchingProgress(game, message, checked, total)
                if (reminderWatch && gameWatchPanel.isVisible) gameWatchPanel.update(message, checked, total)
            } } }
            val prepared = runCatching { resolveSportsMatch(selected, game, snapshot, favoritesSnapshot, profile, quality, true, ::progress, cached, trace) }.getOrElse {
                trace?.finish(if (Thread.currentThread().isInterrupted) "cancelled" else "matching failed")
                if (!Thread.currentThread().isInterrupted) handler.post {
                    if (!closed && started && sourceEpoch == generation && matchEpoch == sportsMatchEpoch && targetActive()) {
                        if (fromScores) { scoreStripLoading = null; scoreStrip.tuning(game.key, "Could not find stream · Retry"); return@post }
                        sportsPanel.showMatchNotice("Could not finish finding this game. Go back and try again.")
                        if (reminderWatch) { gameWatchPanel.dismiss(); applyFilter(); showGuide(true) }
                    }
                }
                return@submit
            }
            sportsStreamCache.put(cacheContext, prepared, System.currentTimeMillis())
            handler.post { present(prepared) }
        }
    }

    private fun startMultiview(channel: Channel) {
        clearSportsPlayback(); stopSportsPrefetch()
        val selected = owner(channel) ?: return
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
        val id = store.recentFeeds.firstOrNull { it != current?.id && guide.channels.any { c -> c.id == it } } ?: if (current?.id != last) last else store.previous(selected)
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
        val recentIds = (store.recentFeeds.ifEmpty { source?.let(store::recent).orEmpty() }).filter { it != channel.id }
        if (recentLookupChannels !== guide.channels) { recentLookupChannels = guide.channels; recentLookup = guide.channels.associateBy { it.id } }
        val target = programme?.let(VodStart::request)
        val selected = owner(channel)
        val canVod = target != null && enabledSources().any { library.repository.matches(it, target).isNotEmpty() }
        val status = if (selected?.kind == "xtream" && target != null) playlistSearch.catalogStatus(selected, if (target.episode == null) setOf("movie", "series") else setOf("series")) else null
        val availability = if (status == null) VodAvailability.Status(false, "") else vodAvailability.update(
            listOf(channel.id, programme?.start, programme?.end, target, sources), android.os.SystemClock.elapsedRealtime(),
            canVod, status.complete, status.checking, status.failed)
        details.update(channel, programme, next, (videoInfo[channel.id]?.label() ?: "Quality unverified") + (if (enabledSources().size > 1) " · ${selected?.name.orEmpty()}" else ""), channel.id in favorites, previousChannel() != null, Catchup.supported(channel), recentIds.mapNotNull(recentLookup::get), selected?.allowHttp == true, (if (watchingRedZone) "NFL RedZone" else playbackGame()?.game?.title), canVod, target?.let(VodStart::targetLabel).orEmpty(), availability.checking, availability.message)
        handler.removeCallbacks(updateVodDetails)
        if (details.isVisible && availability.checking) handler.postDelayed(updateVodDetails, 1000)
    }
    private fun playbackDiagnostics(): String {
        val format = player.videoFormat
        fun rate(value: Float) = if (value.isFinite() && value > 0) "%.2f".format(java.util.Locale.US, value) else "unknown"
        val evidence = if (format == null) "" else "\n${format.sampleMimeType?.removePrefix("video/").orEmpty()} · Input ${rate(sourceVideoFrames.rate(playbackToken))} · Declared ${rate(format.frameRate)} · Decoder ${rate(decoderFrameRate)} · Display samples ${rate(measuredFrameRate)} fps · $scanEvidence · ${current?.let { videoInfo[it.id]?.evidence }.orEmpty()}"
        val transport = if (player.currentManifest is androidx.media3.exoplayer.hls.HlsManifest) "HLS" else if (playbackUrl.substringBefore('?').endsWith(".ts", true)) "TS" else "Stream"
        val health = "\n$transport · Buffer ${"%.1f".format(java.util.Locale.US, player.totalBufferedDuration.coerceAtLeast(0) / 1000.0)} s · $playbackRebuffers stalls · $playbackLoadErrors load errors · $playbackReconnects reconnects"
        val presentation = "Video · $playbackDroppedFrames dropped frames this playback attempt\nDisplay ${rate(frameRates.displayRefreshRate)} Hz · Requested ${rate(frameRates.requestedRate)} fps · Matching ${if (frameRates.enabled) "on" else "off"}"
        val audio = player.audioFormat
        return listOf(onDemand?.title ?: current?.name ?: "No stream playing", lastVideoInfo?.label().orEmpty(), evidence.trim(), health.trim(), presentation,
            audio?.let { "Audio · ${it.sampleMimeType?.removePrefix("audio/").orEmpty()} · ${it.channelCount.takeIf { count -> count > 0 } ?: "unknown"} channels" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }
    private fun showDetails() {
        if (onDemand != null) { showDemandControls(); return }
        if (current == null) return
        handler.removeCallbacks(hideNowPlaying); nowPlaying.hide(immediate = true)
        suspendSportsOverlay()
        source?.let(playlistSearch::prepare)
        details.resumeImages(); refreshDetails(); root.bringChildToFront(details); details.visibility = View.VISIBLE; details.focusActions()
        if (details.checkingVod) { handler.removeCallbacks(updateVodDetails); handler.postDelayed(updateVodDetails, 1000) }
        handler.removeCallbacks(hideDetails); handler.postDelayed(hideDetails, 10000)
    }
    private fun closeDetails() {
        handler.removeCallbacks(hideDetails)
        handler.removeCallbacks(updateVodDetails)
        if (::details.isInitialized) { details.pauseImages(); details.visibility = View.GONE }
    }
    private fun configurePlaylists(next: List<Source>) {
        store.saveSources(next); sources = next
        if (::playlists.isInitialized) playlists.configure(next)
        source = next.firstOrNull { it.id == source?.id && it.enabled } ?: next.firstOrNull { it.enabled }
        store.active = source?.id.orEmpty(); RefreshJobs.configure(this, next)
    }
    private fun setPlaylistEnabled(selected: Source, enabled: Boolean) {
        flushVideoReadings(); cacheEpg.run()
        val replacement = selected.copy(enabled = enabled)
        configurePlaylists(sources.map { if (it.id == selected.id) replacement else it })
        if (!::playlists.isInitialized || source == null || guide.channels.isEmpty()) { if (source == null) clearRemovedPlaylist() else load(source!!); settingsPanel?.refresh(); return }
        stopSportsPrefetch(clear = true); ++sportsMatchEpoch; sportsMatchTask?.cancel(true); clearSportsPlayback()
        if (enabled) loadAdditionalPlaylist(replacement)
        else detachPlaylist(selected)
        enabledSources().filter { it.id != source?.id && it.id !in providerViews }.forEach { loadAdditionalPlaylist(it) }
        groupsForChannels = null; updateGroups(); applyFilter(); settingsPanel?.refresh()
    }
    private fun detachPlaylist(selected: Source) {
        restoringProgrammes.remove(selected.id)
        playlistVersions[selected.id] = (playlistVersions[selected.id] ?: 0) + 1
        providerViews.remove(selected.id); providerOrders.remove(selected.id); playlistMetadata.remove(selected.id); playlistLoading.remove(selected.id); playlistFilters.remove(selected.id)
        val removed = guide.channels.filter { playlistId(it) == selected.id }; val keys = removed.map(guide::key).toSet()
        guide = Guide(guide.channels.filter { playlistId(it) != selected.id }, guide.programmes.filterKeys { it !in keys }, providerOrders[source?.id].orEmpty())
        favorites = favorites.filterNot { playlists.belongs(selected, it) }.toSet()
        videoInfo.keys.removeAll { playlists.belongs(selected, it) }
        unavailable = UnavailableChannels(unavailable.snapshot().filterKeys { !playlists.belongs(selected, it) }.toMutableMap())
        if (browsePlaylist == selected.id) { browsePlaylist = source?.id; group = null }
        if (current?.let { playlistId(it) == selected.id } == true || onDemand?.playlistId == selected.id) {
            saveDemandProgress(); onDemand = null; current = null; cancelPlaybackCallbacks(); playbackToken++; expectedMediaId = ""; player.stop(); multiview.close()
            guide.channels.firstOrNull { !it.media }?.let { tune(it) }
        }
        multiview.removePlaylist(selected.id)
        focused = guide.channels.firstOrNull { it.id == focused?.id }
        syncReplayDiscovery()
    }
    private fun editSource(existing: Source?) = SourceDialog.show(this, existing, { selected ->
        existing?.let(store::bindProvider)
        runCatching { val next = if (existing == null) sources + selected else sources.map { if (it.id == selected.id) selected else it }; configurePlaylists(next) }
            .onSuccess {
                existing?.let { library.invalidateProvider(it.id) }
                if (settingsPanel?.opened == true) closeSettings()
                if (existing != null || !::playlists.isInitialized || guide.channels.isEmpty()) source?.let { load(it, force = existing != null && it.id == selected.id) }
                else { stopSportsPrefetch(clear = true); loadAdditionalPlaylist(selected, force = true); updateGroups() }
            }.onFailure { notice("Could not save", "Android could not securely save this source. Try again.") }
    }, { removed ->
        if (source?.id == removed.id) { handler.removeCallbacks(saveVideoReadings); videoSavePending = false }
        runCatching {
            flushVideoReadings(); cacheEpg.run(); configurePlaylists(sources.filterNot { it.id == removed.id })
            // Cancel catalog producers before deleting their files so a late import cannot recreate them.
            library.invalidateProvider(removed.id)
            cacheWrites.execute { store.removeCache(removed) }
        }
            .onSuccess {
                if (settingsPanel?.opened == true) closeSettings()
                if (source == null) clearRemovedPlaylist() else { detachPlaylist(removed); updateGroups(); applyFilter() }
            }.onFailure { notice("Could not remove", "Android could not update source storage. Try again.") }
    })
    /** Deleting the last source must also clear VOD/resume and every source-owned callback. */
    private fun clearRemovedPlaylist() {
        stopSportsPrefetch(clear = true); clearSportsPlayback()
        ++generation; ++mergeEpoch; deferredRefresh = null; refreshRetryAt = 0L
        handler.removeCallbacks(saveVideoReadings); videoSavePending = false; recentLookupChannels = null; recentLookup = emptyMap()
        importTask?.cancel(true); cancelPlaybackCallbacks(); cancelEpgSave(); handler.removeCallbacks(fetchVisibleEpg)
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
        suspendSportsOverlay()
        settingsReturnGuide = guideVisible; settingsReturnLibrary = library.opened; ++focusEpoch; handler.removeCallbacks(backHold)
        showGuide(false); nowPlaying.hide(immediate = true); previewCaption.visibility = View.GONE
        val panel = existing ?: SettingsPanel(this, ::renderSettingsPage, ::closeSettings).also { settingsPanel = it; it.setPadding(dp(if (store.hiddenDock) 28 else 104), dp(24), dp(24), dp(24)); it.leaveNavigation = { settingsButton.requestFocus() }; root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.bringChildToFront(panel)
        showNavigation("settings")
        panel.background = GuideTheme.guideBackground(store.guideOpacity)
        panel.open(listOf("Playlists", "Local channels", "Playback", "Theme", "Guide", "Categories", "Sports", "Metadata", "Updates", "About Spare", "Open-source licenses", "Exit Spare"))
        syncScoreStrip()
    }
    private fun closeSettings() {
        settingsPanel?.visibility = View.GONE
        if (settingsReturnLibrary && library.opened) { root.bringChildToFront(library.panel); showNavigation(if (library.browsingCatchup) "guide-mode" else "library"); library.panel.focusToolbar() } else showGuide(settingsReturnGuide)
        if (settingsReturnGuide) { ++focusEpoch; if (store.hiddenDock) focusChannel() else settingsButton.requestFocus() }
    }
    private fun renderSettingsPage(page: String) {
        val panel = settingsPanel ?: return
        if (page != "Theme") themeEditor = null
        if (page != "Local channels") localEditor = null
        val parent = when (page) {
            "Frame-rate matching", "Audio track", "Subtitles", "Stream format", "Sports stream recovery", "Playback diagnostics", "Temporarily hidden channels" -> "Playback"
            "Developer tools" -> "Playback diagnostics"
            "Sports overlay" -> "Sports"
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
                panel.page("Playlists", "Enable the playlists you want in Guide, Search and Sports. Collapse playlist categories in the guide to keep browsing simple. Credentials stay encrypted on this TV.")
                sources.forEach { selected ->
                    panel.action(selected.name, if (selected.enabled) "Enabled" + if (selected.id == source?.id) " · Main playlist" else " · Included in Search and Sports" else "Disabled", selected.enabled) { setPlaylistEnabled(selected, !selected.enabled) }
                    if (selected.enabled) panel.action("Browse · ${selected.name}") { closeSettings(); browsePlaylist = selected.id; group = null; favoritesOnly = false; localOnly = false; sportsVisible = false; applyFilter(); showGuide(true); focusChannel() }
                    panel.action("Edit · ${selected.name}") { editSource(selected) }
                    panel.action("Connection limit · ${selected.name}", if (selected.maxStreams == 0) "Not set · App supports up to four views" else "${selected.maxStreams} simultaneous streams") {
                        val limit = (selected.maxStreams + 1) % 5
                        configurePlaylists(sources.map { if (it.id == selected.id) selected.copy(maxStreams = limit) else it }); panel.refresh("Connection limit · ${selected.name}")
                    }
                    if (selected.enabled && source?.id != selected.id) panel.action("Make main · ${selected.name}") { closeSettings(); load(selected) }
                }
                sources.forEach { selected -> panel.action("Refresh settings · ${selected.name}") { renderRefreshSettings(selected); panel.focusContent() } }
                panel.action("Add playlist") { editSource(null) }; if (source != null) panel.action("Edit current playlist") { editSource(source) }
            }
            "Local channels" -> {
                panel.page(page, "Enter a U.S. city or state. Matches update automatically from your playlist; location stays on this device.")
                val editor = LocalChannelsEditor(this, store.localLocation, { value ->
                    store.localLocation = value; localLineup = null; requestLocalMatches(); if (localOnly) applyFilter()
                }, { localOnly = true; favoritesOnly = false; group = null; sportsVisible = false; closeSettings(); applyFilter(); showGuide(true) }, { localStations }, localWorker)
                localEditor = editor; panel.custom(editor, 540); requestLocalMatches(); editor.updateMatches(localIds.size, localLoading)
            }
            "Playback" -> {
                panel.page(page, "Player controls for live TV, catch-up, movies and series. Changes keep this settings surface open.")
                panel.action("Sleep timer", SleepTimer.label(sleepAt, System.currentTimeMillis()), sleepAt > 0) {
                    setSleepTimer(SleepTimer.next(sleepAt, System.currentTimeMillis())); refresh("Sleep timer")
                }
                listOf("Frame-rate matching", "Audio track", "Subtitles", "Stream format", "Sports stream recovery", "Playback diagnostics", "Temporarily hidden channels").forEach { label ->
                    panel.action(label, when (label) { "Frame-rate matching" -> if (store.frameRateMatching) "On" else "Off"; "Subtitles" -> "Channel tracks, app defaults, size and style"; "Stream format" -> playbackSource()?.let(store::streamFormat).orEmpty(); "Sports stream recovery" -> if (store.sportsAutoFailover) "After ${store.sportsFailoverSeconds}s buffering" else "Off"; else -> "" }) { renderSettingsPage(label); panel.focusContent() }
                }
                panel.note("Live buffering adapts to available data. Sustained stalls reconnect automatically; OK offers other streams for recognized games.")
            }
            "Playback diagnostics" -> {
                panel.page(page, "Current stream information · Provider addresses and credentials are never shown.")
                panel.note(playbackDiagnostics())
                panel.action("Refresh diagnostics") { refresh("Refresh diagnostics") }
                panel.action("Copy redacted playback report", "Local clipboard only · No provider addresses or credentials") {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Spare playback report", playbackJournal.report(BuildConfig.VERSION_NAME)))
                    toast("Playback report copied")
                }
                panel.action("Clear playback history") { playbackJournal.clear(); refresh("Clear playback history") }
                panel.action("Developer tools") { renderSettingsPage("Developer tools"); panel.focusContent() }
                panel.note(playbackJournal.report(BuildConfig.VERSION_NAME))
            }
            "Developer tools" -> {
                panel.page(page, "Local measurements for playback and the guide. Leave off for normal viewing.")
                panel.action("Performance overlay", if (store.performanceOverlay) "On · retune for decoder and settle timing" else "Off", store.performanceOverlay) {
                    store.performanceOverlay = !store.performanceOverlay
                    syncPerformanceOverlay()
                    refresh("Performance overlay")
                }
            }
            "Guide" -> {
                panel.page(page, "Browsing preferences. Appearance, fonts, spacing, opacity and preview layout are in Theme.")
                panel.action("Clear recent searches", if (store.recentSearches.isEmpty()) "None saved" else "${store.recentSearches.size} saved on this TV") {
                    store.recentSearches = emptyList(); searchPanel.recent = emptyList(); refresh("Clear recent searches")
                }
                panel.action("Switch categories on highlight", if (store.guideAutoCategories) "On · show channels as you browse" else "Off · press OK to select", store.guideAutoCategories) {
                    store.guideAutoCategories = !store.guideAutoCategories; cancelCategoryPreview(); refresh("Switch categories on highlight")
                }
                panel.action("Open channels full screen", if (store.guideOpenFullscreen) "On · OK plays full screen" else "Off · preview first; select again for full screen", store.guideOpenFullscreen) {
                    store.guideOpenFullscreen = !store.guideOpenFullscreen; refresh("Open channels full screen")
                }
                panel.action("Loop guide channels", if (store.guideLoopChannels) "On · last channel wraps to first" else "Off · stop at either end", store.guideLoopChannels) {
                    store.guideLoopChannels = !store.guideLoopChannels; refresh("Loop guide channels")
                }
                listOf("Navigation dock", "Time format").forEach { label -> panel.action(label) { renderSettingsPage(label); panel.focusContent() } }
                panel.action("Theme editor") { panel.selectSetting("Theme") }
            }
            "Navigation dock" -> {
                panel.page(page, "Left or Back from categories opens the main menu. Right returns to your current screen; OK opens an item.")
                listOf("Hidden dock", "Always open").forEachIndexed { index, label -> panel.action(label, selected = store.hiddenDock == (index == 0)) {
                    store.hiddenDock = index == 0; applyDockPreference(); refresh(label)
                } }
            }
            "Sports stream recovery" -> {
                panel.page(page, "When a matched live game keeps buffering, switch to another confirmed feed from the saved results. Uses one player; no stream probes. Disabled during multiview and on-demand playback. At most three recovery rounds in ten minutes; failed feeds are not revisited.")
                panel.action("Automatic switching", if (store.sportsAutoFailover) "On" else "Off", store.sportsAutoFailover) { store.sportsAutoFailover = !store.sportsAutoFailover; sportsRecovery.pause(); refresh("Automatic switching") }
                listOf(10, 15, 20, 30).forEach { seconds -> panel.action("$seconds seconds", "Continuous buffering before switching", store.sportsFailoverSeconds == seconds) { store.sportsFailoverSeconds = seconds; sportsRecovery.pause(); refresh("$seconds seconds") } }
            }
            "Frame-rate matching" -> {
                panel.page(page, "Match the TV refresh rate to the current stream. Your TV may briefly go black when changing modes. Off leaves display timing to Android.")
                listOf("On", "Off").forEachIndexed { index, label -> panel.action(label, selected = store.frameRateMatching == (index == 0)) {
                    store.frameRateMatching = index == 0; frameRates.enabled = store.frameRateMatching
                    if (store.frameRateMatching && !multiview.active) (if (onDemand != null) lastVideoInfo?.fps else videoInfo[current?.id]?.fps)?.let(frameRates::request)
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
                panel.page("Theme studio", "Choose a quick preset, or open Advanced to edit every detail. Changes save automatically.")
                val editor = ThemeEditor(this, store, { if (!closed) appearanceChanged() }, { panel.focusNavigation() })
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
            "Sports overlay" -> {
                panel.page(page, "Open Sports overlay from a live channel’s OK menu; it stays visible until closed. Press Up to focus it, Left/Right to browse games, OK to watch, and Back to return focus to playback.")
                panel.action("Show sports overlay", if (store.scoreOverlayPinned) "Visible until closed" else "Open from OK → Sports overlay", store.scoreOverlayPinned) { store.scoreOverlayPinned = !store.scoreOverlayPinned; syncScoreStrip(); refresh("Show sports overlay") }
                listOf("Top", "Bottom").forEach { position -> panel.action(position, selected = store.scoreOverlayPosition == position) { store.scoreOverlayPosition = position; syncScoreStrip(); refresh(position) } }
            }
            "Sports" -> {
                panel.page("Sports", "Mark upcoming games with Remind me. Reminders appear at the scheduled start over playback while Spare is open. Schedule updates adjust rescheduled games. Watch best prefers the highest-resolution matching game stream. Known FPS breaks resolution ties. Broadcaster matches are labelled when game coverage is unconfirmed. All qualities remain playable.")
                panel.action("Sports overlay", "Compact live scores · ${store.scoreOverlayPosition}" ) { renderSettingsPage("Sports overlay"); panel.focusContent() }
                panel.action("Play best stream on selection", "Skip live-game actions and play the highest-resolution match. Known FPS breaks resolution ties. Failed starts can try another match, up to three attempts. FPS is learned from normal playback.", selected = store.sportsAutoPlay) { store.sportsAutoPlay = !store.sportsAutoPlay; refresh("Play best stream on selection") }
                panel.action("Hide completed-game scores", selected = store.hideCompletedScores) { store.hideCompletedScores = !store.hideCompletedScores; sportsPanel.setSpoilers(store.hideCompletedScores); selectedGame?.let(::showGameInfo); refresh("Hide completed-game scores") }
                panel.action("Stream quality", "Resolution and FPS preferences apply to Sports only") { openSportsQuality() }
                panel.action("Score delay", delayLabel(store.sportsScoreDelay)) { openSportsScoreDelay() }
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
                val selected = playbackSource()
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
        return (if (PlaylistRefresh.running(selected.id)) PlaylistRefresh.stage(selected.id) + "\n" else "") + "Playlist: ${saved(store.playlistRefreshed(selected))}\nEPG refresh: ${saved(store.epgRefreshed(selected))}" + store.refreshError(selected).takeIf { it.isNotBlank() }?.let { "\n$it · Saved data is kept." }.orEmpty() +
            Timings.summary(listOf("Channels", "Saved guide")).takeIf { it.isNotBlank() }?.let { "\nLast start: $it" }.orEmpty() +
            Timings.summary(listOf("Category", "Movie details", "Series info")).takeIf { it.isNotBlank() }?.let { "\nProvider replies: $it" }.orEmpty()
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
            }.setNegativeButton("Cancel", null).create().also(::themed).show()
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
            val saved = store.channelCaptions(owner(current) ?: source!!, current!!)
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
        panel.page("Subtitle appearance", "Shared by live TV, movies and series. Text tracks use your size and style; Image tracks are resized but retain their original lettering.")
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
        source?.let { store.setFilters(it, next); playlistFilters[it.id] = next }; filters = GuideFilters(groups = next.groups); group = null; applyFilter()
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
        visit(root); navigationRail.appearance(); AppFont.apply(root); if (::library.isInitialized) library.panel.appearance(store.guideOpacity); playbackLoading.indeterminateTintList = android.content.res.ColorStateList.valueOf(GuideTheme.current.accent)
        settingsPanel?.background = GuideTheme.guideBackground(store.guideOpacity)
        sportsManager?.background = GuideTheme.guideBackground(store.guideOpacity)
        sportsCalendar?.background = GuideTheme.guideBackground(store.guideOpacity)
        if (::scoreStrip.isInitialized) scoreStrip.appearance()
        if (::searchPanel.isInitialized) searchPanel.appearance(store.guideOpacity)
        if (::vodStartPanel.isInitialized) vodStartPanel.appearance()
        if (::details.isInitialized) details.appearance()
        if (::demandControls.isInitialized) demandControls.appearance()
        loadingPanel.background = GuideTheme.glass(resources.displayMetrics.density); retryPanel.background = GuideTheme.glass(resources.displayMetrics.density)
        if (::sportsPanel.isInitialized) sportsPanel.refreshTheme()
        sidebarAdapter.notifyItemRangeChanged(0, sidebarAdapter.itemCount); adapter.refreshTheme()
    }
    private fun subtitleMode(): String = (if (onDemand != null) demandSubtitleMode else owner(current)?.let { selected -> current?.let { channel -> store.channelCaptions(selected, channel) } })
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
        val selected = playbackSource() ?: return; val channel = current
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
        if (event.action == KeyEvent.ACTION_DOWN) lastInputAt = android.os.SystemClock.elapsedRealtime()
        if (::gameWatchPanel.isInitialized && gameWatchPanel.isVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancelReminderWatch(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (::vodStartPanel.isInitialized && vodStartPanel.isVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeVodStart(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (::searchPanel.isInitialized && searchPanel.isVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishSearch(true); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) voiceSearch(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (sportsLeaguePicker?.isShown == true) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeSportsLeaguePicker(); return true }
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
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && scoreStripEntryPressed) {
            if (event.action == KeyEvent.ACTION_UP) scoreStripEntryPressed = false
            return true
        }
        if (::scoreStrip.isInitialized && scoreStrip.isVisible && !scoreStrip.interactive && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) { scoreStripEntryPressed = true; scoreStripOpen = true; syncScoreStrip(); scoreStrip.enter() }
            return true
        }
        if (::scoreStrip.isInitialized && scoreStrip.isVisible && scoreStrip.interactive) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) scoreStrip.releaseFocus(); return true }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH)) { scoreStrip.releaseFocus() }
            else return super.dispatchKeyEvent(event)
        }
        if (guideVisible && pendingFocusList != null && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
        if (guideVisible && !sportsVisible && list.hasFocus()) {
            RemoteKeys.zap(event.keyCode)?.let { step ->
                if (event.action == KeyEvent.ACTION_DOWN && adapter.channels.isNotEmpty()) {
                    val layout = list.layoutManager as LinearLayoutManager
                    val page = (layout.findLastVisibleItemPosition() - layout.findFirstVisibleItemPosition()).coerceAtLeast(1)
                    val at = list.focusedChild?.let(list::getChildAdapterPosition)?.takeIf { it >= 0 } ?: 0
                    // Channel+ moves down the list, matching full screen where +1 is the next channel.
                    focusChannelAt((at + step * page).coerceIn(adapter.channels.indices))
                }
                return true
            }
        }
        if (guideVisible && event.keyCode == KeyEvent.KEYCODE_GUIDE) { if (event.action == KeyEvent.ACTION_UP && current != null && !sportsVisible) showGuide(false); return true }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_UP) playbackMenu(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) { if (event.action == KeyEvent.ACTION_UP) search(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { backLongPressed = false; handler.postDelayed(backHold, ViewConfiguration.getLongPressTimeout().toLong()) }
            if (event.action == KeyEvent.ACTION_UP) { handler.removeCallbacks(backHold); if (!backLongPressed && !event.isCanceled) navigateBack() }
            return true
        }
        if (onDemand != null && !guideVisible && retryPanel.visibility != View.VISIBLE) {
            if (sleepEnded && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                if (event.action == KeyEvent.ACTION_UP) resumeAfterSleep(); return true
            }
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
                if (event.action == KeyEvent.ACTION_DOWN) zapBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                return true
            }
            // Dedicated remote keys: Channel +/-, Page, Last channel, Guide and number entry.
            RemoteKeys.zap(event.keyCode)?.let { step -> if (event.action == KeyEvent.ACTION_DOWN) zapBy(step); return true }
            if (event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) { if (event.action == KeyEvent.ACTION_UP) recallPrevious(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_GUIDE) { if (event.action == KeyEvent.ACTION_UP) showGuide(true); return true }
            RemoteKeys.digit(event.keyCode)?.let { digit -> if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) typeChannelDigit(digit); return true }
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !resumeAfterSleep() && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) showDetails()
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
        if (::scoreStrip.isInitialized && scoreStrip.isVisible && scoreStrip.interactive) { scoreStrip.releaseFocus(); return }
        if (redZoneAutoPending) { redZoneAutoPending = false; ++sportsMatchEpoch; sportsMatchTask?.cancel(true); sportsPanel.finishTuning(); return }
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
        else if (!guideVisible) {
            // Back follows what is on screen: a game returns to the Sports cards (not its stream list);
            // any other channel, such as one recalled with Left, returns to the guide on that channel.
            if (sportsVisible && onDemand == null && !watchingRedZone && playbackGame() == null) {
                stopSportsPrefetch(); sportsVisible = false; focused = current; applyFilter()
            }
            val toCards = sportsVisible && sportsPanel.watching
            showGuide(true); if (toCards) sportsPanel.back()
        }
        else if (list.hasFocus() && guideTimeline.ahead((focused ?: current)?.id)) resetTime()
        else if (sportsVisible && sportsPanel.back()) return
        else if (list.hasFocus() || (sportsVisible && sportsPanel.hasFocus())) focusSidebar()
        else if (sidebar.hasFocus()) openNavigation()
        else if (!sportsVisible && navigationRail.hasFocus() && current != null) showGuide(false)
        else focusChannel()
    }
    override fun onStart() { super.onStart(); started = true; syncPerformanceOverlay(); if (::searchPanel.isInitialized && searchPanel.isVisible) updatePlaylistSearch(); sportsCalendar?.takeIf { it.opened }?.let { previewCalendarDate(it.activeDate, false) }; handler.removeCallbacks(refreshTick); handler.post(refreshTick); deferredRefresh?.let { deferredRefresh = null; mergeRefresh(it) }; if (::programmeArtwork.isInitialized) programmeArtwork.resume(); if (::player.isInitialized && (current != null || onDemand != null)) playAttempt(if (hasPlayedChannel) PlaybackPhase.RECONNECTING else PlaybackPhase.OPENING); scheduleEpg(); scheduleSports(); if (onDemand != null) { handler.removeCallbacks(demandTick); handler.postDelayed(demandTick, 1000) } }
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
        sportsLeaguePicker?.visibility = View.GONE
        if (::updater.isInitialized) updater.pause()
        // Stop audio before any cache, image or lifecycle cleanup. A paused TV app
        // must never continue playing over the launcher or another app.
        if (::player.isInitialized) { pausedPlayback = player.playWhenReady; if (onDemand != null) demandPaused = !player.playWhenReady; player.pause(); saveDemandProgress() }
        cancelPlaybackCallbacks(); stopSportsPrefetch(); clearSportsPlayback(); replayDiscovery?.pause()
        if (::multiview.isInitialized) multiview.close()
        if (::sportsPanel.isInitialized) sportsPanel.pauseImages()
        super.onPause()
    }
    override fun onStop() {
        performanceOverlay?.close()
        performanceOverlay = null
        scoreStripEntryPressed = false; if (::scoreStrip.isInitialized) { cancelScoreTune(); scoreStripOpen = false; scoreStrip.suspend() }; sportsRecovery.pause(); if (vodRequest != null) closeVodStart(restore = false); if (::playlistSearch.isInitialized) playlistSearch.pause(cancel = true); replayDiscovery?.pause(); if (::player.isInitialized) player.stop(); calendarSchedule?.cancel(); flushVideoReadings(); pausedPlayback = null; ++sportsMatchEpoch; sportsMatchTask?.cancel(true); pendingSportsTune = null; if (::gameWatchPanel.isInitialized) gameWatchPanel.dismiss(); dismissGameReminder(); handler.removeCallbacks(refreshTick); if (::programmeArtwork.isInitialized) programmeArtwork.pause(); if (::multiview.isInitialized) multiview.close(); saveDemandProgress(); handler.removeCallbacks(demandTick); handler.removeCallbacks(pollLiveSports); handler.removeCallbacks(hideDemandControls); cancelCaptionProbe(); if (::voiceInput.isInitialized) voiceInput.stop(); voiceSearchPending = false; started = false; firstFrame = false; expectedMediaId = ""; closeDetails(); cancelEpgSave(); cacheEpg.run(); handler.removeCallbacks(backHold); handler.removeCallbacks(fetchVisibleEpg); cancelEpgSave(); handler.removeCallbacks(renderSports); sportsRenderPending = false; ++sportsEpoch; sportsComputing = false; sportsDirty = false; sportsTask?.cancel(true); cancelPlaybackCallbacks(); if (::frameRates.isInitialized) frameRates.clear()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        if (::logos.isInitialized) logos.trim()
        if (::programmeArtwork.isInitialized) programmeArtwork.trim()
        if (::details.isInitialized) details.trimImages()
        if (::scoreStrip.isInitialized) scoreStrip.trimImages()
        if (::sportsPanel.isInitialized) sportsPanel.trimImages()
        if (::library.isInitialized) library.panel.trimImages()
        if (::searchPanel.isInitialized) searchPanel.trimImages()
    }

    override fun onDestroy() {
        if (::scoreStrip.isInitialized) scoreStrip.closeImages()
        if (::playlistSearch.isInitialized) playlistSearch.close()
        if (::searchPanel.isInitialized) searchPanel.closeImages()
        replayDiscovery?.close()
        localTask?.cancel(true)
        calendarSchedule?.close()
        flushVideoReadings()
        PlaylistRefresh.unlisten(this)
        if (::programmeArtwork.isInitialized) programmeArtwork.close()
        if (::multiview.isInitialized) multiview.close()
        metadata.close()
        details.closeImages()
        library.destroy()
        sportsPanel.closeImages()
        liveSportsTask?.cancel(true)
        if (::voiceInput.isInitialized) voiceInput.stop()
        closed = true
        ++generation
        handler.removeCallbacksAndMessages(null)
        workers.shutdown()
        sportsStreamCache.clear()
        logos.close()
        if (::updater.isInitialized) updater.close()
        if (::player.isInitialized) {
            video.player = null
            player.release()
        }
        super.onDestroy()
    }
    private fun notice(title: String, message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).create().also(::themed).show() }
    private fun themed(dialog: AlertDialog) { dialog.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(GuideTheme.glass(resources.displayMetrics.density), dp(24))) }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private var sleepAt = 0L
    private var sleepResume: Channel? = null
    private var sleepEnded = false
    private val sleepTimer = Runnable { endSleepTimer() }
    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepTimer)
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (minutes > 0) { handler.postDelayed(sleepTimer, minutes * 60_000L); toast("Sleep timer: playback stops in $minutes minutes") }
    }
    /** Stop playback and let the TV's own sleep take over; OK resumes the same channel live. */
    private fun endSleepTimer() {
        sleepAt = 0L; sleepEnded = true
        if (onDemand != null) { saveDemandProgress(); player.playWhenReady = false }
        else { sleepResume = current; player.stop() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cornerNotice("Sleep timer ended · press OK to keep watching")
    }
    private fun resumeAfterSleep(): Boolean {
        if (!sleepEnded) return false
        val channel = sleepResume
        clearSleepEnded()
        if (channel != null) tune(channel) else player.playWhenReady = true
        return true
    }
    private fun clearSleepEnded() {
        if (!sleepEnded) return
        sleepEnded = false; sleepResume = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); digitsOverlay?.visibility = View.GONE
    }
    private fun cornerNotice(text: String) {
        val overlay = digitsOverlay ?: TextView(this).apply {
            tag = "channel-number-entry"; textSize = 20f; typeface = AppFont.face(true); setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = GuideTheme.glass(resources.displayMetrics.density)
            root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(24); marginEnd = dp(24) })
        }.also { digitsOverlay = it }
        overlay.text = text; overlay.visibility = View.VISIBLE; root.bringChildToFront(overlay)
    }
    private fun zapBy(step: Int) {
        val channels = adapter.channels
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        tune(channels[Math.floorMod(index + step, channels.size)])
    }
    private var channelDigits = ""
    private var digitsOverlay: TextView? = null
    private val commitChannelDigits = Runnable {
        val typed = channelDigits; channelDigits = ""
        val target = RemoteKeys.channelAt(adapter.channels, typed)
        digitsOverlay?.let { overlay -> if (target == null) { overlay.text = "No channel $typed"; handler.postDelayed({ overlay.visibility = View.GONE }, 1200) } else overlay.visibility = View.GONE }
        if (target != null && !guideVisible) tune(target)
    }
    /** Number entry picks the Nth channel of the list being browsed, after a short pause for more digits. */
    private fun typeChannelDigit(digit: Int) {
        channelDigits = (channelDigits + digit).takeLast(4).trimStart('0')
        if (channelDigits.isEmpty()) return
        val target = RemoteKeys.channelAt(adapter.channels, channelDigits)
        cornerNotice(channelDigits + (target?.let { "  ·  ${it.name}" } ?: ""))
        handler.removeCallbacks(commitChannelDigits); handler.postDelayed(commitChannelDigits, 1500)
    }
}

package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = SecureStore(context)

    @Before fun resetGuideStyle() { store.resetScoreDelays(); store.guideAutoCategories = true; store.guideOpenFullscreen = true; store.guideLoopChannels = true; store.scoreOverlayPinned = false; store.scoreOverlayPosition = "Bottom"; store.sportsAutoFailover = false; store.sportsFailoverSeconds = 15; store.localLocation = ""; store.guideOpacity = -1; store.hideCompletedScores = false; store.sportsFollowedOnly = false; store.sportsAutoPlay = false; store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL"; store.frameRateMatching = true; store.smallPreview = false; store.hiddenDock = false; store.theme = "Monochrome"; store.themeStyle = "Glass"; store.customAccent = 0; store.customBackground = -1; store.cornerRadius = -1; store.borderWidth = -1; store.guideTextScale = 100; store.guideRowHeight = 32; store.guideRowGap = 3; store.subtitleSize = "Small"; store.subtitleStyle = "Outline"; store.captions = false; store.followedTeams = emptySet(); store.sportsTeam = ""; store.clock24 = false; store.font = "Default"; store.saveMetadataAddon("") }

    @Test fun encryptedStorageAuthenticatesDataAndNeverStoresPlaintext() {
        val secret = "spare-test-secret-credentials"
        try {
            store.write("test-security.enc", secret.toByteArray())
            val file = File(context.filesDir, "test-security.enc")
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(secret))
            assertEquals(secret, store.read("test-security.enc")!!.toString(Charsets.UTF_8))
            val corrupt = file.readBytes(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte(); file.writeBytes(corrupt)
            try { store.read("test-security.enc"); fail("Tampered ciphertext accepted") } catch (_: java.security.GeneralSecurityException) { }
            assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
            // AtomicFile is not thread-safe across separate SecureStore instances.
            val originalSources = store.sources(); val concurrentSource = Source(name = "Concurrent cache test", kind = "m3u", url = "https://example.com/playlist.m3u")
            val concurrentStore = SecureStore(context); val pool = Executors.newFixedThreadPool(2); val start = java.util.concurrent.CountDownLatch(1)
            try {
                store.saveSources(listOf(concurrentSource))
                val writer = pool.submit { start.await(); repeat(160) { store.saveSources(listOf(concurrentSource.copy(name = "Write $it"))) } }
                val reader = pool.submit { start.await(); repeat(160) { assertEquals("Concurrent reads retain the last committed source", 1, concurrentStore.sources().size) } }
                start.countDown(); writer.get(15, java.util.concurrent.TimeUnit.SECONDS); reader.get(15, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("Write 159", concurrentStore.sources().single().name)
            } finally { pool.shutdownNow(); store.saveSources(originalSources) }
            val files = ArtworkFiles(context); val url = "https://images.example/synthetic-${System.nanoTime()}.png"
            val bytes = ByteArrayOutputStream().also { output -> Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() } }.toByteArray()
            var downloads = 0
            assertArrayEquals(bytes, files.bytes(url) { downloads++; bytes }); assertArrayEquals(bytes, files.bytes(url) { downloads++; bytes }); assertEquals(1, downloads)
            val first = LogoLoader(16, 1024 * 1024, files); val second = LogoLoader(32, 1024 * 1024, files)
            first.prefetch(url); second.prefetch(url)
            val deadline = System.currentTimeMillis() + 5000
            while ((first.get(url, false) {} == null || second.get(url, false) {} == null) && System.currentTimeMillis() < deadline) Thread.sleep(30)
            assertNotNull(first.get(url, false) {}); assertNotNull(second.get(url, false) {}); first.close(); second.close()
            val source = Source(name = "Page test", kind = "xtream", url = "https://example.com")
            try {
                val catalog = LibraryCatalog(store); val writer = catalog.Writer(source, "movie")
                val entries = (1..2190).map { OnDemand("movie:$it", "Movie $it", if (it % 2 == 0) "Category 1" else "Category 2", "https://example.com/private/$it.mp4") }
                entries.chunked(64).forEach(writer::append)
                val checkpoint = writer.checkpoint(mapOf("1" to "Science fiction", "2" to "Drama"))
                val partial = LibraryCatalog(store).cached(source, "movie")!!
                assertFalse(partial.complete); assertEquals(checkpoint.count, partial.count)
                assertEquals("movie:2188", catalog.query(partial, null, "Movie 2188").entries.single().id)
                val snapshot = writer.finish(mapOf("1" to "Science fiction", "2" to "Drama"))
                val first = catalog.query(snapshot, "Science fiction", "", limit = 40)
                val second = catalog.query(snapshot, "Science fiction", "", first.cursor, 40)
                assertEquals(40, first.entries.size); assertTrue(first.more)
                assertTrue(first.entries.none { a -> second.entries.any { b -> a.id == b.id } })
                assertEquals("movie:188", catalog.query(snapshot, null, "Movie 188").entries.first().id)
                val restored = LibraryCatalog(store).cached(source, "movie")!!
                assertEquals(2190, restored.count); assertEquals(snapshot.groups, restored.groups)
                assertTrue("Catalog writes are batched", restored.chunks.size < 8)
                assertTrue(restored.chunks.all { it.keys.size == it.count && it.titles.size == it.count })
                val cold = LibraryCatalog(store); cold.cached(source, "movie")
                assertEquals("movie:2188", cold.matches(source, VodStart.Request("Movie 2188")).single().id)
                assertEquals("movie:2188", LibraryCatalog(store).query(restored, null, "Movie 2188").entries.single().id)
                assertTrue(catalog.query(restored.copy(scopeGroup = "Category 1"), null, "", limit = 40).entries.all { it.category == "Science fiction" })
                store.delete(restored.chunks.first().file)
                assertEquals("Index skips unrelated encrypted pages", "movie:2188", LibraryCatalog(store).query(restored, null, "Movie 2188").entries.single().id)
                assertTrue(context.filesDir.listFiles()!!.filter { it.name.startsWith("catalog-${source.id}-") }.none { it.readBytes().toString(Charsets.UTF_8).contains("https://example.com/private/") })
            } finally { store.removeCache(source) }

        FixtureServer(slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Shared import", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val repository = LibraryCatalog(store); val consumers = Executors.newFixedThreadPool(2)
            val partial = java.util.concurrent.CountDownLatch(1); val second = java.util.concurrent.CountDownLatch(1)
            try {
                val search = consumers.submit<LibraryCatalog.Snapshot> { repository.load(source, "movie") { if (!it.complete) partial.countDown() } }
                assertTrue(partial.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val library = consumers.submit<LibraryCatalog.Snapshot> { repository.load(source, "movie") { if (!it.complete) second.countDown() } }
                assertTrue(second.await(5, java.util.concurrent.TimeUnit.SECONDS)); server.finishVodCatalog.countDown()
                assertEquals(16, search.get(10, java.util.concurrent.TimeUnit.SECONDS).count)
                assertEquals(16, library.get(10, java.util.concurrent.TimeUnit.SECONDS).count)
                assertEquals("Search and Library must share the provider download", 1, server.catalogTargets.count { "get_vod_streams" in it })
                val otherAccount = source.copy(password = "different-account")
                assertNull(repository.memory(otherAccount, "movie")); assertNull(repository.cached(otherAccount, "movie"))
                assertTrue(repository.matches(otherAccount, VodStart.Request("Other movie 16")).isEmpty())
                assertEquals(1, repository.matches(source, VodStart.Request("Other movie 16")).size)
            } finally { server.finishVodCatalog.countDown(); repository.close(); consumers.shutdownNow(); store.removeCache(source) }
        }
        } finally { context.deleteFile("test-security.enc") }
    }

    @Test fun legacyFpsSettingsMigrateAndNewExclusionsPersist() {
        val source = Source(name = "Migration", kind = "xtream", url = "https://example.com")
        val prefs = context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sixty-${source.id}", true).putInt("min-height-${source.id}", 1080).putStringSet("groups-${source.id}", setOf("US | Sports")).commit()
        try {
            assertEquals(GuideFilters(1080, FrameRateFilter.onlySixty), store.sportsFilters(source))
            assertEquals(GuideFilters(groups = setOf("US | Sports")), store.filters(source))
            val profile = GuideFilters(2160, setOf(25, 50))
            store.setSportsFilters(source, profile)
            assertEquals(profile, SecureStore(context).sportsFilters(source))
            assertEquals(setOf("US | Sports"), store.filters(source).groups)
            store.setFilters(source, GuideFilters(groups = setOf("US | News")))
            assertEquals(profile, SecureStore(context).sportsFilters(source))
            assertEquals(GuideFilters(groups = setOf("US | News")), store.filters(source))
            assertFalse(prefs.contains("sixty-${source.id}"))
        } finally { store.removeCache(source) }
    }

    @Test fun savedStartupBackgroundRefreshAndScheduledJobKeepPlaybackAndFocus() {
        FixtureServer().use { server ->
            val source = Source(name = "Refresh fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val savedText = "#EXTM3U\n" + (1..8).joinToString("\n") { "#EXTINF:-1 tvg-id=\"test$it\" group-title=\"${if (it <= 4) "News" else "Culture"}\",Test Channel $it\n${server.base}/channel$it.mp4" }
            val channels = Importers.m3u(savedText, true).channels; val now = System.currentTimeMillis()
            val other = source.copy(id = "restore-other", name = "Other cached playlist", url = "${server.base}/other-playlist.m3u")
            val otherChannel = channels.first().copy(id = "other-channel", guideId = "other-guide")
            store.saveSources(listOf(source, other)); store.active = source.id
            store.cache(source, Guide(channels, mapOf("test1" to listOf(Programme("test1", "Saved programme", now - 60000, now + 3600000)))))
            store.cache(other, Guide(listOf(otherChannel), mapOf("other-guide" to listOf(Programme("other-guide", "Other saved programme", now - 60000, now + 3600000)))))
            store.setRefreshPolicy(other, RefreshPolicy()); store.markFullEpgAttempted(other); store.markEpgRefreshed(other); store.markPlaylistRefreshed(other)
            store.toggleFavorite(source, channels[1]); store.setLast(source, channels[0]); store.setRefreshPolicy(source, RefreshPolicy())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Saved launch must not request the playlist", 0, server.requests["/playlist.m3u"] ?: 0)
                var samePlayer: Player? = null; var item = ""
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player; item = samePlayer!!.currentMediaItem!!.mediaId }
                waitFor(scenario) { activity ->
                    val saved = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    saved.programmes.values.flatten().map { it.title }.containsAll(listOf("Saved programme", "Other saved programme"))
                }
                // Reproduce a lifecycle flush while just one owner's EPG remains
                // unrestored: another owner's listings must not erase its disk cache.
                val originalProgrammes = store.cachedProgrammes(source)
                val flushed = java.util.concurrent.CountDownLatch(1); var completeGuide: Guide? = null
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    completeGuide = field.get(activity) as Guide
                    field.set(activity, completeGuide!!.copy(programmes = completeGuide!!.programmes.filterValues { entries -> entries.any { it.title == "Other saved programme" } }))
                    @Suppress("UNCHECKED_CAST")
                    val restoring = MainActivity::class.java.getDeclaredField("restoringProgrammes").apply { isAccessible = true }.get(activity) as MutableSet<String>
                    restoring.add(source.id)
                    (MainActivity::class.java.getDeclaredField("cacheEpg").apply { isAccessible = true }.get(activity) as Runnable).run()
                    (MainActivity::class.java.getDeclaredField("cacheWrites").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService).execute { flushed.countDown() }
                }
                try {
                    assertTrue(flushed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals("A partial multi-playlist restore must retain the owner's encrypted EPG", originalProgrammes, SecureStore(context).cachedProgrammes(source))
                } finally { scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, completeGuide)
                    @Suppress("UNCHECKED_CAST")
                    val restoring = MainActivity::class.java.getDeclaredField("restoringProgrammes").apply { isAccessible = true }.get(activity) as MutableSet<String>
                    restoring.remove(source.id)
                } }
                scenario.onActivity { activity ->
                    store.markFullEpgAttempted(source); store.markEpgRefreshed(source)
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, false)
                    assertFalse("Missing Xtream archive flags must not repeatedly refresh an M3U playlist", PlaylistRefresh.running(source.id))
                }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadDown(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == channels[1].id }
                val refreshDone = java.util.concurrent.atomic.AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val method = MainActivity::class.java.getDeclaredMethod("refreshCurrent", Source::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
                    method.isAccessible = true; method.invoke(activity, source, true, true, { refreshDone.set(true); Unit })
                }
                waitFor(scenario) { refreshDone.get() && it.window.decorView.findViewWithTag<View>("background-refresh")?.isShown != true }
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(item, samePlayer!!.currentMediaItem!!.mediaId)
                    assertEquals(channels[1].id, (channelList(activity)?.findFocus() as? GuideAdapter.Row)?.channel?.id)
                }
                assertEquals(setOf(channels[1].id), store.favorites(source)); assertEquals(1, server.requests["/playlist.m3u"])
                assertTrue(store.epgRefreshed(source) > 0)
                // Force an actual Android job after making only the playlist schedule due.
                store.setRefreshPolicy(source, RefreshPolicy(playlistHours = 1))
                context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putLong("playlist-refreshed-${source.id}", 0).commit()
                scenario.onActivity { RefreshJobs.configure(it, listOf(source)) }
                val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
                val job = jobs.single { it.extras.getString("source") == source.id }
                assertTrue(job.isPersisted); assertFalse(job.extras.toString().contains(server.base))
                device.executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${job.id}")
                waitFor(scenario) { (server.requests["/playlist.m3u"] ?: 0) == 2 && !PlaylistRefresh.running(source.id) }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(item, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(samePlayer!!.isPlaying) }
                store.setRefreshPolicy(source, RefreshPolicy()); scenario.onActivity { RefreshJobs.configure(it, listOf(source)) }
                // An edited provider address cannot borrow the old provider's cache, which remains intact.
                val bad = source.copy(url = "${server.base}/missing-playlist.m3u")
                store.saveSources(listOf(bad)); val failed = java.util.concurrent.atomic.AtomicReference<PlaylistRefresh.Result>()
                PlaylistRefresh.request(context, bad, true, false) { failed.set(it) }
                waitFor(scenario) { failed.get() != null }
                assertTrue(failed.get().error.isNotBlank()); assertNull(store.cachedLineup(bad)); assertEquals(8, store.cachedLineup(source)!!.channels.size)
            }
            store.saveSources(emptyList()); RefreshJobs.configure(context, emptyList()); store.removeCache(source); store.removeCache(other)
        }
        FixtureServer(channelCount = 48, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Quiet cached launch", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = (1..48).map { Channel("xc:$it", "Channel $it", "News", "${server.base}/live/test/test-secret/$it.mp4", guideId = "xc:$it") }
            val stamp = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, mapOf("xc:1" to listOf(Programme("xc:1", "Saved programme", stamp - 60000, stamp + 3600000)))))
            store.setLast(source, channels.first()); store.markPlaylistRefreshed(source)
            store.setRefreshPolicy(source, RefreshPolicy(playlistOnLaunch = true, epgOnLaunch = true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var item = ""
                scenario.onActivity { item = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                val playingAt = android.os.SystemClock.elapsedRealtime()
                waitFor(scenario) { android.os.SystemClock.elapsedRealtime() - playingAt >= 2500 }
                assertEquals("Cached startup must not immediately redownload catalogs or launch-refresh metadata", 0, server.requests["/player_api.php"] ?: 0)
                assertTrue("Fullscreen playback must not query neighboring or favorite channel guides", server.epgTargets.isEmpty())
                scenario.onActivity { activity ->
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, true)
                }
                waitFor(scenario) { store.playlistRefreshed(source) > stamp }
                assertTrue("Scheduled live refresh must not import VOD", server.catalogTargets.isEmpty())
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Background refresh must not replace the resumed stream", item, player.currentMediaItem!!.mediaId)
                    assertTrue(player.isPlaying)
                }
            }
            store.setRefreshPolicy(source, RefreshPolicy()); store.saveSources(emptyList()); store.removeCache(source)
        }

    }

    @Test fun networkImportPreviewRemoteFavoritesAndResume() {
        FixtureServer().use { server ->
            val source = Source(name = "Spare Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY } == true
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.videoSize?.width?.let { width -> width > 0 } == true }
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.let { list -> list.isShown && list.hasFocus() } == true }
                assertTrue(device.wait(Until.hasObject(By.textContains("World News")), 5000))
                scenario.onActivity { activity ->
                    val video = find<PlayerView>(activity.window.decorView)!!
                    assertEquals("Live video stays full screen behind the translucent guide", activity.window.decorView.width, video.width)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-all.png"))
                device.pressDPadDown(); instrumentation.waitForIdleSync()
                assertEquals("Browsing must not change streams", "/channel1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels[1].id }
                scenario.onActivity { assertFalse("Selecting a new channel opens full screen", channelList(it)!!.isShown) }
                assertEquals("/channel2.mp4", playingPath(scenario))
                device.pressDPadUp(); instrumentation.waitForIdleSync()
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { activity ->
                    channelList(activity)?.let { list ->
                        list.isShown && list.findFocus() is GuideAdapter.Row
                    } == true
                }
                // Hold beyond the threshold: --longpress releases at that exact threshold,
                // which can race View's queued long-click callback on a busy CI emulator.
                val holdMillis = ViewConfiguration.getLongPressTimeout() + 500
                device.executeShellCommand("input dpad keyevent --duration $holdMillis 23")
                instrumentation.waitForIdleSync()
                waitFor(scenario) { channelList(it)?.isShown == true && store.favorites(source).size == 1 }
                assertEquals(1, store.favorites(source).size)
                device.pressDPadLeft(); device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide.png"))
                holdBack()
                waitFor(scenario) { channelList(it)?.isShown == false }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY } == true
                }
                assertEquals("/channel1.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun xtreamLoadsLargeLiveLineupCategoriesAndVideo() {
        FixtureServer(channelCount = 25001, regionGroups = true).use { server ->
            val source = Source(name = "Xtream Test", kind = "xtream", url = "${server.base}/", username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true
                }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
                waitFor(scenario) { device.hasObject(By.textContains("World News")) }
                assertNull("A provider without XMLTV IDs keeps its per-channel fallback", server.requests["/xmltv.php"])
                scenario.onActivity { activity -> assertEquals(25001, channelList(activity)!!.adapter!!.itemCount) }
                val expected = listOf("USA | News", "CA | News", "UK | News", "US | MLB")
                waitFor(scenario) { File(context.filesDir, "lineup-${source.id}.enc").exists() }
                assertEquals(expected, store.cached(source)?.categoryOrder)
                scenario.onActivity { activity ->
                    val categories = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    expected.forEachIndexed { index, name -> assertEquals(name, (categories.findViewHolderForAdapterPosition(index + 2)!!.itemView as android.widget.TextView).text.toString()) }
                }
                scenario.recreate()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 25001 }
                assertEquals(expected, store.cached(source)?.categoryOrder)
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun largeEncryptedProgrammeCacheStreamsAndReadsLegacyJson() {
        val source = Source(name = "Streaming cache", kind = "m3u", url = "https://example.test/playlist")
        store.saveSources(listOf(source)); store.active = source.id
        try {
            val channels = (1..6000).map { Channel("cache:$it", "Cached station", "Group ${it / 100}", "https://example.test/$it") }
            store.saveLineup(source, Guide(channels, emptyMap()))
            val legacy = mapOf(channels.first().id to listOf(Programme(channels.first().id, "Legacy", 1, 2, "Description", "https://example.test/art")))
            store.write("epg-${source.id}.enc", Guide(emptyList(), legacy).json().toString().toByteArray())
            assertEquals(legacy, store.cachedProgrammes(source))
            store.cacheProgrammes(source, legacy, System.currentTimeMillis())
            assertEquals(legacy, Guide.from(org.json.JSONObject(store.read("epg-${source.id}.enc")!!.toString(Charsets.UTF_8))).programmes)
            val now = System.currentTimeMillis()
            val programmes = channels.associate { channel -> channel.id to (0..11).map { slot -> Programme(channel.id, "Slot $slot", now + slot * 3600000L, now + (slot + 1) * 3600000L, "Cached description") } }
            store.cacheProgrammes(source, programmes, now + 1)
            val restored = store.cachedProgrammes(source)
            assertEquals(72000, restored.values.sumOf { it.size }); assertEquals(programmes["cache:6000"], restored["cache:6000"])
            val lineup = store.cachedLineup(source)!!.channels
            assertEquals(channels.size, lineup.size)
            channels.zip(lineup).forEach { (expected, actual) -> assertEquals(expected.id, expected, actual) }
            val file = File(context.filesDir, "epg-${source.id}.enc"); val bytes = file.readBytes()
            assertEquals(1, bytes.first().toInt()); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue("Streaming writes keep authenticated encryption", runCatching { store.cachedProgrammes(source) }.isFailure)
        } finally { store.saveSources(emptyList()); store.removeCache(source) }
    }

    @Test fun providerLogosAreEncryptedPersistBetweenLoadersAndRespectHttpConsent() {
        FixtureServer().use { server ->
            val url = "${server.base}/logo.png?synthetic-account=${System.nanoTime()}"
            val files = GuideLogoFiles(context)
            val first = LogoLoader(96, 1024 * 1024, files)
            val second = LogoLoader(96, 1024 * 1024, GuideLogoFiles(context))
            val name = "guide-logo-${Channel.stableId(url)}.enc"
            try {
                first.prefetch(url, true)
                val deadline = System.currentTimeMillis() + 5000
                while (first.get(url, true) {} == null && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertNotNull(first.get(url, true) {}); assertNull("Cached HTTP still requires consent", first.get(url, false) {})
                first.close(); second.prefetch(url, true)
                while (second.get(url, true) {} == null && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertNotNull(second.get(url, true) {}); assertEquals("Second loader uses encrypted disk bytes", 1, server.requests["/logo.png"])
                val disk = File(context.filesDir, name).readBytes()
                assertFalse(disk.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
                assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic-account"))
                assertNotNull(store.read(name))
            } finally { first.close(); second.close(); store.delete(name) }
        }
    }

    @Test fun cachedStartupDoesNotDownloadVodOrSweepUnvisitedChannels() {
        FixtureServer(channelCount = 20, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Quiet startup", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val lineup = Importers.xtream(source); val now = System.currentTimeMillis()
            store.cache(source, Guide(lineup.channels, mapOf("xc:1" to listOf(Programme("xc:1", "Cached programme", now - 60000, now + 7200000)))))
            store.markPlaylistRefreshed(source); store.markEpgRefreshed(source); store.markFullEpgAttempted(source); store.setRefreshPolicy(source, RefreshPolicy())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var player: Player? = null; var media = ""
                scenario.onActivity { activity ->
                    player = find<PlayerView>(activity.window.decorView)!!.player; media = player!!.currentMediaItem!!.mediaId
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.reset(); warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                }
                Thread.sleep(3500)
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(media, player!!.currentMediaItem!!.mediaId)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("background-refresh").isShown)
                }
                assertTrue("Live startup must not import VOD catalogs", server.catalogTargets.isEmpty())
                assertTrue("Watching a cached channel must not sweep other channels", server.requestedEpgTargets.isEmpty())
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:2"]?.isNotEmpty() == true
                }
                assertFalse(server.requestedEpgTargets.any { it.endsWith("stream_id=20") || it.contains("stream_id=20&") })
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("focusChannelAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, 19) }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:20"]?.isNotEmpty() == true
                }
                scenario.onActivity { assertEquals(media, player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun fullProviderEpgWarmsUnvisitedChannelsAndSurvivesRestart() {
        FixtureServer(channelCount = 96, fullGuide = true).use { server ->
            val source = Source(name = "Full guide fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val lineup = Importers.xtream(source)
            store.cache(source, Guide(lineup.channels.map { it.copy(xmltvId = "") }, emptyMap())); store.markPlaylistRefreshed(source)
            // Simulate an upgrade: the saved lineup predates preservation of XMLTV IDs.
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().remove("epg-mapping-${source.id}").apply()
            assertEquals(6, store.refreshPolicy(source).epgHours)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var media = ""
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                scenario.onActivity { activity ->
                    media = player!!.currentMediaItem!!.mediaId
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    val warmup = MainActivity::class.java.getDeclaredField("playbackWarmup").apply { isAccessible = true }.get(activity) as PlaybackWarmup
                    warmup.reset(); warmup.ready(token, android.os.SystemClock.elapsedRealtime() - 61000, true, 6000)
                    MainActivity::class.java.getDeclaredMethod("autoRefresh", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, source, false)
                }
                try { waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:96"]?.isNotEmpty() == true && store.fullEpgAttempted(source)
                } } catch (failure: AssertionError) { throw AssertionError("Full guide: requests=${server.requests}, mapped=${store.epgMappingReady(source)}, attempted=${store.fullEpgAttempted(source)}, error=${store.refreshError(source)}", failure) }
                assertEquals("One full download fills channels never visited", 1, server.requests["/xmltv.php"])
                assertEquals("The old lineup recovered the provider XMLTV mapping", "test96", store.cachedLineup(source)!!.channels.last().xmltvId)
                assertFalse(server.requestedEpgTargets.any { "stream_id=96&" in it || it.endsWith("stream_id=96") })
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertEquals(media, player!!.currentMediaItem!!.mediaId) }
                assertTrue(store.cachedProgrammes(source)["xc:96"].orEmpty().isNotEmpty())
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("focusChannelAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 95)
                }
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.nativeId == "xc:96" }
                scenario.onActivity { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    assertNotNull(guide.at(guide.channels.first { it.nativeId == "xc:96" }, System.currentTimeMillis()))
                }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.programmes["xc:96"].orEmpty().isNotEmpty()
                }
                assertEquals("Saved guide is reused across launches", 1, server.requests["/xmltv.php"])
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun xtreamCategoryFailureStillLoadsLiveVideo() {
        FixtureServer(failCategories = true).use { server ->
            val source = Source(name = "Category Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun dialogRemoteFocusIsVisibleAndHttpConsentPreservesHttps() {
        FixtureServer().use { server ->
            val seed = Source(name = "UI Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(seed)); store.active = seed.id
            val https = Source(name = "HTTPS Test", kind = "xtream", url = "https://provider.example:8443/panel/", username = "test", password = "test-secret")
            var dialog: AlertDialog? = null
            var saved: Source? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.playbackState == Player.STATE_READY }
                scenario.onActivity { activity ->
                    dialog = SourceDialog.show(activity, https, { saved = it }, {})
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000))
                device.waitForIdle()
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val save = dialog!!.getButton(AlertDialog.BUTTON_POSITIVE)
                    assertTrue(save.hasFocus())
                    assertEquals(Color.BLACK, save.currentTextColor)
                    val bitmap = Bitmap.createBitmap(save.width, save.height, Bitmap.Config.ARGB_8888)
                    save.background.draw(Canvas(bitmap))
                    assertEquals(Color.WHITE, bitmap.getPixel(save.width / 2, save.height / 2))
                    bitmap.recycle()
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "source-save.png"))
                device.pressDPadRight(); instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val cancel = dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE)
                    assertTrue("D-pad right from Save must reach the adjacent Cancel action", cancel.hasFocus())
                    assertEquals(Color.BLACK, cancel.currentTextColor)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "source-cancel.png"))
                device.pressDPadCenter(); instrumentation.waitForIdleSync()
                assertNull("Cancel must not save", saved)
                scenario.onActivity { activity ->
                    dialog = SourceDialog.show(activity, https, { saved = it }, {})
                    val root = dialog!!.window!!.decorView
                    val username = root.findViewWithTag<EditText>("Xtream username")
                    val password = root.findViewWithTag<EditText>("Xtream password")
                    val epg = root.findViewWithTag<EditText>("Optional XMLTV URL (overrides provider guide)")
                    assertTrue(password.transformationMethod is android.text.method.PasswordTransformationMethod)
                    username.requestFocus()
                    username.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    username.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                    assertTrue("D-pad Down must not skip the password", password.hasFocus())
                    password.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next reaches the guide URL", epg.hasFocus())
                    username.requestFocus(); username.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
                    assertTrue("Keyboard Next from username reaches password", password.hasFocus())
                    root.findViewWithTag<CheckBox>("playlist-show-password").performClick()
                    assertNull(password.transformationMethod)
                    root.findViewWithTag<CheckBox>("playlist-show-password").performClick()
                    assertTrue(password.transformationMethod is android.text.method.PasswordTransformationMethod)
                    fun httpConsent(view: View): CheckBox? {
                        if (view is CheckBox && view.tag != "playlist-show-password") return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) httpConsent(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val allow = httpConsent(root)!!
                    allow.performClick()
                    assertEquals(https.url, root.findViewWithTag<EditText>("Playlist URL").text.toString())
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000))
                device.pressDPadCenter(); instrumentation.waitForIdleSync()
                assertNotNull(saved)
                assertEquals(https.url, saved!!.url)
                assertTrue(saved!!.allowHttp)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
                        .invoke(activity, OnDemand("movie:remove", "Removal fixture", "", "${server.base}/channel1.mp4"), 0L)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/channel1.mp4" }
                scenario.onActivity { activity ->
                    val removal = MainActivity::class.java.getDeclaredMethod("editSource", Source::class.java).apply { isAccessible = true }.invoke(activity, seed) as AlertDialog
                    removal.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                }
                waitFor(scenario) { store.sources().isEmpty() }
                scenario.onActivity { activity ->
                    assertNull(MainActivity::class.java.getDeclaredField("onDemand").apply { isAccessible = true }.get(activity))
                    assertEquals("", MainActivity::class.java.getDeclaredField("playbackUrl").apply { isAccessible = true }.get(activity))
                    assertEquals(0, find<PlayerView>(activity.window.decorView)!!.player!!.mediaItemCount)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED).moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Removed VOD must not reopen after returning to the app", 0, player.mediaItemCount)
                    assertFalse(player.isPlaying)
                }
                assertTrue(store.sources().isEmpty())
            }
            store.saveSources(emptyList()); store.removeCache(seed)
        }
    }

    @Test fun transportWithoutAccessUnitDelimitersAndCategorySidebar() {
        FixtureServer(tsMode = true, slowPlaylist = true).use { server ->
            val source = Source(name = "TS Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("import-progress")?.isShown == true }
                val preparing = java.util.concurrent.CountDownLatch(2); val releaseApply = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val workers = MainActivity::class.java.getDeclaredField("imports").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    repeat(2) { workers.execute { preparing.countDown(); releaseApply.await(15, java.util.concurrent.TimeUnit.SECONDS) } }
                }
                try {
                    assertTrue(preparing.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    waitFor(scenario) { (server.requests["/playlist.m3u"] ?: 0) > 0 && !PlaylistRefresh.running(source.id) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertTrue("Progress remains animated while the downloaded lineup is being prepared", activity.window.decorView.findViewWithTag<View>("import-progress").isShown)
                        val empty = MainActivity::class.java.getDeclaredField("emptyInfo").apply { isAccessible = true }.get(activity) as View
                        assertFalse("An unapplied lineup must not show an empty-channel error", empty.isShown)
                    }
                    device.pressMenu()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
                } finally { releaseApply.countDown() }
                waitFor(scenario) { activity ->
                    (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.isNotEmpty()
                }
                scenario.onActivity { activity ->
                    assertTrue("Late startup data must not close a screen opened by the user", activity.window.decorView.findViewWithTag<View>("settings-panel").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("import-progress").isShown)
                }
                leaveSettings(scenario)
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                assertEquals("/channel1.ts", playingPath(scenario))
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadLeft(); instrumentation.waitForIdleSync()
                assertTrue(device.wait(Until.hasObject(By.text("All channels").focused(true)), 5000))
                repeat(2) { device.pressDPadDown() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 4 }
                assertEquals("Category browsing must keep the current video", "/channel1.ts", playingPath(scenario))
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun xtreamRetriesHlsWhenTsEndpointFails() {
        listOf(503, 404, 410).forEach { segmentStatus -> FixtureServer(fallbackHls = true, transientSegmentErrors = 2, transientSegmentStatus = segmentStatus).use { server ->
            val source = Source(name = "Fallback Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                assertEquals("/live/test/test-secret/1.m3u8", playingPath(scenario))
                assertEquals("Transient segment failures must not reopen the channel manifest", 1, server.requests["/live/test/test-secret/1.m3u8"])
                assertTrue("Playback must recover after both failed segment reads", (server.requests["/segment.ts"] ?: 0) >= 3)
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertEquals("Provider live timing must not be replaced with a fixed offset", androidx.media3.common.C.TIME_UNSET, player.currentMediaItem!!.liveConfiguration!!.targetOffsetMs)
                }
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        }
    }

    @Test fun sportsQualityDoesNotHideGuideChannelsOrInterruptPlayback() {
        FixtureServer(longVideo = true).use { server ->
            val source = Source(name = "Sports quality fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val profile = GuideFilters(1080, FrameRateFilter.onlySixty)
            val categories = GuideFilters(groups = setOf("News"))
            store.setSportsFilters(source, profile); store.setFilters(source, categories)
            val legacy = VideoInfo(3840, 2160, 29.97f).json().put("detector", 3)
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putString("video-info-${source.id}", org.json.JSONObject().put("old", legacy).toString()).commit()
            assertEquals("Old display-only FPS must be remeasured", 0f, store.videoInfo(source).getValue("old").fps, 0f)
            store.saveVideoInfo(source, emptyMap())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && channelList(it)?.adapter?.itemCount == 4 }
                var mediaId = ""
                scenario.onActivity { activity ->
                    mediaId = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    activity.window.decorView.findViewWithTag<View>("settings").requestFocus()
                }
                device.pressDPadCenter()
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("settings-nav:Guide filters")) }
                activateSetting(scenario, "Sports"); activateSetting(scenario, "Stream quality")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager")?.isShown == true }
                scenario.onActivity { activity ->
                    val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                    assertNull("Resolution is always ranked first", manager.findViewWithTag<View>("settings-option:HD · 720p or better"))
                    manager.findViewWithTag<View>("settings-option:30 fps · includes 29.97").performClick()
                    assertTrue("Changing quality keeps Sports settings open", manager.opened)
                    assertEquals(0, store.sportsFilters(source).minHeight)
                    assertFalse(30 in store.sportsFilters(source).excludedFps)
                    assertTrue(50 in store.sportsFilters(source).excludedFps)
                    assertEquals(categories, store.filters(source))
                    assertEquals("Sports quality does not retune playback", mediaId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressBack(); device.pressBack(); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.isShown == true && channelList(it)?.adapter?.itemCount == 4 }
                val saved = store.sportsFilters(source)
                store.saveVideoInfo(source, emptyMap()); scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.adapter?.itemCount == 4 && channelList(it)?.isShown == true }
                assertEquals(saved, SecureStore(context).sportsFilters(source)); assertEquals(categories, store.filters(source))
                scenario.onActivity { activity ->
                    val channels = listOf(Channel("local:king", "KING5 FHD", "News", "${server.base}/channel1.ts"),
                        Channel("local:kiro", "KIRO HD", "News", "${server.base}/channel2.ts"), Channel("other:abc", "WABC New York", "News", "${server.base}/channel3.ts"),
                        Channel("show:king", "King of Queens", "News", "${server.base}/channel4.ts"), Channel("show:24", "24/7 KING TV", "News", "${server.base}/channel5.ts"),
                        Channel("show:fox", "FOX Seattle 24x7 Shows", "News", "${server.base}/channel6.ts"),
                        Channel("local:kgw", "KGW Portland OR", "News", "${server.base}/channel7.ts"),
                        Channel("other:wgme", "WGME Portland ME", "News", "${server.base}/channel8.ts"))
                    val localGuide = Guide(channels, emptyMap()); store.cache(source, localGuide); store.markPlaylistRefreshed(source)
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, localGuide)
                    activity.window.decorView.findViewWithTag<View>("settings").performClick()
                    val panel = activity.window.decorView.findViewWithTag<SettingsPanel>("settings-panel")
                    panel.selectSetting("Local channels")
                    val location = panel.findViewWithTag<EditText>("local-location-input")
                    location.requestFocus(); location.setText("Seatt")
                    assertNull(panel.findViewWithTag<View>("settings-option:Save location"))
                    assertTrue(location.hasFocus())
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA") != null }
                scenario.onActivity { assertEquals("Typing a draft must not overwrite saved regions", "", SecureStore(context).localLocation) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("local-location-input").requestFocus() }
                device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("local-add-region")?.hasFocus() == true }
                device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA")?.hasFocus() == true }
                device.pressDPadCenter()
                waitFor(scenario) { activity ->
                    SecureStore(context).localLocation == "Seattle, WA" &&
                        activity.window.decorView.findViewWithTag<EditText>("local-location-input").hasFocus() &&
                        activity.window.decorView.findViewWithTag<TextView>("local-location-matches").text.toString() == "2 local channels matched"
                }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("local-add-region").performClick()
                    val input = activity.window.decorView.findViewWithTag<EditText>("local-location-input")
                    input.setText("Portland Oregon") // TV/phone IMEs may replace the entire draft.
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Portland, OR").performClick()
                    assertEquals("Seattle, WA; Portland, OR", SecureStore(context).localLocation)
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("local-saved-regions").text.contains("Seattle, WA"))
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("local-location-matches").text.toString() == "3 local channels matched" }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("local-edit:0").performClick()
                    activity.window.decorView.findViewWithTag<EditText>("local-location-input").setText("Seattle Washington")
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Seattle, WA").performClick()
                    activity.window.decorView.findViewWithTag<View>("local-remove:1").performClick()
                    assertEquals("Seattle, WA", store.localLocation)
                    activity.window.decorView.findViewWithTag<EditText>("local-location-input").setText("Portland Oregon")
                    activity.window.decorView.findViewWithTag<View>("local-location-suggestion:Portland, OR").performClick()
                    assertEquals("Seattle, WA; Portland, OR", store.localLocation)
                }
                setThemeControl(scenario, "Guide", "Opacity", "80%")
                assertEquals(80, SecureStore(context).guideOpacity)
                leaveSettings(scenario)
                val releaseLocal = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val worker = MainActivity::class.java.getDeclaredField("localWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { releaseLocal.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    MainActivity::class.java.getDeclaredField("localLineup").apply { isAccessible = true }.set(activity, null)
                    activity.window.decorView.findViewWithTag<View>("guide-local").performClick()
                    assertTrue("Local matching has an animated loading indicator", activity.window.decorView.findViewWithTag<View>("local-matching-progress").isShown)
                }
                releaseLocal.countDown()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 3 && !it.window.decorView.findViewWithTag<View>("local-matching-progress").isShown }
                assertEquals("Seattle, WA; Portland, OR", SecureStore(context).localLocation)
                scenario.onActivity { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val fingerprint = LocalMatchCache.fingerprint(guide.channels, store.localLocation)
                    assertEquals(setOf("local:king", "local:kiro", "local:kgw"), LocalMatchCache.decode(store.read("local-matches-${source.id}.enc"), fingerprint))
                    activity.window.decorView.findViewWithTag<View>("guide-all").performClick()
                }
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    @Suppress("UNCHECKED_CAST") val ids = MainActivity::class.java.getDeclaredField("localIds").apply { isAccessible = true }.get(activity) as Set<String>
                    ids == setOf("local:king", "local:kiro", "local:kgw") && !MainActivity::class.java.getDeclaredField("localLoading").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    assertFalse("Local is warmed before opening its tab", activity.window.decorView.findViewWithTag<View>("guide-local").isSelected)
                    activity.window.decorView.findViewWithTag<View>("guide-local").performClick()
                    assertEquals(3, channelList(activity)!!.adapter!!.itemCount)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("local-matching-progress").isShown)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }


    @Test fun gameReminderOverlayKeepsPlaybackAndDismissesWithBack() {
        val previousReminders = store.read("game-reminders.enc")
        var writes: java.util.concurrent.ExecutorService? = null
        FixtureServer(missingStatus = 404).use { server ->
            val source = Source(name = "Reminder Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis(); val teams = listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }
            val game = SportsGame(teams, now + 86400000L, now + 90000000L, emptyList(), "synthetic-reminder", "pre", competition = "NFL")
            val reminders = GameReminders(); reminders.toggle(game); store.write("game-reminders.enc", reminders.json().toString().toByteArray())
            store.saveSources(listOf(source)); store.active = source.id
            // Install the fixture before onCreate can start a real ESPN request.
            // A late live reply must never compete with this synthetic matchup.
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val isolated = AtomicBoolean()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot.EMPTY }; isolated.set(true)
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    assertTrue("The reminder test must never depend on a real scoreboard", isolated.get())
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                    var playing: Player? = null
                    scenario.onActivity { activity ->
                        playing = find<PlayerView>(activity.window.decorView)!!.player
                        writes = MainActivity::class.java.getDeclaredField("cacheWrites").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        val due = GameReminders().apply { toggle(game.copy(start = System.currentTimeMillis() - 1000)) }
                        MainActivity::class.java.getDeclaredField("reminders").apply { isAccessible = true }.set(activity, due)
                        MainActivity::class.java.getDeclaredField("reminderChecking").apply { isAccessible = true }.setBoolean(activity, true)
                        MainActivity::class.java.getDeclaredMethod("checkGameReminders").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("game-reminder").isShown }
                    scenario.onActivity { activity ->
                        assertEquals("reminder-watch", activity.currentFocus?.tag)
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    }
                    device.pressBack()
                    waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("game-reminder").isShown }
                    scenario.onActivity { assertTrue(playing!!.isPlaying); assertSame(playing, find<PlayerView>(it.window.decorView)!!.player) }
                    val releaseReminderSearch = java.util.concurrent.CountDownLatch(1)
                    try {
                        scenario.onActivity { activity ->
                            val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                            worker.submit { releaseReminderSearch.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            val due = GameReminders().apply { toggle(game.copy(start = System.currentTimeMillis() - 1000)) }
                            MainActivity::class.java.getDeclaredField("reminders").apply { isAccessible = true }.set(activity, due)
                            MainActivity::class.java.getDeclaredMethod("checkGameReminders").apply { isAccessible = true }.invoke(activity)
                        }
                        device.pressDPadCenter()
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("game-watch-panel").isShown }
                        scenario.onActivity { activity ->
                            assertEquals("game-watch-cancel", activity.currentFocus?.tag)
                            assertFalse("Watch game must not navigate to the guide while matching", channelList(activity)?.isShown == true)
                            assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                            assertTrue("The previous programme keeps playing during lookup", playing!!.isPlaying)
                        }
                        device.pressBack()
                        waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("game-watch-panel").isShown }
                        scenario.onActivity { activity ->
                            assertFalse(channelList(activity)?.isShown == true)
                            assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                        }
                    } finally { releaseReminderSearch.countDown() }
                    assertFalse(SecureStore(context).sportsAutoPlay)
                    device.pressMenu(); activateSetting(scenario, "Sports"); activateSetting(scenario, "Play best stream on selection")
                    assertTrue("Automatic playback persists from main Settings", SecureStore(context).sportsAutoPlay)
                    leaveSettings(scenario)
                    val releaseWarmWorker = java.util.concurrent.CountDownLatch(1)
                    val largeLineup = (1..25000).map { Channel("filler:$it", "Local news $it", "News", "${server.base}/unused.mp4") }
                    try {
                        scenario.onActivity { activity ->
                            val worker = MainActivity::class.java.getDeclaredField("sportsWarmWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                            worker.submit { releaseWarmWorker.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            val live = game.copy(state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000)
                            val reported = Channel("provider-game", "Seattle Seahawks vs San Francisco 49ers 4K 60fps", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 59.94f))
                            val broken = reported.copy(id = "broken-game", name = "Seattle Seahawks vs San Francisco 49ers 4K 120fps", url = "${server.base}/live/test/test-secret/2.ts", reportedQuality = VideoInfo(3840, 2160, 120f))
                            val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                            val snapshot = guideField.get(activity) as Guide
                            guideField.set(activity, snapshot.copy(channels = snapshot.channels + largeLineup + broken + reported))
                            val scores = LiveSportsSnapshot(listOf(LiveEvent(live.eventId, teams.map { it.id }.toSet(), live.start, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                            activity.liveSportsLoader = { scores }
                            MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                            MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        }
                        // The executor is deliberately blocked: its task cannot post
                        // "Finding stream" until this gate opens. Observe the queued
                        // task itself so cancellation does not race the gate timeout.
                        waitFor(scenario) { activity ->
                            MainActivity::class.java.getDeclaredField("sportsWarmTask").apply { isAccessible = true }.get(activity) != null &&
                                MainActivity::class.java.getDeclaredField("sportsWarmActive").apply { isAccessible = true }.get(activity) == game.key &&
                                activity.window.decorView.findViewWithTag<View>("sports-coverage:${game.key}")?.isShown == true
                        }
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                            assertEquals("Cancelled work leaves an actionable card", "Select to find stream", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.toString())
                        }
                    } finally { releaseWarmWorker.countDown() }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("queueSportsPrefetch", List::class.java).apply { isAccessible = true }.invoke(activity, null)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}")?.text?.contains("Seattle Seahawks") == true }
                    scenario.onActivity { activity ->
                        val record = MainActivity::class.java.getDeclaredMethod("recordVideo", VideoInfo::class.java).apply { isAccessible = true }
                        record.invoke(activity, VideoInfo(3840, 2160, 60f)); record.invoke(activity, VideoInfo(1920, 1080, 30f))
                        assertTrue("Quality changes must retain completed coverage", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.contains("Seattle Seahawks"))
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("queueSportsPrefetch", List::class.java).apply { isAccessible = true }.invoke(activity, null)
                        assertTrue("Returning to Sports retains the discovered channel", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-coverage:${game.key}").text.contains("Seattle Seahawks"))
                    }
                    assertNull("Prefetch never opens the candidate video", server.requests["/live/test/test-secret/2.ts"])
                    assertNull(server.requests["/channel2.mp4"])
                    scenario.onActivity { activity ->
                        val badge = activity.window.decorView.findViewWithTag<TextView>("sports-badge:${game.key}")
                        assertEquals("● LIVE", badge.text.toString()); assertEquals(0xFF07572E.toInt(), badge.currentTextColor)
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && playing?.isPlaying == true }
                    assertEquals("A failed cached pick falls through once to the next game stream", 1, server.requests["/live/test/test-secret/2.ts"])
                    scenario.onActivity { assertSame("Watch Best uses explicit provider FHD+/60 data before the first playback measurement", playing, find<PlayerView>(it.window.decorView)!!.player) }
                    device.pressDPadCenter()
                    focusDetailsAction(scenario, "channel-game-streams")
                    device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game")?.isShown == true }
                    scenario.onActivity { activity ->
                        assertTrue("Opening game alternatives must preserve stall monitoring", MainActivity::class.java.getDeclaredField("monitoringPlayback").apply { isAccessible = true }.getBoolean(activity))
                        activity.window.decorView.findViewWithTag<View>("sports-stream:provider-game").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("showPlaybackFailure", String::class.java).apply { isAccessible = true }.invoke(activity, "Synthetic stream failure")
                        assertTrue("Manual Sports selections keep game alternatives after errors", activity.window.decorView.findViewWithTag<View>("sports-other-streams").isShown)
                        activity.window.decorView.findViewWithTag<View>("sports-other-streams").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:provider-game").performClick() }
                    waitFor(scenario) { playing?.isPlaying == true && it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false }
                    assertEquals("Opening cached alternatives must not retry a missing provider endpoint", 1, server.requests["/live/test/test-secret/2.ts"])
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                        panel.openGameActions(game)
                        assertTrue("Upcoming games still offer reminders", panel.findViewWithTag<View>("sports-reminder").isShown)
                        panel.openGameActions(game.copy(state = "post"))
                        assertEquals("Find replay", panel.findViewWithTag<android.widget.Button>("sports-watch").text.toString())
                        assertTrue("Completed games still show replay actions", panel.findViewWithTag<View>("sports-actions").isShown)
                        assertEquals("/channel2.mp4", playing!!.currentMediaItem!!.localConfiguration!!.uri.path)
                    }
                    scenario.onActivity { activity ->
                        val network = Channel("broadcaster", "USA ESPN 4K", "NFL", "${server.base}/channel3.mp4")
                        val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                        val saved = guideField.get(activity) as Guide
                        guideField.set(activity, saved.copy(channels = saved.channels.filter { it.id !in setOf("provider-game", "broken-game") } + network,
                            programmes = saved.programmes + (network.id to listOf(Programme(network.id, game.title, System.currentTimeMillis() - 60000, System.currentTimeMillis() + 3600000)))))
                        @Suppress("UNCHECKED_CAST") val readings = MainActivity::class.java.getDeclaredField("videoInfo").apply { isAccessible = true }.get(activity) as MutableMap<String, VideoInfo>
                        readings[network.id] = VideoInfo(3840, 2160, 59.94f)
                        val live = game.copy(state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, networks = listOf("ESPN"))
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, live, true)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel3.mp4" && playing?.isPlaying == true }
                    scenario.onActivity { activity ->
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-match-notice").isShown)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    }
                    scenario.onActivity { activity ->
                        val nba = game.copy(teams = SportsCatalog.teams.filter { it.league == "NBA" }.take(2), state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, competition = "NBA", eventId = "nba-unknown")
                        val unknown = Channel("unknown-fps", nba.title + " 4K", "NBA", "${server.base}/channel4.mp4", reportedQuality = VideoInfo(3840, 2160, 0f))
                        val low = unknown.copy(id = "known-50", name = nba.title + " 4K 50FPS", url = "${server.base}/channel5.mp4", reportedQuality = VideoInfo(3840, 2160, 50f))
                        val fhd = unknown.copy(id = "fhd-sixty", name = nba.title + " FHD 60FPS", url = "${server.base}/channel6.mp4", reportedQuality = VideoInfo(1920, 1080, 60f))
                        val scoreboard = LiveSportsSnapshot(listOf(LiveEvent(nba.eventId, nba.teams.map { it.id }.toSet(), nba.start, league = "NBA")), System.currentTimeMillis(), setOf("NBA"))
                        activity.liveSportsLoader = { scoreboard }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scoreboard)
                        MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, Guide(listOf(unknown, low, fhd), emptyMap()))
                        MainActivity::class.java.getDeclaredField("sportsFilters").apply { isAccessible = true }.set(activity, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
                        MainActivity::class.java.getDeclaredMethod("switchMode", java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, true)
                        MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(activity, nba, true)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel4.mp4" && playing?.isPlaying == true }
                    assertNull("The other 4K candidate is not probed", server.requests["/channel5.mp4"])
                    assertNull("Known FHD/60 cannot displace an unplayed 4K match or open an extra connection", server.requests["/channel6.mp4"])
                    waitFor(scenario) { activity ->
                        @Suppress("UNCHECKED_CAST") val readings = MainActivity::class.java.getDeclaredField("videoInfo").apply { isAccessible = true }.get(activity) as Map<String, VideoInfo>
                        (readings["unknown-fps"]?.fps ?: 0f) > 0f
                    }
                    scenario.onActivity { activity ->
                        assertSame("Unknown-FPS choices reuse the primary player", playing, find<PlayerView>(activity.window.decorView)!!.player)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        val hockey = game.copy(teams = listOf("nhl:TOR", "nhl:SEA").map { SportsCatalog.byId.getValue(it) }, state = "in", start = System.currentTimeMillis() - 60000, end = System.currentTimeMillis() + 3600000, competition = "NHL", eventId = "nhl-hd-only", networks = listOf("ESPN"))
                        val hd = Channel("hd-only", "NHL 07 HD", "NHL", "${server.base}/channel5.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
                        val guess = Channel("stale-4k", "ESPN 4K", "Sports", "${server.base}/channel6.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
                        val scoreboard = LiveSportsSnapshot(listOf(LiveEvent(hockey.eventId, hockey.teams.map { it.id }.toSet(), hockey.start, league = "NHL")), System.currentTimeMillis(), setOf("NHL"))
                        activity.liveSportsLoader = { scoreboard }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scoreboard)
                        val guide = Guide(listOf(hd, guess), emptyMap())
                        val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                        val generation = MainActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }.getInt(activity)
                        @Suppress("UNCHECKED_CAST") val favorites = MainActivity::class.java.getDeclaredField("favorites").apply { isAccessible = true }.get(activity) as Set<String>
                        val token = cache.use(source.id, generation, guide, favorites)
                        cache.put(token, SportsStreamCache.Match(hockey.copy(candidates = listOf(guess))), System.currentTimeMillis())
                        MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, guide.copy(programmes = mapOf(hd.id to listOf(Programme(hd.id, "Maple Leafs at Kraken", hockey.start, hockey.end)))))
                        MainActivity::class.java.getDeclaredField("sportsFilters").apply { isAccessible = true }.set(activity, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
                        MainActivity::class.java.getDeclaredField("reminderGame").apply { isAccessible = true }.set(activity, hockey)
                        MainActivity::class.java.getDeclaredMethod("watchGameReminder").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { playing?.currentMediaItem?.localConfiguration?.uri?.path == "/channel5.mp4" && playing?.isPlaying == true }
                    assertNull("An unconfirmed 4K broadcaster must not displace the reminder's exact 720p game", server.requests["/channel6.mp4"])
                    scenario.onActivity { activity ->
                        assertFalse("Lower measured quality must not interrupt a playable game", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        assertFalse("Reminder playback finishes full screen", channelList(activity)?.isShown == true)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("game-watch-panel").isShown)
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                writes?.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                if (previousReminders == null) store.delete("game-reminders.enc") else store.write("game-reminders.enc", previousReminders)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    private fun channelList(activity: MainActivity): RecyclerView? = activity.window.decorView.findViewWithTag("channel-guide")
    @Test fun missingChannelIsHiddenWithoutRetryLoopAndAuthIsNotHidden() {
        listOf(404, 401).forEach { status -> FixtureServer(channelCount = 2, missingStatus = status).use { server ->
            val source = Source(name = "Missing Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadCenter()
                if (status == 404) {
                    waitFor(scenario) { store.unavailable(source).contains("xc:2", System.currentTimeMillis()) && channelList(it)?.isShown == true }
                    waitFor(scenario) { playingPath(scenario) == "/live/test/test-secret/1.ts" }
                    scenario.onActivity { assertEquals(1, channelList(it)!!.adapter!!.itemCount) }
                    assertEquals(1, server.requests["/live/test/test-secret/2.ts"])
                    assertEquals(1, server.requests["/live/test/test-secret/2.m3u8"])
                    scenario.recreate(); waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 }
                    assertTrue(store.unavailable(source).contains("xc:2", System.currentTimeMillis()))
                } else {
                    assertTrue(device.wait(Until.hasObject(By.textContains("HTTP 401")), 10000))
                    assertTrue(store.unavailable(source).snapshot().isEmpty())
                    assertEquals(1, server.requests["/live/test/test-secret/2.ts"])
                    assertNull(server.requests["/live/test/test-secret/2.m3u8"])
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        } }
    }

    @Test fun topControlsUsaOrderingAndBothGuideVideoModesKeepPlayback() {
        FixtureServer(regionGroups = true).use { server ->
            val source = Source(name = "Guide Modes", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.setUsaFirst(source, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val categories = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    assertEquals("USA | News", (categories.findViewHolderForAdapterPosition(2)!!.itemView as android.widget.TextView).text.toString())
                    assertEquals("US | MLB", (categories.findViewHolderForAdapterPosition(3)!!.itemView as android.widget.TextView).text.toString())
                    val rail = activity.window.decorView.findViewWithTag<View>("app-navigation")
                    val a = IntArray(2); val b = IntArray(2); rail.getLocationOnScreen(a); categories.getLocationOnScreen(b)
                    assertTrue(a[0] + rail.width <= b[0])
                    val controls = listOf("search", "guide-mode", "sports-mode", "library", "settings").map { activity.window.decorView.findViewWithTag<View>(it) }
                    assertEquals(1, controls.map { it.width to it.height }.distinct().size)
                    assertTrue(controls.zipWithNext().all { (first, next) -> val x = IntArray(2); val y = IntArray(2); first.getLocationOnScreen(x); next.getLocationOnScreen(y); x[1] + first.height < y[1] })
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    activity.window.decorView.findViewWithTag<View>("settings").requestFocus()
                }
                device.pressDPadCenter(); assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000))
                setThemeControl(scenario, "Guide", "Guide video", "Small preview"); leaveSettings(scenario)
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.let { it.width < activity.window.decorView.width / 2 && it.player?.isPlaying == true } == true }
                assertTrue(store.smallPreview)
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.width >= it.window.decorView.width / 3) }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                // This 1.024-second MP4 naturally opens another request on repeat.
                // Preserve player/attempt identity rather than counting loop requests,
                // which depend on the emulator's speed during remote/UI operations.
                var beforePlayer: Player? = null
                var beforeMediaId = ""
                scenario.onActivity { activity ->
                    beforePlayer = find<PlayerView>(activity.window.decorView)!!.player!!
                    beforeMediaId = beforePlayer!!.currentMediaItem!!.mediaId
                    assertTrue(beforeMediaId.isNotBlank())
                }
                fun assertPlaybackContinues() = waitFor(scenario) { activity ->
                    val playing = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertSame("Guide changes must keep the same player", beforePlayer, playing)
                    assertEquals("Expanding must not start a new playback attempt", beforeMediaId, playing.currentMediaItem!!.mediaId)
                    playing.isPlaying
                }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.isShown == false }
                assertPlaybackContinues()
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                scenario.onActivity { activity ->
                    val rect = android.graphics.Rect(); find<PlayerView>(activity.window.decorView)!!.getGlobalVisibleRect(rect)
                    val colors = mutableSetOf<Int>()
                    for (x in rect.left + rect.width()/4 until rect.right - rect.width()/4 step 8) for (y in rect.top + 10 until rect.bottom - 45 step 8) colors.add(bitmap.getPixel(x, y))
                    assertTrue("Preview must contain rendered video, not an empty black surface", colors.size > 10)
                }; bitmap.recycle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-preview.png"))
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                assertPlaybackContinues()
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun previouslyPlayingLiveEndpointRecoversFromTemporaryProvider404WithoutRetuning() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Temporary live outage", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && MainActivity::class.java.getDeclaredField("hasPlayedChannel").apply { isAccessible = true }.getBoolean(it) }
                var original: Player? = null
                val requestsBefore = server.requests["/channel1.ts"] ?: 0
                server.transientLiveFailures.set(2)
                scenario.onActivity { activity ->
                    original = find<PlayerView>(activity.window.decorView)!!.player!!
                    // Exercise real HTTP errors after proven playback, without a channel change.
                    original!!.setMediaItem(original!!.currentMediaItem!!); original!!.prepare(); original!!.play()
                }
                waitFor(scenario) { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    (server.requests["/channel1.ts"] ?: 0) >= requestsBefore + 3 && player.isPlaying &&
                        MainActivity::class.java.getDeclaredField("firstFrame").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    assertSame(original, player); assertEquals("/channel1.ts", player.currentMediaItem!!.localConfiguration!!.uri.path)
                    assertEquals(2, MainActivity::class.java.getDeclaredField("playbackReconnects").apply { isAccessible = true }.getInt(activity))
                    assertFalse((MainActivity::class.java.getDeclaredField("retryPanel").apply { isAccessible = true }.get(activity) as View).isShown)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun actualStreamStallUsesBufferingAndResumesWithoutReconnect() {
        FixtureServer(stalled = true).use { server ->
            val source = Source(name = "Stall Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.playbackState == Player.STATE_BUFFERING }
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = field.get(activity) as Guide; val channel = guide.channels.first(); val now = System.currentTimeMillis()
                    field.set(activity, guide.copy(programmes = guide.programmes + (guide.key(channel) to listOf(Programme(guide.key(channel), "NFL: Seahawks vs 49ers", now - 60000, now + 3600000)))))
                }
                device.pressDPadCenter()
                focusDetailsAction(scenario, "channel-game-streams")
                device.pressBack()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED).moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertTrue("Pause/resume must restore live stall monitoring", MainActivity::class.java.getDeclaredField("monitoringPlayback").apply { isAccessible = true }.getBoolean(activity))
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-status")?.let { bar -> bar.isShown && bar.contentDescription.toString() == "Buffering…" } == true }
                assertEquals(1, server.requests["/channel1.ts"])
                server.resumeStream.countDown()
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.isPlaying == true && activity.window.decorView.findViewWithTag<View>("playback-status")?.isShown == false }
                assertEquals("A recoverable buffer underrun must keep the existing stream", 1, server.requests["/channel1.ts"])
                var samePlayer: Player? = null; var before = ""
                scenario.onActivity { activity ->
                    samePlayer = find<PlayerView>(activity.window.decorView)!!.player
                    before = samePlayer!!.currentMediaItem!!.mediaId
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 21000, samePlayer!!.currentPosition, null, false, true)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.currentMediaItem?.mediaId != before && p.isPlaying } == true }
                assertEquals("A frozen READY stream is reopened without replacing the app", 2, server.requests["/channel1.ts"])
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    before = samePlayer!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                    assertNull("Reconnect is no longer a playback action", activity.window.decorView.findViewWithTag<View>("channel-reconnect"))
                    assertEquals("Opening details preserves playback", before, samePlayer!!.currentMediaItem!!.mediaId)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun inlineSearchUsesNativeRecognitionAndKeepsFailedVoiceEditable() {
        assertTrue("Reset isolated voice fixture", device.executeShellCommand("run-as ${instrumentation.context.packageName} rm -f files/voice-first-result").isBlank())
        val previous = device.executeShellCommand("settings get secure voice_recognition_service").trim()
        device.executeShellCommand("settings put secure voice_recognition_service io.github.mot1us.spare.debug.test/io.github.mot1us.spare.FakeRecognitionService")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        try {
        FixtureServer().use { server ->
            val source = Source(name = "Search Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                val monitor = instrumentation.addMonitor(IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                try {
                    var attempt = ""
                    scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; assertTrue(it.window.decorView.findViewWithTag<View>("search").requestFocus()) }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertTrue("Search icon must retain remote focus", it.window.decorView.findViewWithTag<View>("search").hasFocus()) }
                    device.pressDPadCenter()
                    waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<EditText>("guide-search-input")?.text.toString() == "Channel 2" && channelList(activity)?.adapter?.itemCount == 1 && activity.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results")?.hasFocus() == true }
                    assertEquals("Native speech must not launch a chooser/activity", 0, monitor.hits)
                    scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); assertEquals(1f, find<PlayerView>(it.window.decorView)!!.player!!.volume, 0f) }
                    assertEquals("/channel1.mp4", playingPath(scenario))
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-search-voice").requestFocus() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertTrue("Pending channel navigation must not steal Voice focus", it.window.decorView.findViewWithTag<View>("guide-search-voice").hasFocus()) }
                    device.pressDPadCenter()
                    waitFor(scenario) { device.hasObject(By.textStartsWith("No speech heard.")) }
                } finally { instrumentation.removeMonitor(monitor) }
                val keyboardPackage = android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))!!.packageName
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.pkg(keyboardPackage)), 5000))
                device.findObject(By.desc("Search playlist")).text = "Channel 7"
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results").getChildAt(0)?.contentDescription?.contains("Test Channel 7") == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results")?.hasFocus() == true }
                assertTrue(device.wait(Until.gone(By.pkg(keyboardPackage)), 5000))
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search.png"))
                var selectedId = ""
                scenario.onActivity { selectedId = (channelList(it)!!.adapter as GuideAdapter).channels.single().id }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("playlist-search-results").getChildAt(0).requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == selectedId && channelList(it)?.isShown == false }
                assertEquals("/channel7.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        } finally {
            if (previous.isBlank() || previous == "null") device.executeShellCommand("settings delete secure voice_recognition_service")
            else device.executeShellCommand("settings put secure voice_recognition_service $previous")
        }
    }

    @Test fun okShowsDetailsAndLeftTogglesWatchedChannelsAcrossFailuresAndRestart() {
        // Observe two continuing streams, with independent provider connections.
        FixtureServer(channelCount = 3, missingStatus = 404, longVideo = true, liveCycles = 4, parallelClients = true).use { server ->
            val source = Source(name = "History Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying && store.last(source) == "xc:1" } == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-details")?.isShown == true && device.hasObject(By.text("Guide")) }
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); assertFalse(channelList(it)!!.isShown) }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "details.png"))
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-details")?.isShown == false && channelList(it)?.isShown == false }
                device.pressDPadUp(); waitFor(scenario) { store.last(source) == "xc:3" }
                assertEquals("xc:1", store.previous(source))
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:1" }
                assertEquals("xc:3", store.previous(source))
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:3" }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                // Dispatch rapid Up/OK in one input batch: focus cannot wait for a later posted task.
                scenario.onActivity { activity ->
                    for (key in listOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)) {
                        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                    }
                }
                waitFor(scenario) { store.unavailable(source).contains("xc:2", System.currentTimeMillis()) && store.last(source) == "xc:3" && playingPath(scenario) == "/live/test/test-secret/3.ts" }
                assertEquals("Failed channels must not replace the previously watched channel", "xc:1", store.previous(source))
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:1" }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying && store.last(source) == "xc:1" } == true }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == "xc:3" }
                assertEquals("xc:1", store.previous(source))
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("recent-watch:xc:1")?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "details-multiview.png"))
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("recent-watch:xc:1").isFocusable)
                    activity.window.decorView.findViewWithTag<View>("recent-watch:xc:1").performLongClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-second")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                device.pressBack(); holdBack()
                waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-multiview")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-multiview").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-controls")?.isShown == true }
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "recent-multiview.png"))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideRepeatsSettleHeaderWithoutDelayingTheFirstPress() {
        FixtureServer().use { server ->
            val source = Source(name = "Guide repeat fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..6).map { Channel("repeat-$it", "Repeat channel $it", "Guide", "${server.base}/channel1.mp4") }
            val now = System.currentTimeMillis()
            val programmes = channels.mapIndexed { index, channel ->
                channel.id to listOf(Programme(channel.id, "Repeat listing ${index + 1}", now - 120_000, now + 1_800_000))
            }.toMap()
            val info = MainActivity::class.java.getDeclaredField("channelInfo").apply { isAccessible = true }
            fun header(activity: MainActivity) = (info.get(activity) as TextView).text.toString()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source); store.markFullEpgAttempted(source)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { player ->
                        player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                    } == true }
                    device.pressBack()
                    waitFor(scenario) { channelList(it)?.findFocus() is GuideAdapter.Row && header(it) == "Repeat listing 1" }
                    scenario.onActivity { activity ->
                        val first = channelList(activity)!!.findFocus() as GuideAdapter.Row
                        val down = android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        first.onKeyDown(down, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, down))
                        assertEquals("Repeat listing 2", header(activity))
                        val second = channelList(activity)!!.findFocus() as GuideAdapter.Row
                        val time = android.os.SystemClock.uptimeMillis()
                        second.onKeyDown(down, android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, down, 1))
                        assertEquals("Focus moves immediately while the repeated header waits", "repeat-3", (channelList(activity)!!.findFocus() as GuideAdapter.Row).channel?.id)
                        assertEquals("Repeat listing 2", header(activity))
                    }
                    waitFor(scenario) { header(it) == "Repeat listing 3" }
                    scenario.onActivity { activity ->
                        assertTrue(channelList(activity)!!.findFocus().contentDescription.contains("Repeat listing 3"))
                    }
                }
            } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun guideScrollingThemesAndCategorySelectionKeepThePlayer() {
        FixtureServer(regionGroups = true).use { server ->
            val source = Source(name = "Navigation Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                repeat(7) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.name == "Test Channel 8" }
                device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels.last().id }
                scenario.onActivity { assertFalse(channelList(it)!!.isShown) }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000)); activateSetting(scenario, "Theme")
                setThemeControl(scenario, "Look", "Palette", "Ocean"); leaveSettings(scenario)
                waitFor(scenario) { store.theme == "Ocean" && GuideTheme.current.name == "Ocean" }
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Categories")), 5000)); activateSetting(scenario, "Categories")
                assertTrue(device.wait(Until.hasObject(By.text("USA & Canada")), 5000)); activateSetting(scenario, "USA & Canada"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 6 }
                assertEquals(setOf("USA | News", "US | MLB", "CA | News"), SecureStore(context).filters(source).groups)
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun vodSharesCaptionAppearanceAndShowsMeasuredVideoQuality() {
        FixtureServer(captionsMode = true).use { server ->
            val source = Source(name = "VOD captions", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.captions = true
            store.subtitleSize = "Medium"; store.subtitleStyle = "Shadow"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                fun verifyAppearance(activity: MainActivity) {
                    val view = find<PlayerView>(activity.window.decorView)!!.subtitleView!!
                    val size = view.javaClass.getDeclaredField("defaultTextSize").apply { isAccessible = true }.getFloat(view)
                    val style = view.javaClass.getDeclaredField("style").apply { isAccessible = true }.get(view) as androidx.media3.ui.CaptionStyleCompat
                    assertEquals(SubtitleAppearance.sizes.getValue("Medium"), size, .001f)
                    assertEquals(androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
                }
                scenario.onActivity { activity ->
                    verifyAppearance(activity)
                    MainActivity::class.java.getDeclaredMethod("playOnDemand", OnDemand::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(activity, OnDemand("movie:captions", "VOD caption fixture", "", "${server.base}/channel1.m3u8", playlistId = source.id), 0L)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.currentMediaItem?.mediaId?.startsWith("movie:captions:") == true && p.isPlaying && p.currentCues.cues.isNotEmpty() } == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("on-demand-quality")?.text?.contains("fps") == true }
                scenario.onActivity { activity ->
                    verifyAppearance(activity)
                    // Image subtitles share size too, without mutating/reallocating the decoded image.
                    val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
                    try {
                        val cue = androidx.media3.common.text.Cue.Builder().setBitmap(bitmap).setSize(.5f).setBitmapHeight(.1f).build()
                        val playerView = PlayerView(activity)
                        SubtitleAppearance.render(playerView, listOf(cue), "Small")
                        @Suppress("UNCHECKED_CAST")
                        val rendered = playerView.subtitleView!!.javaClass.getDeclaredField("cues").apply { isAccessible = true }.get(playerView.subtitleView) as List<androidx.media3.common.text.Cue>
                        assertSame(bitmap, rendered.single().bitmap); assertTrue(rendered.single().size < cue.size)
                        assertEquals(5f, rendered.single().size / rendered.single().bitmapHeight, .001f)
                        playerView.subtitleView!!.setCues(emptyList())
                    } finally { bitmap.recycle() }
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun automaticSubtitlesRenderAndFpsBecomesKnown() {
        FixtureServer(captionsMode = true).use { server ->
            val source = Source(name = "Captions Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("Automatic")), 5000)); activateSetting(scenario, "Automatic"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.any { cue -> cue.text.toString().contains("Spare test captions") } == true }
                assertEquals(CaptionTracks.AUTO, store.channelCaptions(source, store.cached(source)!!.channels.first()))
                waitFor(scenario) { store.videoInfo(source).values.any { it.fps > 0 } }
                scenario.onActivity { assertFalse(store.videoInfo(source).values.first().label().contains("unavailable")) }
                // Automatic above is a channel override; enable the app default separately for the next channel.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("App default")), 5000)); activateSetting(scenario, "App default")
                assertTrue(device.wait(Until.hasObject(By.text("Default subtitles · all channels")), 5000)); activateSetting(scenario, "Automatic"); leaveSettings(scenario)
                waitFor(scenario) { store.captions }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels[1].id }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isNotEmpty() == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("Off")), 5000)); activateSetting(scenario, "Off"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, store.cached(source)!!.channels[1]))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideBackHierarchyAndBrowsingPreferencesKeepFocusAndPlayback() {
        FixtureServer(channelCount = 8).use { server ->
            val source = Source(name = "Guide browsing", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var liveId = ""
                scenario.onActivity { liveId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(2)!!.itemView.requestFocus()
                }
                waitFor(scenario) { (channelList(it)?.adapter as? GuideAdapter)?.channels?.let { c -> c.size == 4 && c.all { channel -> channel.group == "News" } } == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus())
                    assertEquals(liveId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(3)!!.itemView.requestFocus()
                }
                // Right before the debounce expires must enter the highlighted category, not the old rows.
                device.pressDPadRight()
                waitFor(scenario) { channelList(it)?.hasFocus() == true && (channelList(it)?.adapter as? GuideAdapter)?.channels?.all { c -> c.group == "Culture" } == true }
                device.pressBack(); device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressMenu(); activateSetting(scenario, "Switch categories on highlight"); activateSetting(scenario, "Open channels full screen"); activateSetting(scenario, "Loop guide channels")
                scenario.onActivity { assertFalse(store.guideAutoCategories); assertFalse(store.guideOpenFullscreen); assertFalse(store.guideLoopChannels) }
                leaveSettings(scenario); device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").hasFocus() }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(2)!!.itemView.requestFocus() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue((channelList(it)!!.adapter as GuideAdapter).channels.all { c -> c.group == "Culture" }) }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.hasFocus() == true && (channelList(it)?.adapter as? GuideAdapter)?.channels?.all { c -> c.group == "News" } == true }
                device.pressDPadUp(); scenario.onActivity { assertEquals(0, channelList(it)!!.getChildAdapterPosition(channelList(it)!!.focusedChild)) }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.mediaId != liveId && channelList(it)?.hasFocus() == true }
                var previewId = ""; scenario.onActivity { previewId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true && MainActivity::class.java.getDeclaredField("hasPlayedChannel").apply { isAccessible = true }.getBoolean(it) }
                device.pressDPadCenter(); waitFor(scenario) { channelList(it)?.isShown == false }
                scenario.onActivity { assertEquals(previewId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun deepCategoriesReachToolbarAndReturnWithoutScrollingOrRetuning() {
        FixtureServer().use { server ->
            val source = Source(name = "Deep sidebar", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..80).map { Channel("deep:$it", "Channel $it", "Category $it", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                repeat(30) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                var first = -1
                scenario.onActivity { first = (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() }
                device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search").hasFocus() }
                device.pressDPadDown(); device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                device.pressDPadLeft(); waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                scenario.onActivity {
                    assertTrue(kotlin.math.abs(first - (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()) <= 1)
                    assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadLeft(); repeat(3) { device.pressDPadDown() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Playlists")), 5000))
                activateSetting(scenario, "Navigation dock"); activateSetting(scenario, "Hidden dock"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.hasFocus() == true && it.window.decorView.findViewWithTag<View>("navigation-handle").isShown && it.window.decorView.findViewWithTag<View>("app-navigation").alpha < .01f }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                val dockFlashed = AtomicBoolean(false)
                lateinit var dockDraw: android.view.ViewTreeObserver.OnPreDrawListener
                scenario.onActivity { activity ->
                    val dock = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    dockDraw = android.view.ViewTreeObserver.OnPreDrawListener {
                        if (dock.isShown && (dock.expanded || dock.moving || dock.alpha > .01f)) dockFlashed.set(true)
                        true
                    }
                    dock.viewTreeObserver.addOnPreDrawListener(dockDraw)
                }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").viewTreeObserver.removeOnPreDrawListener(dockDraw)
                    assertFalse("Returning from fullscreen must never draw an expanded/closing hidden dock", dockFlashed.get())
                    assertEquals(attempt, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadLeft(); device.pressDPadLeft(); repeat(3) { device.pressDPadDown() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings").hasFocus() && it.window.decorView.findViewWithTag<View>("app-navigation").alpha > .99f }
                scenario.onActivity { activity ->
                    val dock = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    channelList(activity)!!.getChildAt(0).requestFocus()
                    dock.postOnAnimation { dock.focusCurrent() }
                }
                waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").let { it.expanded && !it.moving && it.alpha == 1f && it.hasFocus() } }
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true && it.window.decorView.findViewWithTag<View>("settings-panel").isShown == false }
                device.pressDPadLeft(); device.pressDPadLeft(); repeat(2) { device.pressDPadDown() }; device.pressDPadRight()
                waitFor(scenario) { channelList(it)?.hasFocus() == true && !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                device.pressMenu(); activateSetting(scenario, "Navigation dock"); activateSetting(scenario, "Always open"); leaveSettings(scenario)
                waitFor(scenario) { !store.hiddenDock && it.window.decorView.findViewWithTag<View>("app-navigation").alpha == 1f && !it.window.decorView.findViewWithTag<View>("navigation-handle").isShown }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun undeclaredEmbeddedCaptionsRenderAndChannelChoicesPersist() {
        listOf(false, true).forEach { digital -> FixtureServer(embeddedCaptions = if (digital) 708 else 608, embeddedHls = digital).use { server ->
            val source = Source(name = "Embedded captions", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.captions = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.any { cue -> cue.text.toString().contains("Spare captions") } == true }
                val first = store.cached(source)!!.channels.first()
                scenario.onActivity { activity ->
                    val player = find<PlayerView>(activity.window.decorView)!!.player!!
                    val selected = CaptionTracks.choices(player.currentTracks).first { it.group.isTrackSelected(it.index) }
                    assertEquals(if (digital) androidx.media3.common.MimeTypes.APPLICATION_CEA708 else androidx.media3.common.MimeTypes.APPLICATION_CEA608, selected.format.sampleMimeType)
                }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-subtitles")?.isShown == true }; scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-subtitles").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Off"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, first)); assertTrue(store.captions)
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("settings-panel").isShown && channelList(it)?.isShown == false }
                device.pressDPadDown()
                waitFor(scenario) { store.last(source) != first.id && find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isNotEmpty() == true }
                device.pressDPadLeft(); waitFor(scenario) { store.last(source) == first.id }
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.player!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)) }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { assertTrue(find<PlayerView>(it.window.decorView)!!.player!!.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        } }
    }

    @Test fun compactSportsCardsKeepBothTeamNamesAndScoresInsideTheirBounds() {
        FixtureServer().use { server ->
            val source = Source(name = "Compact Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("compact-feed", "News", "News", "${server.base}/channel1.mp4")
            val teams = SportsCatalog.teams.filter { "NCAAM" in it.competitions }.sortedByDescending { it.name.length }.take(2).sortedBy { it.id }
            val now = System.currentTimeMillis(); val key = "NCAAM:compact-card"
            val snapshot = LiveSportsSnapshot(listOf(LiveEvent("compact-card", teams.map { it.id }.toSet(), now - 60000,
                scores = mapOf(teams[0].id to "103", teams[1].id to "102"), status = "12:34 - 2nd half", league = "NCAAM",
                records = teams.associate { it.id to "11-11" })), now, setOf("NCAAM"))
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NCAAM")
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { snapshot }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-card-team-name:$key:1")?.let { view -> view.layout != null && view.height > 0 } == true }
                scenario.onActivity { activity ->
                    val card = activity.window.decorView.findViewWithTag<ViewGroup>(key)
                    card.requestFocus()
                    val banner = card.findViewWithTag<ViewGroup>("sports-card-banner:$key")
                    val checked = mutableListOf<TextView>()
                    assertTrue("Large crests retain their original artwork space", banner.height >= (72 * activity.resources.displayMetrics.density).toInt())
                    repeat(2) { index ->
                        val name = card.findViewWithTag<TextView>("sports-card-team-name:$key:$index")
                        val record = card.findViewWithTag<TextView>("sports-card-team-record:$key:$index")
                        val score = card.findViewWithTag<TextView>("sports-card-score:$key:$index")
                        assertEquals(teams[index].name, name.text.toString()); assertEquals("(11-11)", record.text.toString())
                        assertEquals(if (index == 0) "103" else "102", score.text.toString())
                        assertEquals("Focused bottom-line scores stay legible", GuideTheme.focusText, score.currentTextColor)
                        assertTrue("Scores belong in the bottom details line", generateSequence(score as View?) { it.parent as? View }.any { it.tag == "sports-card-footer" })
                        assertFalse("The artwork remains free of score widgets", generateSequence(score as View?) { it.parent as? View }.any { it === banner })
                        checked.addAll(listOf(name, record, score))
                    }
                    checked.add(card.findViewWithTag("sports-card-date:$key")); checked.add(card.findViewWithTag("sports-card-live-detail:$key"))
                    checked.forEach { view ->
                        val layout = view.layout!!
                        assertTrue("Text must retain a complete line height: ${view.tag}", layout.getLineBottom(layout.lineCount - 1) + view.totalPaddingTop <= view.height - view.totalPaddingBottom + 1)
                    }
                    val first = card.findViewWithTag<View>("sports-card-team-name:$key:0"); val second = card.findViewWithTag<View>("sports-card-team-name:$key:1")
                    val a = IntArray(2); val b = IntArray(2); first.getLocationOnScreen(a); second.getLocationOnScreen(b)
                    assertTrue("The second team has its own row", a[1] + first.height <= b[1])
                    assertTrue("Fixed body fits within the smaller card", (0 until card.childCount).all { index -> card.getChildAt(index).bottom <= card.height - card.paddingBottom })
                }
                instrumentation.waitForIdleSync(); device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-compact-card.png"))
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys }
        }
    }

    @Test fun largeSportsSlateKeepsNavigationAndScoresResponsiveDuringPendingDiffs() {
        FixtureServer().use { server ->
            val source = Source(name = "Large Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("large-slate-feed", "News", "News", "${server.base}/channel1.mp4")
            val bytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
            val urls = listOf("https://images.example/large-home-${System.nanoTime()}.png", "https://images.example/large-away-${System.nanoTime()}.png")
            val files = ArtworkFiles(context); urls.forEach { files.bytes(it) { bytes } }
            val now = System.currentTimeMillis()
            var latest = LiveSportsSnapshot((1..240).map { LiveEvent("large-slate-$it", setOf("nfl:SEA", "nfl:SF"), now - 60000,
                scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "7"), logos = mapOf("nfl:SEA" to urls[0], "nfl:SF" to urls[1]),
                status = "8:24 - 2nd", league = "NFL", records = mapOf("nfl:SEA" to "6-2", "nfl:SF" to "4-4"), situation = "3rd & 7 at SEA 28", possession = "nfl:SEA") }, now, setOf("NFL"))
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NFL"); store.hiddenDock = true
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) activity.liveSportsLoader = { latest }
            }
            lifecycle.addLifecycleCallback(fixture)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playing = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.let { grid -> grid.adapter?.itemCount == 241 && grid.hasFocus() } == true }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-title")?.text?.contains("(6-2)") == true }
                scenario.onActivity { activity ->
                    val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    val card = grid.findViewHolderForAdapterPosition(0)!!.itemView
                    assertEquals("Seattle Seahawks", card.findViewWithTag<TextView>("sports-card-team-name:NFL:large-slate-1:0").text.toString())
                    assertEquals("(6-2)", card.findViewWithTag<TextView>("sports-card-team-record:NFL:large-slate-1:0").text.toString())
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-detail").text.contains("3rd & 7"))
                }
                val entered = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    val worker = SportsPanel::class.java.getDeclaredField("cardDiffWorker").apply { isAccessible = true }.get(panel) as java.util.concurrent.ExecutorService
                    worker.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    latest = latest.copy(events = latest.events.map { it.copy(scores = mapOf("nfl:SEA" to "21", "nfl:SF" to "7")) })
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, latest)
                    MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    device.pressDPadRight()
                    waitFor(scenario) { activity ->
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        grid.findFocus()?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition == 1 } == true
                    }
                } finally { release.countDown() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.contains("21") == true }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    repeat(12) {
                        val tick = android.os.SystemClock.uptimeMillis()
                        panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                        panel.dispatchKeyEvent(KeyEvent(tick, tick, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
                    }
                }
                waitFor(scenario) { activity ->
                    val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    grid.findFocus()?.let { (grid.findContainingViewHolder(it)?.bindingAdapterPosition ?: -1) >= 30 } == true
                }
                scenario.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    assertFalse("Rapid card navigation must not open the main menu", rail.expanded)
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    assertEquals(241, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter!!.itemCount)
                }
                instrumentation.waitForIdleSync()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-large-slate.png"))
            } } finally { release.countDown(); lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys }
        }
    }

    @Test fun sportsSchedulePickerBackAndLeftRestoreOpenerFocus() {
        FixtureServer().use { server ->
            val source = Source(name = "Schedule picker fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE
                            player.isPlaying
                        } == true
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-schedule")?.isShown == true }
                    listOf({ device.pressBack() }, { device.pressDPadLeft() }).forEach { close ->
                        scenario.onActivity {
                            it.window.decorView.findViewWithTag<View>("sports-schedule").apply { requestFocus(); performClick() }
                        }
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-today").hasFocus() }
                        close()
                        waitFor(scenario) {
                            it.window.decorView.findViewWithTag<View>("sports-schedule").hasFocus() &&
                                !it.window.decorView.findViewWithTag<View>("sports-schedule-menu").isShown
                        }
                    }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
            }
        }
    }

    @Test fun sportsLeagueRailChoosesOnFirstVisitAndPreservesEachLeagueAndCalendar() {
        FixtureServer().use { server ->
            val source = Source(name = "League setup fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("league-feed", "News", "News", "${server.base}/channel1.mp4")
            val now = System.currentTimeMillis()
            val snapshot = LiveSportsSnapshot(listOf(
                LiveEvent("rail-nba", setOf("nba:POR", "nba:LAL"), now - 60000, league = "NBA"),
                LiveEvent("rail-nfl-1", setOf("nfl:SEA", "nfl:SF"), now - 60000, league = "NFL"),
                LiveEvent("rail-nfl-2", setOf("nfl:BUF", "nfl:KC"), now - 60000, league = "NFL")
            ), now, setOf("NFL", "NBA"))
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source); store.resetSportsLeagues()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { calls.incrementAndGet(); snapshot }; activity.calendarSportsLoader = { _, _ -> snapshot }
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playing = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league-picker")?.isShown == true }
                scenario.onActivity { activity ->
                    assertEquals("Do not fetch every league before setup", 0, calls.get())
                    assertFalse(activity.window.decorView.findViewWithTag<View>("sports-league-continue").isEnabled)
                    listOf("NFL", "NBA").forEach { activity.window.decorView.findViewWithTag<View>("sports-setup:$it").performClick() }
                    activity.window.decorView.findViewWithTag<View>("sports-league-continue").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.adapter?.itemCount == 3 }
                scenario.onActivity { activity ->
                    assertEquals(setOf("NFL", "NBA"), store.sportsLeagues)
                    assertNull("No mixed-league option", activity.window.decorView.findViewWithTag<View>("sports-league:all"))
                    assertNull("Disabled leagues stay out of the rail", activity.window.decorView.findViewWithTag<View>("sports-league:MLB"))
                    val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    grid.findViewHolderForAdapterPosition(1)!!.itemView.requestFocus()
                    activity.window.decorView.findViewWithTag<View>("sports-league:NBA").apply { requestFocus(); performClick() }
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 && it.window.decorView.findViewWithTag<View>("NBA:rail-nba")?.isShown == true }
                scenario.onActivity { activity ->
                    assertEquals("NBA", store.sportsLastLeague)
                    assertTrue("Loading cannot steal focus from the rail", activity.window.decorView.findViewWithTag<View>("sports-league:NBA").hasFocus())
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NBA").hasFocus() }
                device.pressDPadRight(); device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-schedule").hasFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-today").hasFocus() }
                device.pressDPadDown(); device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("calendar-games")?.adapter?.itemCount == 1 }
                device.pressBack()
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").apply { requestFocus(); performClick() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3 }
                device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NFL:rail-nfl-2")?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NBA").performClick() }
                waitFor(scenario) { store.sportsLastLeague == "NBA" && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NBA:rail-nba")?.isShown == true }
                scenario.onActivity { activity ->
                    assertFalse("Saved league choices do not reopen setup", activity.window.decorView.findViewWithTag<View>("sports-league-picker")?.isShown == true)
                    assertEquals(setOf("NFL", "NBA"), store.sportsLeagues)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-league-filter.png"))
            } } finally { lifecycle.removeLifecycleCallback(fixture); store.sportsLeagues = SportsLeagues.byId.keys; store.sportsLastLeague = "NFL" }
        }
    }

    @Test fun sportsFindsFollowedGamesSelectsPreviewAndPreservesRemoteFocus() {
        FixtureServer(channelCount = 4).use { server ->
            val source = Source(name = "Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..4).map { Channel("sports:$it", listOf("News", "ESPN", "YES", "CBS")[it - 1], if (it == 1) "News" else "Sports", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis()
            val programmes = mapOf("sports:2" to listOf(Programme("sports:2", "Yankees vs Red Sox", now - 300000, now + 3600000)),
                "sports:3" to listOf(Programme("sports:3", "MLB: NYY @ BOS", now - 300000, now + 3600000)),
                "sports:4" to listOf(Programme("sports:4", "NFL: Bills at Chiefs", now - 300000, now + 4 * 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true; store.followedTeams = setOf("mlb:NYY"); store.sportsTeam = "mlb:NYY"; store.sportsLastLeague = "MLB"
            store.toggleFavorite(source, channels[2]); store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null; scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); scenario.onActivity { fixtureLiveSports(it); it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:mlb-fixture")?.text?.let { label -> label.startsWith("ESPN") || label.startsWith("YES") } == true }
                val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val matcher = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    matcher.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    scenario.onActivity { activity ->
                        store.sportsAutoPlay = true
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                        val path = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem?.localConfiguration?.uri?.path
                        assertTrue("A discovered card starts its cached stream without waiting for the busy search worker: $path", path in setOf("/channel2.mp4", "/channel3.mp4"))
                    }
                } finally { release.countDown(); store.sportsAutoPlay = false }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel").isShown == false }
                var gameMediaId = ""
                scenario.onActivity { activity -> gameMediaId = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId; activity.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel").focusToolbar() }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { assertEquals("Browsing Library must preserve the exact game connection", gameMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-scores")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-scores").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<View>("sports-panel")
                    assertTrue(panel.height > activity.window.decorView.height * .75f); assertTrue(panel.width > activity.window.decorView.width * .8f)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("guide-header").isShown)
                    assertTrue("Hero: ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text} / ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text}; focus ${activity.currentFocus?.tag}", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("LIVE"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("sports-leagues"))
                    activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    activity.window.decorView.findViewWithTag<View>("sports-choose-stream").requestFocus()
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png")); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3").performClick() }
                waitFor(scenario) { store.last(source) == "sports:3" }; assertEquals("/channel3.mp4", playingPath(scenario))
                scenario.onActivity { assertFalse("Selecting a Sports result opens full screen", it.window.decorView.findViewWithTag<View>("sports-panel").isShown) }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:2")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:2").performLongClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-second")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var retainedPlayer: Player? = null; var retainedSurface: android.view.Surface? = null; var retainedTile: View? = null
                scenario.onActivity { activity ->
                    val extra = activity.window.decorView.findViewWithTag<PlayerView>("multiview-second")
                    retainedPlayer = extra.player; retainedSurface = (extra.videoSurfaceView as android.view.SurfaceView).holder.surface
                    retainedTile = activity.window.decorView.findViewWithTag<View>("multiview-tile:1")
                    activity.window.decorView.findViewWithTag<View>("multiview-add-channel").performClick()
                }
                waitFor(scenario) { channelList(it)?.isShown == true }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-controls")?.isShown == true && retainedPlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    val extra = activity.window.decorView.findViewWithTag<PlayerView>("multiview-second")
                    assertSame(retainedPlayer, extra.player); assertTrue("Returning keeps playback requested", retainedPlayer!!.playWhenReady)
                    assertSame(retainedSurface, (extra.videoSurfaceView as android.view.SurfaceView).holder.surface)
                    assertSame("Returning reuses the existing pane controls", retainedTile, activity.window.decorView.findViewWithTag<View>("multiview-tile:1"))
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-add-game").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league:NFL")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-league:NFL").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.performClick(); activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:4")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:4").performLongClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-third")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-add-channel").performClick() }
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { channelList(it)!!.findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<PlayerView>("multiview-fourth")?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { activity ->
                    val players = listOf(samePlayer!!) + listOf("multiview-second", "multiview-third", "multiview-fourth").map { activity.window.decorView.findViewWithTag<PlayerView>(it).player!! }
                    assertEquals(1, players.count { it.volume > 0f }); assertFalse(activity.window.decorView.findViewWithTag<View>("multiview-two-up").isEnabled)
                    activity.window.decorView.findViewWithTag<View>("multiview-tile:0").requestFocus(); assertEquals(1f, samePlayer!!.volume, 0f)
                    activity.window.decorView.findViewWithTag<View>("multiview-tile:3").requestFocus(); assertEquals(0f, samePlayer!!.volume, 0f)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "multiview.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-remove").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-fourth") == null }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-tile:2").requestFocus(); it.window.decorView.findViewWithTag<View>("multiview-remove").performClick(); it.window.decorView.findViewWithTag<View>("multiview-two-up").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-third") == null && it.window.decorView.findViewWithTag<View>("multiview-two-up").isEnabled }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-second") == null && channelList(it)?.isShown == true }
                scenario.onActivity { activity -> assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000)), now, SportsLeagues.byId.keys) }
                    pollSportsFixture(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo") }
                scenario.onActivity { activity ->
                    val games = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    games.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, status = "Updated score")), now, SportsLeagues.byId.keys) }
                    pollSportsFixture(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail").text.contains("Updated score") }
                scenario.onActivity { assertTrue("A scoreboard update keeps game-card focus", it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus()); assertFalse(it.window.decorView.findViewWithTag<NavigationRail>("app-navigation").hasFocus()) }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressDPadLeft(); device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadDown(); device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
            }
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet()
        }
    }

    @Test fun performanceOverlayIsOptInAndKeepsThePlayingConnection() {
        FixtureServer().use { server ->
            val source = Source(name = "Performance fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.performanceOverlay = false
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                        } == true
                    }
                    var mediaId = ""
                    scenario.onActivity {
                        assertNull(it.window.decorView.findViewWithTag<View>("performance-overlay"))
                        mediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    }
                    device.pressMenu()
                    activateSetting(scenario, "Playback diagnostics")
                    activateSetting(scenario, "Developer tools")
                    activateSetting(scenario, "Performance overlay")
                    waitFor(scenario) {
                        it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("UI 10s") == true
                    }
                    scenario.onActivity { activity ->
                        val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                        assertTrue(journal.report("fixture").contains("performance · Display"))
                        assertEquals(mediaId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    }
                    activateSetting(scenario, "Performance overlay")
                    scenario.onActivity {
                        assertNull(it.window.decorView.findViewWithTag<View>("performance-overlay"))
                        assertFalse(store.performanceOverlay)
                        assertEquals(mediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                    }
                }
            } finally { store.performanceOverlay = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun activityMemoryTrimKeepsVisibleSportsCardsAndReloadsOnReturn() {
        FixtureServer().use { server ->
            val source = Source(name = "Trim fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val stamp = System.nanoTime()
            val logos = mapOf("nfl:SEA" to "https://fixture.invalid/$stamp-sea.png", "nfl:SF" to "https://fixture.invalid/$stamp-sf.png")
            val bytes = ByteArrayOutputStream().also { output ->
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
                }
            }.toByteArray()
            val files = ArtworkFiles(context)
            logos.values.forEach { url -> files.bytes(url) { bytes } }
            store.saveSources(listOf(source)); store.active = source.id
            store.sportsLeagues = setOf("NFL")
            val now = System.currentTimeMillis()
            val event = LiveEvent("trim-game", logos.keys, now - 60_000, league = "NFL", logos = logos)
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { LiveSportsSnapshot(listOf(event), now, setOf("NFL")) }
                }
            }
            fun cardBitmaps(activity: MainActivity): List<Bitmap> {
                val banner = activity.window.decorView.findViewWithTag<ViewGroup>("sports-card-banner:NFL:trim-game")
                if (banner?.isShown != true) return emptyList()
                val crests = banner.getChildAt(0) as ViewGroup
                return (0 until crests.childCount).mapNotNull { index ->
                    ((crests.getChildAt(index) as ImageView).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                }
            }
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) {
                        find<PlayerView>(it.window.decorView)?.player?.let { player ->
                            player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying
                        } == true
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { cardBitmaps(it).size == 2 }
                    scenario.onActivity { activity ->
                        val before = cardBitmaps(activity)
                        activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                        val after = cardBitmaps(activity)
                        assertEquals(2, after.size)
                        before.zip(after).forEach { (old, current) -> assertSame(old, current); assertFalse(current.isRecycled) }
                        activity.window.decorView.findViewWithTag<View>("guide-mode").performClick()
                    }
                    waitFor(scenario) { channelList(it)?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                    waitFor(scenario) { cardBitmaps(it).size == 2 }
                }
            } finally {
                lifecycle.removeLifecycleCallback(fixture)
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun largeSportsImagesStayBoundAfterMemoryCacheEviction() {
        val files = ArtworkFiles(context)
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(2048, 1365, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }
        }.toByteArray()
        val urls = (1..2).map { "https://images.example/large-${System.nanoTime()}-$it.png" }
        urls.forEach { files.bytes(it) { bytes } }
        val loader = LogoLoader(1280, 32 * 1024, files) // Each image deliberately exceeds the LRU.
        val images = List(2) { android.widget.ImageView(context) }
        val callbacks = IntArray(2)
        try {
            instrumentation.runOnMainSync { images.forEachIndexed { i, image -> loader.bind(image, urls[i]) { callbacks[i]++ } } }
            val deadline = System.currentTimeMillis() + 5000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = images.all { it.drawable != null } }; Thread.sleep(30)
            }
            assertTrue("Large artwork must finish even when evicted before its callback", ready)
            instrumentation.runOnMainSync {
                val original = images.map { (it.drawable as android.graphics.drawable.BitmapDrawable).bitmap }
                original.forEach { assertTrue(maxOf(it.width, it.height) <= 1280) }
                repeat(32) { images.forEachIndexed { i, image -> loader.bind(image, urls[i]) { callbacks[i]++ } } }
                images.forEachIndexed { i, image -> assertSame(original[i], (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap); assertEquals(2, callbacks[i]) }
            }
            val retryUrl = "https://images.example/retry-${System.nanoTime()}.png"
            files.bytes(retryUrl) { bytes }
            val retryImage = android.widget.ImageView(context)
            instrumentation.runOnMainSync {
                loader.pause(); loader.bind(retryImage, retryUrl)
                assertNull(retryImage.drawable)
                loader.resume(); loader.bind(retryImage, retryUrl)
            }
            val retryDeadline = System.currentTimeMillis() + 5000
            var retried = false
            while (!retried && System.currentTimeMillis() < retryDeadline) {
                instrumentation.runOnMainSync { retried = retryImage.drawable != null }; Thread.sleep(30)
            }
            assertTrue("A bind skipped while paused must retry the same image after resume", retried)
        } finally { instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun sportsScoreDelayWithholdsNewScoresAcrossCardsHeroOverlayAndCalendar() {
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Delayed score fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis(); val ids = setOf("nba:POR", "nba:LAL")
            val first = LiveEvent("delayed-fixture", ids, now - 60000, scores = mapOf("nba:POR" to "11", "nba:LAL" to "22"), status = "Q1", league = "NBA")
            val initial = LiveSportsSnapshot(listOf(first), now, setOf("NBA"))
            var latest = initial
            val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val fixture = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (activity is MainActivity && stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE) {
                    activity.liveSportsLoader = { latest }; activity.calendarSportsLoader = { _, _ -> latest }
                }
            }
            store.saveSources(listOf(source)); store.active = source.id; store.sportsLeagues = setOf("NBA"); store.sportsScoreDelay = 30
            lifecycle.addLifecycleCallback(fixture)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                    var playing: Player? = null
                    scenario.onActivity { activity ->
                        playing = find<PlayerView>(activity.window.decorView)!!.player
                        MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.toString() == "Score delayed" }
                    scenario.onActivity { activity ->
                        val history = MainActivity::class.java.getDeclaredField("delayedScores").apply { isAccessible = true }.get(activity) as SportsScoreDelay
                        val tick = android.os.SystemClock.elapsedRealtime(); history.clear(); history.observe(initial, tick - 31000)
                        latest = initial.copy(events = listOf(first.copy(state = "post", scores = mapOf("nba:POR" to "44", "nba:LAL" to "22"), status = "Final")))
                        history.observe(latest, tick)
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, latest)
                        MainActivity::class.java.getDeclaredMethod("scheduleSports").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-hero-score")?.text?.contains("11") == true }
                    scenario.onActivity { activity ->
                        repeat(2) { index -> assertFalse(activity.window.decorView.findViewWithTag<TextView>("sports-card-score:NBA:delayed-fixture:$index").text.contains("44")) }
                        assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-badge").text.contains("LIVE"))
                        assertFalse(activity.window.decorView.findViewWithTag<TextView>("sports-coverage:NBA:delayed-fixture").text.contains("catch-up", true))
                        MainActivity::class.java.getDeclaredMethod("openSportsScoreDelay").apply { isAccessible = true }.invoke(activity)
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.findViewWithTag<View>("settings-option:Current channel").performClick()
                        manager.findViewWithTag<View>("settings-option:15 seconds").performClick()
                        val channel = MainActivity::class.java.getDeclaredField("current").apply { isAccessible = true }.get(activity) as Channel
                        assertEquals(15, store.channelScoreDelay("${source.id}:${channel.nativeId}")); assertEquals(30, store.sportsScoreDelay); assertTrue(manager.opened)
                        manager.selectSetting("Score delay"); manager.findViewWithTag<View>("settings-option:Off · immediate scores").performClick()
                        assertEquals(0, store.sportsScoreDelay)
                        manager.findViewWithTag<View>("settings-option:30 seconds").performClick()
                        MainActivity::class.java.getDeclaredMethod("closeSportsManager").apply { isAccessible = true }.invoke(activity)
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        MainActivity::class.java.getDeclaredMethod("openScoreStrip").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { device.hasObject(By.textContains("POR 11")) }
                    scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("openSportsCalendar").apply { isAccessible = true }.invoke(activity) }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NBA:delayed-fixture")?.isShown == true }
                    scenario.onActivity { activity ->
                        val row = activity.window.decorView.findViewWithTag<ViewGroup>("calendar-game:NBA:delayed-fixture")
                        assertTrue((row.getChildAt(0) as TextView).text.contains("11")); assertFalse((row.getChildAt(0) as TextView).text.contains("44"))
                        assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playing!!.isPlaying)
                    }
                }
            } finally { lifecycle.removeLifecycleCallback(fixture); store.resetScoreDelays(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsHeroRailAndFullScreenTeamManagerKeepFocusAndLeaguePreferences() {
        FixtureServer().use { server ->
            val artFiles = ArtworkFiles(context)
            val artBytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(2048, 1365, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
            val artUrls = (1..16).map { "https://images.example/grid-${System.nanoTime()}-$it.png" }; artUrls.forEach { artFiles.bytes(it) { artBytes } }
            val source = Source(name = "Sports focus fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("focus", "NFL RedZone FHD", "NFL", "${server.base}/channel1.mp4")
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            try {
                val scoresReady = java.util.concurrent.CountDownLatch(1)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var playback: Player? = null
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> playback = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack()
                    scenario.onActivity { activity ->
                        val now = System.currentTimeMillis()
                        val snapshot = LiveSportsSnapshot((1..8).map { LiveEvent("focus-$it", setOf("nfl:BUF", "nfl:KC"), now - 300000, logos = mapOf("nfl:BUF" to artUrls[(it - 1) * 2], "nfl:KC" to artUrls[(it - 1) * 2 + 1]), league = "NFL") }, now, setOf("NFL"))
                        activity.liveSportsLoader = { scoresReady.await(5, java.util.concurrent.TimeUnit.SECONDS); snapshot }
                        activity.calendarSportsLoader = { day, leagues ->
                            val today = java.time.LocalDate.now()
                            val start = if (day == today) maxOf(System.currentTimeMillis() - 60000, day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) else day.atTime(17, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            LiveSportsSnapshot(listOf(LiveEvent("calendar-$day", setOf("nfl:BUF", "nfl:KC"), start, if (day == today) "in" else if (day.isBefore(today)) "post" else "pre", scores = mapOf("nfl:BUF" to "24", "nfl:KC" to "21"), league = "NFL")), System.currentTimeMillis(), leagues)
                        }
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-skeleton")?.isShown == true }
                    var loadingHeroHeight = 0
                    scenario.onActivity { activity ->
                        assertEquals(0, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter!!.itemCount)
                        assertTrue(activity.window.decorView.findViewWithTag<View>("sports-schedule").hasFocus())
                        loadingHeroHeight = activity.window.decorView.findViewWithTag<View>("sports-hero").height
                    }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-loading.png")); scoresReady.countDown()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 9 && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-scroll-hint")?.let { hint -> hint.isShown && hint.visibility == View.VISIBLE && hint.text.contains("Scroll down ↓") } == true }
                    var heroHeight = 0; var firstKey: Any? = null
                    scenario.onActivity { activity ->
                        assertNull(activity.window.decorView.findViewWithTag<View>("sports-leagues"))
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        firstKey = grid.findFocus().tag; heroHeight = activity.window.decorView.findViewWithTag<View>("sports-hero").height
                        assertEquals(loadingHeroHeight, heroHeight); assertFalse(activity.window.decorView.findViewWithTag<View>("sports-skeleton").isShown)
                        pollSportsFixture(activity)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertEquals(firstKey, activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().tag)
                        assertEquals(heroHeight, activity.window.decorView.findViewWithTag<View>("sports-hero").height)
                        val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                        assertFalse(rail.expanded); rail.contentHasFocus = false; rail.sports.requestFocus(); assertFalse(rail.expanded)
                    }
                    // A cache update may be busy on another thread while the viewer keeps browsing.
                    // Holding its monitor makes any accidental UI-thread cache access observable.
                    val cacheHeld = java.util.concurrent.CountDownLatch(1)
                    val releaseCache = java.util.concurrent.CountDownLatch(1)
                    val uiResponsive = java.util.concurrent.CountDownLatch(1)
                    val cacheOwner = Executors.newSingleThreadExecutor()
                    lateinit var cache: SportsStreamCache
                    lateinit var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener
                    scenario.onActivity { activity ->
                        cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                        focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
                            if ((focused?.tag as? String)?.startsWith("NFL:focus-") == true && focused?.tag != firstKey) {
                                focused?.post { uiResponsive.countDown() }
                            }
                        }
                        activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
                    }
                    var responsive = false
                    try {
                        cacheOwner.submit { synchronized(cache) { cacheHeld.countDown(); releaseCache.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
                        assertTrue(cacheHeld.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        val now = android.os.SystemClock.uptimeMillis()
                        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                            val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0).apply { this.source = android.view.InputDevice.SOURCE_DPAD }
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false))
                        }
                        responsive = uiResponsive.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } finally {
                        releaseCache.countDown(); cacheOwner.shutdownNow()
                        scenario.onActivity { it.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener) }
                    }
                    assertTrue("Sports focus and the main looper must respond while stream-cache work is blocked", responsive)
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { activity ->
                        val card = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)?.itemView
                        val drawable = card?.let { find<android.widget.ImageView>(it) }?.drawable as? android.graphics.drawable.BitmapDrawable
                        (drawable?.bitmap?.width ?: 0) >= 256 && drawable?.isFilterBitmap == true
                    }
                    scenario.onActivity { selectSportsSchedule(it, "sports-date") }
                    val calendarToday = java.time.LocalDate.now()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == true && it.window.decorView.findViewWithTag<View>("calendar-day:$calendarToday")?.hasFocus() == true }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("calendar-games").adapter?.itemCount == 1 && !it.window.decorView.findViewWithTag<View>("calendar-games-loading").isShown }
                    scenario.onActivity { activity ->
                        val calendar = activity.window.decorView.findViewWithTag<View>("sports-calendar")
                        assertEquals(activity.window.decorView.width, calendar.width); assertEquals(activity.window.decorView.height, calendar.height)
                        assertSame(playback, find<PlayerView>(activity.window.decorView)!!.player); assertTrue(playback!!.isPlaying)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                        val image = Bitmap.createBitmap(calendar.width, calendar.height, Bitmap.Config.ARGB_8888)
                        calendar.draw(Canvas(image))
                        File(context.getExternalFilesDir(null), "sports-calendar.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                    }
                    if (calendarToday.dayOfWeek == java.time.DayOfWeek.SATURDAY) {
                        // Right at the grid edge opens the agenda. Reach Sunday via the next row instead.
                        device.pressDPadDown(); repeat(6) { device.pressDPadLeft() }
                    } else device.pressDPadRight()
                    device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${calendarToday.plusDays(1)}")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        val panel = activity.window.decorView.findViewWithTag<SportsCalendarPanel>("sports-calendar")
                        panel.submit(calendarToday, emptyList(), false, false, false)
                        assertEquals("A stale date response cannot clear the current games", 1, activity.window.decorView.findViewWithTag<RecyclerView>("calendar-games").adapter!!.itemCount)
                        activity.window.decorView.findViewWithTag<View>("calendar-open-date").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == false && it.window.decorView.findViewWithTag<View>("sports-date")?.isSelected == true }
                    scenario.onActivity { activity ->
                        assertEquals("date:${calendarToday.plusDays(1)}", MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.get(activity))
                        selectSportsSchedule(activity, "sports-date")
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-day:${calendarToday.plusDays(1)}")?.hasFocus() == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("calendar-next").apply { requestFocus(); performClick() } }
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-schedule")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        assertEquals("date:${calendarToday.plusDays(1)}", MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.get(activity))
                        selectSportsSchedule(activity, "sports-today")
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    }
                    device.pressDPadDown(); device.pressDPadRight()
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation").expanded) }
                    device.pressDPadDown(); device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                    scenario.onActivity { activity ->
                        assertEquals("NFL COVERAGE", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.toString())
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("LIVE"))
                    }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick(); it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:focus")?.isShown == true }
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-watch").isShown) }
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.hasFocus() == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-teams").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager").isShown }
                    scenario.onActivity { activity ->
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.selectSetting("Preferences"); manager.findViewWithTag<View>("settings-option:Show only my teams").performClick()
                        assertTrue(manager.opened); assertTrue(store.sportsFollowedOnly)
                        manager.findViewWithTag<View>("settings-option:Play best stream on selection").performClick()
                        assertTrue("Sports preferences save automatic playback without closing", manager.opened && SecureStore(context).sportsAutoPlay)
                        manager.selectSetting("Leagues"); manager.focusOption("Champions League"); manager.findViewWithTag<View>("settings-option:Champions League").performClick()
                        assertTrue(manager.opened); assertFalse("UCL" in SecureStore(context).sportsLeagues)
                        listOf("NCAA Football", "NCAA Men's Basketball", "NCAA Women's Basketball", "NCAA Baseball").forEach { name ->
                            assertNotNull("College leagues appear in Sports preferences", manager.findViewWithTag<View>("settings-option:$name"))
                        }
                        manager.findViewWithTag<View>("settings-option:NCAA Football").performClick()
                        assertFalse("NCAAF" in SecureStore(context).sportsLeagues)
                        manager.findViewWithTag<View>("settings-option:NCAA Football").performClick()
                        assertTrue("NCAAF" in SecureStore(context).sportsLeagues)
                        manager.selectSetting("NCAA Football")
                    }
                    waitFor(scenario) { find<RecyclerView>(it.window.decorView.findViewWithTag<SettingsPanel>("sports-manager"))?.findViewHolderForAdapterPosition(0)?.itemView?.isShown == true }
                    device.pressDPadCenter(); waitFor(scenario) { "ncaaf:2000" in store.followedTeams }
                    scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager").selectSetting("NFL") }
                    device.pressDPadCenter(); waitFor(scenario) { "nfl:ARI" in store.followedTeams }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-manager.png"))
                    scenario.onActivity { activity ->
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.selectSetting("Preferences"); manager.findViewWithTag<View>("settings-option:Reset Sports settings").performClick()
                        assertTrue(manager.findViewWithTag<View>("settings-option:Cancel").hasFocus())
                        manager.findViewWithTag<View>("settings-option:Cancel").performClick(); assertTrue("nfl:ARI" in store.followedTeams)
                        manager.findViewWithTag<View>("settings-option:Reset Sports settings").performClick(); manager.findViewWithTag<View>("settings-option:Reset Sports").performClick()
                        assertTrue(store.followedTeams.isEmpty()); assertEquals(SportsLeagues.byId.keys, store.sportsLeagues)
                        assertFalse("Reset asks for leagues again", store.sportsLeaguesChosen)
                        assertFalse(store.sportsFollowedOnly); assertFalse(SecureStore(context).sportsAutoPlay); assertEquals(GuideFilters(), store.sportsFilters(source)); assertTrue(store.hideCompletedScores); assertEquals("today", store.sportsTeam)
                        assertEquals(source.id, store.active)
                    }
                    device.pressBack(); device.pressBack()
                    waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-manager").isShown && it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league-picker")?.isShown == true }
                    scenario.onActivity { activity ->
                        activity.window.decorView.findViewWithTag<View>("sports-setup:NFL").performClick()
                        activity.window.decorView.findViewWithTag<View>("sports-league-continue").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 9 }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-schedule").requestFocus() }
                    device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-league-filter").hasFocus() }
                    device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                    device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png"))
                    val focusFrames = java.util.concurrent.CountDownLatch(1)
                    scenario.onActivity { activity ->
                        val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                        val grid = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                        val target = (0 until grid.adapter!!.itemCount).first { grid.findViewHolderForAdapterPosition(it) == null }
                        grid.suppressLayout(true)
                        SportsPanel::class.java.getDeclaredMethod("focusAt", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, target)
                        // A user can move to the header before the requested offscreen
                        // card is laid out. The old retry must not steal focus back.
                        activity.window.decorView.findViewWithTag<View>("sports-schedule").requestFocus()
                        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                        grid.suppressLayout(false)
                        grid.postOnAnimation { grid.postOnAnimation { focusFrames.countDown() } }
                    }
                    assertTrue("Offscreen Sports focus layout completes", focusFrames.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    scenario.onActivity { assertTrue("New header input wins over delayed card focus", it.window.decorView.findViewWithTag<View>("sports-teams").hasFocus()) }
                    scenario.onActivity { selectSportsSchedule(it, "sports-date") }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${java.time.LocalDate.now()}")?.isShown == true }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("calendar-game:NFL:calendar-${java.time.LocalDate.now()}").performClick() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-calendar")?.isShown == false && it.window.decorView.findViewWithTag<View>("sports-watch").isShown }
                    scenario.onActivity { assertSame(playback, find<PlayerView>(it.window.decorView)!!.player); assertTrue(playback!!.isPlaying) }
                    device.pressHome(); instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { assertFalse(playback!!.playWhenReady) }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    instrumentation.runOnMainSync { assertEquals(Player.STATE_IDLE, playback!!.playbackState) }
                }
            } finally { store.sportsLeagues = SportsLeagues.byId.keys; store.sportsFollowedOnly = false; store.hiddenDock = false; store.followedTeams = emptySet(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun perChannelGuideTimeCanRewindResetAndReachNowWithRemote() {
        FixtureServer().use { server ->
            val source = Source(name = "Timeline fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..2).map { Channel("time:$it", "Channel $it", "News", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis(); val slot = GuideTimeline.live(now)
            val programmes = channels.associate { c -> c.id to listOf(Programme(c.id, "Live ${c.id}", slot, slot + 2 * GuideTimeline.SLOT), Programme(c.id, "Later ${c.id}", slot + 2 * GuideTimeline.SLOT, slot + 3 * 3600000)) }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                repeat(2) { device.pressDPadRight() }; waitFor(scenario) { device.hasObject(By.text("Later time:1")) }
                device.pressDPadDown(); waitFor(scenario) { device.hasObject(By.text("Live time:2")) }
                device.pressDPadUp(); waitFor(scenario) { device.hasObject(By.text("Later time:1")) }
                repeat(2) { device.pressDPadLeft() }; waitFor(scenario) { device.hasObject(By.text("Live time:1")) }
                device.pressDPadRight(); device.pressBack(); waitFor(scenario) { device.hasObject(By.text("Live time:1")) && channelList(it)?.hasFocus() == true }
                device.pressDPadRight(); device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-now").hasFocus() }
                device.pressDPadCenter(); waitFor(scenario) { device.hasObject(By.text("Live time:1")) }
                device.pressDPadDown(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search").requestFocus() }
                repeat(6) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("search").hasFocus()) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Guide")), 5000)); activateSetting(scenario, "Time format")
                assertTrue(device.wait(Until.hasObject(By.text("24 hour · 19:30")), 5000)); activateSetting(scenario, "24 hour · 19:30"); leaveSettings(scenario)
                waitFor(scenario) { store.clock24 && GuideClock.use24Hour }
                scenario.recreate(); waitFor(scenario) { GuideClock.use24Hour && find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun favoritesClearSearchAndImmediatelyConfirmAndRedrawChanges() {
        // This test edits a query; the separate voice test covers microphone/recognition behavior.
        // A clean CI install must not route the Favorites activation into a permission prompt.
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        FixtureServer().use { server ->
            val source = Source(name = "Favorites fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..3).map { Channel("favorite:$it", listOf("One", "Two", "Three")[it - 1], "News", "${server.base}/channel$it.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            channels.take(2).forEach { store.toggleFavorite(source, it) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search").performClick() }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("One") }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:favorite:1")?.isShown == true }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == false }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(1)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 2 && channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = channelList(activity)!!.findViewHolderForAdapterPosition(0)!!.itemView
                    row.requestFocus(); val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888); row.draw(Canvas(bitmap))
                    val d = activity.resources.displayMetrics.density
                    assertEquals("Favorite star must be dark on a highlighted row", Color.BLACK, bitmap.getPixel(((GuideAdapter.CHANNEL_WIDTH_DP - 12) * d).toInt(), row.height / 2))
                    bitmap.recycle(); row.performLongClick()
                }
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 && device.hasObject(By.text("Removed from Favorites · One")) }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 3 && (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:1" }
                device.pressDPadDown(); device.pressDPadDown()
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:3" }
                device.executeShellCommand("input dpad keyevent --duration ${ViewConfiguration.getLongPressTimeout() + 500} 23")
                waitFor(scenario) { device.hasObject(By.text("Added to Favorites · Three")) && (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "favorite:3" }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "favorite-feedback.png"))
                scenario.onActivity { activity ->
                    val icon = activity.getDrawable(R.drawable.ic_spare)!!
                    val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(image))
                    File(context.getExternalFilesDir(null), "icon.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").findViewHolderForAdapterPosition(1)!!.itemView.requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { (channelList(it)?.adapter as? GuideAdapter)?.channels?.map { it.id } == listOf("favorite:2", "favorite:3") }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun sportsReplayFindsArchiveHidesScoresAndReturnsToTheGame() {
        FixtureServer(archivedSports = true).use { server ->
            val source = Source(name = "Sports replay fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channel = Channel("xc:1", "ESPN HD", "NFL", "${server.base}/live/test/test-secret/1.mp4", catchupDays = 2, catchupMode = "xc", reportedQuality = VideoInfo(1280, 720, 30f))
            store.setSportsFilters(source, GuideFilters(1080, FrameRateFilter.sixtyOrBetter))
            store.saveSources(listOf(source)); store.active = source.id
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().remove("sports-hide-completed-scores").commit()
            assertTrue("Completed scores are hidden before the first Sports visit", store.hideCompletedScores)
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var player: Player? = null
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> player = p; p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack()
                    scenario.onActivity { activity ->
                        val now = System.currentTimeMillis()
                        val finals = LiveSportsSnapshot(listOf(LiveEvent("sports-archive", setOf("nfl:SEA", "nfl:SF"), now - 7200000, "post", networks = listOf("ESPN"), scores = mapOf("nfl:SEA" to "24", "nfl:SF" to "21"), status = "Seahawks win 24–21", league = "NFL")), now, setOf("NFL"))
                        activity.liveSportsLoader = { finals }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, finals)
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                        MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(activity, "date:${java.time.Instant.ofEpochMilli(now - 7200000).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}")
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text == "Score hidden" && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    waitFor(scenario) { !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(it) }
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST") val cache = MainActivity::class.java.getDeclaredField("replayCache").apply { isAccessible = true }.get(activity) as MutableMap<String, Pair<Long, List<SportsReplay>>>
                        cache.entries.toList().forEach { entry -> cache[entry.key] = entry.value.first - SportsReplayDiscovery.HISTORY_MS - 1000L to entry.value.second }
                        MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(it) && it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    scenario.onActivity { activity ->
                        assertFalse(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail")?.text?.contains("win") == true)
                        assertFalse(activity.window.decorView.findViewWithTag<View>("sports-watch").isShown)
                        assertEquals("Automatic discovery confirms the lower-quality archive", "REPLAY", activity.window.decorView.findViewWithTag<TextView>("sports-badge:NFL:sports-archive").text.toString())
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    }
                    assertEquals("Only the matching broadcaster's metadata archive is queried", 1, server.archiveRequests.get())
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/test/test-secret/60/") == true && (player?.videoSize?.width ?: 0) > 0 }
                    scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player) }
                    device.pressBack()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() && find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/live/") == true }
                    scenario.onActivity { activity ->
                        assertFalse("Completed games have no Hide/Reveal score button", activity.window.decorView.findViewWithTag<View>("sports-reminder").isShown)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:sports-archive")?.text?.contains("Play replay") == true }
                    scenario.onActivity { activity ->
                        val badge = activity.window.decorView.findViewWithTag<TextView>("sports-badge:NFL:sports-archive")
                        assertEquals("A lower-quality archive still gets a confirmed replay badge", "REPLAY", badge.text.toString())
                        val availableWidth = badge.width - badge.compoundPaddingLeft - badge.compoundPaddingRight
                        assertTrue("The full replay label fits inside its badge", badge.paint.measureText(badge.text.toString()) <= availableWidth)
                        assertEquals(0xFF123D68.toInt(), badge.currentTextColor)
                        activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findFocus().performClick()
                    }
                    waitFor(scenario) { player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/") == true }
                    assertEquals("Repeat replay lookup uses its saved match", 1, server.archiveRequests.get())
                    device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    scenario.onActivity { activity ->
                        assertEquals("Score hidden", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text.toString())
                        activity.window.decorView.findViewWithTag<View>("settings").performClick()
                        activity.window.decorView.findViewWithTag<SettingsPanel>("settings-panel").selectSetting("Sports")
                        activity.window.decorView.findViewWithTag<View>("settings-option:Hide completed-game scores").performClick()
                        assertFalse(SecureStore(context).hideCompletedScores)
                        MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-score").text.contains("24") }
                    scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-reminder").isShown) }
                    waitFor(scenario) { store.read("sports-replays-${source.id}.enc") != null }
                    scenario.recreate()
                    waitFor(scenario) { activity ->
                        @Suppress("UNCHECKED_CAST") val restored = MainActivity::class.java.getDeclaredField("replayCache").apply { isAccessible = true }.get(activity) as Map<String, Pair<Long, List<SportsReplay>>>
                        restored.entries.any { it.key.endsWith(":NFL:sports-archive") && it.value.second.singleOrNull()?.channel?.id == channel.id }
                    }
                    assertEquals("Restart restores a confirmed replay without searching the provider again", 1, server.archiveRequests.get())
                }
            } finally { store.hideCompletedScores = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sportsSchedulesShowLiveAndUpcomingWithoutScanningPlaylistAndLibraryIsReachable() {
        store.delete("game-reminders.enc")
        FixtureServer(channelCount = 12).use { server ->
            val source = Source(name = "Schedule fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = (1..12).map { Channel("xc:$it", "ESPN $it", if (it == 12) "NFL Game Pass" else "Sports", "${server.base}/live/test/test-secret/$it.mp4", guideId = "xc:$it", reportedQuality = if (it == 12) VideoInfo(1920, 1080, 0f) else null) }
            val cachedAt = System.currentTimeMillis()
            val midnight = java.time.Instant.ofEpochMilli(cachedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val upcomingAt = cachedAt + minOf(3600000L, (midnight - cachedAt) / 2)
            val programmes = channels.associate { it.guideId to listOf(Programme(it.guideId, "Cached guide", cachedAt - 60000, cachedAt + 3 * 3600000)) } + mapOf(channels.first().guideId to listOf(
                Programme(channels.first().guideId, "Cached guide", cachedAt - 60000, upcomingAt),
                Programme(channels.first().guideId, "NFL: Seahawks vs 49ers", upcomingAt, upcomingAt + 3 * 3600000))) + mapOf(channels.last().guideId to listOf(Programme(channels.last().guideId, "NFL RedZone", cachedAt - 60000, cachedAt + 3 * 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            store.setSportsFilters(source, GuideFilters(minHeight = 1080)); store.followedTeams = setOf("nfl:SEA")
            val scoreboard = LiveSportsSnapshot(listOf(LiveEvent("live", setOf("nfl:BUF", "nfl:KC"), cachedAt - 300000),
                LiveEvent("next", setOf("nfl:SEA", "nfl:SF"), upcomingAt, "pre", records = mapOf("nfl:SEA" to "6-2", "nfl:SF" to "2-6"))), cachedAt, setOf("NFL", "MLB"))
            val destinationStarted = java.util.concurrent.CountDownLatch(1)
            val finishDestination = java.util.concurrent.CountDownLatch(1)
            val firstBatchDrawn = java.util.concurrent.CountDownLatch(1)
            val finishScoreboard = java.util.concurrent.CountDownLatch(1)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                val openedAt = android.os.SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    activity.redZoneLoader = { snapshot, profile, quality, at ->
                        destinationStarted.countDown()
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
                        while (finishDestination.count > 0 && System.nanoTime() < deadline) try { finishDestination.await(100, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        SportsDestinations.redZone(snapshot, profile, quality, at)
                    }
                    activity.liveSportsLoader = {
                        assertTrue(destinationStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        val request = MainActivity::class.java.getDeclaredField("liveSportsRequest").apply { isAccessible = true }.getInt(activity)
                        val publish = MainActivity::class.java.getDeclaredMethod("publishSportsSchedule", LiveSportsSnapshot::class.java, java.time.LocalDate::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        publish.invoke(activity, scoreboard.copy(events = scoreboard.events.takeLast(1)), null, request, false)
                        assertTrue(firstBatchDrawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        publish.invoke(activity, scoreboard, null, request, false)
                        finishScoreboard.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        scoreboard.copy(checked = System.currentTimeMillis())
                    }
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                firstBatchDrawn.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3 && it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                assertTrue("Cold scoreboard results must not wait behind RedZone discovery", android.os.SystemClock.elapsedRealtime() - openedAt < 3000)
                scenario.onActivity { assertTrue("More scoreboard results are still arriving", MainActivity::class.java.getDeclaredField("liveSportsChecking").apply { isAccessible = true }.getBoolean(it)) }
                scenario.onActivity { activity ->
                    val request = MainActivity::class.java.getDeclaredField("liveSportsRequest").apply { isAccessible = true }.getInt(activity)
                    MainActivity::class.java.getDeclaredMethod("publishSportsSchedule", LiveSportsSnapshot::class.java, java.time.LocalDate::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                        .invoke(activity, LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NBA")), null, request, false)
                }
                waitFor(scenario) { activity ->
                    val scores = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.get(activity) as LiveSportsSnapshot
                    "NBA" in scores.leagues && activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3
                }
                finishScoreboard.countDown(); finishDestination.countDown()
                waitFor(scenario) { activity ->
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                    summaries["live"]?.startsWith("Finding stream") == false
                }
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST") val summaries = MainActivity::class.java.getDeclaredField("sportsStreamSummaries").apply { isAccessible = true }.get(activity) as Map<String, String>
                    assertFalse("Upcoming games must not start automatic playlist searches", summaries.containsKey("next"))
                }
                assertEquals("Displaying schedules must not query every provider channel", 0, server.epgTargets.size)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage")?.text.toString().contains("ESPN 12") }
                scenario.onActivity { activity ->
                    assertTrue("Hero: ${activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text}; focus ${activity.currentFocus?.tag}", activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Seattle"))
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("UPCOMING"))
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-title").text.contains("(6-2)"))
                    val card = activity.window.decorView.findViewWithTag<View>("sports-card-teams:next")
                    assertEquals("Seattle Seahawks", card.findViewWithTag<TextView>("sports-card-team-name:next:0").text.toString())
                    assertEquals("(6-2)", card.findViewWithTag<TextView>("sports-card-team-record:next:0").text.toString())
                    assertEquals("San Francisco 49ers", card.findViewWithTag<TextView>("sports-card-team-name:next:1").text.toString())
                    assertEquals("(2-6)", card.findViewWithTag<TextView>("sports-card-team-record:next:1").text.toString())
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage").text.toString().contains("ESPN 12"))
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-records.png"))
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-today").isSelected)
                    store.sportsFollowedOnly = true
                    selectSportsSchedule(activity, "sports-live")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo") }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-schedule").hasFocus()); assertTrue(activity.window.decorView.findViewWithTag<View>("sports-live").isSelected)
                    assertNotNull("RedZone remains reachable in Live", activity.window.decorView.findViewWithTag<View>("sports-redzone"))
                }
                device.pressDPadDown(); device.pressDPadUp(); device.pressDPadCenter()
                device.pressDPadUp(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Seattle") }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-reminder").isShown }
                device.pressDPadRight(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.Button>("sports-reminder").text.contains("Reminder set") }
                device.pressBack(); waitFor(scenario) { it.currentFocus?.tag == "next" }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick()
                    activity.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-status").text.contains("1 RedZone channels") }
                assertEquals("A cached Game Pass listing needs no provider lookup", 0, server.epgTargets.size)
                device.pressBack(); waitFor(scenario) { it.currentFocus?.tag == "sports-redzone" }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-schedule").hasFocus() }
                device.pressDPadLeft(); device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                scenario.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<NavigationRail>("app-navigation")
                    assertTrue(rail.contentHasFocus); assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, rail.descendantFocusability)
                    activity.window.decorView.findViewWithTag<View>("sports-schedule").requestFocus()
                }
                var liveMediaId = ""
                scenario.onActivity { liveMediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadLeft(); device.pressDPadLeft(); device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel").focusToolbar() }
                device.pressBack(); waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown && channelList(it)?.isShown == false }
                scenario.onActivity { assertEquals("Back from Library preserves the playing stream without retuning", liveMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
            }
            store.delete("game-reminders.enc")
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet(); store.sportsFollowedOnly = false
        }
    }

    @Test fun missingVodMatchDoesNotStartAnAutomaticCatalogDownload() {
        FixtureServer(longVideo = true, programmeTitle = "Missing from the VOD fixture", slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Missing VOD", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                var media = ""
                scenario.onActivity { activity ->
                    media = find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-match-status")?.contentDescription?.contains("Open Library or Search") == true }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("vod-match-progress").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("channel-vod-start").isShown)
                    assertEquals(media, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                assertTrue("OK only checks saved titles", server.catalogTargets.isEmpty())
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("openPlaylistSearch", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, "Missing from the VOD fixture", false) }
                waitFor(scenario) { server.catalogTargets.isNotEmpty() }
                server.finishVodCatalog.countDown()
                waitFor(scenario) { activity ->
                    val search = MainActivity::class.java.getDeclaredField("playlistSearch").apply { isAccessible = true }.get(activity) as PlaylistSearch
                    search.catalogStatus(source, setOf("movie", "series")).complete
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("clearGuideSearch").apply { isAccessible = true }.invoke(activity)
                    MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                    MainActivity::class.java.getDeclaredMethod("showDetails").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-match-status")?.contentDescription?.contains("Not available in your VOD library") == true }
                assertEquals(0, server.archiveRequests.get())
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun liveProgrammeCanStartItsVodMovieOrIdentifiedEpisodeWithoutUsingCatchup() {
        listOf("Fixture movie (2025)" to "/movie/test/test-secret/101.mp4", "Fixture series S01E02" to "/series/test/test-secret/202.mp4").forEach { (title, path) ->
            FixtureServer(longVideo = true, programmeTitle = title, slowVod = title.startsWith("Fixture movie"), slowEpisodes = title.startsWith("Fixture series"), parallelClients = true).use { server ->
                val source = Source(name = "VOD start fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
                store.saveSources(listOf(source)); store.active = source.id
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    importVodForProgramme(scenario, source, title)
                    device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains(title)) }
                    holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }; device.pressDPadCenter()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                    val lookupStarted = android.os.SystemClock.elapsedRealtime()
                    scenario.onActivity { activity ->
                        val label = activity.window.decorView.findViewWithTag<View>("channel-vod-start").contentDescription.toString()
                        assertTrue(label.contains(if (title.startsWith("Fixture series")) "Fixture series · S01E02" else "Fixture movie (2025)"))
                    }
                    scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-catchup").isShown); val player = find<PlayerView>(it.window.decorView)!!.player!!
                        player.volume = 0f; player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
                        it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick()
                        // A cached, unique movie may already be playing synchronously.
                        assertFalse(it.window.decorView.findViewWithTag<View>("playlist-search").isShown) }
                    if (title.startsWith("Fixture series")) {
                        waitFor(scenario) { server.episodesRequested.count == 0L && it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                        scenario.onActivity { activity ->
                            assertFalse("Episode lookup must never open the Library", activity.window.decorView.findViewWithTag<View>("library-panel").isShown)
                            assertFalse(activity.window.decorView.findViewWithTag<View>("playlist-search").isShown)
                            assertTrue(activity.window.decorView.findViewWithTag<View>("vod-start-panel").hasFocus())
                            assertEquals("Fixture series · S01E02", activity.window.decorView.findViewWithTag<TextView>("vod-start-title").text.toString())
                        }
                        device.takeScreenshot(File(context.getExternalFilesDir(null), "vod-opening.png"))
                        device.pressBack()
                        waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                        assertFalse(playingPath(scenario).orEmpty().startsWith("/series/"))
                        server.finishEpisodes.countDown()
                        device.pressDPadCenter()
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                        scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                    }
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == path }
                    scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<View>("library-panel").isShown) }
                    if (title.startsWith("Fixture movie")) {
                        assertTrue("Autoplay must not wait for the complete catalog", android.os.SystemClock.elapsedRealtime() - lookupStarted < 5000)
                        assertEquals(1L, server.finishVodCatalog.count)
                        server.finishVodCatalog.countDown()
                    }
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.isPlaying && p.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO) && p.volume == 1f } == true }
                    assertEquals("VOD start must never query the channel archive", 0, server.archiveRequests.get())
                }
                store.saveSources(emptyList()); store.removeCache(source)
            }
        }
        FixtureServer(longVideo = true, programmeTitle = "Other movie 16", slowVod = true, parallelClients = true).use { server ->
            val source = Source(name = "Late VOD match", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                server.finishVodCatalog.countDown(); importVodForProgramme(scenario, source, "Other movie 16")
                var liveId = ""
                scenario.onActivity { liveId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains("Other movie 16")) }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }; device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("vod-match-progress").isShown)
                    assertTrue(activity.window.decorView.findViewWithTag<View>("vod-match-status").contentDescription.toString().contains("Other movie 16"))
                    assertEquals(liveId, find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
        FixtureServer(longVideo = true, programmeTitle = "Fixture movie", slowVod = true, ambiguousVod = true).use { server ->
            val source = Source(name = "VOD release choice", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                server.finishVodCatalog.countDown(); importVodForProgramme(scenario, source, "Fixture movie", 2)
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true && device.hasObject(By.textContains("Fixture movie")) }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-start-choice:movie:102")?.isShown == true }
                scenario.onActivity {
                    assertEquals(2, it.window.decorView.findViewWithTag<ViewGroup>("vod-start-options").childCount)
                    assertFalse(it.window.decorView.findViewWithTag<View>("playlist-search").isShown)
                }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("vod-start-panel").isShown }
                assertFalse(playingPath(scenario).orEmpty().startsWith("/movie/"))
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-vod-start")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-vod-start").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("vod-start-choice:movie:101")?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/102.mp4" }
                server.finishVodCatalog.countDown()
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun guideAndLibraryShareSearchAcrossChannelsMoviesSeriesAndKnownEpisodes() {
        store.performanceOverlay = false
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        FixtureServer(libraryCategories = true, longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channel = Channel("xc:1", "Fixture live", "Other", "${server.base}/live/test/test-secret/1.mp4", logo = "${server.base}/logo.png", catchupDays = 2, catchupMode = "xc")
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity {
                    assertTrue(channelList(it)!!.getChildAt(0).contentDescription.toString().contains("Catch-up: 2 days"))
                    it.window.decorView.findViewWithTag<View>("search").performClick()
                    it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Fixture")
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:101")?.isShown == true && it.window.decorView.findViewWithTag<View>("search-result:series:1")?.isShown == true }
                waitFor(scenario) { activity ->
                    val root = activity.window.decorView
                    val movie = root.findViewWithTag<View>("search-result:movie:101")
                    val channelResult = root.findViewWithTag<View>("search-result:live:xc:1")
                    movie != null && channelResult != null &&
                        find<ImageView>(movie)?.drawable is android.graphics.drawable.BitmapDrawable &&
                        find<ImageView>(channelResult)?.drawable is android.graphics.drawable.BitmapDrawable
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:movie:101").requestFocus() }
                device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:xc:1")?.hasFocus() == true }
                device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:101")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-all.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:series").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("search-poster-grid")?.findViewWithTag<View>("search-result:series:1")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-poster-grid.png"))
                device.pressDPadDown(); device.pressDPadRight()
                scenario.onActivity { assertTrue("Search owns remote focus at the results boundary", it.window.decorView.findViewWithTag<View>("playlist-search").hasFocus()) }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-search").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").setText("Pilot") }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:episode:201")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:episode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("search-poster-grid")?.findViewWithTag<View>("search-result:episode:201")?.hasFocus() == true }
                device.pressDPadCenter()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/201.mp4" }
                assertEquals(1, server.catalogTargets.count { !it.contains("category_id=") })
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun indexedProgrammeSearchRendersResultsOnAHundredThousandChannelsAndTunes() {
        store.performanceOverlay = false
        FixtureServer(longVideo = true, parallelClients = true).use { server ->
            val source = Source(name = "Search benchmark", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val initial = Channel("initial", "Initial live feed", "Fixture", "${server.base}/channel1.mp4")
            val now = System.currentTimeMillis()
            val channels = List(100_000) { id -> Channel("benchmark:$id", "Feed $id", "Television", "${server.base}/channel2.mp4", logo = "${server.base}/logo.png") }
            val titles = List(1000) { "Studio $it Edition" }
            val guide = Guide(channels, channels.mapIndexed { i, channel ->
                val start = if (i < 3_000) now - 60_000 else now + 3_600_000
                channel.id to listOf(Programme(channel.id, titles[i % titles.size], start, start + 3_600_000))
            }.toMap())
            val prepared = GuideProgrammeIndex.Builder().prepare(guide, now)
            store.recentFeeds = emptyList()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(initial), emptyMap())); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("openPlaylistSearch", String::class.java, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, "", false)
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, guide)
                    MainActivity::class.java.getDeclaredField("programmeIndex").apply { isAccessible = true }.set(activity, prepared)
                    val worker = MainActivity::class.java.getDeclaredField("liveSearchWorker").apply { isAccessible = true }
                        .get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    activity.window.decorView.findViewWithTag<EditText>("guide-search-input").setText(titles[19])
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-skeleton")?.isShown == true }
                instrumentation.waitForIdleSync()
                Thread.sleep(120)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-loading.png"))
                release.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:benchmark:19")?.isShown == true }

                val elapsed = mutableListOf<Long>()
                for (id in 20 until 44) {
                    val rendered = java.util.concurrent.CountDownLatch(1)
                    var began = 0L
                    var ended = 0L
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val observer = root.viewTreeObserver
                        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (root.findViewWithTag<View>("search-result:live:benchmark:$id")?.isShown == true) {
                                    ended = android.os.SystemClock.elapsedRealtimeNanos()
                                    observer.removeOnGlobalLayoutListener(this)
                                    rendered.countDown()
                                }
                            }
                        }
                        observer.addOnGlobalLayoutListener(listener)
                        began = android.os.SystemClock.elapsedRealtimeNanos()
                        root.findViewWithTag<EditText>("guide-search-input").setText(titles[id])
                    }
                    assertTrue("Render a programme-title match for query $id", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    elapsed.add((ended - began) / 1_000_000)
                }
                val ordered = elapsed.sorted()
                println("Search benchmark 100000 channels and programmes, ${elapsed.size} queries: p50=${ordered[11]}ms p95=${ordered[22]}ms")
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-kind:live").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:benchmark:43")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<View>("search-result:live:benchmark:43")
                    assertTrue(row.contentDescription.contains(titles[43]))
                    assertTrue(row.contentDescription.contains("Live"))
                    assertFalse(row.contentDescription.contains(source.name))
                }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search-live.png"))
                device.pressDPadCenter()
                waitFor(scenario) {
                    it.window.decorView.findViewWithTag<View>("playlist-search")?.isShown == false &&
                        find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4"
                }
            } } finally {
                release.countDown()
                store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source)
            }
        }
    }

    @Test fun libraryMoviesSeriesArchiveAndFontUseSamePlayerAndReturnToLive() {
        FixtureServer(libraryCategories = true, longVideo = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                scenario.onActivity { activity ->
                    assertNull(activity.window.decorView.findViewWithTag<View>("library-tab:catchup"))
                    assertEquals("Watchlist", activity.window.decorView.findViewWithTag<android.widget.Button>("library-tab:favorites").text.toString())
                    store.theme = "Ocean"; store.themeStyle = "Flat"; store.guideOpacity = 85
                    MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
                    fun colors(view: View): List<Int> {
                        val background = view.background.constantState!!.newDrawable()
                        val bitmap = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888)
                        background.setBounds(0, 0, 8, 2); background.draw(Canvas(bitmap))
                        return listOf(bitmap.getPixel(0, 0), bitmap.getPixel(7, 0)).also { bitmap.recycle() }
                    }
                    val guide = MainActivity::class.java.getDeclaredField("guidePanel").apply { isAccessible = true }.get(activity) as View
                    assertEquals("Library must use the Guide palette and opacity", colors(guide), colors(activity.window.decorView.findViewWithTag<View>("library-panel")))
                }
                assertTrue("Initial browsing requests only one category", server.catalogTargets.any { it.contains("category_id=1") })
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").requestFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:series").hasFocus() }
                device.pressDPadUp(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-category").hasFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-search").hasFocus() }
                device.pressDPadUp(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-categories").hasFocus() }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-category").hasFocus() && !it.window.decorView.findViewWithTag<View>("library-categories").isShown }
                chooseLibraryCategory(scenario, 2)
                waitFor(scenario) { server.actionCatalogStarted.count == 0L && it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 80 }
                scenario.onActivity { activity -> assertEquals("Action ▾", activity.window.decorView.findViewWithTag<TextView>("library-category").text.toString()) }
                chooseLibraryCategory(scenario, 3)
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("library-heading").text.toString() == "Comedy" }
                server.finishActionCatalog.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:3001")?.isShown == true }
                scenario.onActivity { activity -> assertTrue(activity.window.decorView.findViewWithTag<View>("library-category").hasFocus()) }
                chooseLibraryCategory(scenario, 1)
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                device.pressMenu(); activateSetting(scenario, "Time format"); activateSetting(scenario, "24 hour · 19:30")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && store.clock24 }
                leaveSettings(scenario)
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown != true }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); it.window.decorView.findViewWithTag<View>("library-item:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-favorite").performClick() }
                waitFor(scenario) { store.libraryFavorites(source).contains("movie:101") }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel")
                    val play = panel.findViewWithTag<View>("library-play")
                    val back = panel.findViewWithTag<View>("library-back")
                    back.requestFocus()
                    panel.detail(OnDemand("movie:101", "Fixture movie", "Fixture cinema", description = "Updated provider metadata"),
                        { title, action -> android.widget.Button(activity).apply { text = title; setOnClickListener { action() } } },
                        { play.performClick() }, { back.performClick() }, false)
                    assertTrue("Provider metadata must preserve the focused Back action", panel.findViewWithTag<View>("library-back").hasFocus())
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/101.mp4" && samePlayer?.isPlaying == true }
                var beforeRecovery = ""; var resumePosition = 0L
                scenario.onActivity { activity ->
                    val control = PlaybackLoadControl(); val id = androidx.media3.exoplayer.analytics.PlayerId.UNSET
                    control.onPrepared(id)
                    fun parameters(bufferedUs: Long, rebuffering: Boolean, vod: Boolean): androidx.media3.exoplayer.LoadControl.Parameters {
                        val item = androidx.media3.common.MediaItem.Builder().setUri("https://example.test/movie.mp4").apply { if (vod) setTag(PlaybackLoadControl.VOD) }.build()
                        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(60000000, true, false, false, null, item)
                        return androidx.media3.exoplayer.LoadControl.Parameters(id, timeline, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, bufferedUs, 1f, true, rebuffering, androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET)
                    }
                    try {
                        assertTrue("Live channel startup stays fast", control.shouldStartPlayback(parameters(1500000, false, false)))
                        assertFalse("VOD must build its startup cushion", control.shouldStartPlayback(parameters(1500000, false, true)))
                        assertTrue(control.shouldStartPlayback(parameters(5000000, false, true)))
                        assertFalse("VOD rebuffering builds a larger cushion", control.shouldStartPlayback(parameters(10000000, true, true)))
                        assertTrue(control.shouldStartPlayback(parameters(12000000, true, true)))
                    } finally { control.onReleased(id) }
                    samePlayer!!.pause(); samePlayer!!.seekTo(samePlayer!!.duration / 3)
                }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_READY && (samePlayer?.currentPosition ?: 0) > 0 }
                scenario.onActivity { activity ->
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    beforeRecovery = samePlayer!!.currentMediaItem!!.mediaId
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 46000, samePlayer!!.currentPosition, null, true, true, bufferedMs = samePlayer!!.totalBufferedDuration)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                    assertEquals("A paused episode must never reconnect", beforeRecovery, samePlayer!!.currentMediaItem!!.mediaId)
                    samePlayer!!.play()
                }
                waitFor(scenario) { samePlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    val progress = MainActivity::class.java.getDeclaredField("playbackProgress").apply { isAccessible = true }.get(activity) as PlaybackProgress
                    val token = MainActivity::class.java.getDeclaredField("playbackToken").apply { isAccessible = true }.getInt(activity)
                    resumePosition = samePlayer!!.currentPosition
                    progress.reset(); progress.stalled(token, android.os.SystemClock.elapsedRealtime() - 46000, resumePosition, null, true, true, bufferedMs = samePlayer!!.totalBufferedDuration)
                    (MainActivity::class.java.getDeclaredField("monitorPlayback").apply { isAccessible = true }.get(activity) as Runnable).run()
                    assertEquals("Recovery must save the exact VOD position before stopping", resumePosition, MainActivity::class.java.getDeclaredField("demandPosition").apply { isAccessible = true }.getLong(activity))
                }
                waitFor(scenario) { samePlayer?.currentMediaItem?.mediaId != beforeRecovery && samePlayer?.isPlaying == true }
                scenario.onActivity { activity ->
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    assertEquals("VOD recovery must never tune a live channel", "/movie/test/test-secret/101.mp4", samePlayer!!.currentMediaItem!!.localConfiguration!!.uri.path)
                    assertEquals(resumePosition, MainActivity::class.java.getDeclaredField("demandPosition").apply { isAccessible = true }.getLong(activity))
                }
                scenario.onActivity {
                    assertTrue(it.window.decorView.findViewWithTag<View>("on-demand-controls").isShown)
                    it.window.decorView.findViewWithTag<View>("on-demand-pause").performClick()
                }
                waitFor(scenario) { samePlayer?.playWhenReady == false }
                scenario.onActivity { activity ->
                    val seek = activity.window.decorView.findViewWithTag<android.widget.SeekBar>("on-demand-seek")
                    assertTrue("Seekable movie exposes its native seek bar", seek.isEnabled); seek.requestFocus()
                }
                device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("on-demand-seek").hasFocus() && (samePlayer?.currentPosition ?: 0) > 0 }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-restart").performClick() }
                waitFor(scenario) { samePlayer?.currentPosition == 0L && samePlayer?.playWhenReady == false }
                scenario.onActivity { samePlayer!!.repeatMode = Player.REPEAT_MODE_OFF; samePlayer!!.seekTo(samePlayer!!.duration - 20); samePlayer!!.play() }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_ENDED }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-pause").performClick() }
                waitFor(scenario) { samePlayer?.playbackState == Player.STATE_READY && samePlayer?.isPlaying == true }
                scenario.onActivity { samePlayer!!.repeatMode = Player.REPEAT_MODE_ONE }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true && samePlayer?.playWhenReady == false }
                // Reproduce a large category reduction without a quadratic UI-thread diff.
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<MediaBrowserPanel>("library-panel")
                    val entries = (1..25000).map { BrowserRow("bulk:$it", "Title $it") {} }
                    panel.submit(entries, entries.associate { it.key to if (it.key.substringAfter(':').toInt() % 2 == 0) "Science fiction" else "Drama" })
                }
                chooseLibraryCategory(scenario, 2)
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 12500 }
                device.pressDPadLeft(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressDPadDown()
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("library-live")) }
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-mode").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" && channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick(); it.window.decorView.findViewWithTag<View>("library-tab:series").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:series:1")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:series:1").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-favorite").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.Button>("library-favorite").text.toString() == "✓ In Watchlist" && store.libraryFavorites(source).contains("series:1") }
                val beforeEpisodeRefresh = server.requests["/player_api.php"] ?: 0
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-refresh").apply { requestFocus(); performClick() } }
                waitFor(scenario) {
                    (server.requests["/player_api.php"] ?: 0) > beforeEpisodeRefresh &&
                        it.window.decorView.findViewWithTag<View>("library-loading").isShown == false &&
                        it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true
                }
                scenario.onActivity { assertTrue("Episode refresh must not move focus away from Refresh", it.window.decorView.findViewWithTag<View>("library-refresh").hasFocus()) }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "library.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:episode:201").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/201.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-next").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/202.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                assertEquals("On-demand titles must not replace live channel history", "xc:1", store.last(source))
                device.pressBack()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true && samePlayer?.currentMediaItem == null }
                scenario.onActivity { activity ->
                    assertEquals("Stopping an episode leaves the primary player stopped", Player.STATE_IDLE, samePlayer!!.playbackState)
                    activity.window.decorView.findViewWithTag<View>("library-mark-watched").performClick()
                    activity.window.decorView.findViewWithTag<View>("library-tab:favorites").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:series:1")?.contentDescription?.toString()?.contains("All caught up") == true && store.libraryFavorites(source).watched == setOf("episode:201", "episode:202") }
                assertEquals(setOf("episode:201", "episode:202"), store.libraryFavorites(source).watched)
                scenario.onActivity { assertNull("Catch-up is no longer a Library tab", it.window.decorView.findViewWithTag<View>("library-tab:catchup")) }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-catchup").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:xc:1")?.isShown == true }
                scenario.onActivity {
                    assertFalse(it.window.decorView.findViewWithTag<View>("library-tab:movie").isShown)
                    assertTrue(it.window.decorView.findViewWithTag<View>("archive-channels").isShown)
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:xc:1").performClick() }
                waitFor(scenario) { device.hasObject(By.textContains("Archived fixture")) && !it.window.decorView.findViewWithTag<View>("library-loading").isShown }
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("library-items").findViewHolderForAdapterPosition(0)!!.itemView.performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/test/test-secret/60/") == true && samePlayer?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) && (samePlayer?.videoSize?.width ?: 0) > 0 }
                scenario.onActivity {
                    assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player)
                    val controls = it.window.decorView.findViewWithTag<ViewGroup>("on-demand-controls")
                    fun live(v: View): View? { if (v is android.widget.Button && v.text.toString() == "Live TV") return v; if (v is ViewGroup) for (i in 0 until v.childCount) live(v.getChildAt(i))?.let { return it }; return null }
                    live(controls)!!.performClick()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" && channelList(it)?.isShown == true }
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
                val liveAttempt = playingPath(scenario)
                var liveMediaId = ""; scenario.onActivity { liveMediaId = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-catchup")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-catchup").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
                device.pressBack(); waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("library-panel").isShown && channelList(it)?.isShown == false }
                assertEquals(liveAttempt, playingPath(scenario))
                scenario.onActivity { assertEquals("Closing the Catch-up browser must preserve the live connection", liveMediaId, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId) }
                device.pressMenu(); setThemeControl(scenario, "Type", "Font", "Monospace")
                val selectedFont = AppFont.faceFor("Monospace", true)
                waitFor(scenario) { store.font == "Monospace" && it.window.decorView.findViewWithTag<TextView>("settings-page-title").typeface == selectedFont }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(selectedFont, it.window.decorView.findViewWithTag<android.widget.TextView>("settings-page-title").typeface) }
                leaveSettings(scenario)
                scenario.recreate(); waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Monospace", store.font)
                scenario.onActivity { activity ->
                    val m3u = source.copy(id = "m3u-library-return", kind = "m3u")
                    val movie = Channel("fixture-film", "M3U film", "Movies", "${server.base}/channel1.mp4", media = true)
                    val controller = LibraryController(activity, store,
                        { title, action -> android.widget.Button(activity).apply { text = title; setOnClickListener { action() } } },
                        { m3u }, { Guide(listOf(movie), emptyMap()) }, { _, _ -> }, {})
                    try {
                        controller.open("movie")
                        val panel = controller.panel
                        panel.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
                        panel.layout(0, 0, 960, 540)
                        panel.findViewWithTag<View>("library-item:m3u:fixture-film").performClick()
                        assertTrue(panel.findViewWithTag<View>("library-details").visibility == View.VISIBLE)
                        controller.back()
                        assertEquals("Movies", panel.findViewWithTag<android.widget.TextView>("library-heading").text.toString())
                        assertEquals("Returning to an M3U movie catalog must retain its titles", 1, panel.findViewWithTag<RecyclerView>("library-items").adapter!!.itemCount)
                        assertEquals("M3U Back must not start an Xtream category request", View.GONE, panel.findViewWithTag<View>("library-loading").visibility)
                    } finally { controller.destroy() }
                }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun continueWatchingPositionsAreEncryptedScopedAndClearedWhenFinished() {
        val source = Source(name = "Progress", kind = "xtream", url = "https://example.com", username = "secret-user", password = "secret-password")
        val item = OnDemand("movie:101", "Fixture movie", "Films", "https://example.com/movie/secret-user/secret-password/101.mp4")
        try {
            store.saveWatchProgress(source, item, 45000, 120000)
            val restored = SecureStore(context).watchProgress(source).single()
            assertEquals(45000, restored.position); assertEquals(item, restored.item)
            val bytes = File(context.filesDir, "watch-${source.id}-${source.providerKey().take(16)}.enc").readBytes().toString(Charsets.UTF_8)
            assertFalse(bytes.contains("secret-password")); assertFalse(bytes.contains("Fixture movie"))
            assertTrue(store.watchProgress(source.copy(id = "other-source")).isEmpty())
            store.bindProvider(source)
            val edited = source.copy(password = "other-account")
            assertTrue(store.bindProvider(edited)); assertTrue(store.watchProgress(edited).isEmpty())
            store.saveVideoInfo(source, mapOf("old" to VideoInfo(3840, 2160, 60f)))
            assertTrue("An old background writer cannot restore the edited account's old quality cache", store.videoInfo(edited).isEmpty())
            assertEquals("The old account's encrypted progress remains available to that account", 45000, store.watchProgress(source).single().position)
            store.bindProvider(source)

            store.saveWatchProgress(source, item, 120000, 120000, ended = true); assertTrue(store.watchProgress(source).isEmpty())
            store.cacheCatalog(source, "movie", listOf(item)); assertEquals(item, SecureStore(context).catalog(source, "movie")!!.second.single())
        } finally { store.removeCache(source) }
        assertFalse(File(context.filesDir, "watch-${source.id}.enc").exists()); assertFalse(File(context.filesDir, "library-${source.id}-movie.enc").exists())
    }

    @Test fun providerRedirectChainPreservesByteRangeAndEncryptedAddonSettings() {
        FixtureServer().use { server ->
            val source = ProviderDataSource { true }
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri("${server.base}/redirect.mp4").setPosition(12).setLength(40).build()
            try {
                assertEquals(40, source.open(spec)); val bytes = ByteArray(40); var read = 0
                while (read < bytes.size) { val count = source.read(bytes, read, bytes.size - read); assertTrue(count > 0); read += count }
                val expected = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }.copyOfRange(12, 52)
                assertArrayEquals(expected, bytes); assertEquals(-1, source.read(ByteArray(1), 0, 1)); assertEquals("/channel1.mp4", source.uri!!.path)
                assertEquals(1, server.requests["/redirect.mp4"]); assertEquals(1, server.requests["/redirect-relative.mp4"])
            } finally { source.close() }
        }
        val url = "https://metadata.example/private-config-token/manifest.json"
        store.saveMetadataAddon(url)
        assertEquals(url, SecureStore(context).metadataAddon())
        assertFalse(File(context.filesDir, "metadata-addon.enc").readBytes().toString(Charsets.UTF_8).contains("private-config-token"))
        store.saveMetadataAddon("")
        FixtureServer().use { server ->
            var panel: MediaBrowserPanel? = null; var cover: android.widget.ImageView? = null; var fallback: ((String) -> Unit)? = null
            instrumentation.runOnMainSync {
                val activityContext = android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_NoActionBar)
                panel = MediaBrowserPanel(activityContext, { text, action -> android.widget.Button(activityContext).apply { this.text = text; setOnClickListener { action() } } }, {}, {})
                panel!!.visibility = View.VISIBLE; panel!!.allowArtworkHttp(true)
                panel!!.submit(listOf(BrowserRow("missing", "Missing artwork", requestPoster = { fallback = it }) {}))
                panel!!.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)); panel!!.layout(0, 0, 960, 540)
                cover = find<android.widget.ImageView>(panel!!.findViewWithTag<View>("library-item:missing"))!!
                assertNotNull(fallback); assertNull((cover!!.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap); fallback!!("${server.base}/logo.png")
            }
            val deadline = System.currentTimeMillis() + 7000
            var loaded = false
            while (!loaded && System.currentTimeMillis() < deadline) { instrumentation.runOnMainSync { loaded = (cover!!.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap != null }; Thread.sleep(50) }
            assertTrue("Metadata poster is applied to the visible card", loaded)
            instrumentation.runOnMainSync { panel!!.closeImages() }
        }
    }

    private fun fixtureLiveSports(activity: MainActivity) {
        activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(
            LiveEvent("mlb-fixture", setOf("mlb:NYY", "mlb:BOS"), now - 300000), LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000)), now, setOf("MLB", "NFL")) }
    }
    private fun fastRemote(key: Int, count: Int) {
        val down = android.os.SystemClock.uptimeMillis()
        repeat(count) { repeat ->
            instrumentation.sendKeySync(android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN, key, repeat))
            android.os.SystemClock.sleep(12)
        }
        instrumentation.sendKeySync(android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, key, 0))
    }
    @Test fun rapidCategoryScrollingKeepsHighlightAndHeldUpStopsAtFirstChannel() {
        FixtureServer().use { server ->
            val source = Source(name = "Held remote fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..120).map { Channel("held:$it", "Channel $it", "Category $it", "${server.base}/channel1.mp4") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 80)
                waitFor(scenario) { activity ->
                    val list = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    (list.findFocus() as? android.widget.TextView)?.text?.toString()?.removePrefix("Category ")?.toIntOrNull()?.let { it >= 20 } == true
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "held-categories.png"))
                device.pressDPadRight(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertEquals("Highlighted categories update the guide without OK", 1, channelList(activity)!!.adapter!!.itemCount)
                    activity.window.decorView.findViewWithTag<View>("guide-all").performClick()
                }
                waitFor(scenario) { channelList(it)?.hasFocus() == true && channelList(it)?.adapter?.itemCount == 120 }
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 50)
                fastRemote(android.view.KeyEvent.KEYCODE_DPAD_UP, 100)
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel != null }
                scenario.onActivity { channelList(it)!!.scrollToPosition(0) }
                waitFor(scenario) { channelList(it)?.findViewHolderForAdapterPosition(0) != null }
                scenario.onActivity { channelList(it)!!.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus() }
                device.pressDPadUp(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "held:120" }
                device.pressDPadDown(); waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.id == "held:1" }
                device.pressDPadLeft(); device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("guide-mode").hasFocus() }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun startupUpdatePromptOffersInstallOrLaterWithoutInterruptingPlayback() {
        FixtureServer().use { server ->
            val source = Source(name = "Startup update fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            var updater: Updater? = null; val checks = java.util.concurrent.atomic.AtomicInteger()
            val update = Release("99.0.0", 999999, "https://github.com/mot1us/spare/releases/download/v99.0.0/spare.apk", "a".repeat(64))
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var media = ""
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                scenario.onActivity { activity ->
                    media = player!!.currentMediaItem!!.mediaId
                    updater = Updater(activity, store, lookup = { checks.incrementAndGet(); update }, enabled = true) { }
                    updater!!.check(startup = true)
                }
                waitFor(scenario) { checks.get() == 1 }
                instrumentation.waitForIdleSync()
                assertFalse("A backgrounded update check must not open a dialog", device.hasObject(By.text("Install now")))
                scenario.onActivity { updater!!.resume() }
                assertTrue(device.wait(Until.hasObject(By.text("Install now")), 5000))
                scenario.onActivity { assertEquals(media, player!!.currentMediaItem!!.mediaId); assertTrue(player!!.isPlaying) }
                device.findObject(By.text("Later")).click()
                assertTrue(device.wait(Until.gone(By.text("Install now")), 5000))
                scenario.onActivity { updater!!.check(startup = true); updater!!.pause(); updater!!.resume() }
                instrumentation.waitForIdleSync(); assertEquals(1, checks.get())
                assertFalse("Later dismisses this launch's offer", device.hasObject(By.text("Install now")))
            } } finally { updater?.close(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun twoPaneSettingsKeepsFullscreenVideoAndLauncherUsesWordmarkBanner() {
        FixtureServer().use { server ->
            val source = Source(name = "Settings fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null; var attempt = ""
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player; attempt = samePlayer!!.currentMediaItem!!.mediaId }
                device.pressMenu(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
                scenario.onActivity { activity ->
                    val nav = activity.window.decorView.findViewWithTag<View>("settings-navigation"); val content = activity.window.decorView.findViewWithTag<View>("settings-content")
                    val left = IntArray(2); val right = IntArray(2); nav.getLocationOnScreen(left); content.getLocationOnScreen(right)
                    assertTrue(left[0] + nav.width < right[0]); assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    listOf("Playback", "Theme", "Guide", "Categories").forEach { assertNotNull(activity.window.decorView.findViewWithTag<View>("settings-nav:$it")) }
                    listOf("Font", "Guide video", "Subtitles", "Time format").forEach { assertNull(activity.window.decorView.findViewWithTag<View>("settings-nav:$it")) }
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Local channels").requestFocus()
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Playback").requestFocus()
                    assertEquals("Rapid menu movement does not build skipped pages", "Playlists", activity.window.decorView.findViewWithTag<TextView>("settings-page-title").text.toString())
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Theme").requestFocus()
                }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-mode:Presets")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("theme-preset:Classic").isShown)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("theme-tab:Look").isShown)
                    activity.window.decorView.findViewWithTag<View>("theme-preset:Nostalgia").performClick()
                    assertEquals("Nostalgia", store.themeStyle); assertEquals("Monospace", store.font)
                    activity.window.decorView.findViewWithTag<View>("theme-preset:Glass").performClick()
                }
                waitFor(scenario) { GuideTheme.style == "Glass" && GuideTheme.current.name == "Monochrome" }
                device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "theme-presets.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("theme-mode:Advanced").requestFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-tab:Look")?.hasFocus() == true }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                var samePalette: View? = null; var samePreview: View? = null; var previousPreview: Bitmap? = null
                scenario.onActivity { activity ->
                    samePalette = activity.window.decorView.findViewWithTag<View>("theme-control:Palette")
                    samePreview = activity.window.decorView.findViewWithTag<View>("theme-preview")
                    assertTrue(samePreview!!.width > 0 && samePreview!!.height > 0)
                    previousPreview = Bitmap.createBitmap(samePreview!!.width, samePreview!!.height, Bitmap.Config.ARGB_8888).also { samePreview!!.draw(Canvas(it)) }
                }
                device.pressDPadRight()
                waitFor(scenario) { store.theme == "Ocean" && GuideTheme.current.name == "Ocean" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    assertSame(samePalette, activity.window.decorView.findViewWithTag<View>("theme-control:Palette"))
                    assertSame(samePreview, activity.window.decorView.findViewWithTag<View>("theme-preview"))
                    val changed = Bitmap.createBitmap(samePreview!!.width, samePreview!!.height, Bitmap.Config.ARGB_8888).also { samePreview!!.draw(Canvas(it)) }
                    assertFalse("Palette changes immediately redraw the local preview", previousPreview!!.sameAs(changed))
                    previousPreview!!.recycle(); changed.recycle()
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadUp(); device.pressDPadLeft(); device.pressDPadLeft(); device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-nav:Theme").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings").hasFocus() }
                device.pressDPadRight(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-mode:Presets")?.hasFocus() == true }
                device.pressDPadRight(); device.pressDPadDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("theme-tab:Look")?.hasFocus() == true }
                device.pressDPadDown(); device.pressDPadLeft()
                waitFor(scenario) { store.theme == "Monochrome" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                device.pressDPadCenter(); waitFor(scenario) { store.theme == "Ocean" && it.window.decorView.findViewWithTag<View>("theme-control:Palette")?.hasFocus() == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "settings.png"))
                setThemeControl(scenario, "Look", "Style", "Flat"); waitFor(scenario) { store.themeStyle == "Flat" && GuideTheme.style == "Flat" }
                setThemeControl(scenario, "Look", "Palette", "Violet"); setThemeControl(scenario, "Look", "Style", "Outline")
                setThemeControl(scenario, "Look", "Corners", "12 dp"); setThemeControl(scenario, "Look", "Borders", "2 dp")
                activateSetting(scenario, "Theme")
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("theme-mode:Advanced").performClick()
                    activity.window.decorView.findViewWithTag<View>("theme-tab:Colors").performClick()
                    val accent = activity.window.decorView.findViewWithTag<EditText>("theme-control:Accent hex")
                    val background = activity.window.decorView.findViewWithTag<EditText>("theme-control:Background hex")
                    accent.requestFocus(); accent.setText("123456"); background.requestFocus(); background.setText("07131B")
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(background.windowToken, 0)
                }
                waitFor(scenario) { store.customAccent == 0xFF123456.toInt() && store.customBackground == 0x07131B && GuideTheme.current.accent == 0xFF123456.toInt() }
                setThemeControl(scenario, "Type", "Font", "Light"); setThemeControl(scenario, "Type", "Guide text size", "110%")
                setThemeControl(scenario, "Guide", "Row height", "36 dp"); setThemeControl(scenario, "Guide", "Row gap", "5 dp")
                setThemeControl(scenario, "Guide", "Opacity", "80%"); setThemeControl(scenario, "Guide", "Guide video", "Small preview")
                scenario.onActivity { activity ->
                    val saved = SecureStore(context)
                    assertEquals("Violet", saved.theme); assertEquals("Outline", saved.themeStyle); assertEquals("Light", saved.font)
                    assertEquals(0xFF123456.toInt(), saved.customAccent); assertEquals(0x07131B, saved.customBackground)
                    assertEquals(12, saved.cornerRadius); assertEquals(2, saved.borderWidth); assertEquals(110, saved.guideTextScale)
                    assertEquals(36, saved.guideRowHeight); assertEquals(5, saved.guideRowGap); assertEquals(80, saved.guideOpacity); assertTrue(saved.smallPreview)
                    assertEquals(AppFont.face(true), activity.window.decorView.findViewWithTag<TextView>("settings-page-title").typeface)
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                activateSetting(scenario, "Theme")
                scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("theme-control:Nostalgia")) }
                setThemeControl(scenario, "Look", "Style", "Nostalgia")
                waitFor(scenario) { store.themeStyle == "Nostalgia" }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(it.window.decorView.findViewWithTag<View>("theme-control:Style").hasFocus()) }
                leaveSettings(scenario); device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "nostalgia.png"))
                device.pressMenu()
                activateSetting(scenario, "Frame-rate matching"); activateSetting(scenario, "Off")
                assertFalse(SecureStore(context).frameRateMatching)
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(it.window.decorView.findViewWithTag<View>("settings-panel").isShown); assertEquals(0, it.window.attributes.preferredDisplayModeId) }
                activateSetting(scenario, "On"); assertTrue(SecureStore(context).frameRateMatching)
                scenario.onActivity { activity ->
                    val matching = MainActivity::class.java.getDeclaredField("frameRates").apply { isAccessible = true }.get(activity) as FrameRates
                    val saved = matching.requestedRate
                    matching.request(60f); assertEquals(60f, matching.requestedRate, .001f)
                    matching.request(59.94f); assertEquals("Live TV must not discard fractional display requests", 59.94f, matching.requestedRate, .001f)
                    if (saved > 0) matching.request(saved)
                    val diagnostic = MainActivity::class.java.getDeclaredMethod("playbackDiagnostics").apply { isAccessible = true }.invoke(activity) as String
                    assertTrue(diagnostic.contains("dropped frames this playback attempt")); assertTrue(diagnostic.contains("Hz · Requested"))
                    assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player)
                    assertEquals("Matching corrections must preserve the playing connection", attempt, samePlayer!!.currentMediaItem!!.mediaId)
                }
                activateSetting(scenario, "Subtitles"); activateSetting(scenario, "Subtitle appearance")
                activateSetting(scenario, "Medium"); activateSetting(scenario, "Shadow")
                waitFor(scenario) { store.subtitleSize == "Medium" && store.subtitleStyle == "Shadow" }
                scenario.onActivity { activity ->
                    val info = activity.packageManager.getActivityInfo(android.content.ComponentName(activity, MainActivity::class.java), 0)
                    assertEquals(R.drawable.tv_banner, info.banner); assertEquals(R.drawable.ic_spare, info.icon)
                    val banner = info.loadBanner(activity.packageManager)!!; val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                    banner.setBounds(0, 0, 960, 540); banner.draw(Canvas(bitmap))
                    for (x in 0 until bitmap.width) { assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(x, 0)); assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(x, bitmap.height - 1)) }
                    for (y in 0 until bitmap.height) { assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(0, y)); assertEquals(Color.rgb(11, 16, 24), bitmap.getPixel(bitmap.width - 1, y)) }
                    File(context.getExternalFilesDir(null), "launcher-banner.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                leaveSettings(scenario); scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    private fun importVodForProgramme(scenario: ActivityScenario<MainActivity>, source: Source, title: String, count: Int = 1) {
        val target = VodStart.request(Programme("fixture", title, 1, 2))!!
        scenario.onActivity { MainActivity::class.java.getDeclaredMethod("openPlaylistSearch", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, target.query, false) }
        waitFor(scenario) { activity ->
            val library = MainActivity::class.java.getDeclaredField("library").apply { isAccessible = true }.get(activity) as LibraryController
            library.repository.matches(source, target).size >= count
        }
        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("clearGuideSearch").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("applyFilter").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        }
    }

    private fun activateSetting(scenario: ActivityScenario<MainActivity>, label: String) {
        val parent = when (label) {
            "Frame-rate matching", "Audio track", "Subtitles", "Stream format", "Playback diagnostics", "Temporarily hidden channels" -> "Playback"
            "Navigation dock", "Time format", "Switch categories on highlight", "Open channels full screen", "Loop guide channels" -> "Guide"
            "Category order" -> "Categories"
            else -> null
        }
        waitFor(scenario) { activity ->
            val panel = activity.window.decorView.findViewWithTag<View>("settings-panel")
            if (panel?.isShown != true) false else {
                val control = panel.findViewWithTag<View>("settings-nav:$label") ?: panel.findViewWithTag<View>("settings-option:$label")
                if (control != null) { control.requestFocus(); control.performClick(); true }
                else { parent?.let { panel.findViewWithTag<View>("settings-nav:$it")?.performClick() }; false }
            }
        }
    }
    private fun setThemeControl(scenario: ActivityScenario<MainActivity>, tab: String, title: String, value: String) {
        activateSetting(scenario, "Theme")
        scenario.onActivity { activity ->
            activity.window.decorView.findViewWithTag<View>("theme-mode:Advanced").performClick()
            activity.window.decorView.findViewWithTag<View>("theme-tab:$tab").apply { requestFocus(); performClick() }
        }
        scenario.onActivity { activity ->
            val control = activity.window.decorView.findViewWithTag<View>("theme-control:$title")
            control.requestFocus()
            var found = false
            repeat(32) {
                if (control.contentDescription.toString().startsWith("$title, $value.")) found = true
                if (!found) control.performClick()
            }
            assertTrue("Theme control $title must offer $value", found)
            assertTrue("Adjusting a theme row keeps remote focus", control.hasFocus())
        }
    }
    private fun pollSportsFixture(activity: MainActivity) {
        assertNull("Sports updates automatically without a Refresh control", activity.window.decorView.findViewWithTag<View>("sports-refresh"))
        MainActivity::class.java.getDeclaredField("liveSportsAttempt").apply { isAccessible = true }.setLong(activity, 0)
        MainActivity::class.java.getDeclaredMethod("checkLiveSports", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
        MainActivity::class.java.getDeclaredMethod("scheduleSports").apply { isAccessible = true }.invoke(activity)
    }
    private fun leaveSettings(scenario: ActivityScenario<MainActivity>) {
        repeat(5) {
            var open = false; scenario.onActivity { activity -> open = activity.window.decorView.findViewWithTag<View>("settings-panel")?.isShown == true }
            if (open) { device.pressBack(); instrumentation.waitForIdleSync() }
        }
        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown != true }
    }

    private fun activateDialogButton(scenario: ActivityScenario<MainActivity>, id: Int) {
        // TV buttons may focus on first touch or click immediately when already focused.
        // Set native focus directly and send exactly one remote activation instead of mixing both inputs.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            waitFor(scenario) {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                val button = root?.findViewById<android.widget.Button>(id)
                button?.isShown == true && button.requestFocus() && button.hasFocus()
            }
            device.pressDPadCenter(); instrumentation.waitForIdleSync()
        } else fail("The isolated automated TV suite requires API 29 or newer")
    }

    private fun holdBack() { device.executeShellCommand("input dpad keyevent --duration ${ViewConfiguration.getLongPressTimeout() + 500} 4"); instrumentation.waitForIdleSync() }
    private fun repeatTransport(input: ByteArray, cycles: Int): ByteArray {
        val result = ByteArray(input.size * cycles); val counters = mutableMapOf<Int, Int>()
        fun timestamp(bytes: ByteArray, offset: Int, delta: Long) {
            val a = (0..4).map { bytes[offset + it].toInt() and 255 }
            val pts = ((a[0].toLong() shr 1 and 7) shl 30) or (a[1].toLong() shl 22) or ((a[2].toLong() shr 1) shl 15) or (a[3].toLong() shl 7) or (a[4].toLong() shr 1)
            val shifted = (pts + delta) and 0x1ffffffffL
            bytes[offset] = ((a[0] and 0xf1) or (((shifted shr 30).toInt() and 7) shl 1)).toByte()
            bytes[offset + 1] = (shifted shr 22).toByte(); bytes[offset + 2] = (((shifted shr 15).toInt() and 127) shl 1 or 1).toByte()
            bytes[offset + 3] = (shifted shr 7).toByte(); bytes[offset + 4] = ((shifted.toInt() and 127) shl 1 or 1).toByte()
        }
        repeat(cycles) { cycle ->
            val bytes = input.copyOf(); val delta = cycle * 225000L
            for (offset in bytes.indices step 188) {
                if (offset + 188 > bytes.size) break
                val pid = ((bytes[offset + 1].toInt() and 31) shl 8) or (bytes[offset + 2].toInt() and 255)
                val flags = bytes[offset + 3].toInt() and 255
                if (flags and 16 != 0) { val count = counters[pid] ?: 0; bytes[offset + 3] = ((flags and 240) or count).toByte(); counters[pid] = (count + 1) % 16 }
                var payload = offset + 4
                if (flags and 32 != 0) {
                    val length = bytes[payload].toInt() and 255
                    if (length >= 7 && bytes[payload + 1].toInt() and 16 != 0) {
                        val start = payload + 2
                        var base = 0L; for (index in 0..3) base = (base shl 8) or (bytes[start + index].toLong() and 255)
                        base = (base shl 1) or (bytes[start + 4].toLong() ushr 7 and 1); base = (base + delta) and 0x1ffffffffL
                        for (index in 0..3) bytes[start + index] = (base shr (25 - index * 8)).toByte()
                        bytes[start + 4] = ((bytes[start + 4].toInt() and 127) or ((base.toInt() and 1) shl 7)).toByte()
                    }
                    payload += length + 1
                }
                if (bytes[offset + 1].toInt() and 64 != 0 && payload + 14 < offset + 188 && bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()) {
                    val ptsFlags = bytes[payload + 7].toInt() and 192
                    if (ptsFlags and 128 != 0) timestamp(bytes, payload + 9, delta)
                    if (ptsFlags == 192 && payload + 19 < offset + 188) timestamp(bytes, payload + 14, delta)
                }
            }
            bytes.copyInto(result, cycle * input.size)
        }
        return result
    }
    @Test fun enabledPlaylistsShareSearchAndSportsWithoutMixingProviderState() {
        FixtureServer().use { first -> FixtureServer().use { second -> FixtureServer().use { disabled ->
            val a = Source(name = "Provider A", kind = "xtream", url = first.base, username = "test", password = "test-secret", allowHttp = true)
            val b = Source(name = "Provider B", kind = "xtream", url = second.base, username = "test", password = "test-secret", allowHttp = true, maxStreams = 1)
            val c = Source(name = "Disabled provider", kind = "m3u", url = "${disabled.base}/playlist.m3u", allowHttp = true, enabled = false)
            val ca = Channel("xc:1", "KING 5 HD", "USA News", "${first.base}/channel1.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
            val cb = ca.copy(name = "KING 5 FHD", group = "NFL Game Pass", url = "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 60f))
            val backup = cb.copy(id = "xc:2", name = "Backup feed", url = "${second.base}/channel2.mp4", reportedQuality = VideoInfo(1280, 720, 30f))
            val now = System.currentTimeMillis()
            val listing = Programme("xc:1", "NFL Football: Seattle Seahawks at San Francisco 49ers", now - 60000, now + 3 * 3600000)
            store.saveSources(listOf(a, b, c)); store.active = a.id; store.catalogRoot = a.id; store.recentFeeds = emptyList()
            store.cache(a, Guide(listOf(ca), mapOf(ca.id to listOf(listing))))
            store.cache(b, Guide(listOf(cb, backup), mapOf(cb.id to listOf(listing), backup.id to listOf(listing.copy(channel = backup.id)))))
            store.markPlaylistRefreshed(a); store.markPlaylistRefreshed(b); store.toggleFavorite(a, ca)
            val movieA = OnDemand("movie:42", "Orbit", "1", "${first.base}/movie.mp4", year = "2025")
            val movieB = movieA.copy(url = "${second.base}/movie.mp4")
            val catalog = LibraryCatalog(store)
            listOf(a to movieA, b to movieB).forEach { (provider, movie) -> catalog.Writer(provider, "movie").apply { append(listOf(movie)); finish(mapOf("1" to "Movies")) } }
            catalog.close()
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var playing: Player? = null; var ownedB: Channel? = null
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val p = find<PlayerView>(activity.window.decorView)?.player
                    p?.repeatMode = Player.REPEAT_MODE_ONE
                    if (guide.channels.size == 3 && p?.isPlaying == true) { playing = p; ownedB = guide.channels.firstOrNull { it.playlistId == b.id && it.nativeId == "xc:1" }; true } else false
                }
                scenario.onActivity { activity ->
                    assertNotNull(ownedB); assertNotEquals(ca.id, ownedB!!.id)
                    assertEquals(first.base.substringAfterLast(':'), playing!!.currentMediaItem!!.localConfiguration!!.uri.port.toString())
                    val denied = b.copy(allowHttp = false)
                    MainActivity::class.java.getDeclaredMethod("configurePlaylists", List::class.java).apply { isAccessible = true }.invoke(activity, listOf(a, denied, c))
                    val attempt = playing!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredMethod("tune", Channel::class.java).apply { isAccessible = true }.invoke(activity, ownedB)
                    assertEquals("Another provider's HTTP permission cannot authorize this feed", attempt, playing!!.currentMediaItem!!.mediaId)
                    MainActivity::class.java.getDeclaredMethod("configurePlaylists", List::class.java).apply { isAccessible = true }.invoke(activity, listOf(a, b, c))
                    MainActivity::class.java.getDeclaredMethod("toggleFavorite", Channel::class.java).apply { isAccessible = true }.invoke(activity, ownedB)
                    assertEquals(setOf("xc:1"), store.favorites(a)); assertEquals(setOf("xc:1"), store.favorites(b))
                    MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist:${b.id}")?.isShown == true }
                scenario.onActivity { activity ->
                    val attempt = playing!!.currentMediaItem!!.mediaId
                    activity.window.decorView.findViewWithTag<View>("playlist:${b.id}").performClick()
                    assertFalse(store.playlistCollapsed(b.id))
                    assertEquals("Expanding another playlist does not restart playback", attempt, playing!!.currentMediaItem!!.mediaId)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playlist-group:${b.id}:NFL Game Pass") != null }
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<View>("playlist-group:${b.id}:NFL Game Pass").performClick()
                    assertTrue((channelList(activity)!!.adapter as GuideAdapter).channels.all { it.playlistId == b.id })
                    val scores = LiveSportsSnapshot(listOf(LiveEvent("multi-game", setOf("nfl:SEA", "nfl:SF"), now - 60000, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), System.currentTimeMillis(), matchChannels = false).single()
                    MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, game, true)
                }
                waitFor(scenario) { playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') && playing!!.isPlaying }
                scenario.onActivity { activity ->
                    assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player)
                    assertFalse("Watch best starts the other provider full screen", channelList(activity)?.isShown == true)
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    val secondB = guide.channels.first { it.playlistId == b.id && it.nativeId == "xc:2" }
                    MainActivity::class.java.getDeclaredMethod("startMultiview", Channel::class.java).apply { isAccessible = true }.invoke(activity, secondB)
                    val views = MainActivity::class.java.getDeclaredField("multiview").apply { isAccessible = true }.get(activity) as Multiview
                    assertEquals("Provider B's configured connection limit is respected", 1, views.count)
                    MainActivity::class.java.getDeclaredMethod("startMultiview", Channel::class.java).apply { isAccessible = true }.invoke(activity, ca)
                    assertEquals("Another provider can supply the second view", 2, views.count)
                    views.close()
                    MainActivity::class.java.getDeclaredMethod("openPlaylistSearch", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, "KING 5", false)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:live:xc:1") != null && it.window.decorView.findViewWithTag<View>("search-result:live:${ownedB!!.id}") != null }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("search-result:live:xc:1").contentDescription.contains(a.name))
                    assertTrue(activity.window.decorView.findViewWithTag<View>("search-result:live:${ownedB!!.id}").contentDescription.contains(b.name))
                    MainActivity::class.java.getDeclaredMethod("openPlaylistSearch", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, "Orbit", false)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search-result:movie:42") != null && it.window.decorView.findViewWithTag<View>("search-result:${b.id}:movie:42") != null }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("search-result:${b.id}:movie:42").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { activity ->
                    val item = MainActivity::class.java.getDeclaredField("onDemand").apply { isAccessible = true }.get(activity) as? OnDemand
                    item?.playlistId == b.id && playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') && playing!!.isPlaying
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("returnToLive").apply { isAccessible = true }.invoke(activity)
                    MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, b, false)
                }
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    guide.channels.size == 1 && playing!!.currentMediaItem?.localConfiguration?.uri?.port.toString() == first.base.substringAfterLast(':') && playing!!.isPlaying
                }
                assertEquals("Disabling a playlist keeps its own favorites", setOf("xc:1"), store.favorites(b))
                assertTrue("Disabled sources are not fetched", disabled.requests.isEmpty())
                scenario.onActivity { activity ->
                    val toggle = MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    toggle.invoke(activity, a, false)
                    assertTrue((MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.isEmpty())
                    toggle.invoke(activity, a, true)
                }
                waitFor(scenario) { (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(it) as Guide).channels.size == 1 && playing!!.isPlaying }
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("setPlaylistEnabled", Source::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, b, true) }
                waitFor(scenario) { activity -> (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 3 }
                var base = Guide.EMPTY
                scenario.onActivity { base = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(it) as Guide }
                val archivedAt = System.currentTimeMillis() - 3600000
                val archives = (1000..12999).map { Channel("xc:$it", "Station $it", "Other", "${first.base}/live/test/test-secret/$it.ts", catchupDays = 2, catchupMode = "xc") }
                val history = archives.associate { it.id to listOf(Programme(it.id, "Unrelated film", archivedAt, archivedAt + 1800000, "Local weather and community report. ".repeat(16))) }
                val complete = LiveSportsSnapshot((1..40).map { LiveEvent("final-$it", setOf("nfl:SEA", "nfl:SF"), archivedAt, "post", league = "NFL") }, System.currentTimeMillis(), setOf("NFL"))
                val openedAt = android.os.SystemClock.elapsedRealtime(); var attempt = ""
                scenario.onActivity { activity ->
                    attempt = playing!!.currentMediaItem!!.mediaId
                    MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.set(activity, Guide(base.channels + archives, base.programmes + history))
                    activity.liveSportsLoader = { complete.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, complete)
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    // Opening Sports intentionally selects Today. Apply the historical fixture date afterward.
                    val day = Instant.ofEpochMilli(archivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    MainActivity::class.java.getDeclaredField("sportsTeam").apply { isAccessible = true }.set(activity, "date:$day")
                    MainActivity::class.java.getDeclaredField("liveSportsDay").apply { isAccessible = true }.set(activity, day)
                }
                waitFor(scenario) { (it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount ?: 0) >= 40 }
                assertTrue("Fixtures must appear before scanning forty games across a large archive", android.os.SystemClock.elapsedRealtime() - openedAt < 5000)
                scenario.onActivity { activity -> assertSame(playing, find<PlayerView>(activity.window.decorView)!!.player); assertEquals("Opening Sports keeps playback on its existing attempt", attempt, playing!!.currentMediaItem!!.mediaId) }
            } } finally { store.saveSources(emptyList()); listOf(a, b, c).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } } }
    }

    @Test fun redZonePublishesNamesBeforeGuideAndPromotesFhdFiftyWithoutMovingFocus() {
        FixtureServer().use { first -> FixtureServer().use { second ->
            val a = Source(name = "RedZone primary", kind = "m3u", url = "${first.base}/playlist.m3u", allowHttp = true)
            val b = Source(name = "RedZone secondary", kind = "m3u", url = "${second.base}/playlist.m3u", allowHttp = true)
            val low = Channel("redzone", "NFL RedZone HD", "NFL", "${first.base}/channel1.mp4", reportedQuality = VideoInfo(1280, 720, 60f))
            val high = Channel("sky", "Sky Sports Mix FHD", "UK Sports", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis()
            store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id
            store.cache(a, Guide(listOf(low), emptyMap())); store.cache(b, Guide(listOf(high), mapOf(high.id to listOf(Programme(high.id, "NFL RedZone", now - 1000, now + 3600000)))))
            listOf(a, b).forEach(store::markPlaylistRefreshed)
            val finish = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var samePlayer: Player? = null
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; samePlayer = p; p?.isPlaying == true && (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 2 }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }
                    activity.redZoneLoader = { snapshot, profile, quality, at -> finish.await(6, java.util.concurrent.TimeUnit.SECONDS); SportsDestinations.redZone(snapshot, profile, quality, at) }
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performLongClick(); it.window.decorView.findViewWithTag<View>("sports-choose-stream").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:redzone")?.isShown == true }
                scenario.onActivity { activity ->
                    assertTrue("Named results are usable before guide discovery completes", finish.count > 0)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:redzone").requestFocus()
                }
                finish.countDown()
                val highId = "playlist:${b.id}:sky"
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:$highId")?.contentDescription.toString().contains("50 fps") && it.window.decorView.findViewWithTag<TextView>("sports-status").text.toString().contains("2 RedZone channels") }
                scenario.onActivity { activity ->
                    assertTrue("Refinement preserves the selected row", activity.window.decorView.findViewWithTag<View>("sports-stream:redzone").hasFocus())
                    val rows = activity.window.decorView.findViewWithTag<RecyclerView>("sports-streams")
                    assertEquals("Higher resolution beats 60 FPS at a lower resolution", "sports-stream:$highId", rows.getChildViewHolder(rows.getChildAt(0)).itemView.tag)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:$highId").performClick()
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            } } finally { finish.countDown(); store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    @Test fun confirmedSportsCardsPlayFromSessionCacheWithoutWaitingForAnotherSearch() {
        FixtureServer().use { server ->
            val source = Source(name = "Sports session cache", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val now = System.currentTimeMillis()
            val news = Channel("xc:1", "News", "News", "${server.base}/channel1.mp4")
            val feed = Channel("xc:2", "Game feed 4K", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 59.94f))
            val listing = Programme(feed.id, "NFL: Seahawks vs 49ers", now - 1800000, now + 7200000)
            val scores = LiveSportsSnapshot(listOf(LiveEvent("session-cache", setOf("nfl:SEA", "nfl:SF"), now - 1200000, league = "NFL")), now, setOf("NFL"))
            store.recentFeeds = emptyList(); store.saveSources(listOf(source)); store.active = source.id; store.sportsAutoPlay = true; store.performanceOverlay = false
            store.cache(source, Guide(listOf(news, feed), mapOf(news.id to listOf(listing.copy(channel = news.id, title = "World News")), feed.id to listOf(listing)))); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { scores.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:session-cache")?.text?.contains(feed.name) == true }
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = field.get(activity) as Guide
                    val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                    val generation = MainActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }.getInt(activity)
                    val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
                    val token = cache.use(source.id, generation, guide, emptySet())
                    val match = cache.get(token, game, now)!!
                    assertTrue(cache.put(token, match, now - 600000))
                    field.set(activity, guide.copy(programmes = guide.programmes + ("unrelated" to listOf(listing.copy(title = "Headlines")))))
                    val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { release.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                    activity.window.decorView.findViewWithTag<View>(game.key).performClick()
                    assertFalse("A confirmed card opens playback in the same frame, without the search worker", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    assertEquals("/channel2.mp4", find<PlayerView>(activity.window.decorView)!!.player!!.currentMediaItem!!.localConfiguration!!.uri.path)
                }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("sports-coverage:NFL:session-cache")?.text?.contains(feed.name) == true }
                scenario.onActivity { activity ->
                    val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                    assertFalse("No Sports trace is recorded while diagnostics are off", journal.report("fixture").contains("Sports #"))
                    store.performanceOverlay = true
                    MainActivity::class.java.getDeclaredMethod("syncPerformanceOverlay").apply { isAccessible = true }.invoke(activity)
                    activity.window.decorView.findViewWithTag<View>("NFL:session-cache").performClick()
                    assertFalse("Returning to Sports and selecting again reuses the same discovered game", activity.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("first frame") == true }
                scenario.onActivity { activity ->
                    val summary = activity.window.decorView.findViewWithTag<TextView>("performance-overlay").text.toString()
                    assertTrue(summary, summary.contains("peek hit"))
                    assertTrue(summary, summary.contains("sent 0 / completed 0"))
                    assertTrue(summary, summary.contains("auto→frame"))
                    println("Sports trace warm fixture: " + summary.substringAfter("Sports #").replace("\n", " | "))
                }
                assertTrue("Known guide coverage needs no provider re-confirmation", server.requestedEpgTargets.isEmpty())
            } } finally { release.countDown(); store.performanceOverlay = false; store.sportsAutoPlay = false; store.recentFeeds = emptyList(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun sustainedSportsBufferingUsesAnotherMatchedProviderOnTheSamePlayer() {
        FixtureServer(stalled = true, stallSeconds = 30).use { first -> FixtureServer().use { second ->
            val a = Source(name = "Primary game", kind = "m3u", url = "${first.base}/playlist.m3u", allowHttp = true)
            val b = Source(name = "Backup game", kind = "m3u", url = "${second.base}/playlist.m3u", allowHttp = true)
            val normal = Channel("normal", "News", "News", "${first.base}/channel2.mp4")
            val stalled = Channel("stall", "NFL main 4K", "NFL", "${first.base}/channel1.ts", reportedQuality = VideoInfo(3840, 2160, 60f))
            val backup = Channel("backup", "NFL backup FHD", "NFL", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis(); val listing = Programme(stalled.id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000)
            store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id; store.recentFeeds = emptyList()
            store.cache(a, Guide(listOf(normal, stalled), mapOf(stalled.id to listOf(listing))))
            store.cache(b, Guide(listOf(backup), mapOf(backup.id to listOf(listing.copy(channel = backup.id)))))
            listOf(a, b).forEach(store::markPlaylistRefreshed); store.sportsAutoFailover = true; store.sportsFailoverSeconds = 10
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var samePlayer: Player? = null
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; samePlayer = p; p?.isPlaying == true && (MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide).channels.size == 3 }
                scenario.onActivity { activity ->
                    val snapshot = LiveSportsSnapshot(listOf(LiveEvent("recovery", setOf("nfl:SEA", "nfl:SF"), now - 1000, league = "NFL")), System.currentTimeMillis(), setOf("NFL"))
                    activity.liveSportsLoader = { snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                    MainActivity::class.java.getDeclaredMethod("switchMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("NFL:recovery")?.isShown == true }
                scenario.onActivity { activity -> store.sportsAutoPlay = true; activity.window.decorView.findViewWithTag<View>("NFL:recovery").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.currentMediaItem?.localConfiguration?.uri?.path == "/channel1.ts" && it.isPlaying } == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { assertSame("Recovery reuses one player", samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            } } finally { store.sportsAutoPlay = false; store.sportsAutoFailover = false; first.resumeStream.countDown(); store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    @Test fun pinnedScoreStripSharesLiveScoresPreservesFocusAndCancelsPendingTunes() {
        store.delete("game-reminders.enc")
        FixtureServer().use { server ->
            val source = Source(name = "Score strip fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channels = (1..2).map { Channel("score$it", "Sports feed $it", "Sports", "${server.base}/channel$it.mp4") }
            val one = LiveEvent("one", setOf("nfl:SEA", "nfl:SF"), now - 1000, scores = mapOf("nfl:SEA" to "7", "nfl:SF" to "3"), league = "NFL")
            val two = LiveEvent("two", setOf("nfl:BUF", "nfl:KC"), now - 1000, scores = mapOf("nfl:BUF" to "10", "nfl:KC" to "7"), league = "NFL")
            var snapshot = LiveSportsSnapshot(listOf(one, two, one.copy(id = "final", state = "post", start = maxOf(now - 60000, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())), two.copy(id = "upcoming", state = "pre", start = now + 3600000)), now, setOf("NFL"))
            val programmes = mapOf(channels[0].id to listOf(Programme(channels[0].id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000)), channels[1].id to listOf(Programme(channels[1].id, "NFL: Bills vs Chiefs", now - 1000, now + 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; var original = ""; val polls = java.util.concurrent.atomic.AtomicInteger()
                waitFor(scenario) { activity -> val p = find<PlayerView>(activity.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; player = p; p?.isPlaying == true }
                scenario.onActivity { activity ->
                    original = player!!.currentMediaItem!!.mediaId
                    activity.liveSportsLoader = { polls.incrementAndGet(); snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-scores").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip")
                    assertEquals(android.view.Gravity.BOTTOM, (strip.layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
                    assertEquals(3, activity.window.decorView.findViewWithTag<RecyclerView>("score-strip-games").adapter!!.itemCount)
                    assertEquals(original, player!!.currentMediaItem!!.mediaId)
                    assertNull(activity.window.decorView.findViewWithTag<View>("score-strip-pin"))
                    assertTrue("Opening scores pins automatically", SecureStore(context).scoreOverlayPinned)
                }
                device.pressBack()
                scenario.onActivity { activity -> val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip"); assertTrue(strip.isShown); assertFalse(strip.interactive); assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, strip.descendantFocusability); assertTrue(SecureStore(context).scoreOverlayPinned); store.scoreOverlayPosition = "Top" }
                device.pressDPadUp()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.hasFocus() == true }
                scenario.onActivity { assertEquals("Up focuses scores instead of changing channels", original, player!!.currentMediaItem!!.mediaId) }
                scenario.onActivity { activity ->
                    assertEquals(android.view.Gravity.TOP, (activity.window.decorView.findViewWithTag<View>("sports-score-strip").layoutParams as android.widget.FrameLayout.LayoutParams).gravity)
                    snapshot = snapshot.copy(events = listOf(one.copy(scores = mapOf("nfl:SEA" to "14", "nfl:SF" to "3")), two), checked = System.currentTimeMillis())
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                    MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:one")?.contentDescription.toString().contains("14") }
                val blocked = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
                var worker: java.util.concurrent.ExecutorService? = null
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("score-game:NFL:one").hasFocus()); assertEquals(original, player!!.currentMediaItem!!.mediaId)
                    worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker!!.submit { blocked.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NFL:two").performClick() }
                device.pressBack(); release.countDown(); worker!!.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
                scenario.onActivity { assertEquals("Cancel keeps the current game playing", original, player!!.currentMediaItem!!.mediaId) }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-scores").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:two")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NFL:two").performClick() }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && player!!.isPlaying }
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player)
                    val strip = activity.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip"); assertTrue(strip.isShown); assertFalse(strip.interactive)
                    assertFalse(channelList(activity)?.isShown == true)
                    assertEquals("Visible score polling shares the minute budget", 1, polls.get())
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "score-strip.png"))
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<SportsScoreStrip>("sports-score-strip")?.interactive == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-strip-close").performClick(); assertFalse(SecureStore(context).scoreOverlayPinned) }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { activity -> assertFalse(activity.window.decorView.findViewWithTag<View>("sports-score-strip").isShown); assertEquals("Top", SecureStore(context).scoreOverlayPosition) }
            } } finally { store.scoreOverlayPinned = false; store.scoreOverlayPosition = "Bottom"; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun redZoneTileAutoplaysTheBestFeedAndDetailsRetainManualChoices() {
        FixtureServer().use { server ->
            val source = Source(name = "RedZone autoplay", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val normal = Channel("normal", "News", "News", "${server.base}/channel1.mp4")
            val low = Channel("low", "NFL RedZone HD", "NFL", "${server.base}/channel2.mp4", reportedQuality = VideoInfo(1280, 720, 60f))
            val best = Channel("best", "Sky Sports Mix FHD", "UK Sports", "${server.base}/channel3.mp4", reportedQuality = VideoInfo(1920, 1080, 50f))
            val now = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(normal, low, best), mapOf(best.id to listOf(Programme(best.id, "NFL RedZone", now - 1000, now + 3600000))))); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { val p = find<PlayerView>(it.window.decorView)?.player; p?.repeatMode = Player.REPEAT_MODE_ONE; player = p; p?.isPlaying == true }
                scenario.onActivity { activity -> activity.liveSportsLoader = { LiveSportsSnapshot(emptyList(), System.currentTimeMillis(), setOf("NFL")) }; activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-redzone")?.isShown == true && it.window.decorView.findViewWithTag<TextView>("sports-redzone-coverage")?.text.toString().contains("Sky Sports") }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-redzone").performClick() }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel3.mp4" && player!!.isPlaying }
                scenario.onActivity { assertFalse(channelList(it)?.isShown == true); assertSame(player, find<PlayerView>(it.window.decorView)!!.player) }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-game-streams")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("channel-game-streams").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:best")?.isShown == true && it.window.decorView.findViewWithTag<View>("sports-stream:low")?.isShown == true }
                scenario.onActivity { assertEquals("Opening alternatives preserves playback", "/channel3.mp4", player!!.currentMediaItem!!.localConfiguration!!.uri.path) }
            } } finally { store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun playbackAlternativesFinishWhenScoreboardRefreshesToAnotherGame() {
        FixtureServer().use { server ->
            val source = Source(name = "Playback alternatives", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val current = Channel("watched", "Sports One", "NFL", "${server.base}/channel1.mp4")
            val alternate = Channel("alternate", "Sports Two", "NFL", "${server.base}/channel2.mp4")
            val other = Channel("other", "Sports Three", "NFL", "${server.base}/channel3.mp4")
            val guess = Channel("guess", "CBS 4K", "NFL", "${server.base}/missing.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
            val programme = Programme(current.id, "NFL: Seahawks vs 49ers", now - 60000, now + 3600000)
            val event = LiveEvent("watched", setOf("nfl:SEA", "nfl:SF"), now - 60000, networks = listOf("CBS"), league = "NFL")
            var scores = LiveSportsSnapshot(listOf(event), now, SportsLeagues.byId.keys)
            val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(listOf(current, other, guess), mapOf(current.id to listOf(programme)))); store.markPlaylistRefreshed(source)
            val release = java.util.concurrent.CountDownLatch(1)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { val p = find<PlayerView>(it.window.decorView)?.player; player = p; p?.repeatMode = Player.REPEAT_MODE_ONE; p?.isPlaying == true }
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { scores }
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(game.key)?.isShown == true }
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(it, game, true) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel")?.isShown == false && player!!.isPlaying }
                var playing = ""
                val blocked = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    playing = player!!.currentMediaItem!!.mediaId
                    val guideField = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }
                    val guide = guideField.get(activity) as Guide
                    guideField.set(activity, guide.copy(channels = guide.channels + alternate, programmes = guide.programmes + (alternate.id to listOf(programme.copy(channel = alternate.id)))))
                    scores = scores.copy(events = listOf(event.copy(id = "different-game", teams = setOf("nfl:BUF", "nfl:KC"))))
                    MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores)
                    val worker = MainActivity::class.java.getDeclaredField("sportsMatchWorker").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                    worker.submit { blocked.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                }
                assertTrue(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS))
                device.pressDPadCenter()
                focusDetailsAction(scenario, "channel-game-streams")
                device.pressDPadCenter()
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("refreshSports").apply { isAccessible = true }.invoke(it) }
                waitFor(scenario) { activity ->
                    val panel = activity.window.decorView.findViewWithTag<SportsPanel>("sports-panel")
                    @Suppress("UNCHECKED_CAST") val games = SportsPanel::class.java.getDeclaredField("games").apply { isAccessible = true }.get(panel) as List<SportsGame>
                    games.singleOrNull()?.eventId == "different-game" && !MainActivity::class.java.getDeclaredField("sportsComputing").apply { isAccessible = true }.getBoolean(activity)
                }
                scenario.onActivity { activity ->
                    val selected = MainActivity::class.java.getDeclaredField("selectedGame").apply { isAccessible = true }.get(activity) as SportsGame?
                    assertEquals("Scoreboard refresh cannot replace the active alternatives search", game.key, selected?.key)
                    assertEquals("Searching alternatives keeps the original stream", playing, player!!.currentMediaItem!!.mediaId)
                }
                release.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:alternate")?.isShown == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("sports-hero-title").text.contains("Seattle"))
                    assertNull("A generic 4K broadcaster must not appear beside confirmed game feeds", activity.window.decorView.findViewWithTag<View>("sports-stream:guess"))
                    assertEquals(playing, player!!.currentMediaItem!!.mediaId)
                    activity.window.decorView.findViewWithTag<View>("sports-stream:alternate").performClick()
                }
                waitFor(scenario) { player!!.currentMediaItem?.localConfiguration?.uri?.path == "/channel2.mp4" && player!!.isPlaying }
                device.pressDPadCenter(); focusDetailsAction(scenario, "channel-game-streams"); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:watched")?.isShown == true && it.window.decorView.findViewWithTag<View>("sports-stream:alternate")?.isShown == true }
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertEquals("/channel2.mp4", player!!.currentMediaItem!!.localConfiguration!!.uri.path) }
            } } finally { release.countDown(); store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    @Test fun basketballSearchFindsTheTeamFeedInTheSecondPlaylistInsteadOfRadio() {
        FixtureServer(parallelClients = true).use { first -> FixtureServer(programmeTitle = "NBA: Bucks at Celtics", parallelClients = true).use { second ->
            val a = Source(name = "Basketball primary", kind = "xtream", url = first.base, username = "test", password = "test-secret", allowHttp = true)
            val b = a.copy(id = java.util.UUID.randomUUID().toString(), name = "Basketball alternate", url = second.base)
            val now = System.currentTimeMillis()
            val news = Channel("xc:1", "News", "News", "${first.base}/channel1.mp4")
            val radio = Channel("xc:2", "Celtics vs Bucks Radio 4K", "Radio", "${first.base}/channel2.mp4", reportedQuality = VideoInfo(3840, 2160, 60f))
            val generic = (100..159).map { Channel("xc:$it", "NBA Event $it 4K", "NBA", "${first.base}/unused$it.mp4", reportedQuality = VideoInfo(3840, 2160, 0f)) }
            val filler = (1000..26999).map { Channel("xc:$it", "Local news $it", "News", "${first.base}/unused$it.mp4") }
            val video = Channel("xc:9", "Boston Celtics FHD", "NBA Teams", "${second.base}/channel1.mp4", reportedQuality = VideoInfo(1920, 1080, 0f))
            val listing = Programme(radio.id, "NBA: Bucks at Celtics", now - 1000, now + 3600000)
            store.recentFeeds = emptyList(); store.sportsLastLeague = "NBA"; store.saveSources(listOf(a, b)); store.active = a.id; store.catalogRoot = a.id; store.performanceOverlay = true
            store.cache(a, Guide(listOf(news, radio) + generic + filler, mapOf(radio.id to listOf(listing)))); store.cache(b, Guide(listOf(video), emptyMap()))
            listOf(a, b).forEach(store::markPlaylistRefreshed)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null
                waitFor(scenario) { activity ->
                    val guide = MainActivity::class.java.getDeclaredField("guide").apply { isAccessible = true }.get(activity) as Guide
                    player = find<PlayerView>(activity.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE
                    player?.isPlaying == true && guide.channels.any { it.playlistId == b.id }
                }
                val scores = LiveSportsSnapshot(listOf(LiveEvent("basketball", setOf("nba:BOS", "nba:MIL"), now - 1000, league = "NBA")), now, SportsLeagues.byId.keys)
                val game = SportsSchedule.games(scores, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
                scenario.onActivity { activity -> activity.liveSportsLoader = { scores }; MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, scores); activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(game.key)?.isShown == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopSportsPrefetch", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                    val cache = MainActivity::class.java.getDeclaredField("sportsStreamCache").apply { isAccessible = true }.get(activity) as SportsStreamCache
                    cache.clear()
                    MainActivity::class.java.getDeclaredMethod("selectGame", SportsGame::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, game, true)
                }
                waitFor(scenario) { player?.let { it.isPlaying && it.currentMediaItem?.localConfiguration?.uri?.port.toString() == second.base.substringAfterLast(':') } == true }
                scenario.onActivity { activity ->
                    assertSame(player, find<PlayerView>(activity.window.decorView)!!.player)
                    val results = MainActivity::class.java.getDeclaredField("sportsResults").apply { isAccessible = true }.get(activity)!!
                    val matched = results.javaClass.getDeclaredField("game").apply { isAccessible = true }.get(results) as SportsGame
                    assertTrue("Both provider passes finish before no-match can be reported", matched.coverageComplete)
                    assertTrue(matched.broadcasts.any { it.channel.playlistId == b.id })
                    assertFalse(matched.broadcasts.any { it.channel.name.contains("Radio") })
                }
                assertTrue("The team channel's owning provider is queried", second.requestedEpgTargets.any { it.contains("stream_id=9") })
                assertNull("A radio match must never be auto-tuned", first.requests["/channel2.mp4"])
                waitFor(scenario) { it.window.decorView.findViewWithTag<TextView>("performance-overlay")?.text?.contains("first frame") == true }
                scenario.onActivity { activity ->
                    val summary = activity.window.decorView.findViewWithTag<TextView>("performance-overlay").text.toString()
                    assertTrue(summary, summary.contains("peek miss"))
                    assertTrue(summary, summary.contains("channels /"))
                    val journal = MainActivity::class.java.getDeclaredField("playbackJournal").apply { isAccessible = true }.get(activity) as PlaybackJournal
                    val report = journal.report("fixture")
                    assertTrue(report, report.contains("candidate 1 first frame"))
                    assertFalse(report, report.contains(second.base) || report.contains(a.password))
                    println("Sports trace cold fixture: " + summary.substringAfter("Sports #").replace("\n", " | "))
                }
                device.pressDPadCenter(); focusDetailsAction(scenario, "channel-game-streams"); device.pressDPadCenter()
                val rowTag = "sports-stream:playlist:${b.id}:xc:9"
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>(rowTag)?.isShown == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<View>(rowTag)
                    assertTrue("Sports results use the Guide's channel row", row is GuideAdapter.Row)
                    assertEquals((GuideTheme.rowHeightDp * activity.resources.displayMetrics.density).toInt(), row.height)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-results.png"))
            } } finally { store.performanceOverlay = false; store.saveSources(emptyList()); listOf(a, b).forEach(store::removeCache); store.catalogRoot = ""; store.recentFeeds = emptyList() }
        } }
    }

    private fun selectSportsSchedule(activity: MainActivity, option: String) {
        val root = activity.window.decorView
        root.findViewWithTag<View>("sports-schedule").performClick()
        root.findViewWithTag<View>(option).performClick()
    }

    private fun chooseLibraryCategory(scenario: ActivityScenario<MainActivity>, position: Int) {
        scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-category").performClick() }
        waitFor(scenario) {
            val categories = it.window.decorView.findViewWithTag<RecyclerView>("library-categories")
            categories.isShown && categories.findViewHolderForAdapterPosition(position) != null
        }
        scenario.onActivity {
            it.window.decorView.findViewWithTag<RecyclerView>("library-categories")
                .findViewHolderForAdapterPosition(position)!!.itemView.performClick()
        }
    }

    private fun focusDetailsAction(scenario: ActivityScenario<MainActivity>, tag: String) {
        waitFor(scenario) { it.window.decorView.findViewWithTag<View>(tag)?.isShown == true }
        scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>(tag).requestFocus()) }
    }

    @Test fun playbackActionsAndTodaysOverlayStayBoundedAndHideInSettings() {
        FixtureServer().use { server ->
            val source = Source(name = "Overlay menu fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis()
            val channel = Channel("overlay", "Sports feed", "Sports", "${server.base}/channel1.mp4")
            val one = LiveEvent("overlay-live", setOf("nfl:SEA", "nfl:SF"), now - 1000, league = "NFL")
            val two = LiveEvent("overlay-other", setOf("nfl:BUF", "nfl:KC"), now - 1000, league = "NFL")
            val finalStart = maxOf(now - 7200000, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            val final = LiveEvent("overlay-final", setOf("nba:BOS", "nba:MIL"), finalStart, "post", scores = mapOf("nba:BOS" to "123", "nba:MIL" to "112"), league = "NBA")
            val snapshot = LiveSportsSnapshot(listOf(one, two, final), now, SportsLeagues.byId.keys)
            val game = SportsSchedule.games(LiveSportsSnapshot(listOf(one), now, setOf("NFL")), Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(listOf(channel), mapOf(channel.id to listOf(Programme(channel.id, "NFL: Seahawks vs 49ers", now - 1000, now + 3600000))))); store.markPlaylistRefreshed(source)
            try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: Player? = null; val polls = java.util.concurrent.atomic.AtomicInteger()
                waitFor(scenario) { player = find<PlayerView>(it.window.decorView)?.player; player?.repeatMode = Player.REPEAT_MODE_ONE; player?.isPlaying == true }
                device.pressBack()
                scenario.onActivity { activity ->
                    activity.liveSportsLoader = { polls.incrementAndGet(); snapshot.copy(checked = System.currentTimeMillis()) }
                    MainActivity::class.java.getDeclaredField("liveSportsAttempt").apply { isAccessible = true }.setLong(activity, now)
                    MainActivity::class.java.getDeclaredField("reminderGame").apply { isAccessible = true }.set(activity, game.copy(state = "pre"))
                    MainActivity::class.java.getDeclaredMethod("watchGameReminder").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { activity ->
                    val scores = MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.get(activity) as LiveSportsSnapshot
                    scores.events.any { it.id == two.id } && player?.isPlaying == true && activity.window.decorView.findViewWithTag<View>("game-watch-panel")?.isShown != true
                }
                assertEquals("A reminder forces the full scoreboard refresh despite the minute cooldown", 1, polls.get())
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("channel-scores")?.hasFocus() == true }
                scenario.onActivity { activity ->
                    val row = activity.window.decorView.findViewWithTag<ViewGroup>("playback-actions")
                    assertTrue(row.childCount >= 8)
                    repeat(row.childCount) { i -> val button = row.getChildAt(i) as android.widget.Button; assertNotNull(button.compoundDrawables[1]); assertEquals(row, button.parent) }
                    assertNull(activity.window.decorView.findViewWithTag<View>("channel-reconnect"))
                    assertEquals("Sports overlay", activity.window.decorView.findViewWithTag<android.widget.Button>("channel-scores").text.toString())
                }
                device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "playback-actions.png"))
                repeat(3) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-scores").hasFocus()) }
                device.pressDPadRight(); scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-guide-action").hasFocus()) }
                device.pressDPadRight(); device.pressDPadDown(); scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("channel-game-streams").hasFocus()) }
                device.pressDPadLeft(); device.pressDPadLeft(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NFL:overlay-live")?.hasFocus() == true }
                scenario.onActivity { assertEquals(3, it.window.decorView.findViewWithTag<RecyclerView>("score-strip-games").adapter!!.itemCount) }
                repeat(4) { device.pressDPadLeft() }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("score-game:NFL:overlay-live").hasFocus()) }
                device.pressDPadRight(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.hasFocus() == true }
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").contentDescription.toString().contains("FINAL")) }
                scenario.onActivity { activity -> store.hideCompletedScores = true; MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity) }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.contentDescription.toString().contains("Scores hidden") }
                scenario.onActivity { activity ->
                    assertFalse(activity.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").contentDescription.toString().contains("123"))
                    store.hideCompletedScores = false; MainActivity::class.java.getDeclaredMethod("updateScoreStrip").apply { isAccessible = true }.invoke(activity)
                }
                device.pressDPadRight(); repeat(4) { device.pressDPadRight() }
                scenario.onActivity { assertTrue("The right edge stops at Close instead of wrapping", it.window.decorView.findViewWithTag<View>("score-strip-close").hasFocus()) }
                device.pressDPadLeft(); device.pressBack()
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("playbackMenu").apply { isAccessible = true }.invoke(activity)
                    assertEquals("Settings hides the elevated overlay in the same frame", View.GONE, activity.window.decorView.findViewWithTag<View>("sports-score-strip").visibility)
                }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-score-strip")?.isShown == true }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("sportsTeams").apply { isAccessible = true }.invoke(activity)
                    assertEquals(View.GONE, activity.window.decorView.findViewWithTag<View>("sports-score-strip").visibility)
                    MainActivity::class.java.getDeclaredMethod("closeSportsManager").apply { isAccessible = true }.invoke(activity)
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-score-strip")?.isShown == true }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final")?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("score-game:NBA:overlay-final").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-actions")?.isShown == true && it.window.decorView.findViewWithTag<TextView>("sports-hero-badge")?.text.toString().contains("FINAL") }
                scenario.onActivity { assertEquals("Overlay details open in the game's league", "NBA", store.sportsLastLeague) }
                scenario.onActivity { assertSame(player, find<PlayerView>(it.window.decorView)!!.player); assertTrue(player!!.isPlaying) }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-overlay-final.png"))
            } } finally { store.scoreOverlayPinned = false; store.saveSources(emptyList()); store.removeCache(source) }
        }
    }

    private fun playingPath(scenario: ActivityScenario<MainActivity>): String? {
        var result: String? = null
        scenario.onActivity { result = find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path }
        return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        val ready = AtomicBoolean()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { ready.set(predicate(it)) }
            if (ready.get()) return
            Thread.sleep(100)
        }
        device.takeScreenshot(File(context.getExternalFilesDir(null), "failure.png"))
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "failure.xml"))
        scenario.onActivity { activity ->
            val player = find<PlayerView>(activity.window.decorView)?.player
            player?.playerError?.let { android.util.Log.e("SpareFixture", "Synthetic playback failed", it) }
            android.util.Log.e("SpareFixture", "State=${player?.playbackState}; video=${player?.videoSize}; duration=${player?.duration}; archive=${player?.currentMediaItem?.localConfiguration?.uri?.path?.startsWith("/timeshift/")}; textDisabled=${player?.trackSelectionParameters?.disabledTrackTypes}; language=${player?.trackSelectionParameters?.preferredTextLanguages}; selectDefault=${player?.trackSelectionParameters?.selectTextByDefault}; text=${player?.currentTracks?.let { tracks -> CaptionTracks.choices(tracks).map { listOf(it.key, it.group.isTrackSelected(it.index)) } }}")
        }
        fail("Timed out waiting for app state")
    }
    private inline fun <reified T : View> find(root: View): T? {
        if (root is T) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            val found = findView(root.getChildAt(i), T::class.java)
            if (found != null) return found
        }
        return null
    }
    private fun <T : View> findView(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) findView(root.getChildAt(i), type)?.let { return it }
        return null
    }

    /** Invoked only by the release smoke runner against the separately installed minified APK. */
    fun releaseArtifactSmoke() {
        val releasePackage = "io.github.mot1us.spare"
        fun button(text: String) = device.wait(Until.findObject(By.pkg(releasePackage).text(text)), 8000)
            ?: error("Could not find $text in release app")
        FixtureServer().use { server ->
            val launch = device.executeShellCommand("am start -W -n $releasePackage/.MainActivity")
            assertTrue(launch, launch.contains("Status: ok"))
            assertTrue(device.wait(Until.hasObject(By.pkg(releasePackage)), 10000))
            device.pressMenu()
            button("Add playlist").click()
            val fields = device.wait(Until.findObjects(By.pkg(releasePackage).clazz(EditText::class.java)), 8000)
            check(fields != null && fields.size >= 2) { "Playlist URL field is missing" }
            val playlist = "${server.base}/playlist.m3u"
            fields[1].text = playlist
            assertEquals("Playlist URL entry", playlist, fields[1].text)
            val consent = button("Allow HTTP-only links from this provider")
            consent.click()
            if (!consent.isChecked) {
                assertTrue("Focus reached HTTP consent", consent.isFocused)
                device.pressDPadCenter()
            }
            assertTrue("Fixture requires explicit HTTP consent", consent.isChecked)
            device.pressDPadDown()
            assertTrue("Focus reached Save", button("Save").isFocused)
            device.pressDPadCenter()
            val firstChannel = By.pkg(releasePackage).descStartsWith("Test Channel 1.")
            val playbackDeadline = android.os.SystemClock.elapsedRealtime() + 20000
            var selectedChannel = false
            while (server.requests["/channel1.mp4"] == null && android.os.SystemClock.elapsedRealtime() < playbackDeadline) {
                if (!selectedChannel) {
                    device.findObject(firstChannel)?.let { channel -> channel.click(); selectedChannel = true }
                }
                Thread.sleep(100)
            }
            assertNotNull("The minified app must request the fixture video; requests: ${server.requests.keys.sorted()}", server.requests["/channel1.mp4"])
            device.pressMenu()
            button("Playback").click()
            val diagnostics = device.wait(Until.findObject(By.pkg(releasePackage).desc("Playback diagnostics")), 8000)
                ?: error("Could not find Playback diagnostics in release app")
            diagnostics.click()
            val refresh = By.pkg(releasePackage).desc("Refresh diagnostics")
            if (!device.wait(Until.hasObject(refresh), 1000)) {
                assertTrue("Focus reached Playback diagnostics", diagnostics.isFocused)
                device.pressDPadCenter()
            }
            val frame = By.pkg(releasePackage).textContains(" · first frame · ")
            assertTrue("Playback diagnostics opened", device.wait(Until.hasObject(refresh), 5000))
            if (!device.hasObject(frame)) {
                device.findObject(By.pkg(releasePackage).scrollable(true).hasDescendant(refresh))
                    ?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.75f)
            }
            assertTrue("The minified app must render a video frame", device.hasObject(frame))
        }
    }

    private inner class FixtureServer(private val failCategories: Boolean = false, channelCount: Int = 1, private val tsMode: Boolean = false, private val fallbackHls: Boolean = false, private val slowPlaylist: Boolean = false, private val missingStatus: Int = 0, private val regionGroups: Boolean = false, private val stalled: Boolean = false, private val stallSeconds: Int = 14, private val captionsMode: Boolean = false, private val embeddedCaptions: Int = 0, private val embeddedHls: Boolean = false, private val slowSportsGuide: Boolean = false, private val libraryCategories: Boolean = false, private val longVideo: Boolean = false, private val archivedSports: Boolean = false, private val transientSegmentErrors: Int = 0, private val transientSegmentStatus: Int = 503, private val programmeTitle: String = "World News", private val slowVod: Boolean = false, private val ambiguousVod: Boolean = false, private val liveCycles: Int = 16, parallelClients: Boolean = false, private val slowEpisodes: Boolean = false, private val fullGuide: Boolean = false) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        private val connections = if (parallelClients) Executors.newFixedThreadPool(4) else null
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
        val requests = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val transientLiveFailures = java.util.concurrent.atomic.AtomicInteger()
        val resumeStream = java.util.concurrent.CountDownLatch(1)
        private val video = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }
        private val transport = instrumentation.context.assets.open("no-aud.ts").use { it.readBytes() }
        private val avTransport by lazy { instrumentation.context.assets.open("stall-av.ts").use { it.readBytes() } }
        private val captionTransport by lazy { EmbeddedCaptionFixture.add(repeatTransport(avTransport, 8), embeddedCaptions == 708) }
        private val longTransport by lazy { repeatTransport(avTransport, liveCycles) }
        private val streams = (1..channelCount).joinToString(",", "[", "]") { id ->
            """{"stream_id":$id,"name":"Xtream Test $id","category_id":"${if (regionGroups) listOf("uk", "us-news", "us-sports", "ca")[(id - 1) % 4] else "1"}","container_extension":"${if (fallbackHls || missingStatus != 0) "ts" else "mp4"}","epg_channel_id":"${if (fullGuide) "test$id" else ""}","tv_archive":1,"tv_archive_duration":7}"""
        }.toByteArray()
        private val logo = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("S", 22f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        private val playlist = ("#EXTM3U url-tvg=\"$base/guide.xml\"\n" + (1..8).joinToString("\n") {
            "#EXTINF:-1 tvg-id=\"test$it\" tvg-logo=\"$base/logo.png\" group-title=\"${if (regionGroups) listOf("UK | News", "USA | News", "US | MLB", "CA | News")[(it - 1) % 4] else if (it <= 4) "News" else "Culture"}\",Test Channel $it\n$base/channel$it.${if (captionsMode || embeddedHls) "m3u8" else if (tsMode || stalled || longVideo || embeddedCaptions != 0) "ts" else "mp4"}"
        }).toByteArray()
        private val xml: ByteArray
        val finishSportsGuide = java.util.concurrent.CountDownLatch(1)
        val sportsTailBlocked = java.util.concurrent.CountDownLatch(1)
        private val liveSportsSent = AtomicBoolean(false)
        val episodesRequested = java.util.concurrent.CountDownLatch(1)
        val finishEpisodes = java.util.concurrent.CountDownLatch(1)
        val finishVodCatalog = java.util.concurrent.CountDownLatch(1)
        private val slowMovies by lazy { (1..16).joinToString(",", "[", "]") { id -> org.json.JSONObject().put("stream_id", 100 + id).put("name", if (id == 1) "EN | Fixture movie (2025) 4K DV" else if (ambiguousVod && id == 2) "Fixture movie (1990) FHD" else "Other movie $id").put("category_id", "1").put("container_extension", "mp4").put("plot", "x".repeat(1024)).toString() }.toByteArray() }
        val actionCatalogStarted = java.util.concurrent.CountDownLatch(1)
        val finishActionCatalog = java.util.concurrent.CountDownLatch(1)
        private val actionMovies = (1..400).joinToString(",", "[", "]") { """{"stream_id":${2000+it},"name":"Action title $it","category_id":"2","container_extension":"mp4"}""" }.toByteArray()
        val archiveRequests = java.util.concurrent.atomic.AtomicInteger()
        val epgTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestedEpgTargets = java.util.Collections.synchronizedList(mutableListOf<String>())
        val catalogTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val shortEpg: ByteArray
        init {
            if (embeddedCaptions != 0) captionTransport // Build fixtures before starting HTTP, so generation errors fail directly.
            val now = System.currentTimeMillis() / 1800000L * 1800000L
            val encodedTitle = java.util.Base64.getEncoder().encodeToString(programmeTitle.toByteArray())
            shortEpg = """{"epg_listings":[{"title":"$encodedTitle","description":"U3BhcmUgcHJvZ3JhbW1lIGd1aWRlIHRlc3Qu","start_timestamp":"${now / 1000}","stop_timestamp":${(now + 3600000) / 1000}}]}""".toByteArray()
            val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
            xml = ("<tv>" + (1..if (fullGuide) channelCount else 8).joinToString("") { channel -> (0..6).joinToString("") { slot ->
                "<programme channel=\"test$channel\" start=\"${format.format(Instant.ofEpochMilli(now + slot * 1800000L))}\" stop=\"${format.format(Instant.ofEpochMilli(now + (slot + 1) * 1800000L))}\"><title>${if (programmeTitle == "World News") listOf("World News", "Live Report", "Evening Update")[slot % 3] else programmeTitle}</title><desc>Spare playback and guide test fixture.</desc></programme>"
            } } + "</tv>").toByteArray()
            fun serve(client: java.net.Socket) {
                clients.add(client)
                runCatching {
                    client.use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        for (i in 0 until 100) { if (reader.readLine().isNullOrEmpty()) break }
                        val target = request.split(' ').getOrNull(1).orEmpty()
                        val path = target.substringBefore('?'); requests.merge(path, 1, Int::plus)
                        if (target.contains("get_short_epg")) requestedEpgTargets.add(target)
                        if (target.contains("get_vod_streams")) catalogTargets.add(target)
                        if (target.contains("get_simple_data_table")) archiveRequests.incrementAndGet()
                        if (slowEpisodes && target.contains("get_series_info")) { episodesRequested.countDown(); finishEpisodes.await(12, java.util.concurrent.TimeUnit.SECONDS) }
                        if (path == "/redirect.mp4" || path == "/redirect-relative.mp4") {
                            val location = if (path == "/redirect.mp4") "$base/redirect-relative.mp4" else "/channel1.mp4"
                            connection.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
                        }
                        var sportsPayload: ByteArray? = null
                        if (slowSportsGuide && target.contains("get_short_epg")) {
                            epgTargets.add(target)
                            val id = Regex("stream_id=([0-9]+)").find(target)?.groupValues?.get(1)?.toInt() ?: 1
                            // Socket arrival order can differ from request order. Delay only after
                            // the first live listing was actually served, never in front of it.
                            if (id > 2 && liveSportsSent.get()) { sportsTailBlocked.countDown(); finishSportsGuide.await(20, java.util.concurrent.TimeUnit.SECONDS) }
                            if (id == 1) liveSportsSent.set(true)
                            val time = System.currentTimeMillis(); val title = if (id == 1) "Yankees vs Red Sox" else if (id == 2) "Bills vs Chiefs" else "World News"
                            val start = if (id == 2) time + 3600000 else time - 300000
                            sportsPayload = """{"epg_listings":[{"title":"$title","start_timestamp":${start / 1000},"stop_timestamp":${(start + 3600000) / 1000}}]}""".toByteArray()
                        }
                        val (body, mime) = when (path) {
                            "/playlist.m3u" -> playlist to "application/x-mpegURL"
                            "/guide.xml" -> xml to "application/xml"
                            "/logo.png" -> logo to "image/png"
                            "/av.ts" -> (if (embeddedCaptions != 0) captionTransport else avTransport) to "video/mp2t"
                            "/media.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:${if (embeddedCaptions != 0) 20 else 3}\n#EXTINF:${if (embeddedCaptions != 0) 20 else 2.5},\n$base/av.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.5,\n$base/captions.vtt\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.vtt" -> "WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nSpare test captions\n".toByteArray() to "text/vtt"
                            "/player_api.php" -> (if (target.contains("get_vod_categories") || target.contains("get_series_categories")) (if (libraryCategories) """[{"category_id":"1","category_name":"Fixture cinema"},{"category_id":"2","category_name":"Action"},{"category_id":"3","category_name":"Comedy"}]""" else """[{"category_id":"1","category_name":"Fixture cinema"}]""").toByteArray()
                                else if (target.contains("get_vod_streams")) when {
                                    slowVod -> slowMovies
                                    libraryCategories && target.contains("category_id=2") -> actionMovies
                                    libraryCategories && target.contains("category_id=3") -> """[{"stream_id":3001,"name":"Comedy title","category_id":"3","container_extension":"mp4"}]""".toByteArray()
                                    else -> """[{"stream_id":101,"name":"Fixture movie","category_id":"1","container_extension":"mp4","stream_icon":"$base/logo.png","year":"2025"}]""".toByteArray()
                                }
                                else if (target.contains("get_vod_info")) """{"info":{"plot":"A provider-supplied film description.","cover_big":"$base/logo.png","releasedate":"2025-01-01","duration":"01:30:00","genre":"Drama"}}""".toByteArray()
                                else if (target.contains("get_series_info")) """{"episodes":{"1":[{"id":201,"title":"Pilot","episode_num":1,"container_extension":"mp4"},{"id":202,"title":"Second episode","episode_num":2,"container_extension":"mp4"}]}}""".toByteArray()
                                else if (target.contains("get_series")) """[{"series_id":1,"name":"Fixture series","category_id":"1"}]""".toByteArray()
                                else if (target.contains("get_simple_data_table")) { val time = System.currentTimeMillis(); """{"epg_listings":[{"title":"${if (archivedSports) "NFL: Seattle Seahawks vs San Francisco 49ers" else "Archived fixture"}","start_timestamp":${(time - 7200000) / 1000},"stop_timestamp":${(time - 3600000) / 1000}}]}""".toByteArray() }
                                else if (target.contains("get_short_epg")) sportsPayload ?: shortEpg else if (target.contains("get_live_categories")) (if (regionGroups) """[{"category_id":"us-news","category_name":"USA | News"},{"category_id":"ca","category_name":"CA | News"},{"category_id":"uk","category_name":"UK | News"},{"category_id":"us-sports","category_name":"US | MLB"}]""" else """[{"category_id":"1","category_name":"News"}]""").toByteArray() else streams) to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            "/live/test/test-secret/1.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n$base/segment.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            else -> if (embeddedHls && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CLOSED-CAPTIONS=NONE\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (embeddedCaptions != 0 && path.endsWith(".ts")) captionTransport to "video/mp2t" else if (captionsMode && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$base/captions.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES=\"subs\"\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (longVideo && path.endsWith(".ts")) longTransport to "video/mp2t" else if (stalled && path == "/channel1.ts") longTransport to "video/mp2t" else if (path.endsWith(".ts")) transport to "video/mp2t" else video to "video/mp4"
                        }
                        val output = connection.getOutputStream()
                        if (path == "/channel1.ts" && transientLiveFailures.getAndUpdate { count -> (count - 1).coerceAtLeast(0) } > 0) {
                            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return@use
                        }
                        val status = if (path == "/segment.ts" && (requests[path] ?: 0) <= transientSegmentErrors) "$transientSegmentStatus Temporary" else if (missingStatus != 0 && path in setOf("/live/test/test-secret/2.ts", "/live/test/test-secret/2.m3u8")) "$missingStatus Missing" else if ((failCategories && target.contains("get_live_categories")) || (fallbackHls && path == "/live/test/test-secret/1.ts")) "404 Not Found" else "200 OK"
                        val length = if (stalled && path == "/channel1.ts") "" else "Content-Length: ${body.size}\r\n"
                        output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\n${length}Connection: close\r\n\r\n".toByteArray())
                        if (stalled && path == "/channel1.ts") {
                            val split = avTransport.size * 2
                            output.write(body, 0, split); output.flush()
                            resumeStream.await(stallSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, split, body.size - split); output.flush()
                        } else if (slowVod && target.contains("get_vod_streams")) {
                            val value = body.toString(Charsets.UTF_8); var cut = 0
                            repeat(8) { cut = value.indexOf("},{", cut) + 2 }
                            output.write(body, 0, cut); output.flush()
                            finishVodCatalog.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, cut, body.size - cut); output.flush()
                        } else if (libraryCategories && target.contains("get_vod_streams") && target.contains("category_id=2")) {
                            val value = body.toString(Charsets.UTF_8); var cut = 0
                            repeat(264) { cut = value.indexOf("},{", cut) + 2 }
                            output.write(body, 0, cut); output.flush(); actionCatalogStarted.countDown()
                            finishActionCatalog.await(15, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, cut, body.size - cut); output.flush()
                        } else if (slowPlaylist && path == "/playlist.m3u") {
                            for (chunk in body.asList().chunked(200)) { output.write(chunk.toByteArray()); output.flush(); Thread.sleep(200) }
                        } else { output.write(body); output.flush() }
                    }
                }
                clients.remove(client)
            }
            worker.execute {
                while (!socket.isClosed) runCatching {
                    val client = socket.accept()
                    if (connections != null) connections.execute { serve(client) } else serve(client)
                }
            }
        }
        override fun close() { finishEpisodes.countDown(); finishVodCatalog.countDown(); finishActionCatalog.countDown(); finishSportsGuide.countDown(); resumeStream.countDown(); socket.close(); clients.forEach { runCatching { it.close() } }; connections?.shutdownNow(); worker.shutdownNow() }
    }
}

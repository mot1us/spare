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
import android.widget.EditText
import android.view.View
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

    @Before fun resetGuideStyle() { store.sportsLeagues = SportsLeagues.byId.keys; store.frameRateMatching = true; store.smallPreview = false; store.hiddenDock = false; store.theme = "Monochrome"; store.themeStyle = "Glass"; store.subtitleSize = "Small"; store.subtitleStyle = "Outline"; store.captions = false; store.followedTeams = emptySet(); store.sportsTeam = ""; store.clock24 = false; store.font = "Default"; store.saveMetadataAddon("") }

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
                val entries = (1..190).map { OnDemand("movie:$it", "Movie $it", if (it % 2 == 0) "Category 1" else "Category 2", "https://example.com/private/$it.mp4") }
                entries.chunked(64).forEach(writer::append)
                val snapshot = writer.finish(mapOf("1" to "Science fiction", "2" to "Drama"))
                val first = catalog.query(snapshot, "Science fiction", "", limit = 40)
                val second = catalog.query(snapshot, "Science fiction", "", first.cursor, 40)
                assertEquals(40, first.entries.size); assertTrue(first.more)
                assertTrue(first.entries.none { a -> second.entries.any { b -> a.id == b.id } })
                assertEquals("movie:188", catalog.query(snapshot, null, "Movie 188").entries.single().id)
                val restored = LibraryCatalog(store).cached(source, "movie")!!
                assertEquals(190, restored.count); assertEquals(snapshot.groups, restored.groups)
                assertTrue(context.filesDir.listFiles()!!.filter { it.name.startsWith("catalog-${source.id}-") }.none { it.readBytes().toString(Charsets.UTF_8).contains("https://example.com/private/") })
            } finally { store.removeCache(source) }

        } finally { context.deleteFile("test-security.enc") }
    }

    @Test fun legacyFpsSettingsMigrateAndNewExclusionsPersist() {
        val source = Source(name = "Migration", kind = "xtream", url = "https://example.com")
        context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putBoolean("sixty-${source.id}", true).commit()
        try {
            assertEquals(FrameRateFilter.onlySixty, store.filters(source).excludedFps)
            val profile = GuideFilters(1080, setOf(25, 50), setOf("US | Sports"))
            store.setFilters(source, profile)
            assertEquals(profile, SecureStore(context).filters(source))
            assertFalse(context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).contains("sixty-${source.id}"))
        } finally { store.removeCache(source) }
    }

    @Test fun savedStartupBackgroundRefreshAndScheduledJobKeepPlaybackAndFocus() {
        FixtureServer().use { server ->
            val source = Source(name = "Refresh fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val savedText = "#EXTM3U\n" + (1..8).joinToString("\n") { "#EXTINF:-1 tvg-id=\"test$it\" group-title=\"${if (it <= 4) "News" else "Culture"}\",Test Channel $it\n${server.base}/channel$it.mp4" }
            val channels = Importers.m3u(savedText, true).channels; val now = System.currentTimeMillis()
            store.saveSources(listOf(source)); store.active = source.id
            store.cache(source, Guide(channels, mapOf("test1" to listOf(Programme("test1", "Saved programme", now - 60000, now + 3600000)))))
            store.toggleFavorite(source, channels[1]); store.setLast(source, channels[0]); store.setRefreshPolicy(source, RefreshPolicy())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Saved launch must not request the playlist", 0, server.requests["/playlist.m3u"] ?: 0)
                var samePlayer: Player? = null; var item = ""
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player; item = samePlayer!!.currentMediaItem!!.mediaId }
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
                // A provider refresh failure leaves the authenticated local cache usable.
                val bad = source.copy(url = "${server.base}/missing-playlist.m3u")
                store.saveSources(listOf(bad)); val failed = java.util.concurrent.atomic.AtomicReference<PlaylistRefresh.Result>()
                PlaylistRefresh.request(context, bad, true, false) { failed.set(it) }
                waitFor(scenario) { failed.get() != null }
                assertTrue(failed.get().error.isNotBlank()); assertEquals(8, store.cachedLineup(bad)!!.channels.size)
            }
            store.saveSources(emptyList()); RefreshJobs.configure(context, emptyList()); store.removeCache(source)
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
                scenario.onActivity { assertTrue("New selection must stay in the guide", channelList(it)!!.isShown) }
                assertEquals("/channel2.mp4", playingPath(scenario))
                holdBack(); waitFor(scenario) { channelList(it)?.isShown == false }
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
                assertNull("Xtream must not require the whole provider XMLTV", server.requests["/xmltv.php"])
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
                    assertTrue(root.findViewWithTag<EditText>("Xtream password").transformationMethod is android.text.method.PasswordTransformationMethod)
                    find<CheckBox>(root)!!.performClick()
                    assertEquals(https.url, root.findViewWithTag<EditText>("Playlist URL").text.toString())
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                waitFor(scenario) { dialog?.window?.decorView?.hasWindowFocus() == true }
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000))
                device.pressDPadCenter(); instrumentation.waitForIdleSync()
                assertNotNull(saved)
                assertEquals(https.url, saved!!.url)
                assertTrue(saved!!.allowHttp)
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
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                assertEquals("/channel1.ts", playingPath(scenario))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
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
        FixtureServer(fallbackHls = true).use { server ->
            val source = Source(name = "Fallback Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.videoSize.width > 0 && it.playbackState == Player.STATE_READY } == true }
                assertEquals("/live/test/test-secret/1.m3u8", playingPath(scenario))
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun strictUnknownQualityFiltersAndBrowseAllPersistIndependently() {
        FixtureServer(longVideo = true).use { server ->
            val source = Source(name = "Filters Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val profile = GuideFilters(1080, FrameRateFilter.onlySixty, setOf("News")); store.setFilters(source, profile)
            val legacy = VideoInfo(3840, 2160, 29.97f).json().put("detector", 3)
            context.getSharedPreferences("view-state", android.content.Context.MODE_PRIVATE).edit().putString("video-info-${source.id}", org.json.JSONObject().put("old", legacy).toString()).commit()
            assertEquals("Old display-only FPS must be remeasured", 0f, store.videoInfo(source).getValue("old").fps, 0f)
            assertEquals("Provider declarations are retained independently", 29.97f, Channel.from(Channel("old", "Old", "News", "https://example.com", reportedQuality = VideoInfo.from(legacy)).json()).reportedQuality!!.fps, .001f)
            store.saveVideoInfo(source, emptyMap())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { device.hasObject(By.textContains("No known channels match")) }
                scenario.onActivity { assertEquals(0, channelList(it)!!.adapter!!.itemCount) }
                assertTrue(store.videoInfo(source).isEmpty())
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Guide filters")
                activateSetting(scenario, if (store.videoInfo(source).isEmpty()) "Show unverified channels" else "Use guide filters"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { store.videoInfo(source).values.any { it.evidence == "input timestamps" } }
                val observed = store.videoInfo(source).values.first(); assertTrue(observed.width > 0); assertTrue(observed.fps > 0)
                assertEquals(profile, store.filters(source))
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Guide filters")
                activateSetting(scenario, if (store.videoInfo(source).isEmpty()) "Show unverified channels" else "Use guide filters"); leaveSettings(scenario)
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 0 }
                assertEquals(profile, store.filters(source))
                // An invalidated FPS cache must not prevent the saved FHD channel
                // from resuming so playback can verify it under strict filters.
                val lastId = store.last(source)
                store.saveVideoInfo(source, mapOf(lastId to VideoInfo(1920, 1080, 0f)))
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                device.pressBack()
                waitFor(scenario) { device.hasObject(By.textContains("No known channels match")) }
                assertEquals(profile, store.filters(source))
                waitFor(scenario) { store.videoInfo(source).values.any { it.evidence == "input timestamps" && it.fps > 0 } }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun gameReminderOverlayKeepsPlaybackAndDismissesWithBack() {
        val previousReminders = store.read("game-reminders.enc")
        var writes: java.util.concurrent.ExecutorService? = null
        FixtureServer().use { server ->
            val source = Source(name = "Reminder Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val now = System.currentTimeMillis(); val teams = listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }
            val game = SportsGame(teams, now + 86400000L, now + 90000000L, emptyList(), "synthetic-reminder", "pre", competition = "NFL")
            val reminders = GameReminders(); reminders.toggle(game); store.write("game-reminders.enc", reminders.json().toString().toByteArray())
            store.saveSources(listOf(source)); store.active = source.id
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { player -> player.repeatMode = Player.REPEAT_MODE_ONE; player.isPlaying } == true }
                    var playing: Player? = null
                    scenario.onActivity { activity ->
                        playing = find<PlayerView>(activity.window.decorView)!!.player
                        writes = MainActivity::class.java.getDeclaredField("cacheWrites").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
                        MainActivity::class.java.getDeclaredMethod("showGuide", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                        MainActivity::class.java.getDeclaredField("reminderPending").apply { isAccessible = true }.set(activity, game.copy(state = "in", start = now - 1000) to System.currentTimeMillis())
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
                }
            } finally {
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
                device.pressDPadCenter(); assertTrue(device.wait(Until.hasObject(By.text("Guide video")), 5000))
                activateSetting(scenario, "Guide video")
                assertTrue(device.wait(Until.hasObject(By.text("Small live preview")), 5000))
                activateSetting(scenario, "Small live preview"); leaveSettings(scenario)
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

    @Test fun actualStreamStallUsesBufferingAndResumesWithoutReconnect() {
        FixtureServer(stalled = true).use { server ->
            val source = Source(name = "Stall Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.isPlaying == true }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.playbackState == Player.STATE_BUFFERING }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("playback-status")?.let { bar -> bar.isShown && bar.contentDescription.toString() == "Buffering…" } == true }
                assertEquals(1, server.requests["/channel1.ts"])
                server.resumeStream.countDown()
                waitFor(scenario) { activity -> find<PlayerView>(activity.window.decorView)?.player?.isPlaying == true && activity.window.decorView.findViewWithTag<View>("playback-status")?.isShown == false }
                assertEquals("A recoverable buffer underrun must keep the existing stream", 1, server.requests["/channel1.ts"])
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
                    waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<EditText>("guide-search-input")?.text.toString() == "Channel 2" && channelList(activity)?.adapter?.itemCount == 1 }
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
                device.findObject(By.desc("Search channels")).text = "Channel 7"
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 && (channelList(it)!!.adapter as GuideAdapter).channels.single().name == "Test Channel 7" && device.hasObject(By.textStartsWith("Test Channel 7  ·")) }
                scenario.onActivity { it.window.decorView.findViewWithTag<EditText>("guide-search-input").onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) }
                waitFor(scenario) { channelList(it)?.hasFocus() == true }
                assertTrue(device.wait(Until.gone(By.pkg(keyboardPackage)), 5000))
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "search.png"))
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels.single().id && channelList(it)?.isShown == true }
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
        FixtureServer(channelCount = 3, missingStatus = 404).use { server ->
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
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("recent-add:xc:1")?.isShown == true }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("recent-watch:xc:1").isFocusable)
                    activity.window.decorView.findViewWithTag<View>("recent-add:xc:1").performClick()
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
                scenario.onActivity { assertTrue(channelList(it)!!.isShown) }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000)); activateSetting(scenario, "Theme")
                assertTrue(device.wait(Until.hasObject(By.text("Ocean")), 5000)); activateSetting(scenario, "Ocean"); leaveSettings(scenario)
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
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                activateSetting(scenario, "Subtitles")
                assertTrue(device.wait(Until.hasObject(By.text("Off")), 5000)); activateSetting(scenario, "Off"); leaveSettings(scenario)
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, store.cached(source)!!.channels[1]))
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
                assertTrue(device.wait(Until.hasObject(By.text("Off")), 5000)); device.findObject(By.text("Off")).click()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isEmpty() == true }
                assertEquals(CaptionTracks.OFF, SecureStore(context).channelCaptions(source, first)); assertTrue(store.captions)
                device.pressBack(); device.pressDPadDown()
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

    @Test fun sportsFindsFollowedGamesSelectsPreviewAndPreservesRemoteFocus() {
        FixtureServer(channelCount = 4).use { server ->
            val source = Source(name = "Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..4).map { Channel("sports:$it", listOf("News", "ESPN", "YES", "CBS")[it - 1], if (it == 1) "News" else "Sports", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis()
            val programmes = mapOf("sports:2" to listOf(Programme("sports:2", "Yankees vs Red Sox", now - 300000, now + 3600000)),
                "sports:3" to listOf(Programme("sports:3", "MLB: NYY @ BOS", now - 300000, now + 3600000)),
                "sports:4" to listOf(Programme("sports:4", "NFL: Bills at Chiefs", now - 300000, now + 4 * 3600000)))
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true; store.followedTeams = setOf("mlb:NYY"); store.sportsTeam = "mlb:NYY"
            store.toggleFavorite(source, channels[2]); store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null; scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); scenario.onActivity { fixtureLiveSports(it); it.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-team:today").isSelected)
                    activity.window.decorView.findViewWithTag<View>("sports-team:mlb:NYY").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                scenario.onActivity { activity ->
                    val panel = activity.window.decorView.findViewWithTag<View>("sports-panel")
                    assertTrue(panel.height > activity.window.decorView.height * .75f); assertTrue(panel.width > activity.window.decorView.width * .8f)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("guide-header").isShown)
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("LIVE"))
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-team:league:UCL") != null)
                    activity.window.decorView.findViewWithTag<View>("sports-watch").requestFocus()
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png")); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-stream:sports:3").performClick() }
                waitFor(scenario) { store.last(source) == "sports:3" }; assertEquals("/channel3.mp4", playingPath(scenario))
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
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("multiview-controls")?.isShown == true }
                scenario.onActivity { activity ->
                    val extra = activity.window.decorView.findViewWithTag<PlayerView>("multiview-second")
                    assertSame(retainedPlayer, extra.player); assertTrue(retainedPlayer!!.isPlaying)
                    assertSame(retainedSurface, (extra.videoSurfaceView as android.view.SurfaceView).holder.surface)
                    assertSame("Returning reuses the existing pane controls", retainedTile, activity.window.decorView.findViewWithTag<View>("multiview-tile:1"))
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("multiview-add-game").performClick(); it.window.decorView.findViewWithTag<View>("sports-scope").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").findViewHolderForAdapterPosition(1)!!.itemView.performClick() }
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
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-team:league:NFL").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 && it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo") }
                scenario.onActivity { activity ->
                    val games = activity.window.decorView.findViewWithTag<RecyclerView>("sports-games")
                    games.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                    activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(LiveEvent("nfl-fixture", setOf("nfl:BUF", "nfl:KC"), now - 300000, status = "Updated score")), now, setOf("NFL")) }
                    activity.window.decorView.findViewWithTag<View>("sports-refresh").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-detail").text.contains("Updated score") }
                scenario.onActivity { assertTrue("A scoreboard update keeps game-card focus", it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus()); assertFalse(it.window.decorView.findViewWithTag<View>("sports-leagues").hasFocus()) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-team:league:NFL").requestFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadDown(); device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown }
            }
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet()
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
        } finally { instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun sportsHeroRailAndFullScreenTeamManagerKeepFocusAndLeaguePreferences() {
        FixtureServer().use { server ->
            val artFiles = ArtworkFiles(context)
            val artBytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(2048, 1365, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() } }.toByteArray()
            val artUrls = (1..16).map { "https://images.example/rail-${System.nanoTime()}-$it.png" }
            artUrls.forEach { artFiles.bytes(it) { artBytes } }
            val source = Source(name = "Sports focus fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channel = Channel("focus", "News", "News", "${server.base}/channel1.mp4")
            store.saveSources(listOf(source)); store.active = source.id; store.hiddenDock = true
            store.cache(source, Guide(listOf(channel), emptyMap())); store.markPlaylistRefreshed(source)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                    device.pressBack()
                    scenario.onActivity { activity ->
                        val now = System.currentTimeMillis()
                        val snapshot = LiveSportsSnapshot((1..8).map { LiveEvent("focus-$it", setOf("nfl:BUF", "nfl:KC"), now - 300000, logos = mapOf("nfl:BUF" to artUrls[(it - 1) * 2], "nfl:KC" to artUrls[(it - 1) * 2 + 1]), league = "NFL") }, now, setOf("NFL"))
                        activity.liveSportsLoader = { snapshot }
                        MainActivity::class.java.getDeclaredField("liveSports").apply { isAccessible = true }.set(activity, snapshot)
                        activity.window.decorView.findViewWithTag<View>("sports-mode").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 8 }
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-team:league:NFL").apply { performClick(); requestFocus() } }
                    device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-watch").hasFocus() }
                    device.pressDPadDown()
                    repeat(12) { device.pressDPadRight() }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                    scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<NavigationRail>("app-navigation").expanded) }
                    repeat(7) { device.pressDPadLeft() }
                    scenario.onActivity { activity ->
                        assertTrue(activity.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus())
                        activity.window.decorView.findViewWithTag<View>("sports-teams").performClick()
                    }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-manager").isShown }
                    scenario.onActivity { activity ->
                        val manager = activity.window.decorView.findViewWithTag<SettingsPanel>("sports-manager")
                        manager.selectSetting("Leagues"); manager.focusOption("Champions League")
                        manager.findViewWithTag<View>("settings-option:Champions League").performClick()
                        assertTrue(manager.opened); assertFalse("UCL" in SecureStore(context).sportsLeagues)
                        manager.selectSetting("NFL")
                    }
                    device.pressDPadCenter()
                    waitFor(scenario) { "nfl:ARI" in store.followedTeams }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "sports-manager.png"))
                    device.pressBack(); device.pressBack()
                    waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-manager").isShown && it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-team:league:UCL") == null }
                    scenario.onActivity { assertNull(it.window.decorView.findViewWithTag<View>("sports-team:league:UCL")); it.window.decorView.findViewWithTag<View>("sports-team:league:NFL").requestFocus() }
                    device.pressDPadLeft()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                    device.pressDPadRight()
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-watch").hasFocus() }
                    var playback: Player? = null
                    scenario.onActivity { playback = find<PlayerView>(it.window.decorView)?.player; assertTrue(playback?.isPlaying == true) }
                    device.pressHome()
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { assertFalse("Home must pause playback while Sports is open", playback!!.playWhenReady) }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    instrumentation.runOnMainSync { assertEquals(Player.STATE_IDLE, playback!!.playbackState) }
                }
            } finally { store.sportsLeagues = SportsLeagues.byId.keys; store.hiddenDock = false; store.followedTeams = emptySet(); store.saveSources(emptyList()); store.removeCache(source) }
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
                assertTrue(device.wait(Until.hasObject(By.text("Time format")), 5000)); activateSetting(scenario, "Time format")
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
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 1 }
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

    @Test fun sportsSchedulesShowLiveAndUpcomingWithoutScanningPlaylistAndLibraryIsReachable() {
        FixtureServer(channelCount = 12).use { server ->
            val source = Source(name = "Schedule fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            val channels = (1..12).map { Channel("xc:$it", "ESPN $it", "Sports", "${server.base}/live/test/test-secret/$it.mp4", guideId = "xc:$it") }
            store.saveSources(listOf(source)); store.active = source.id; store.cache(source, Guide(channels, emptyMap())); store.markPlaylistRefreshed(source)
            store.setFilters(source, GuideFilters(minHeight = 1080)); store.followedTeams = setOf("nfl:SEA")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { device.hasObject(By.textContains("No known channels match")) }
                scenario.onActivity { activity -> activity.liveSportsLoader = { val now = System.currentTimeMillis(); LiveSportsSnapshot(listOf(
                    LiveEvent("live", setOf("nfl:BUF", "nfl:KC"), now - 300000), LiveEvent("next", setOf("nfl:SEA", "nfl:SF"), now + 4 * 86400000L, "pre")), now, setOf("NFL", "MLB")) }; activity.window.decorView.findViewWithTag<View>("sports-mode").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 1 }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("sports-team:today").isSelected)
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Buffalo"))
                    activity.window.decorView.findViewWithTag<View>("sports-scope").performClick()
                }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 2 }
                assertEquals("Displaying schedules must not query every provider channel", 0, server.epgTargets.size)
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-title").text.contains("Seattle"))
                    assertTrue(activity.window.decorView.findViewWithTag<android.widget.TextView>("sports-hero-badge").text.contains("UPCOMING"))
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-team:nfl:SEA").performClick() }
                waitFor(scenario) { device.hasObject(By.textContains("Seattle Seahawks")) }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-team:nfl:SEA").requestFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-mode").hasFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library").hasFocus() }
                device.pressDPadCenter(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun libraryMoviesSeriesArchiveAndFontUseSamePlayerAndReturnToLive() {
        FixtureServer(libraryCategories = true).use { server ->
            val source = Source(name = "Library fixture", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                assertTrue("Initial browsing requests only one category", server.catalogTargets.any { it.contains("category_id=1") })
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").requestFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:series").hasFocus() }
                device.pressDPadLeft(); device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-search").hasFocus() }
                device.pressDPadUp(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-tab:movie").hasFocus() }
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<RecyclerView>("library-categories").findViewHolderForAdapterPosition(2)!!.itemView.apply { requestFocus(); performClick() } }
                waitFor(scenario) { server.actionCatalogStarted.count == 0L && it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 80 }
                scenario.onActivity { activity -> assertEquals("Action", (activity.window.decorView.findViewWithTag<RecyclerView>("library-categories").findFocus() as android.widget.TextView).text.toString()) }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("library-heading").text.toString() == "Comedy" }
                server.finishActionCatalog.countDown()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:3001")?.isShown == true }
                scenario.onActivity { activity -> assertEquals("Comedy", (activity.window.decorView.findViewWithTag<RecyclerView>("library-categories").findFocus() as android.widget.TextView).text.toString()); activity.window.decorView.findViewWithTag<RecyclerView>("library-categories").findViewHolderForAdapterPosition(1)!!.itemView.apply { requestFocus(); performClick() } }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:movie:101")?.isShown == true }
                device.pressMenu(); activateSetting(scenario, "Time format"); activateSetting(scenario, "24 hour · 19:30")
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && store.clock24 }
                leaveSettings(scenario)
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-panel")?.isShown != true }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); it.window.decorView.findViewWithTag<View>("library-item:movie:101").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-play")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-play").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/movie/test/test-secret/101.mp4" && samePlayer?.playbackState == Player.STATE_READY }
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
                assertTrue(device.wait(Until.hasObject(By.desc("Category · Science fiction")), 5000))
                scenario.onActivity { it.window.decorView.findViewWithTag<RecyclerView>("library-categories").findViewHolderForAdapterPosition(2)!!.itemView.requestFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("library-items").adapter?.itemCount == 12500 && it.window.decorView.findViewWithTag<RecyclerView>("library-items").hasFocus() }
                device.pressDPadDown()
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-live").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" && channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick(); it.window.decorView.findViewWithTag<View>("library-tab:series").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:series:1")?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:series:1").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:episode:201")?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "library.png"))
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library-item:episode:201").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/201.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("on-demand-next").performClick() }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/series/test/test-secret/202.mp4" && samePlayer?.playbackState == Player.STATE_READY }
                assertEquals("On-demand titles must not replace live channel history", "xc:1", store.last(source))
                device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true && find<PlayerView>(it.window.decorView)?.player?.currentMediaItem?.localConfiguration?.uri?.path == "/live/test/test-secret/1.mp4" }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("library").performClick(); it.window.decorView.findViewWithTag<View>("library-tab:catchup").performClick() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("library-item:xc:1")?.isShown == true }
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
                device.pressMenu(); activateSetting(scenario, "Font"); activateSetting(scenario, "Monospace")
                waitFor(scenario) { store.font == "Monospace" }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(AppFont.face(true), it.window.decorView.findViewWithTag<android.widget.TextView>("settings-page-title").typeface) }
                leaveSettings(scenario)
                scenario.recreate(); waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals("Monospace", store.font)
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
            val bytes = File(context.filesDir, "watch-${source.id}.enc").readBytes().toString(Charsets.UTF_8)
            assertFalse(bytes.contains("secret-password")); assertFalse(bytes.contains("Fixture movie"))
            assertTrue(store.watchProgress(source.copy(id = "other-source")).isEmpty())
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
                panel = MediaBrowserPanel(activityContext, { text, action -> android.widget.Button(activityContext).apply { this.text = text; setOnClickListener { action() } } }, {}, {}, {})
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
                    activity.window.decorView.findViewWithTag<View>("settings-nav:Theme").requestFocus()
                }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-option:Monochrome").hasFocus() }
                device.pressDPadDown(); device.pressDPadCenter(); waitFor(scenario) { store.theme == "Ocean" }
                waitFor(scenario) { it.window.decorView.findViewWithTag<android.widget.TextView>("settings-page-title")?.text.toString() == "Theme" && it.window.decorView.findViewWithTag<View>("settings-option:Ocean")?.hasFocus() == true }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings-nav:Theme").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-panel").isShown && it.window.decorView.findViewWithTag<View>("settings").hasFocus() }
                device.pressDPadRight(); device.pressDPadRight()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings-option:Monochrome").hasFocus() }
                device.pressDPadCenter(); waitFor(scenario) { store.theme == "Monochrome" && it.window.decorView.findViewWithTag<View>("settings-panel").isShown }
                activateSetting(scenario, "Ocean")
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId) }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "settings.png"))
                activateSetting(scenario, "Style · Flat"); waitFor(scenario) { store.themeStyle == "Flat" && GuideTheme.style == "Flat" }
                activateSetting(scenario, "Violet"); activateSetting(scenario, "Style · Outline")
                waitFor(scenario) { store.theme == "Violet" && store.themeStyle == "Outline" && GuideTheme.style == "Outline" }
                activateSetting(scenario, "Font"); activateSetting(scenario, "Light")
                waitFor(scenario) { store.font == "Light" }
                scenario.onActivity { activity -> assertEquals(AppFont.face(true), activity.window.decorView.findViewWithTag<android.widget.TextView>("settings-page-title").typeface); assertSame(samePlayer, find<PlayerView>(activity.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId) }
                activateSetting(scenario, "Theme"); activateSetting(scenario, "Nostalgia preset")
                waitFor(scenario) { store.themeStyle == "Nostalgia" && store.font == "Monospace" && store.theme == "Phosphor" }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId) }
                leaveSettings(scenario); device.pressBack()
                waitFor(scenario) { channelList(it)?.isShown == true }
                instrumentation.waitForIdleSync(); device.waitForIdle(); device.takeScreenshot(File(context.getExternalFilesDir(null), "nostalgia.png"))
                device.pressMenu()
                activateSetting(scenario, "Frame-rate matching"); activateSetting(scenario, "Off")
                assertFalse(SecureStore(context).frameRateMatching)
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player); assertEquals(attempt, samePlayer!!.currentMediaItem!!.mediaId); assertTrue(it.window.decorView.findViewWithTag<View>("settings-panel").isShown); assertEquals(0, it.window.attributes.preferredDisplayModeId) }
                activateSetting(scenario, "On"); assertTrue(SecureStore(context).frameRateMatching)
                activateSetting(scenario, "Subtitles"); activateSetting(scenario, "Subtitle appearance")
                activateSetting(scenario, "Medium"); activateSetting(scenario, "Shadow")
                waitFor(scenario) { store.subtitleSize == "Medium" && store.subtitleStyle == "Shadow" }
                scenario.onActivity { activity ->
                    val info = activity.packageManager.getActivityInfo(android.content.ComponentName(activity, MainActivity::class.java), 0)
                    assertEquals(R.drawable.tv_banner, info.banner); assertEquals(R.drawable.ic_spare, info.icon)
                    val banner = info.loadBanner(activity.packageManager)!!; val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                    banner.setBounds(0, 0, 960, 540); banner.draw(Canvas(bitmap)); File(context.getExternalFilesDir(null), "launcher-banner.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                leaveSettings(scenario); scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    private fun activateSetting(scenario: ActivityScenario<MainActivity>, label: String) {
        waitFor(scenario) { activity ->
            val panel = activity.window.decorView.findViewWithTag<View>("settings-panel")
            if (panel?.isShown != true) false else {
                val control = panel.findViewWithTag<View>("settings-nav:$label") ?: panel.findViewWithTag<View>("settings-option:$label")
                control?.let { it.requestFocus(); it.performClick(); true } ?: false
            }
        }
    }
    private fun leaveSettings(scenario: ActivityScenario<MainActivity>) {
        repeat(2) {
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
            android.util.Log.e("SpareFixture", "State=${player?.playbackState}; textDisabled=${player?.trackSelectionParameters?.disabledTrackTypes}; language=${player?.trackSelectionParameters?.preferredTextLanguages}; selectDefault=${player?.trackSelectionParameters?.selectTextByDefault}; text=${player?.currentTracks?.let { tracks -> CaptionTracks.choices(tracks).map { listOf(it.key, it.group.isTrackSelected(it.index)) } }}")
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

    private inner class FixtureServer(private val failCategories: Boolean = false, channelCount: Int = 1, private val tsMode: Boolean = false, private val fallbackHls: Boolean = false, private val slowPlaylist: Boolean = false, private val missingStatus: Int = 0, private val regionGroups: Boolean = false, private val stalled: Boolean = false, private val captionsMode: Boolean = false, private val embeddedCaptions: Int = 0, private val embeddedHls: Boolean = false, private val slowSportsGuide: Boolean = false, private val libraryCategories: Boolean = false, private val longVideo: Boolean = false) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        val requests = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val resumeStream = java.util.concurrent.CountDownLatch(1)
        private val video = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }
        private val transport = instrumentation.context.assets.open("no-aud.ts").use { it.readBytes() }
        private val avTransport by lazy { instrumentation.context.assets.open("stall-av.ts").use { it.readBytes() } }
        private val captionTransport by lazy { EmbeddedCaptionFixture.add(repeatTransport(avTransport, 8), embeddedCaptions == 708) }
        private val longTransport by lazy { repeatTransport(avTransport, 16) }
        private val streams = (1..channelCount).joinToString(",", "[", "]") { id ->
            """{"stream_id":$id,"name":"Xtream Test $id","category_id":"${if (regionGroups) listOf("uk", "us-news", "us-sports", "ca")[(id - 1) % 4] else "1"}","container_extension":"${if (fallbackHls || missingStatus != 0) "ts" else "mp4"}","epg_channel_id":"test1","tv_archive":1,"tv_archive_duration":7}"""
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
        val actionCatalogStarted = java.util.concurrent.CountDownLatch(1)
        val finishActionCatalog = java.util.concurrent.CountDownLatch(1)
        private val actionMovies = (1..400).joinToString(",", "[", "]") { """{"stream_id":${2000+it},"name":"Action title $it","category_id":"2","container_extension":"mp4"}""" }.toByteArray()
        val epgTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val catalogTargets = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val shortEpg: ByteArray
        init {
            if (embeddedCaptions != 0) captionTransport // Build fixtures before starting HTTP, so generation errors fail directly.
            val now = System.currentTimeMillis() / 1800000L * 1800000L
            shortEpg = """{"epg_listings":[{"title":"V29ybGQgTmV3cw==","description":"U3BhcmUgcHJvZ3JhbW1lIGd1aWRlIHRlc3Qu","start_timestamp":"${now / 1000}","stop_timestamp":${(now + 3600000) / 1000}}]}""".toByteArray()
            val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
            xml = ("<tv>" + (1..8).joinToString("") { channel -> (0..6).joinToString("") { slot ->
                "<programme channel=\"test$channel\" start=\"${format.format(Instant.ofEpochMilli(now + slot * 1800000L))}\" stop=\"${format.format(Instant.ofEpochMilli(now + (slot + 1) * 1800000L))}\"><title>${listOf("World News", "Live Report", "Evening Update")[slot % 3]}</title><desc>Spare playback and guide test fixture.</desc></programme>"
            } } + "</tv>").toByteArray()
            worker.execute {
                while (!socket.isClosed) runCatching {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        for (i in 0 until 100) { if (reader.readLine().isNullOrEmpty()) break }
                        val target = request.split(' ').getOrNull(1).orEmpty()
                        val path = target.substringBefore('?'); requests.merge(path, 1, Int::plus)
                        if (target.contains("get_vod_streams")) catalogTargets.add(target)
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
                                    libraryCategories && target.contains("category_id=2") -> actionMovies
                                    libraryCategories && target.contains("category_id=3") -> """[{"stream_id":3001,"name":"Comedy title","category_id":"3","container_extension":"mp4"}]""".toByteArray()
                                    else -> """[{"stream_id":101,"name":"Fixture movie","category_id":"1","container_extension":"mp4","stream_icon":"$base/logo.png","year":"2025"}]""".toByteArray()
                                }
                                else if (target.contains("get_vod_info")) """{"info":{"plot":"A provider-supplied film description.","cover_big":"$base/logo.png","releasedate":"2025-01-01","duration":"01:30:00","genre":"Drama"}}""".toByteArray()
                                else if (target.contains("get_series_info")) """{"episodes":{"1":[{"id":201,"title":"Pilot","episode_num":1,"container_extension":"mp4"},{"id":202,"title":"Second episode","episode_num":2,"container_extension":"mp4"}]}}""".toByteArray()
                                else if (target.contains("get_series")) """[{"series_id":1,"name":"Fixture series","category_id":"1"}]""".toByteArray()
                                else if (target.contains("get_simple_data_table")) { val time = System.currentTimeMillis(); """{"epg_listings":[{"title":"Archived fixture","start_timestamp":${(time - 7200000) / 1000},"stop_timestamp":${(time - 3600000) / 1000}}]}""".toByteArray() }
                                else if (target.contains("get_short_epg")) sportsPayload ?: shortEpg else if (target.contains("get_live_categories")) (if (regionGroups) """[{"category_id":"us-news","category_name":"USA | News"},{"category_id":"ca","category_name":"CA | News"},{"category_id":"uk","category_name":"UK | News"},{"category_id":"us-sports","category_name":"US | MLB"}]""" else """[{"category_id":"1","category_name":"News"}]""").toByteArray() else streams) to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            "/live/test/test-secret/1.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n$base/segment.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            else -> if (embeddedHls && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CLOSED-CAPTIONS=NONE\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (embeddedCaptions != 0 && path.endsWith(".ts")) captionTransport to "video/mp2t" else if (captionsMode && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$base/captions.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES=\"subs\"\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (longVideo && path.endsWith(".ts")) longTransport to "video/mp2t" else if (stalled && path == "/channel1.ts") longTransport to "video/mp2t" else if (path.endsWith(".ts")) transport to "video/mp2t" else video to "video/mp4"
                        }
                        val output = connection.getOutputStream()
                        val status = if (missingStatus != 0 && path in setOf("/live/test/test-secret/2.ts", "/live/test/test-secret/2.m3u8")) "$missingStatus Missing" else if ((failCategories && target.contains("get_live_categories")) || (fallbackHls && path == "/live/test/test-secret/1.ts")) "404 Not Found" else "200 OK"
                        val length = if (stalled && path == "/channel1.ts") "" else "Content-Length: ${body.size}\r\n"
                        output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\n${length}Connection: close\r\n\r\n".toByteArray())
                        if (stalled && path == "/channel1.ts") {
                            val split = avTransport.size * 2
                            output.write(body, 0, split); output.flush()
                            resumeStream.await(14, java.util.concurrent.TimeUnit.SECONDS)
                            output.write(body, split, body.size - split); output.flush()
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
            }
        }
        override fun close() { finishActionCatalog.countDown(); finishSportsGuide.countDown(); resumeStream.countDown(); socket.close(); worker.shutdownNow() }
    }
}

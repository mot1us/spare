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

    @Before fun resetGuideStyle() { store.smallPreview = false; store.theme = "Monochrome"; store.captions = false; store.followedTeams = emptySet() }

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
                waitFor(scenario) { store.cached(source)?.categoryOrder == expected }
                scenario.onActivity { activity ->
                    val categories = activity.window.decorView.findViewWithTag<RecyclerView>("guide-categories")
                    expected.forEachIndexed { index, name -> assertEquals(name, (categories.findViewHolderForAdapterPosition(index + 3)!!.itemView as android.widget.TextView).text.toString()) }
                }
                scenario.recreate()
                waitFor(scenario) { store.cached(source)?.categoryOrder == expected && channelList(it)?.adapter?.itemCount == 25001 }
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
                repeat(3) { device.pressDPadDown() }; device.pressDPadCenter()
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
        FixtureServer().use { server ->
            val source = Source(name = "Filters Test", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            val profile = GuideFilters(1080, FrameRateFilter.onlySixty, setOf("News")); store.setFilters(source, profile)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { device.hasObject(By.textContains("No verified channels match")) }
                scenario.onActivity { assertEquals(0, channelList(it)!!.adapter!!.itemCount) }
                assertTrue(store.videoInfo(source).isEmpty())
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Show unverified channels|Use guide filters"))), 5000))
                device.findObject(By.text(java.util.regex.Pattern.compile("Show unverified channels|Use guide filters"))).click()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                val observed = store.videoInfo(source).values.first(); assertTrue(observed.width > 0); assertTrue(observed.fps > 0)
                assertEquals(profile, store.filters(source))
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Show unverified channels|Use guide filters"))), 5000))
                device.findObject(By.text(java.util.regex.Pattern.compile("Show unverified channels|Use guide filters"))).click()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 0 }
                assertEquals(profile, store.filters(source))
                scenario.recreate()
                waitFor(scenario) { device.hasObject(By.textContains("No verified channels match")) }
                assertEquals(profile, store.filters(source)); assertFalse(store.videoInfo(source).isEmpty())
            }
            store.saveSources(emptyList()); store.removeCache(source)
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
                    assertEquals("USA | News", (categories.findViewHolderForAdapterPosition(3)!!.itemView as android.widget.TextView).text.toString())
                    assertEquals("US | MLB", (categories.findViewHolderForAdapterPosition(4)!!.itemView as android.widget.TextView).text.toString())
                    val toolbar = activity.window.decorView.findViewWithTag<View>("guide-toolbar")
                    val a = IntArray(2); val b = IntArray(2); toolbar.getLocationOnScreen(a); categories.getLocationOnScreen(b)
                    assertTrue(a[1] + toolbar.height <= b[1]); assertEquals(2, (toolbar as ViewGroup).childCount)
                    assertEquals(activity.window.decorView.width, find<PlayerView>(activity.window.decorView)!!.width)
                    activity.window.decorView.findViewWithTag<View>("settings").requestFocus()
                }
                device.pressDPadCenter(); assertTrue(device.wait(Until.hasObject(By.text("Guide video")), 5000))
                device.findObject(By.text("Guide video")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Small live preview")), 5000))
                device.findObject(By.text("Small live preview")).click()
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
                    scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; it.window.decorView.findViewWithTag<View>("search").requestFocus() }; device.pressDPadCenter()
                    waitFor(scenario) { activity -> activity.window.decorView.findViewWithTag<EditText>("guide-search-input")?.text.toString() == "Channel 2" && channelList(activity)?.adapter?.itemCount == 1 }
                    assertEquals("Native speech must not launch a chooser/activity", 0, monitor.hits)
                    scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); assertEquals(1f, find<PlayerView>(it.window.decorView)!!.player!!.volume, 0f) }
                    assertEquals("/channel1.mp4", playingPath(scenario))
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("guide-search-voice").requestFocus() }; device.pressDPadCenter()
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
                repeat(12) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { (channelList(it)?.findFocus() as? GuideAdapter.Row)?.channel?.name == "Test Channel 8" }
                device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels.last().id }
                scenario.onActivity { assertTrue(channelList(it)!!.isShown) }
                var attempt = ""
                scenario.onActivity { attempt = find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId; it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000)); device.findObject(By.text("Theme")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Ocean")), 5000)); device.findObject(By.text("Ocean")).click()
                waitFor(scenario) { store.theme == "Ocean" && GuideTheme.current.name == "Ocean" }
                scenario.onActivity { assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId); it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Categories")), 5000)); device.findObject(By.text("Categories")).click()
                assertTrue(device.wait(Until.hasObject(By.text("USA & Canada")), 5000)); device.findObject(By.text("USA & Canada")).click()
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
                assertTrue(device.wait(Until.hasObject(By.text("Subtitles")), 5000)); device.findObject(By.text("Subtitles")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Automatic")), 5000)); device.findObject(By.text("Automatic")).click()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.any { cue -> cue.text.toString().contains("Spare test captions") } == true }
                assertEquals(CaptionTracks.AUTO, store.channelCaptions(source, store.cached(source)!!.channels.first()))
                waitFor(scenario) { store.videoInfo(source).values.any { it.fps > 0 } }
                scenario.onActivity { assertFalse(store.videoInfo(source).values.first().label().contains("unavailable")) }
                // Automatic above is a channel override; enable the app default separately for the next channel.
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Subtitles")), 5000)); device.findObject(By.text("Subtitles")).click()
                assertTrue(device.wait(Until.hasObject(By.text("App default")), 5000)); device.findObject(By.text("App default")).click(); device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Default subtitles · all channels")), 5000)); device.findObject(By.text("Automatic")).click()
                waitFor(scenario) { store.captions }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadDown(); device.pressDPadCenter()
                waitFor(scenario) { store.last(source) == (channelList(it)!!.adapter as GuideAdapter).channels[1].id }
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.currentCues?.cues?.isNotEmpty() == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("settings").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Subtitles")), 5000)); device.findObject(By.text("Subtitles")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Off")), 5000)); device.findObject(By.text("Off")).click()
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
                repeat(31) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                var first = -1
                scenario.onActivity { first = (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() }
                device.pressDPadLeft()
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search").hasFocus() }
                device.pressDPadRight(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("settings").hasFocus() }
                device.pressDPadDown(); waitFor(scenario) { device.hasObject(By.text("Category 29").focused(true)) }
                scenario.onActivity {
                    assertEquals(first, (it.window.decorView.findViewWithTag<RecyclerView>("guide-categories").layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition())
                    assertEquals(attempt, find<PlayerView>(it.window.decorView)!!.player!!.currentMediaItem!!.mediaId)
                }
                device.pressDPadLeft(); device.pressDPadRight(); device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Playlists")), 5000)); device.pressBack()
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
        FixtureServer().use { server ->
            val source = Source(name = "Sports fixture", kind = "m3u", url = "${server.base}/playlist.m3u", allowHttp = true)
            val channels = (1..4).map { Channel("sports:$it", listOf("News", "ESPN", "YES", "CBS")[it - 1], if (it == 1) "News" else "Sports", "${server.base}/channel$it.mp4") }
            val now = System.currentTimeMillis()
            val programmes = mapOf(
                "sports:2" to listOf(Programme("sports:2", "Yankees vs Red Sox", now - 300000, now + 3600000), Programme("sports:2", "Yankees vs Red Sox", now + 5 * 3600000, now + 8 * 3600000)),
                "sports:3" to listOf(Programme("sports:3", "MLB: NYY @ BOS", now - 300000, now + 3600000)),
                "sports:4" to listOf(Programme("sports:4", "NFL: Bills at Chiefs", now + 1800000, now + 4 * 3600000)),
            )
            store.saveSources(listOf(source)); store.active = source.id; store.smallPreview = true
            store.followedTeams = setOf("mlb:NYY"); store.toggleFavorite(source, channels[2])
            store.cache(source, Guide(channels, programmes)); store.markPlaylistRefreshed(source)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                var samePlayer: Player? = null
                scenario.onActivity { samePlayer = find<PlayerView>(it.window.decorView)!!.player }
                device.pressBack(); waitFor(scenario) { channelList(it)?.hasFocus() == true }; device.pressDPadLeft()
                repeat(2) { device.pressDPadDown() }; device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games")?.let { r -> r.isShown && r.adapter?.itemCount == 2 && r.hasFocus() } == true }
                instrumentation.waitForIdleSync(); device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "sports.png"))
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("★ YES · Quality unverified")), 5000))
                device.findObject(By.text("★ YES · Quality unverified")).click()
                waitFor(scenario) { store.last(source) == "sports:3" }
                assertEquals("/channel3.mp4", playingPath(scenario))
                scenario.onActivity {
                    assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player)
                    assertTrue(it.window.decorView.findViewWithTag<View>("sports-panel").isShown)
                    assertTrue(find<PlayerView>(it.window.decorView)!!.width < it.window.decorView.width / 2)
                    it.window.decorView.findViewWithTag<View>("sports-scope").requestFocus()
                }
                device.pressDPadCenter()
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").adapter?.itemCount == 3 }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-teams").requestFocus() }; device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("NFL · Football")), 5000)); device.findObject(By.text("NFL · Football")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Buffalo Bills")), 5000)); device.findObject(By.text("Buffalo Bills")).click()
                device.findObject(By.text("Save")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Save").focused(true)), 5000)); device.pressDPadCenter()
                waitFor(scenario) { SecureStore(context).followedTeams == setOf("mlb:NYY", "nfl:BUF") }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("sports-teams").requestFocus() }
                device.pressDPadDown(); waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                repeat(6) { device.pressDPadDown(); instrumentation.waitForIdleSync() }
                waitFor(scenario) { it.window.decorView.findViewWithTag<RecyclerView>("sports-games").hasFocus() }
                device.pressDPadLeft(); waitFor(scenario) { device.hasObject(By.text("Sports").focused(true)) }
                device.pressDPadLeft(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("search").hasFocus() }
                device.pressDPadRight(); device.pressDPadDown(); waitFor(scenario) { device.hasObject(By.text("Sports").focused(true)) }
                device.pressDPadRight(); holdBack()
                waitFor(scenario) { !it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                device.pressBack(); waitFor(scenario) { it.window.decorView.findViewWithTag<View>("sports-panel").isShown }
                scenario.onActivity { assertSame(samePlayer, find<PlayerView>(it.window.decorView)!!.player) }
                scenario.recreate()
                waitFor(scenario) { find<PlayerView>(it.window.decorView)?.player?.let { p -> p.repeatMode = Player.REPEAT_MODE_ONE; p.isPlaying } == true }
                assertEquals(setOf("mlb:NYY", "nfl:BUF"), SecureStore(context).followedTeams)
            }
            store.saveSources(emptyList()); store.removeCache(source); store.followedTeams = emptySet()
        }
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

    private inner class FixtureServer(private val failCategories: Boolean = false, channelCount: Int = 1, private val tsMode: Boolean = false, private val fallbackHls: Boolean = false, private val slowPlaylist: Boolean = false, private val missingStatus: Int = 0, private val regionGroups: Boolean = false, private val stalled: Boolean = false, private val captionsMode: Boolean = false, private val embeddedCaptions: Int = 0, private val embeddedHls: Boolean = false) : AutoCloseable {
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
            """{"stream_id":$id,"name":"Xtream Test $id","category_id":"${if (regionGroups) listOf("uk", "us-news", "us-sports", "ca")[(id - 1) % 4] else "1"}","container_extension":"${if (fallbackHls || missingStatus != 0) "ts" else "mp4"}","epg_channel_id":"test1"}"""
        }.toByteArray()
        private val logo = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("S", 22f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        private val playlist = ("#EXTM3U url-tvg=\"$base/guide.xml\"\n" + (1..8).joinToString("\n") {
            "#EXTINF:-1 tvg-id=\"test$it\" tvg-logo=\"$base/logo.png\" group-title=\"${if (regionGroups) listOf("UK | News", "USA | News", "US | MLB", "CA | News")[(it - 1) % 4] else if (it <= 4) "News" else "Culture"}\",Test Channel $it\n$base/channel$it.${if (captionsMode || embeddedHls) "m3u8" else if (tsMode || stalled || embeddedCaptions != 0) "ts" else "mp4"}"
        }).toByteArray()
        private val xml: ByteArray
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
                        val (body, mime) = when (path) {
                            "/playlist.m3u" -> playlist to "application/x-mpegURL"
                            "/guide.xml" -> xml to "application/xml"
                            "/logo.png" -> logo to "image/png"
                            "/av.ts" -> (if (embeddedCaptions != 0) captionTransport else avTransport) to "video/mp2t"
                            "/media.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:${if (embeddedCaptions != 0) 20 else 3}\n#EXTINF:${if (embeddedCaptions != 0) 20 else 2.5},\n$base/av.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.5,\n$base/captions.vtt\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            "/captions.vtt" -> "WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nSpare test captions\n".toByteArray() to "text/vtt"
                            "/player_api.php" -> (if (target.contains("get_short_epg")) shortEpg else if (target.contains("get_live_categories")) (if (regionGroups) """[{"category_id":"us-news","category_name":"USA | News"},{"category_id":"ca","category_name":"CA | News"},{"category_id":"uk","category_name":"UK | News"},{"category_id":"us-sports","category_name":"US | MLB"}]""" else """[{"category_id":"1","category_name":"News"}]""").toByteArray() else streams) to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            "/live/test/test-secret/1.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n$base/segment.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            else -> if (embeddedHls && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CLOSED-CAPTIONS=NONE\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (embeddedCaptions != 0 && path.endsWith(".ts")) captionTransport to "video/mp2t" else if (captionsMode && path.endsWith(".m3u8")) "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"$base/captions.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES=\"subs\"\n$base/media.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl" else if (stalled && path == "/channel1.ts") longTransport to "video/mp2t" else if (path.endsWith(".ts")) transport to "video/mp2t" else video to "video/mp4"
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
                        } else if (slowPlaylist && path == "/playlist.m3u") {
                            for (chunk in body.asList().chunked(200)) { output.write(chunk.toByteArray()); output.flush(); Thread.sleep(200) }
                        } else { output.write(body); output.flush() }
                    }
                }
            }
        }
        override fun close() { resumeStream.countDown(); socket.close(); worker.shutdownNow() }
    }
}

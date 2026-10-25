package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.AlertDialog
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
                waitFor(scenario) { channelList(it)?.isShown == false }
                assertEquals("/channel2.mp4", playingPath(scenario))
                device.pressDPadUp(); instrumentation.waitForIdleSync()
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressDPadCenter()
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
                device.pressBack()
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
        FixtureServer(channelCount = 25001).use { server ->
            val source = Source(name = "Xtream Test", kind = "xtream", url = "${server.base}/", username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true
                }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { activity -> assertEquals(25001, channelList(activity)!!.adapter!!.itemCount) }
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
                assertTrue(device.wait(Until.hasObject(By.text("My guide").focused(true)), 5000))
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
            val profile = GuideFilters(1080, true, setOf("News")); store.setFilters(source, profile)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { device.hasObject(By.textContains("No verified channels match")) }
                scenario.onActivity { assertEquals(0, channelList(it)!!.adapter!!.itemCount) }
                assertTrue(store.videoInfo(source).isEmpty())
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("browse-all").requestFocus() }; device.pressDPadCenter()
                waitFor(scenario) { channelList(it)?.adapter?.itemCount == 8 }
                scenario.onActivity { channelList(it)!!.getChildAt(0).requestFocus() }
                device.pressDPadCenter()
                waitFor(scenario) { store.videoInfo(source).isNotEmpty() }
                val observed = store.videoInfo(source).values.first(); assertTrue(observed.width > 0); assertTrue(observed.fps > 0)
                assertEquals(profile, store.filters(source))
                device.pressBack(); waitFor(scenario) { channelList(it)?.isShown == true }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("browse-all").requestFocus() }; device.pressDPadCenter()
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

    private inner class FixtureServer(private val failCategories: Boolean = false, channelCount: Int = 1, private val tsMode: Boolean = false, private val fallbackHls: Boolean = false, private val slowPlaylist: Boolean = false) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        private val video = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }
        private val transport = instrumentation.context.assets.open("no-aud.ts").use { it.readBytes() }
        private val streams = (1..channelCount).joinToString(",", "[", "]") { id ->
            """{"stream_id":$id,"name":"Xtream Test $id","category_id":"1","container_extension":"${if (fallbackHls) "ts" else "mp4"}","epg_channel_id":"test1"}"""
        }.toByteArray()
        private val logo = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("S", 22f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        private val playlist = ("#EXTM3U url-tvg=\"$base/guide.xml\"\n" + (1..8).joinToString("\n") {
            "#EXTINF:-1 tvg-id=\"test$it\" tvg-logo=\"$base/logo.png\" group-title=\"${if (it <= 4) "News" else "Culture"}\",Test Channel $it\n$base/channel$it.${if (tsMode) "ts" else "mp4"}"
        }).toByteArray()
        private val xml: ByteArray
        init {
            val now = System.currentTimeMillis() / 1800000L * 1800000L
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
                        val path = target.substringBefore('?')
                        val (body, mime) = when (path) {
                            "/playlist.m3u" -> playlist to "application/x-mpegURL"
                            "/guide.xml" -> xml to "application/xml"
                            "/logo.png" -> logo to "image/png"
                            "/player_api.php" -> (if (target.contains("get_live_categories")) """[{"category_id":"1","category_name":"News"}]""".toByteArray() else streams) to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            "/live/test/test-secret/1.m3u8" -> "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n$base/segment.ts\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl"
                            else -> if (path.endsWith(".ts")) transport to "video/mp2t" else video to "video/mp4"
                        }
                        val output = connection.getOutputStream()
                        val status = if ((failCategories && target.contains("get_live_categories")) || (fallbackHls && path == "/live/test/test-secret/1.ts")) "404 Not Found" else "200 OK"
                        output.write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (slowPlaylist && path == "/playlist.m3u") {
                            for (chunk in body.asList().chunked(200)) { output.write(chunk.toByteArray()); output.flush(); Thread.sleep(200) }
                        } else { output.write(body); output.flush() }
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.shutdownNow() }
    }
}

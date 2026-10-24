package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
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
                device.pressDPadCenter()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.let { list -> list.isShown && list.hasFocus() } == true }
                assertTrue(device.wait(Until.hasObject(By.textContains("World News")), 5000))
                scenario.onActivity { activity ->
                    val video = find<PlayerView>(activity.window.decorView)!!
                    assertTrue("Guide preview must be smaller than fullscreen", video.width < activity.window.decorView.width / 2)
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-all.png"))
                device.pressDPadDown(); instrumentation.waitForIdleSync()
                assertEquals("Browsing must not change streams", "/channel1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.isShown == false }
                assertEquals("/channel2.mp4", playingPath(scenario))
                device.pressDPadUp(); instrumentation.waitForIdleSync()
                assertEquals("/channel1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.isShown == true }
                device.executeShellCommand("input keyevent --longpress 23")
                instrumentation.waitForIdleSync()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.isShown == true && store.favorites(source).size == 1 }
                assertEquals(1, store.favorites(source).size)
                device.findObject(By.text("Favorites")).click()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.adapter?.itemCount == 1 }
                scenario.onActivity { find<RecyclerView>(it.window.decorView)!!.getChildAt(0).requestFocus() }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide.png"))
                device.pressBack()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.isShown == false }
                scenario.recreate()
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY } == true
                }
                assertEquals("/channel1.mp4", playingPath(scenario))
            }
            store.saveSources(emptyList()); store.removeCache(source)
        }
    }

    @Test fun xtreamLoadsLiveCategoriesChannelsAndVideo() {
        FixtureServer().use { server ->
            val source = Source(name = "Xtream Test", kind = "xtream", url = server.base, username = "test", password = "test-secret", allowHttp = true)
            store.saveSources(listOf(source)); store.active = source.id
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { activity ->
                    find<PlayerView>(activity.window.decorView)?.player?.let { it.repeatMode = Player.REPEAT_MODE_ONE; it.playbackState == Player.STATE_READY && it.videoSize.width > 0 } == true
                }
                assertEquals("/live/test/test-secret/1.mp4", playingPath(scenario))
                device.pressDPadCenter()
                waitFor(scenario) { find<RecyclerView>(it.window.decorView)?.isShown == true }
                scenario.onActivity { activity -> assertEquals(1, find<RecyclerView>(activity.window.decorView)!!.adapter!!.itemCount) }
            }
            store.saveSources(emptyList()); store.removeCache(source)
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

    private inner class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        private val worker = Executors.newSingleThreadExecutor()
        private val video = instrumentation.context.assets.open("sample.mp4").use { it.readBytes() }
        private val logo = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawText("S", 22f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f })
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        private val playlist = ("#EXTM3U url-tvg=\"$base/guide.xml\"\n" + (1..8).joinToString("\n") {
            "#EXTINF:-1 tvg-id=\"test$it\" tvg-logo=\"$base/logo.png\" group-title=\"${if (it <= 4) "News" else "Culture"}\",Test Channel $it\n$base/channel$it.mp4"
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
                            "/player_api.php" -> (if (target.contains("get_live_categories")) """[{"category_id":"1","category_name":"News"}]""" else """[{"stream_id":1,"name":"Xtream Test","category_id":"1","container_extension":"mp4","epg_channel_id":"test1"}]""").toByteArray() to "application/json"
                            "/xmltv.php" -> xml to "application/xml"
                            else -> video to "video/mp4"
                        }
                        val output = connection.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body); output.flush()
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.shutdownNow() }
    }
}

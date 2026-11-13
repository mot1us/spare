package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class LogoLoaderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun png() = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
    }.toByteArray()

    private fun withActivity(test: (ActivityScenario<MainActivity>) -> Unit) {
        val store = SecureStore(context); val sources = store.sources(); val active = store.active
        store.saveSources(emptyList())
        try { ActivityScenario.launch(MainActivity::class.java).use(test) }
        finally { store.saveSources(sources); store.active = active }
    }
    private fun await(message: String, timeout: Long = 5000, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(30)
        }
        assertTrue(message, condition())
    }
    private fun hasImage(image: ImageView): Boolean {
        var ready = false
        instrumentation.runOnMainSync { ready = image.drawable != null }
        return ready
    }

    @Test fun visibleFailedLogoRetriesWithoutRebindingAndPauseCancelsRetry() = withActivity { scenario ->
        val bytes = png(); val attempts = AtomicInteger()
        val loader = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
                if (attempts.incrementAndGet() == 1) throw IOException("Synthetic temporary CDN failure")
                return bytes
            }
        })
        lateinit var image: ImageView
        try {
            scenario.onActivity { activity ->
                image = ImageView(activity)
                activity.addContentView(image, FrameLayout.LayoutParams(64, 64))
                loader.bind(image, "https://images.example/transient.png")
            }
            await("A visible failed logo must recover without another bind") { hasImage(image) }
            assertEquals("Exactly one automatic retry", 2, attempts.get())
        } finally { instrumentation.runOnMainSync { loader.close() } }

        val failures = AtomicInteger()
        val paused = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
                failures.incrementAndGet(); throw IOException("Synthetic CDN unavailable")
            }
        })
        val url = "https://images.example/paused.png"
        try {
            scenario.onActivity { paused.bind(image, url) }
            await("Initial failure completed") { paused.unavailable(url) }
            instrumentation.runOnMainSync { paused.pause(); paused.resume() }
            Thread.sleep(1800)
            assertEquals("Pausing drops scheduled retries until a new bind", 1, failures.get())
        } finally { instrumentation.runOnMainSync { paused.close() } }
    }

    @Test fun warmedPosterIsDecodedWhilePausedAndShownWithoutAnotherFetch() = withActivity { scenario ->
        val bytes = png(); val reads = AtomicInteger(); val url = "https://images.example/warm.png"
        val loader = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray { reads.incrementAndGet(); return bytes }
        })
        try {
            instrumentation.runOnMainSync { loader.pause() }
            loader.warm(url)
            await("A paused loader still warms the requested poster") { reads.get() == 1 && loader.get(url, false) {} != null }
            lateinit var image: ImageView
            scenario.onActivity { activity ->
                loader.resume(); image = ImageView(activity); activity.addContentView(image, FrameLayout.LayoutParams(64, 64))
                loader.bind(image, url)
            }
            await("The warmed poster binds from memory") { hasImage(image) }
            assertEquals("Opening Library must not fetch the warmed poster again", 1, reads.get())
        } finally { instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun queueRejectedLogoWaitsUntilVisibleThenRetriesWithoutRebinding() = withActivity { scenario ->
        val bytes = png(); val running = CountDownLatch(2); val release = CountDownLatch(1)
        val targetReads = AtomicInteger(); val target = "https://images.example/queue-target.png"
        val loader = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
                if (url == target) targetReads.incrementAndGet()
                else { running.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return bytes
            }
        })
        lateinit var image: ImageView
        try {
            repeat(2) { loader.prefetch("https://images.example/running-$it.png") }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            repeat(48) { loader.prefetch("https://images.example/queued-$it.png") }
            scenario.onActivity { activity -> image = ImageView(activity); loader.bind(image, target) }
            release.countDown()
            Thread.sleep(1200)
            assertEquals("Detached images do not retry in the background", 0, targetReads.get())
            scenario.onActivity { it.addContentView(image, FrameLayout.LayoutParams(64, 64)) }
            await("A queue-rejected logo retries once it is attached and visible") { hasImage(image) }
            assertEquals(1, targetReads.get())
        } finally { release.countDown(); instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun corruptSportsArtworkIsReplacedAndInvalidResponsesAreNeverCached() {
        val files = ArtworkFiles(context); val bytes = png()
        val urls = (1..2).map { "https://images.example/corrupt-${System.nanoTime()}-$it.png" }
        val paths = urls.map { File(context.cacheDir, "sports-images/${Channel.stableId(it)}.img") }
        try {
            paths[0].writeBytes(bytes.copyOf(40))
            var reads = 0
            assertArrayEquals(bytes, files.bytes(urls[0]) { reads++; bytes })
            assertArrayEquals(bytes, files.bytes(urls[0]) { fail("Valid replacement must be reused"); bytes })
            assertEquals(1, reads)
            assertTrue(runCatching { files.bytes(urls[1]) { "Temporary CDN error".toByteArray() } }.isFailure)
            assertFalse("An invalid response must not poison the disk cache", paths[1].exists())
            assertArrayEquals(bytes, files.bytes(urls[1]) { bytes })
        } finally { paths.forEach { android.util.AtomicFile(it).delete() } }
    }
}

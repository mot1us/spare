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

    @Test fun postersFetchTheSmallTmdbRenditionAndDecodeAtTheRequestedTileSize() = withActivity { scenario ->
        // A 600x900 poster, as a provider or TMDB's w780 would send it.
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED); compress(Bitmap.CompressFormat.JPEG, 80, output); recycle() }
        }.toByteArray()
        val fetched = java.util.concurrent.CopyOnWriteArrayList<String>()
        val loader = LogoLoader(480, 8 * 1024 * 1024, object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray { fetched.add(url); return bytes }
        }, opaque = true, source = PosterUrls::sized)
        val tmdb = "https://image.tmdb.org/t/p/w780/synthetic.jpg"; val other = "https://images.example/t/p/w780/synthetic.jpg"
        fun bitmap(image: ImageView): Bitmap? { var value: Bitmap? = null; instrumentation.runOnMainSync { value = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }; return value }
        try {
            lateinit var tile: ImageView; lateinit var cover: ImageView; lateinit var plain: ImageView
            scenario.onActivity { activity ->
                tile = ImageView(activity); cover = ImageView(activity); plain = ImageView(activity)
                listOf(tile, cover, plain).forEach { activity.addContentView(it, FrameLayout.LayoutParams(64, 96)) }
                loader.bind(tile, tmdb, dimension = 256)
            }
            await("The tile-sized poster is decoded") { bitmap(tile) != null }
            assertEquals("TMDB artwork is requested and saved at w342, whatever size the provider listed", listOf("https://image.tmdb.org/t/p/w342/synthetic.jpg"), fetched.toList())
            bitmap(tile)!!.let { assertTrue("Decoded near the 256 px tile, not at 480: ${it.width}x${it.height}", maxOf(it.width, it.height) in 192..288); assertEquals(Bitmap.Config.RGB_565, it.config) }
            assertNotNull("The tile's copy can stand in for the details cover", loader.peek(tmdb, 256)); assertNull("No larger copy was decoded for it", loader.peek(tmdb))
            // The details cover asks for the loader's full size: a separate, larger bitmap of the same saved image.
            scenario.onActivity { loader.bind(cover, tmdb); loader.bind(plain, other, dimension = 256) }
            await("The larger copy and the other host's poster are decoded") { bitmap(cover) != null && bitmap(plain) != null }
            bitmap(cover)!!.let { assertTrue("${it.width}x${it.height}", maxOf(it.width, it.height) in 400..480) }
            assertNotSame(bitmap(tile), bitmap(cover))
            // The two loads run in parallel, so their order is not fixed.
            assertEquals("Other hosts are fetched exactly as supplied; the larger copy reads the same w342 address", setOf("https://image.tmdb.org/t/p/w342/synthetic.jpg", other), fetched.toSet())
            // Rebinding the same poster at the same size is served from memory.
            val reads = fetched.size
            scenario.onActivity { loader.bind(plain, tmdb, dimension = 256) }
            await("Bound from memory") { bitmap(plain) === bitmap(tile) }
            assertEquals(reads, fetched.size)
        } finally { instrumentation.runOnMainSync { loader.close() } }
    }

    @Test fun heldKeyScrollingDefersPostersUntilItStops() = withActivity { scenario ->
        val bytes = png(); val reads = AtomicInteger()
        val loader = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray { reads.incrementAndGet(); return bytes }
        })
        lateinit var image: ImageView
        try {
            scenario.onActivity { activity ->
                image = ImageView(activity); activity.addContentView(image, FrameLayout.LayoutParams(64, 64))
                loader.quiet(600); loader.bind(image, "https://images.example/held.png")
            }
            Thread.sleep(300)
            assertEquals("Nothing is fetched while the key is held", 0, reads.get()); assertFalse(hasImage(image))
            await("The visible poster loads once scrolling stops") { hasImage(image) }
            assertEquals(1, reads.get())
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

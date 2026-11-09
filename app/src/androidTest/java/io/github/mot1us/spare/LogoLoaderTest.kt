package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoLoaderTest {
    @Test fun trimEvictsDecodedCacheWithoutCancellingBindings() {
        val readyUrl = "https://fixture.invalid/ready.png"
        val pendingUrl = "https://fixture.invalid/pending.png"
        val pendingRead = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val pending = CountDownLatch(1)
        val reloaded = CountDownLatch(1)
        val png = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }.toByteArray()
        val loader = LogoLoader(files = object : ImageFiles {
            override fun bytes(url: String, fetch: () -> ByteArray): ByteArray {
                if (url == pendingUrl) {
                    pendingRead.countDown()
                    check(finishRead.await(5, SECONDS))
                }
                return png
            }
        })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var shown: ImageView
        lateinit var loading: ImageView
        try {
            instrumentation.runOnMainSync {
                shown = ImageView(context)
                loading = ImageView(context)
                loader.bind(shown, readyUrl) { if (it != null) ready.countDown() }
                loader.bind(loading, pendingUrl) { if (it != null) pending.countDown() }
            }
            assertTrue(ready.await(5, SECONDS))
            assertTrue(pendingRead.await(5, SECONDS))
            instrumentation.runOnMainSync {
                val bitmap = (shown.drawable as BitmapDrawable).bitmap
                assertSame(bitmap, loader.get(readyUrl, false) {})
                loader.trim()
                assertSame(bitmap, (shown.drawable as BitmapDrawable).bitmap)
                assertFalse(bitmap.isRecycled)
                assertNull("The next lookup needs to decode again", loader.get(readyUrl, false) { reloaded.countDown() })
            }
            finishRead.countDown()
            assertTrue(pending.await(5, SECONDS))
            assertTrue(reloaded.await(5, SECONDS))
            instrumentation.runOnMainSync {
                assertNotNull(loading.drawable)
                assertNotNull(loader.get(readyUrl, false) {})
            }
        } finally {
            finishRead.countDown()
            instrumentation.runOnMainSync { loader.close() }
        }
    }
}

package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.Executors

class LogoLoader(private val maxDimension: Int = 256, cacheBytes: Int = 4 * 1024 * 1024) {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val workers = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    fun get(url: String, http: Boolean, redraw: () -> Unit): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        if (!closed && url !in failed && pending.add(url)) workers.execute {
            val bitmap = runCatching {
                val bytes = Network.get(url, http, 2 * 1024 * 1024, timeoutMs = 5000)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= maxDimension }
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
            pending.remove(url)
            if (bitmap != null) cache.put(url, bitmap) else failed.add(url)
            if (!closed) handler.post { if (!closed) redraw() }
        }
        return null
    }
    fun close() { closed = true; workers.shutdownNow(); handler.removeCallbacksAndMessages(null); cache.evictAll() }
}

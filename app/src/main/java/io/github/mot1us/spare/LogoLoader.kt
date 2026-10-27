package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LogoLoader(private val maxDimension: Int = 256, cacheBytes: Int = 4 * 1024 * 1024, private val files: ArtworkFiles? = null) {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private class Binding(val url: String, val http: Boolean)
    private val bindings = WeakHashMap<ImageView, Binding>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val workers = Executors.newFixedThreadPool(2) as java.util.concurrent.ThreadPoolExecutor
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var generation = 0
    fun get(url: String, http: Boolean, redraw: () -> Unit): Bitmap? = request(url, http) { redraw() }

    /** Deliver the decoded bitmap to this binding, even if the memory cache evicts it.
     * A completed image never triggers another card's fetch or whole-rail redraw. */
    fun bind(view: ImageView, url: String, http: Boolean = false, loaded: ((Bitmap?) -> Unit)? = null) {
        val previous = bindings[view]
        if (previous?.url == url && previous.http == http) return
        val binding = Binding(url, http); bindings[view] = binding
        val target = WeakReference(view)
        fun apply(bitmap: Bitmap?) {
            val image = target.get() ?: return
            if (bindings[image] !== binding) return
            if ((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) image.setImageBitmap(bitmap)
            loaded?.invoke(bitmap)
            if (bitmap == null) bindings.remove(image)
        }
        val bitmap = request(url, http, ::apply)
        // Keep a pending binding so repeated score/layout updates do not subscribe again.
        if ((view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) view.setImageBitmap(bitmap)
        loaded?.invoke(bitmap)
    }

    private fun request(url: String, http: Boolean, redraw: (Bitmap?) -> Unit): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        if (closed || paused || !Network.allowed(url, http) || unavailable(url)) return null
        synchronized(pending) {
            pending[url]?.let { if (it.size < 32) it.add(redraw); return null }
            if (workers.queue.size >= 48) return null
            pending[url] = mutableListOf(redraw)
        }
        val token = generation
        workers.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if (closed || paused || token != generation) return@execute
            val bitmap = runCatching {
                val fetch = { Network.get(url, http, 2 * 1024 * 1024, timeoutMs = 4000) }
                val bytes = files?.bytes(url, fetch) ?: fetch()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                // Round sampling up: rounding down can decode an image larger than its
                // entire LRU budget and create an endless decode/evict/redraw cycle.
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while ((maxOf(bounds.outWidth, bounds.outHeight) + inSampleSize - 1) / inSampleSize > maxDimension) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            if (token == generation) {
                if (bitmap == null) failed[url] = System.currentTimeMillis() + 30000 else failed.remove(url)
                while (failed.size > 256) failed.keys.firstOrNull()?.let(failed::remove)
                val callbacks = synchronized(pending) { pending.remove(url).orEmpty() }
                handler.post { if (!closed && !paused && token == generation) callbacks.forEach { it(bitmap) } }
            }
        }
        return null
    }
    fun unavailable(url: String): Boolean { val until = failed[url] ?: return false; if (System.currentTimeMillis() < until) return true; failed.remove(url); return false }
    fun prefetch(url: String, http: Boolean = false) { get(url, http) {} }
    fun pause() { bindings.clear(); paused = true; ++generation; workers.queue.clear(); synchronized(pending) { pending.clear() }; handler.removeCallbacksAndMessages(null) }
    fun resume() { paused = false }
    fun close() { bindings.clear(); closed = true; workers.shutdownNow(); synchronized(pending) { pending.clear() }; handler.removeCallbacksAndMessages(null); cache.evictAll() }
}

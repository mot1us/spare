package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Posters pass [opaque] = true: RGB_565 halves memory (twice as many fit in the cache) and they never need mipmaps.
 * [source] maps the catalog's address to the one actually downloaded and saved (a smaller rendition of the same image).
 * Callers that draw the same image at different sizes pass a decode size below [maxDimension]; each size is cached on its own.
 */
class LogoLoader(private val maxDimension: Int = 256, cacheBytes: Int = 4 * 1024 * 1024, private val files: ImageFiles? = null, private val opaque: Boolean = false, threads: Int = 2,
    private val source: (String) -> String = { it }) {
    private fun size(dimension: Int) = if (dimension <= 0) maxDimension else dimension.coerceAtMost(maxDimension)
    private fun key(url: String, dimension: Int) = if (dimension == maxDimension) url else "$url\n$dimension"
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private class Binding(val url: String, val http: Boolean, val dimension: Int, var loaded: ((Bitmap?) -> Unit)?) {
        var deferred = false
        var waiting = false
        var complete = false
        var attempts = 0
        var retryAt = 0L
        var deadline = SystemClock.uptimeMillis() + 30000
        var attached: View.OnAttachStateChangeListener? = null
        var laidOut: View.OnLayoutChangeListener? = null
    }
    private val bindings = WeakHashMap<ImageView, Binding>()
    private class Failure(val until: Long, val attempts: Int)
    private val failed = ConcurrentHashMap<String, Failure>()
    private val workers = Executors.newFixedThreadPool(threads) as java.util.concurrent.ThreadPoolExecutor
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var generation = 0
    private var retryPosted = false
    private var retryWhen = 0L
    private val visibleBounds = android.graphics.Rect()
    private val retryImages = Runnable {
        retryPosted = false
        if (!closed && !paused) {
            val now = SystemClock.uptimeMillis()
            var checked = 0
            bindings.entries.toList().forEach { (view, binding) ->
                if (!binding.waiting && !binding.complete && bindings[view] === binding) {
                    if (now > binding.deadline) forget(view, binding)
                    else if (now >= binding.retryAt && visible(view) && (binding.deferred || checked < 4)) {
                        if (!binding.deferred) checked++
                        load(view, binding)
                    }
                }
            }
            scheduleRetries()
        }
    }
    fun get(url: String, http: Boolean, dimension: Int = 0, redraw: () -> Unit): Bitmap? = request(url, http, size(dimension), { redraw() })
    /** An already decoded copy at this size, without starting a load. */
    fun peek(url: String, dimension: Int = 0): Bitmap? = if (url.isBlank()) null else cache.get(key(url, size(dimension)))

    /** Deliver the decoded bitmap to this binding, even if the memory cache evicts it.
     * A completed image never triggers another card's fetch or whole-rail redraw. */
    fun bind(view: ImageView, url: String, http: Boolean = false, dimension: Int = 0, loaded: ((Bitmap?) -> Unit)? = null) {
        val previous = bindings[view]
        if (previous?.url == url && previous.http == http && previous.dimension == size(dimension)) return
        if (previous != null) forget(view, previous)
        if (closed || paused || url.isBlank() || !Network.allowed(url, http)) {
            view.setImageDrawable(null); loaded?.invoke(null); return
        }
        val binding = Binding(url, http, size(dimension), loaded); bindings[view] = binding
        binding.attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                binding.deadline = SystemClock.uptimeMillis() + 30000
                scheduleRetries()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }.also(view::addOnAttachStateChangeListener)
        // Attachment precedes the first layout: it can still have an empty visible
        // rectangle then. Wake again once layout makes the image eligible to retry.
        binding.laidOut = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleRetries() }
            .also(view::addOnLayoutChangeListener)
        load(view, binding, initial = true)
    }

    private fun load(view: ImageView, binding: Binding, initial: Boolean = false) {
        val target = WeakReference(view)
        fun apply(bitmap: Bitmap?) {
            val image = target.get() ?: return
            if (bindings[image] !== binding) return
            binding.waiting = false
            binding.complete = bitmap != null
            if (bitmap == null) { if (image.drawable != null) image.setImageDrawable(null) }
            else if ((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) image.setImageBitmap(bitmap)
            val loaded = binding.loaded
            if (bitmap != null) binding.loaded = null
            loaded?.invoke(bitmap)
            if (bindings[image] !== binding) return
            if (bitmap != null) {
                binding.attached?.let(image::removeOnAttachStateChangeListener); binding.attached = null
                binding.laidOut?.let(image::removeOnLayoutChangeListener); binding.laidOut = null
            } else if (binding.attempts >= 3 || SystemClock.uptimeMillis() >= binding.deadline) forget(image, binding)
            else {
                binding.retryAt = maxOf(SystemClock.uptimeMillis() + 1000, failed[binding.url]?.until ?: 0)
                scheduleRetries()
            }
        }
        // While a remote key is held the list is flying past: show placeholders and load what is on screen once it stops.
        if (SystemClock.uptimeMillis() < quietUntil && cache.get(key(binding.url, binding.dimension)) == null) {
            if (initial) { if (view.drawable != null) view.setImageDrawable(null); binding.loaded?.invoke(null) }
            binding.deferred = true; binding.retryAt = quietUntil; scheduleRetries(); return
        }
        binding.deferred = false
        val bitmap = request(binding.url, binding.http, binding.dimension, ::apply) { binding.waiting = true; binding.attempts++ }
        if (bitmap != null) apply(bitmap)
        else if (initial) {
            if (view.drawable != null) view.setImageDrawable(null)
            binding.loaded?.invoke(null)
        }
        // Queue pressure and a cooldown do not have a completion callback. Keep the
        // visible binding alive so it can join the bounded worker queue later.
        if (bitmap == null && !binding.waiting && bindings[view] === binding) {
            binding.retryAt = maxOf(SystemClock.uptimeMillis() + 1000, failed[binding.url]?.until ?: 0)
            scheduleRetries()
        }
    }

    private fun visible(view: ImageView) = view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(visibleBounds)
    private fun forget(view: ImageView, binding: Binding) {
        if (bindings[view] === binding) bindings.remove(view)
        binding.loaded = null
        binding.attached?.let(view::removeOnAttachStateChangeListener); binding.attached = null
        binding.laidOut?.let(view::removeOnLayoutChangeListener); binding.laidOut = null
    }
    private fun scheduleRetries() {
        if (closed || paused) return
        val now = SystemClock.uptimeMillis()
        val next = bindings.entries.asSequence().filter { !it.value.waiting && !it.value.complete }.minOfOrNull { (view, binding) ->
            // Hidden/detached views only get cleanup, never another network attempt.
            if (visible(view)) minOf(binding.retryAt, binding.deadline + 1) else binding.deadline + 1
        } ?: return
        val whenReady = maxOf(now + 60, next)
        if (retryPosted && retryWhen <= whenReady) return
        handler.removeCallbacks(retryImages)
        retryPosted = true
        retryWhen = whenReady
        handler.postDelayed(retryImages, whenReady - now)
    }

    private fun request(url: String, http: Boolean, dimension: Int, redraw: (Bitmap?) -> Unit, warm: Boolean = false, queued: () -> Unit = {}): Bitmap? {
        if (url.isBlank() || !Network.allowed(url, http)) return null
        val key = key(url, dimension)
        cache.get(key)?.let { return it }
        if (closed || (paused && !warm) || !Network.allowed(url, http) || unavailable(url)) return null
        synchronized(pending) {
            pending[key]?.let { if (it.size < 32) { it.add(redraw); queued() }; return null }
            if (workers.queue.size >= 48) return null
            pending[key] = mutableListOf(redraw); queued()
        }
        val token = generation
        workers.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if (closed || (paused && !warm) || token != generation) return@execute
            val bitmap = runCatching {
                val address = source(url).takeIf { it == url || Network.allowed(it, http) } ?: url
                val fetch = { Network.get(address, http, 2 * 1024 * 1024, timeoutMs = 4000) }
                val bytes = files?.bytes(address, fetch) ?: fetch()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                // Round sampling up: rounding down can decode an image larger than its
                // entire LRU budget and create an endless decode/evict/redraw cycle.
                val options = BitmapFactory.Options().apply {
                    if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 1
                    while ((maxOf(bounds.outWidth, bounds.outHeight) + inSampleSize - 1) / inSampleSize > dimension) inSampleSize *= 2
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                // Power-of-two sampling can land far below the target (700 px -> 44 px). Decode
                // one step larger and scale smoothly instead, so small logos stay crisp.
                val sized = if (decoded != null && options.inSampleSize > 1 && maxOf(decoded.width, decoded.height) < dimension * 3 / 4) {
                    val larger = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = options.inSampleSize / 2; if (opaque) inPreferredConfig = Bitmap.Config.RGB_565 })
                    if (larger == null) decoded
                    // Within an eighth of the target (a 342 px poster halved for a 256 px tile): a second scaling pass buys nothing.
                    else if (maxOf(larger.width, larger.height) <= dimension + dimension / 8) { decoded.recycle(); larger }
                    else {
                        decoded.recycle()
                        val scale = dimension.toFloat() / maxOf(larger.width, larger.height)
                        larger.scale(maxOf(1, (larger.width * scale).toInt()), maxOf(1, (larger.height * scale).toInt()), filter = true)
                            .also { if (it !== larger) larger.recycle() }
                    }
                } else decoded
                sized?.apply { if (!opaque) setHasMipMap(true) }
            }.getOrNull()
            if (bitmap != null && !closed) cache.put(key, bitmap)
            if (token == generation) {
                if (bitmap == null) {
                    val attempts = ((failed[url]?.attempts ?: 0) + 1).coerceAtMost(3)
                    val delay = when (attempts) { 1 -> 1500L; 2 -> 5000L; else -> 30000L }
                    failed[url] = Failure(SystemClock.uptimeMillis() + delay, attempts)
                } else failed.remove(url)
                while (failed.size > 256) failed.keys.firstOrNull()?.let(failed::remove)
                val callbacks = synchronized(pending) { pending.remove(key).orEmpty() }
                handler.post { if (!closed && !paused && token == generation) callbacks.forEach { it(bitmap) } }
            }
        }
        return null
    }
    fun unavailable(url: String): Boolean = failed[url]?.let { SystemClock.uptimeMillis() < it.until } ?: false
    fun prefetch(url: String, http: Boolean = false, dimension: Int = 0) { if (SystemClock.uptimeMillis() >= quietUntil) get(url, http, dimension) {} }
    @Volatile private var quietUntil = 0L
    /** Hold new poster loads for [millis]; called on every repeat of a held remote key. Posters already in memory still show at once. */
    fun quiet(millis: Long = 250) { quietUntil = SystemClock.uptimeMillis() + millis }
    /** Load into the caches while the panel is closed, so the next open shows posters at once. */
    fun warm(url: String, http: Boolean = false, dimension: Int = 0) { request(url, http, size(dimension), {}, warm = true) }
    private fun clearBindings() { bindings.entries.toList().forEach { (view, binding) -> forget(view, binding) } }
    fun trim() { cache.evictAll() }
    fun pause() { clearBindings(); paused = true; ++generation; workers.queue.clear(); synchronized(pending) { pending.clear() }; handler.removeCallbacksAndMessages(null); retryPosted = false }
    fun resume() { paused = false }
    fun close() { clearBindings(); closed = true; workers.shutdownNow(); synchronized(pending) { pending.clear() }; handler.removeCallbacksAndMessages(null); retryPosted = false; cache.evictAll() }
}

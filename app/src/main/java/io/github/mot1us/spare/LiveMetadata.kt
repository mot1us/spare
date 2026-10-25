package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** A short focus debounce avoids querying an addon while the user scrolls through channels. */
class LiveMetadata(private val store: SecureStore, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = linkedMapOf<String, OnDemand?>()
    private val pending = hashSetOf<String>()
    private var closed = false
    private var addon: String? = null
    private var last: String? = null
    private var task: Runnable? = null
    fun clear() { addon = null; cache.clear(); last = null }
    fun get(programme: Programme): OnDemand? {
        val key = "${programme.title}:${programme.start}"
        if (cache.containsKey(key)) return cache[key]
        if (last == key || key in pending) return null
        last = key; task?.let(handler::removeCallbacks)
        task = Runnable {
            if (closed) return@Runnable
            pending.add(key)
            worker.execute {
                val url = addon ?: runCatching { store.metadataAddon() }.getOrDefault("").also { addon = it }
                val result = if (url.isBlank()) null else runCatching { MetadataAddon.live(url, programme) }.getOrNull()
                handler.post { if (!closed) { pending.remove(key); cache[key] = result; while (cache.size > 100) cache.remove(cache.keys.first()); if (last == key && result != null) changed() } }
            }
        }.also { handler.postDelayed(it, 500) }
        return null
    }
    fun close() { closed = true; handler.removeCallbacksAndMessages(null); worker.shutdownNow() }
}

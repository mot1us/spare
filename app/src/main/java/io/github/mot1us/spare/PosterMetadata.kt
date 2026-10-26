package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/** Only visible missing posters request metadata. A bounded encrypted cache avoids repeating lookups. */
class PosterMetadata(private val store: SecureStore) {
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private val cache = linkedMapOf<String, OnDemand>()
    private val pending = mutableMapOf<String, MutableList<(String) -> Unit>>()
    @Volatile private var addon: String? = null
    private var loaded = false
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var savedEntries: JSONObject? = null
    private var persist: Runnable? = null
    private fun key(item: OnDemand) = "${item.kind}:${item.externalId.ifBlank { item.title }}:${item.year}"
    fun known(item: OnDemand): OnDemand = cache[key(item)]?.let { merge(item, it) } ?: item
    fun request(item: OnDemand, done: (String) -> Unit) {
        if (closed || item.kind !in setOf("movie", "series")) return
        val key = key(item); cache[key]?.let { done(it.poster); return }
        pending[key]?.let { it.add(done); return }
        if (workers.queue.size >= 8) return
        pending[key] = mutableListOf(done); val token = generation
        workers.execute {
            val url = addon ?: runCatching { store.metadataAddon() }.getOrDefault("").also { if (token == generation) addon = it }
            val identity = Channel.stableId(url)
            val saved = synchronized(this) {
                if (!loaded) {
                    loaded = true
                    val disk = runCatching { store.read("metadata-posters.enc")?.let { JSONObject(it.toString(Charsets.UTF_8)) } }.getOrNull()
                    savedEntries = disk?.takeIf { it.optString("addon") == identity }?.optJSONObject("entries")
                }
                savedEntries
            }
            val stored = saved?.optJSONObject(key)?.let { runCatching { OnDemand.from(it) }.getOrNull() }
            val result = stored ?: if (url.isBlank()) item.copy(poster = "") else runCatching { MetadataAddon.enrich(url, item) }.getOrDefault(item.copy(poster = ""))
            // The cache deliberately excludes provider URLs, source identifiers and credentials.
            val art = OnDemand("metadata", result.title, "", kind = item.kind, poster = result.poster, backdrop = result.backdrop, description = result.description, year = result.year, genre = result.genre, runtime = result.runtime, cast = result.cast, externalId = result.externalId)
            handler.post {
                if (!closed && token == generation) {
                    saved?.keys()?.asSequence()?.take(160)?.forEach { id -> runCatching { OnDemand.from(saved.getJSONObject(id)) }.getOrNull()?.let { cache.putIfAbsent(id, it) } }
                    cache[key] = art; while (cache.size > 160) cache.remove(cache.keys.first())
                    pending.remove(key)?.forEach { it(art.poster) }
                    if (url.isNotBlank() && art.poster.isNotBlank()) {
                        persist?.let(handler::removeCallbacks)
                        persist = Runnable {
                            val entries = cache.toMap()
                            if (!closed) workers.execute { if (token == generation && !closed) runCatching { store.write("metadata-posters.enc", JSONObject().put("addon", identity).put("entries", JSONObject(entries.mapValues { it.value.json() })).toString().toByteArray()) } }
                        }.also { handler.postDelayed(it, 500) }
                    }
                }
            }
        }
    }
    private fun merge(item: OnDemand, art: OnDemand) = item.copy(poster = art.poster.ifBlank { item.poster }, backdrop = art.backdrop.ifBlank { item.backdrop }, description = art.description.ifBlank { item.description }, externalId = art.externalId.ifBlank { item.externalId })
    fun clear() {
        ++generation; persist?.let(handler::removeCallbacks); workers.queue.clear(); pending.clear(); cache.clear(); addon = null
        synchronized(this) { loaded = true; savedEntries = null }
        workers.execute { runCatching { store.write("metadata-posters.enc", JSONObject().toString().toByteArray()) } }
    }
    fun close() { closed = true; ++generation; pending.clear(); workers.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}

package io.github.mot1us.spare

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/** Stable requests: score/layout updates do not restart a pending artwork fetch. */
class SportsArtwork(context: Context, private val ready: (String, String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) as java.util.concurrent.ThreadPoolExecutor
    private val store = SecureStore(context.applicationContext)
    private val cache = linkedMapOf<String, Triple<Long, String, String>>()
    private val pending = mutableSetOf<String>()
    private var closed = false
    private var paused = false
    private var disk: JSONObject? = null
    private var loaded = false
    fun request(game: SportsGame) {
        if (closed || paused) return
        val now = System.currentTimeMillis()
        cache[game.key]?.takeIf { now - it.first < if (it.second.isBlank()) 60000 else 24 * 3600000L }?.let { ready(game.key, it.second, it.third); return }
        val league = SportsLeagues.byId[game.leagueId] ?: return
        if (!game.eventId.matches(Regex("[0-9]{1,20}")) || !pending.add(game.key)) return
        if (worker.queue.size > 4) { pending.remove(game.key); return }
        worker.execute {
            val saved = synchronized(this) { if (!loaded) { loaded = true; disk = runCatching { store.read("sports-art.enc")?.let { JSONObject(it.toString(Charsets.UTF_8)) } }.getOrNull() }; disk?.optJSONObject(game.key) }
            val result = saved?.takeIf { now - it.optLong("at") in 0 until 24 * 3600000L && Network.allowed(it.optString("url"), false) }?.let { it.getString("url") to it.optString("credit") }
                ?: runCatching { parse(Network.get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/summary?event=${game.eventId}", limit = 2 * 1024 * 1024, timeoutMs = 2500).toString(Charsets.UTF_8), game.eventId) }.getOrDefault("" to "")
            if (result.first.isNotBlank()) synchronized(this) {
                val entries = disk ?: JSONObject().also { disk = it }; entries.put(game.key, JSONObject().put("at", now).put("url", result.first).put("credit", result.second))
                while (entries.length() > 64) entries.remove(entries.keys().next())
                runCatching { store.write("sports-art.enc", entries.toString().toByteArray()) }
            }
            handler.post { if (!closed) { pending.remove(game.key); cache[game.key] = Triple(now, result.first, result.second); while (cache.size > 64) cache.remove(cache.keys.first()); if (!paused) ready(game.key, result.first, result.second) } }
        }
    }
    fun prefetch(games: List<SportsGame>) { games.take(2).forEach(::request) }
    fun resume() { paused = false }
    fun pause() { paused = true }
    fun close() { closed = true; worker.shutdownNow(); handler.removeCallbacksAndMessages(null) }
    companion object {
        fun parse(text: String, eventId: String): Pair<String, String> {
            val article = JSONObject(text).optJSONObject("article") ?: return "" to ""
            if (article.optString("gameId") != eventId) return "" to ""
            val images = article.optJSONArray("images") ?: return "" to ""
            val best = (0 until minOf(images.length(), 12)).mapNotNull { images.optJSONObject(it) }
                .filter { it.optInt("width") >= 480 && Network.allowed(it.optString("url").take(4096), false) }.maxByOrNull { it.optInt("width") }
            return if (best != null) best.optString("url").take(4096) to best.optString("credit").take(120) else "" to ""
        }
    }
}

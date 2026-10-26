package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/** Only the focused event requests its own article artwork, never generic league news. */
class SportsArtwork(private val ready: (String, String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(1) as java.util.concurrent.ThreadPoolExecutor
    private val cache = linkedMapOf<String, Pair<String, String>>()
    private var epoch = 0
    private var closed = false
    private var paused = false
    private var task: Runnable? = null
    fun request(game: SportsGame) {
        if (closed || paused) return
        val token = ++epoch; task?.let(handler::removeCallbacks); worker.queue.clear()
        cache[game.key]?.let { ready(game.key, it.first, it.second); return }
        val league = SportsLeagues.byId[game.leagueId] ?: return
        if (!game.eventId.matches(Regex("[0-9]{1,20}"))) return
        task = Runnable { if (!closed && token == epoch) worker.execute {
            val result = runCatching { parse(Network.get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/summary?event=${game.eventId}", limit = 2 * 1024 * 1024, timeoutMs = 3500).toString(Charsets.UTF_8), game.eventId) }.getOrDefault("" to "")
            handler.post { if (!closed) { cache[game.key] = result; while (cache.size > 64) cache.remove(cache.keys.first()); if (token == epoch) ready(game.key, result.first, result.second) } }
        } }.also { handler.postDelayed(it, 350) }
    }
    fun resume() { paused = false }
    fun pause() { paused = true; ++epoch; worker.queue.clear(); task?.let(handler::removeCallbacks) }
    fun close() { closed = true; pause(); worker.shutdownNow() }
    companion object {
        fun parse(text: String, eventId: String): Pair<String, String> {
            val article = JSONObject(text).optJSONObject("article") ?: return "" to ""
            if (article.optString("gameId") != eventId) return "" to ""
            val images = article.optJSONArray("images") ?: return "" to ""
            val best = (0 until minOf(images.length(), 12)).mapNotNull { images.optJSONObject(it) }
                .filter { it.optInt("width") >= 480 && Network.allowed(it.optString("url").take(4096), false) }
                .maxByOrNull { it.optInt("width") }
            if (best != null) return best.optString("url").take(4096) to best.optString("credit").take(120)
            return "" to ""
        }
    }
}

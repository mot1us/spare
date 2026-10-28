package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.Executors

/** Date requests are debounced, bounded and independent of playlist/player work. */
class SportsCalendarLoader(private val store: SecureStore,
    private val load: (LocalDate, Set<String>) -> LiveSportsSnapshot,
    private val update: (LocalDate, LiveSportsSnapshot, Boolean, Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(1) { task -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "spare-calendar") } as java.util.concurrent.ThreadPoolExecutor
    private val cache = linkedMapOf<String, LiveSportsSnapshot>()
    private var task: java.util.concurrent.Future<*>? = null
    private var epoch = 0
    private var closed = false
    private var activeKey: String? = null
    private var running = false
    fun request(day: LocalDate, leagues: Set<String>, seed: LiveSportsSnapshot, force: Boolean = false) {
        val key = "$day:${leagues.sorted().joinToString(",")}"
        if (!force && activeKey == key && running) return
        cancel(); if (closed) return
        val token = epoch; activeKey = key; running = true
        val cached = cache[key]
        if (!force && cached != null && cached.fresh(System.currentTimeMillis()) && cached.leagues.containsAll(leagues)) { running = false; update(day, cached, false, false); refreshLater(day, leagues, cached, token); return }
        val initial = cached ?: seed
        update(day, initial, true, false)
        handler.postDelayed({
            if (closed || token != epoch) return@postDelayed
            task = worker.submit {
                val disk = if (cached == null) runCatching { store.read("sports-schedule-$day.enc")?.let { SportsSchedule.from(org.json.JSONObject(it.toString(Charsets.UTF_8))) } }.getOrNull() else null
                if (disk != null) handler.post { if (!closed && token == epoch) update(day, disk, true, false) }
                val fresh = runCatching { load(day, leagues) }.getOrDefault(LiveSportsSnapshot.EMPTY)
                handler.post {
                    if (closed || token != epoch) return@post
                    running = false
                    val available = fresh.leagues.isNotEmpty() || fresh.events.isNotEmpty()
                    val result = if (available) fresh else disk ?: initial
                    if (available) { cache[key] = fresh; while (cache.size > 8) cache.remove(cache.keys.first()) }
                    update(day, result, false, !fresh.leagues.containsAll(leagues))
                    refreshLater(day, leagues, result, token)
                }
            }
        }, 350)
    }
    private fun refreshLater(day: LocalDate, leagues: Set<String>, saved: LiveSportsSnapshot, token: Int) {
        if (day == LocalDate.now()) handler.postDelayed({ if (!closed && token == epoch) request(day, leagues, saved, true) }, 60000)
    }
    fun cancel() { ++epoch; activeKey = null; running = false; handler.removeCallbacksAndMessages(null); task?.cancel(true); worker.queue.clear() }
    fun close() { closed = true; cancel(); worker.shutdownNow(); cache.clear() }
}

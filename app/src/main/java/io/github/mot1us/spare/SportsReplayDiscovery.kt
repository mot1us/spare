package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Metadata-only archive checks. Call lifecycle/update methods on the main thread. */
class SportsReplayDiscovery internal constructor(
    private val onResult: (String, Int, SportsGame, List<SportsReplay>, Boolean) -> Unit,
    private val onChecking: (String?) -> Unit,
    private val post: ((() -> Unit) -> Unit)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val load: (Source, Channel, Int) -> List<Programme> = Catchup::load,
    private val budgetMs: Long = 3500,
) {
    private val handler = if (post == null) Handler(Looper.getMainLooper()) else null
    private fun main(block: () -> Unit) { if (post != null) post.invoke(block) else handler!!.post(block) }
    private fun pool(count: Int, name: String) = Executors.newFixedThreadPool(count) { task ->
        Thread(task, name).apply { priority = Thread.MIN_PRIORITY }
    } as ThreadPoolExecutor
    private val worker = pool(1, "spare-replay")
    private val metadata = pool(2, "spare-replay-metadata")
    private data class ArchiveKey(val source: String, val channel: String, val guide: String, val url: String)
    private data class History(val at: Long, val entries: List<Programme>)
    private data class Checked(val at: Long, val signature: String, val complete: Boolean, val found: Boolean, val progressed: Boolean, val seen: Set<String>)
    private data class Outcome(val replays: List<SportsReplay>, val complete: Boolean, val history: Map<ArchiveKey, History> = emptyMap(), val progressed: Boolean = false, val seen: Set<String> = emptySet())
    private data class Loaded(val channel: Channel, val history: History?)
    private val history = linkedMapOf<ArchiveKey, History>()
    private val checked = linkedMapOf<String, Checked>()
    private var source: Source? = null
    private var generation = 0
    private var lineup: List<Channel>? = null
    private var guide = Guide.EMPTY
    private var games = emptyList<SportsGame>()
    private var active = false
    private var closed = false
    private var epoch = 0
    private var task: Future<*>? = null
    private var running: SportsGame? = null
    var checkingKey: String? = null
        private set
    val checkedKeys: Set<String> get() {
        val at = now()
        return checked.filterValues { it.complete && at - it.at in 0 until HISTORY_MS }.keys.toSet()
    }

    fun update(source: Source?, generation: Int, guide: Guide, games: List<SportsGame>, focused: SportsGame?, active: Boolean) {
        if (closed) return
        // Guide programme refreshes reuse the lineup. A replaced lineup/source rejects
        // in-flight callbacks and old negative results before the next check starts.
        if (this.source != source || this.generation != generation || lineup !== guide.channels) {
            cancel(); history.clear(); checked.clear()
            this.source = source; this.generation = generation; lineup = guide.channels
            publishChecking(null, force = true)
        }
        if (this.guide.programmes !== guide.programmes) checked.entries.removeAll { !it.value.found }
        this.guide = guide
        this.games = targets(games, focused, now())
        this.active = active && source != null
        if (!this.active) { pause(); return }
        val priority = focused?.takeIf { candidate -> this.games.any { it.key == candidate.key } && due(candidate) }
        if (running?.let { current -> this.games.none { it.key == current.key } || priority != null && priority.key != current.key } == true) cancel()
        advance()
    }

    private fun signature(game: SportsGame) = "${game.start}:${game.teams.joinToString { it.id }}:${game.networks.joinToString()}"
    private fun due(game: SportsGame): Boolean {
        val previous = checked[game.key] ?: return true
        val age = now() - previous.at
        return previous.signature != signature(game) || age !in 0 until if (previous.complete) HISTORY_MS else if (previous.progressed) 0L else RETRY_MS
    }
    private fun publishChecking(key: String?, force: Boolean = false) {
        if (!force && checkingKey == key) return
        checkingKey = key
        val token = epoch
        main { if (!closed && token == epoch && checkingKey == key) onChecking(key) }
    }
    private fun advance() {
        if (!active || closed || running != null) return
        val selected = source ?: return
        val game = games.firstOrNull(::due) ?: run { publishChecking(null); return }
        val token = epoch; val sourceGeneration = generation; val snapshot = guide; val saved = history.toMap()
        val seen = checked[game.key]?.takeIf { it.signature == signature(game) && now() - it.at in 0 until HISTORY_MS }?.seen.orEmpty()
        running = game; publishChecking(game.key)
        task = worker.submit {
            val outcome = try { discover(selected, snapshot, game, saved, seen) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@submit }
            catch (_: Exception) { Outcome(emptyList(), false) }
            if (Thread.currentThread().isInterrupted) return@submit
            main {
                if (closed || !active || token != epoch || running?.key != game.key) return@main
                running = null; task = null
                history.putAll(outcome.history)
                while (history.size > 128 || history.values.sumOf { it.entries.size } > 16000) history.remove(history.keys.first())
                checked.remove(game.key)
                val freshGuide = this.guide.programmes === snapshot.programmes
                if (outcome.replays.isNotEmpty() || freshGuide) checked[game.key] = Checked(now(), signature(game), outcome.complete, outcome.replays.isNotEmpty(), outcome.progressed, outcome.seen)
                while (checked.size > 256) checked.remove(checked.keys.first())
                publishChecking(null)
                onResult(selected.id, sourceGeneration, game, outcome.replays, outcome.complete)
                advance()
            }
        }
    }

    private fun discover(source: Source, guide: Guide, game: SportsGame, saved: Map<ArchiveKey, History>, seen: Set<String>): Outcome {
        val at = now()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val local = SportsReplays.find(game, guide, at)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (local.isNotEmpty() || source.kind != "xtream") return Outcome(local, true)
        val candidates = SportsReplays.candidates(game, guide, emptySet(), at, limit = Importers.MAX_CHANNELS).filter { it.id.matches(XTREAM_ID) }
        val listings = linkedMapOf<Channel, List<Programme>>()
        val fresh = linkedMapOf<ArchiveKey, History>()
        val missing = candidates.filter { channel ->
            val cached = saved[key(source, channel)]?.takeIf { at - it.at in 0 until HISTORY_MS }
            if (cached != null) listings[channel] = cached.entries
            cached == null && channel.id !in seen
        }
        val cached = SportsReplays.findListings(game, guide, listings.mapKeys { it.key.id }, at)
        if (cached.isNotEmpty()) return Outcome(cached, true)
        var complete = missing.size <= 6
        val completion = ExecutorCompletionService<Loaded>(metadata)
        val jobs = mutableListOf<Future<Loaded>>()
        try {
            for (channel in missing.take(6)) jobs.add(completion.submit(java.util.concurrent.Callable {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0 || Thread.currentThread().isInterrupted) return@Callable Loaded(channel, null)
                val entries = runCatching { load(source, channel, minOf(1200L, left).toInt().coerceAtLeast(1)) }.getOrNull()
                Loaded(channel, entries?.let { History(now(), it.take(2000)) })
            }))
            var remaining = jobs.size
            while (remaining > 0) {
                val left = deadline - System.nanoTime()
                if (left <= 0) { complete = false; break }
                val result = completion.poll(left, TimeUnit.NANOSECONDS)?.get()
                if (result == null) { complete = false; break }
                remaining--
                if (result.history == null) complete = false else {
                    listings[result.channel] = result.history.entries
                    fresh[key(source, result.channel)] = result.history
                }
            }
        } finally { jobs.forEach { it.cancel(true) }; metadata.purge() }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        // Two variants sharing an XMLTV key can have different provider archives.
        // Match each response only against the exact channel that supplied it.
        val found = listings.flatMap { (channel, entries) ->
            SportsReplays.find(game, Guide(listOf(channel), mapOf(guide.key(channel) to entries)), at)
        }
        return Outcome(SportsReplays.rank(found), complete || found.isNotEmpty(), fresh,
            found.isEmpty() && missing.size > jobs.size && fresh.isNotEmpty(), seen + listings.keys.map { it.id })
    }
    fun remember(selected: Source, sourceGeneration: Int, snapshot: Guide, listings: Map<String, List<Programme>>) {
        if (closed || source != selected || generation != sourceGeneration || lineup !== snapshot.channels) return
        snapshot.channels.filter { listings.containsKey(it.id) }.forEach { channel -> history[key(selected, channel)] = History(now(), listings.getValue(channel.id).take(2000)) }
        while (history.size > 128 || history.values.sumOf { it.entries.size } > 16000) history.remove(history.keys.first())
        checked.entries.removeAll { !it.value.found }
    }
    fun cachedListings(selected: Source, sourceGeneration: Int): Map<String, List<Programme>> {
        if (closed || source != selected || generation != sourceGeneration) return emptyMap()
        val at = now()
        return history.filter { (key, value) -> key.source == selected.id && at - value.at in 0 until HISTORY_MS }
            .mapKeys { it.key.channel }.mapValues { it.value.entries }
    }
    private fun key(source: Source, channel: Channel) = ArchiveKey(source.id, channel.id, channel.guideId.ifBlank { channel.id }, channel.url)
    private fun cancel() {
        ++epoch; task?.cancel(true); task = null; running = null
        worker.purge(); metadata.purge(); publishChecking(null)
    }
    fun pause() { active = false; cancel() }
    fun close() { if (closed) return; pause(); closed = true; worker.shutdownNow(); metadata.shutdownNow(); handler?.removeCallbacksAndMessages(null); history.clear(); checked.clear() }
    companion object {
        internal const val HISTORY_MS = 10 * 60000L
        private const val RETRY_MS = 60000L
        private val XTREAM_ID = Regex("xc:[0-9]{1,20}")
        internal fun targets(games: List<SportsGame>, focused: SportsGame?, now: Long) = (listOfNotNull(focused) + games).asSequence()
            .filter { it.state == "post" && it.start in 1..now }.distinctBy { it.key }.take(256).toList()
    }
}

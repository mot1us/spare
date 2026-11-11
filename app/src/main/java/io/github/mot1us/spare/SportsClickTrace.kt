package io.github.mot1us.spare

/** Created only by the opt-in performance overlay. No titles, addresses or source IDs are reported. */
internal class SportsClickTraces(private val clock: () -> Long, private val emit: (Long, String) -> Unit) {
    private val clicks = ArrayDeque<SportsClickTrace>()
    @Volatile private var enabled = true
    private var game = ""
    var playing: SportsClickTrace? = null; private set
    @Synchronized fun begin(gameKey: String, automatic: Boolean): SportsClickTrace {
        clicks.lastOrNull()?.finish("superseded")
        game = gameKey
        return SportsClickTrace(clicks.lastOrNull()?.number?.plus(1) ?: 1, automatic, clock, { enabled }, emit).also {
            clicks.addLast(it)
            while (clicks.size > 32) clicks.removeFirst()
        }
    }
    @Synchronized fun forGame(gameKey: String): SportsClickTrace? = clicks.lastOrNull().takeIf { game == gameKey }
    fun tune(trace: SportsClickTrace?, channelId: String = "") {
        if (playing !== trace) playing?.finish("tune changed")
        playing = trace
        trace?.tuned(channelId)
    }
    @Synchronized fun report(): String {
        val snapshots = clicks.map { it.snapshot() }
        val latest = snapshots.lastOrNull() ?: return "Sports · no clicks recorded"
        val automatic = snapshots.filter { it.automatic }
        fun percentile(label: String, values: List<Long>): String {
            if (values.isEmpty()) return "$label —"
            val sorted = values.sorted()
            fun at(p: Int) = sorted[(sorted.size * p + 99) / 100 - 1]
            return "$label ${at(50)}/${at(95)}ms (n=${sorted.size})"
        }
        return latest.report + "\nSports p50/p95 · " + listOf(
            percentile("local", snapshots.mapNotNull { it.localMs }),
            percentile("provider", snapshots.mapNotNull { it.providerMs }),
            percentile("auto→tune", automatic.mapNotNull { it.tuneMs }),
            percentile("auto→frame", automatic.mapNotNull { it.firstFrameMs }),
        ).joinToString(" · ")
    }
    @Synchronized fun close() { clicks.lastOrNull()?.finish("overlay closed"); enabled = false; playing = null; clicks.clear() }
}

/** Mutable counters stay within this developer-only session; workers never touch Android views. */
class SportsClickTrace internal constructor(
    val number: Int, private val automatic: Boolean, private val clock: () -> Long, private val enabled: () -> Boolean,
    private val emit: (Long, String) -> Unit,
) {
    internal data class Snapshot(val report: String, val localMs: Long?, val providerMs: Long?, val tuneMs: Long?, val firstFrameMs: Long?, val automatic: Boolean)
    private val began = clock()
    @Volatile private var finished = false
    private var peek = "pending"
    private var peekMs: Long? = null
    private var workerCache = "—"
    private var localMs: Long? = null
    private var providerMs: Long? = null
    private var channels = 0
    private var keys = 0
    private var programmes = 0
    private var outsideWindow = 0
    private var separator = 0
    private var separatorRejected = 0
    private var nearMs = 0L
    private var tuneMs: Long? = null
    private var firstFrameMs: Long? = null
    private var mediaId = ""
    private var candidateAt = 0L
    private val candidates = LinkedHashMap<String, Int>()
    private var candidate = 0
    private val outcomes = ArrayDeque<String>()
    private val requests = mutableMapOf<String, String>()
    private var sent = 0
    private var completed = 0
    private var timedOut = 0
    private var cancelled = 0
    private var errors = 0
    private var providerOpen = false
    private var state = "matching"

    internal fun active() = enabled() && !finished
    private fun log(value: String) { val now = clock(); emit(now, "Sports #$number +${(now - began).coerceAtLeast(0)}ms · $value") }
    @Synchronized internal fun peekStarted(): Long? = if (active()) clock() else null
    @Synchronized internal fun peek(reason: SportsStreamCache.Peek, start: Long?) {
        if (active() && start != null) {
            peek = reason.label; peekMs = (clock() - start).coerceAtLeast(0)
            log("peek $peek · ${peekMs}ms")
        }
    }
    @Synchronized internal fun workerCache(hit: Boolean) { if (active()) { workerCache = if (hit) "hit" else "miss"; log("worker cache $workerCache") } }
    @Synchronized internal fun matchStarted(): SportsMatchMeasurement? = if (active()) SportsMatchMeasurement(clock()) else null
    @Synchronized internal fun matchEnded(measurement: SportsMatchMeasurement) {
        if (!active()) return
        measurement.ended = clock()
    }
    @Synchronized internal fun matched(measurement: SportsMatchMeasurement) {
        if (!active()) return
        val elapsed = (measurement.ended - measurement.began).coerceAtLeast(0)
        val nearElapsed = (clock() - measurement.ended).coerceAtLeast(0)
        localMs = (localMs ?: 0) + elapsed; nearMs += nearElapsed
        channels += measurement.channels; keys += measurement.guideKeys.size; programmes += measurement.programmes
        outsideWindow += measurement.outsideWindow; separator += measurement.separator
        separatorRejected += measurement.separatorRejected
        log("local ${elapsed}ms · ${measurement.channels} channels / ${measurement.guideKeys.size} keys / ${measurement.programmes} programmes · near window ${measurement.outsideWindow} / separator ${measurement.separator} (${measurement.separatorRejected} rejected; ${nearElapsed}ms diagnostics)")
    }
    @Synchronized internal fun providerStarted(): Long? {
        if (!active()) return null
        providerOpen = true; requests.clear()
        return clock()
    }
    @Synchronized internal fun requestStarted(id: String) { if (active() && providerOpen) { sent++; requests[id] = "pending" } }
    @Synchronized internal fun requestCompleted(id: String, timeout: Boolean, error: Boolean) {
        if (!active() || !providerOpen || requests[id] != "pending") return
        requests[id] = "done"; completed++; if (timeout) timedOut++; if (error) errors++
    }
    @Synchronized internal fun providerFinished(start: Long, budgetExpired: Boolean) {
        if (!active()) return
        providerOpen = false
        providerMs = (providerMs ?: 0) + (clock() - start).coerceAtLeast(0)
        requests.replaceAll { _, state ->
            if (state == "pending") { if (budgetExpired) timedOut++ else cancelled++; "cancelled" } else state
        }
        log("provider ${providerMs}ms · sent $sent / completed $completed / timed out $timedOut / cancelled $cancelled / errors $errors")
    }
    @Synchronized internal fun tuned(id: String) {
        if (!active()) return
        candidate = candidates.getOrPut(id) { candidates.size + 1 }
        mediaId = ""; candidateAt = clock(); state = "tuning"
        if (tuneMs == null) tuneMs = (candidateAt - began).coerceAtLeast(0)
        outcome("tune")
    }
    @Synchronized internal fun choices(count: Int) {
        if (active()) { state = "choose stream"; log("results · $count candidates") }
    }
    @Synchronized internal fun attempt(expectedMediaId: String) { if (active() && candidate > 0) mediaId = expectedMediaId }
    @Synchronized internal fun outcome(value: String) {
        if (!active() || candidate == 0) return
        val text = "candidate $candidate $value +${(clock() - candidateAt).coerceAtLeast(0)}ms"
        outcomes.addLast(text); while (outcomes.size > 12) outcomes.removeFirst()
        log(text)
    }
    @Synchronized internal fun playerError(httpCode: Int?, playerCode: Int) =
        outcome(if (httpCode != null) "HTTP $httpCode" else "player code $playerCode")
    @Synchronized internal fun firstFrame(expectedMediaId: String) {
        if (!active() || candidate == 0 || mediaId.isEmpty() || mediaId != expectedMediaId) return
        firstFrameMs = (clock() - began).coerceAtLeast(0)
        outcome("first frame"); finish("playing")
    }
    @Synchronized internal fun finish(reason: String) {
        if (!active()) return
        state = reason; log(reason); finished = true
    }
    @Synchronized internal fun snapshot() = Snapshot(
        "Sports #$number · $state · peek $peek ${peekMs ?: "—"}ms · worker $workerCache\n" +
            "Local ${localMs ?: "—"}ms · $channels channels / $keys keys / $programmes programmes · near window $outsideWindow / separator $separator ($separatorRejected rejected; ${nearMs}ms diagnostics)\n" +
            "Provider ${providerMs ?: "—"}ms · sent $sent / completed $completed / timeout $timedOut / cancelled $cancelled / errors $errors\n" +
            "Click→tune ${tuneMs ?: "—"}ms · click→frame ${firstFrameMs ?: "—"}ms" +
            (if (outcomes.isEmpty()) "" else "\n" + outcomes.joinToString(" · ")),
        localMs, providerMs, tuneMs, firstFrameMs, automatic)
}

internal class SportsMatchMeasurement(val began: Long) {
    val guideKeys = mutableListOf<String>()
    var ended = began
    var channels = 0; var programmes = 0
    var outsideWindow = 0; var separator = 0; var separatorRejected = 0

    fun visited(key: String, count: Int) { guideKeys.add(key); programmes += count }
}

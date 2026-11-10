package io.github.mot1us.spare

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PERFORMANCE_WINDOW_MS = 10_000L

/** A bounded window of app-frame durations. Android may deliver samples off the UI thread. */
internal class PerformanceFrames(private val capacity: Int = 4096) {
    data class Summary(val count: Int, val p50: Long, val p95: Long, val p99: Long, val jankyPercent: Double, val missedReports: Long)
    private val times = LongArray(capacity)
    private val durations = LongArray(capacity)
    private val janky = BooleanArray(capacity)
    private val missed = IntArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun record(now: Long, durationNs: Long, deadlineNs: Long, reportsDropped: Int) {
        if (durationNs <= 0 || deadlineNs <= 0) return
        prune(now)
        if (size == capacity) removeFirst()
        val slot = (head + size) % capacity
        times[slot] = now
        durations[slot] = durationNs
        janky[slot] = durationNs >= deadlineNs
        missed[slot] = reportsDropped.coerceAtLeast(0)
        size++
    }

    fun summary(now: Long): Summary? {
        val snapshot: LongArray
        var jankCount = 0
        var reportCount = 0L
        synchronized(this) {
            prune(now)
            if (size == 0) return null
            snapshot = LongArray(size) { index ->
                val slot = (head + index) % capacity
                if (janky[slot]) jankCount++
                reportCount += missed[slot]
                durations[slot]
            }
        }
        snapshot.sort()
        fun percentile(p: Int) = snapshot[(snapshot.size * p + 99) / 100 - 1]
        return Summary(snapshot.size, percentile(50), percentile(95), percentile(99), jankCount * 100.0 / snapshot.size, reportCount)
    }

    private fun prune(now: Long) {
        while (size > 0 && now - times[head] >= PERFORMANCE_WINDOW_MS) removeFirst()
    }

    private fun removeFirst() {
        head = (head + 1) % capacity
        size--
    }
}

internal class PerformanceDrops {
    private val batches = ArrayDeque<Pair<Long, Int>>()
    fun record(now: Long, dropped: Int) {
        if (dropped <= 0) return
        prune(now)
        batches.addLast(now to dropped)
    }
    fun count(now: Long): Long {
        prune(now)
        return batches.sumOf { it.second.toLong() }
    }
    fun clear() = batches.clear()
    private fun prune(now: Long) {
        while (batches.firstOrNull()?.let { now - it.first >= PERFORMANCE_WINDOW_MS } == true) batches.removeFirst()
    }
}

/** Observe compatible active modes without making a request or assuming the request succeeded. */
internal class DisplaySettle {
    private var tuneAt: Long? = null
    private var candidateAt: Long? = null
    private var mode = 0
    private var rate = 0f
    private var requested = 0f

    fun tune(now: Long) { tuneAt = now; resetObservation() }
    fun resetObservation() { candidateAt = null }

    fun observe(now: Long, modeId: Int, refresh: Float, request: Float, source: Float, rendered: Boolean): Long? {
        val start = tuneAt ?: return null
        if (!rendered || !compatible(refresh, request) || source <= 0f || abs(source - request) > .01f) {
            resetObservation()
            return null
        }
        if (candidateAt == null || mode != modeId || abs(rate - refresh) > .01f || abs(requested - request) > .01f) {
            mode = modeId
            rate = refresh
            requested = request
            candidateAt = now
        }
        val since = candidateAt ?: return null
        // A match must persist across observations. Report the first observation of that match.
        return (since - start).takeIf { now - since >= 500 }
    }

    companion object {
        fun compatible(refresh: Float, request: Float): Boolean {
            if (!refresh.isFinite() || !request.isFinite() || refresh <= 0f || request <= 0f) return false
            val multiple = (refresh / request).roundToInt()
            return multiple >= 1 && abs(refresh - multiple * request) <= .01f
        }
    }
}

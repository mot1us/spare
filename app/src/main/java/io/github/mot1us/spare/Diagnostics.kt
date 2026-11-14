package io.github.mot1us.spare

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * What the app was doing when it felt slow, for Settings › Performance report. Kept in memory only, bounded,
 * and never sent anywhere: the viewer reads it off the screen. It answers three questions the emulator cannot:
 * what blocked the screen, where slow Library frames spent their time, and what else was running when
 * playback hiccuped (and whether the stream's buffer was empty, which points at the network, or full,
 * which points at this app or the device).
 */
internal object Diagnostics {
    private const val OURS = "io.github.mot1us.spare"
    private val began = SystemClock.elapsedRealtime()
    private fun clock(at: Long = SystemClock.elapsedRealtime()): String { val s = (at - began) / 1000; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

    // Screen stalls: where the main thread was each time it stopped answering.
    private class Stall { var count = 0; var total = 0L; var worst = 0L; var last = 0L }
    private val stalls = HashMap<String, Stall>()
    private var watchdog: Thread? = null
    @Volatile private var answered = 0L
    private val main = Handler(Looper.getMainLooper())
    private val answer = Runnable { answered = SystemClock.elapsedRealtime() }

    fun start() {
        if (watchdog != null) return
        answered = SystemClock.elapsedRealtime()
        watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val asked = SystemClock.elapsedRealtime(); main.post(answer); Thread.sleep(120)
                    if (answered >= asked) continue
                    // The screen has not answered for 120 ms: note where it is, then wait for it to come back.
                    val where = place(Looper.getMainLooper().thread.stackTrace)
                    while (answered < asked && SystemClock.elapsedRealtime() - asked < 20000) Thread.sleep(20)
                    val lasted = SystemClock.elapsedRealtime() - asked
                    if (lasted >= 200) synchronized(stalls) {
                        if (stalls.size < 60 || where in stalls) stalls.getOrPut(where) { Stall() }.apply { count++; total += lasted; if (lasted > worst) worst = lasted; last = asked }
                    }
                }
            } catch (_: InterruptedException) {}
        }, "spare-diagnostics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
    fun stop() { watchdog?.interrupt(); watchdog = null; main.removeCallbacks(answer) }

    /** The deepest of this app's own methods on a stack, with the system call it was inside. */
    private fun place(stack: Array<StackTraceElement>): String {
        val top = stack.firstOrNull() ?: return "idle"
        val own = stack.firstOrNull { it.className.startsWith(OURS) && !it.className.contains("Diagnostics") }
        fun name(e: StackTraceElement) = e.className.substringAfterLast('.').substringBefore('$') + "." + e.methodName + ":" + e.lineNumber
        return if (own == null) name(top) else if (own === top) name(own) else name(own) + " in " + top.className.substringAfterLast('.') + "." + top.methodName
    }

    // Playback hiccups, each with the buffer left and the app threads that were busy.
    private val hiccups = ArrayDeque<String>()
    private var hiccupAt = 0L
    fun hiccup(kind: String, bufferedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - hiccupAt < 1500) return
        hiccupAt = now
        val busy = runCatching {
            Thread.getAllStackTraces().entries.filter { (thread, stack) ->
                thread !== Looper.getMainLooper().thread && thread.state == Thread.State.RUNNABLE && thread.name != "spare-diagnostics" &&
                    stack.any { it.className.startsWith(OURS) } && stack.firstOrNull()?.let { !it.isNativeMethod || !it.methodName.contains("poll", true) && !it.methodName.contains("park", true) } == true
            }.map { (thread, stack) -> thread.name.take(24) + "@" + place(stack) }.sorted().take(4)
        }.getOrDefault(emptyList())
        val runtime = Runtime.getRuntime(); val used = (runtime.totalMemory() - runtime.freeMemory()) / 1048576; val max = runtime.maxMemory() / 1048576
        val line = "${clock(now)} $kind · buffer ${String.format(Locale.ROOT, "%.1f", bufferedMs / 1000.0)} s · memory $used/$max MB · busy: ${busy.joinToString("; ").ifEmpty { "nothing of ours" }}"
        synchronized(hiccups) { hiccups.addLast(line); while (hiccups.size > 8) hiccups.removeFirst() }
    }

    // Background jobs with a name (startup stages, catalog import), to line up against hiccups.
    private val jobs = ArrayDeque<String>()
    fun job(name: String, beganAt: Long, endedAt: Long) {
        if (endedAt - beganAt < 500) return
        synchronized(jobs) { jobs.addLast("${clock(beganAt)}–${clock(endedAt)} $name"); while (jobs.size > 10) jobs.removeFirst() }
    }

    // Posters: how long they wait for a worker, and how long fetching plus decoding takes.
    private var posters = 0; private var posterWait = 0L; private var posterWork = 0L; private var posterWorst = 0L; private var posterFailed = 0
    @Synchronized fun poster(waitMs: Long, workMs: Long, ok: Boolean) { posters++; posterWait += waitMs; posterWork += workMs; if (workMs > posterWorst) posterWorst = workMs; if (!ok) posterFailed++ }

    @Synchronized private fun posterLine() = if (posters == 0) null else "Posters: $posters loaded · waited ${posterWait / posters} ms · took ${posterWork / posters} ms each (worst $posterWorst) · $posterFailed failed"

    fun report(): String {
        val lines = ArrayList<String>()
        lines += "Running ${clock()} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
        LibraryFrames.summary()?.let { lines += "Library frames: $it" }
        LibraryFrames.phases()?.let { lines += "Slow frames spent: $it" }
        posterLine()?.let { lines += it }
        val worst = synchronized(stalls) { stalls.entries.sortedByDescending { it.value.total }.take(8).map { (where, s) -> "  ${s.count}× ${s.total} ms (worst ${s.worst}, last ${clock(s.last)}) $where" } }
        lines += if (worst.isEmpty()) "Screen stalls: none over 0.2 s" else "Screen stalls (what blocked the screen):"
        lines += worst
        val playback = synchronized(hiccups) { hiccups.toList() }
        lines += if (playback.isEmpty()) "Playback hiccups: none" else "Playback hiccups:"
        lines += playback.map { "  $it" }
        val background = synchronized(jobs) { jobs.toList() }
        if (background.isNotEmpty()) { lines += "Background jobs:"; lines += background.map { "  $it" } }
        return lines.joinToString("\n")
    }
    fun reset() { synchronized(stalls) { stalls.clear() }; synchronized(hiccups) { hiccups.clear() }; synchronized(jobs) { jobs.clear() }; synchronized(this) { posters = 0; posterWait = 0; posterWork = 0; posterWorst = 0; posterFailed = 0 } }
}

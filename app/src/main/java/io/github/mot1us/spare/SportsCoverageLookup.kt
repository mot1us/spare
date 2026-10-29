package io.github.mot1us.spare

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Consume guide replies in arrival order and stop once current game coverage is confirmed. */
object SportsCoverageLookup {
    private data class Reply(val channel: Channel, val entries: List<Programme>?)
    fun load(channels: List<Channel>, executor: ExecutorService, budgetMs: Long = 3500,
        get: (Channel, Int) -> List<Programme>, confirmed: (Channel, List<Programme>) -> Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> }): Map<Channel, List<Programme>> {
        val targets = channels.distinctBy { it.id }.take(8)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val completion = ExecutorCompletionService<Reply>(executor)
        val jobs = mutableListOf<Future<Reply>>()
        val results = linkedMapOf<Channel, List<Programme>>()
        var received = 0
        fun receive(reply: Reply): Boolean {
            received++; progress(received, targets.size)
            val entries = reply.entries?.takeIf { it.isNotEmpty() } ?: return false
            results[reply.channel] = entries
            return confirmed(reply.channel, entries)
        }
        try {
            targets.forEach { channel -> jobs.add(completion.submit(java.util.concurrent.Callable {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0 || Thread.currentThread().isInterrupted) Reply(channel, null)
                else Reply(channel, runCatching { get(channel, minOf(1000L, left).toInt().coerceAtLeast(1)) }.getOrNull())
            })) }
            while (received < jobs.size) {
                val left = deadline - System.nanoTime()
                val ready = completion.poll() ?: if (left > 0) completion.poll(left, TimeUnit.NANOSECONDS) else null
                if (ready == null) break
                if (receive(ready.get())) break
            }
            // Preserve other replies already received before the deadline or the first match.
            while (true) receive((completion.poll() ?: break).get())
            return results
        } finally { jobs.forEach { it.cancel(true) } }
    }
}

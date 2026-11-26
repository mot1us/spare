package io.github.mot1us.spare

import java.util.concurrent.*

/**
 * One producer per key. A cancelled consumer never cancels another consumer's work. Work whose last consumer leaves is
 * cancelled, except work a consumer stopped waiting for after its time ran out: that carries on for whoever asks next.
 */
class SharedJobs<K, V : Any>(private val executor: ExecutorService) {
    private inner class Entry {
        val listeners = linkedMapOf<Any, (V) -> Unit>()
        var latest: V? = null
        var kept = false
        lateinit var future: FutureTask<V>
    }
    private val lock = Any()
    private val entries = mutableMapOf<K, Entry>()
    /** [waitMs] above 0: wait at most that long, then throw [TimeoutException] and leave the work running. */
    fun await(key: K, progress: (V) -> Unit, waitMs: Long = 0, work: ((V) -> Unit) -> V): V {
        val subscriber = Any()
        var start = false
        val entry = synchronized(lock) {
            val value = entries[key] ?: Entry().also { e ->
                e.future = FutureTask {
                    try { work { update ->
                        val listeners = synchronized(lock) { e.latest = update; e.listeners.values.toList() }
                        listeners.forEach { listener -> runCatching { listener(update) } }
                    } } finally { synchronized(lock) { if (entries[key] === e) entries.remove(key) } }
                }
                entries[key] = e; start = true
            }
            value.listeners[subscriber] = progress; value
        }
        try {
            if (start) executor.execute(entry.future)
            synchronized(lock) { entry.latest }?.let(progress)
            if (waitMs <= 0) return entry.future.get()
            try { return entry.future.get(waitMs, TimeUnit.MILLISECONDS) }
            catch (timeout: TimeoutException) { synchronized(lock) { entry.kept = true }; throw timeout }
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } finally {
            synchronized(lock) {
                entry.listeners.remove(subscriber)
                if (entry.listeners.isEmpty() && !entry.future.isDone && !entry.kept) {
                    if (entries[key] === entry) entries.remove(key)
                    entry.future.cancel(true)
                }
            }
        }
    }
    fun cancel(predicate: (K) -> Boolean) = synchronized(lock) {
        entries.keys.filter(predicate).forEach { key -> entries.remove(key)?.future?.cancel(true) }; Unit
    }
    fun close() { cancel { true }; executor.shutdownNow() }
}

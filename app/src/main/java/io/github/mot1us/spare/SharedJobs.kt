package io.github.mot1us.spare

import java.util.concurrent.*

/** One producer per key. A cancelled consumer never cancels another consumer's work. */
class SharedJobs<K, V : Any>(private val executor: ExecutorService) {
    private inner class Entry {
        val listeners = linkedMapOf<Any, (V) -> Unit>()
        var latest: V? = null
        lateinit var future: FutureTask<V>
    }
    private val lock = Any()
    private val entries = mutableMapOf<K, Entry>()
    fun await(key: K, progress: (V) -> Unit, work: ((V) -> Unit) -> V): V {
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
            return entry.future.get()
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } finally {
            synchronized(lock) {
                entry.listeners.remove(subscriber)
                if (entry.listeners.isEmpty() && !entry.future.isDone) {
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

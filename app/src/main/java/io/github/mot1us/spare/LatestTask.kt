package io.github.mot1us.spare

/** One running snapshot and one latest pending snapshot, instead of an unbounded write queue. */
class LatestTask<T : Any>(private val executor: java.util.concurrent.Executor, private val process: (T) -> Unit) {
    private val lock = Any()
    private var pending: T? = null
    private var running = false
    fun submit(value: T) {
        synchronized(lock) { pending = value; if (running) return; running = true }
        executor.execute {
            while (true) {
                val next = synchronized(lock) { pending?.also { pending = null } ?: run { running = false; return@execute } }
                runCatching { process(next) }
            }
        }
    }
}

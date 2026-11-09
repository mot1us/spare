package io.github.mot1us.spare

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Owns MainActivity's executors. Only persistent cache writes drain on teardown. */
internal class ActivityWorkers {
    private val cancellable = mutableListOf<ExecutorService>()
    private val draining = mutableListOf<ExecutorService>()

    fun pool(name: String, threads: Int = 1, background: Boolean = false, drain: Boolean = false): ExecutorService {
        val next = AtomicInteger()
        val defaultFactory = Executors.defaultThreadFactory()
        val factory = ThreadFactory { task ->
            val work = if (background) Runnable {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            } else task
            defaultFactory.newThread(work).apply { this.name = "spare-$name-${next.incrementAndGet()}" }
        }
        val executor = if (threads == 1) Executors.newSingleThreadExecutor(factory) else Executors.newFixedThreadPool(threads, factory)
        (if (drain) draining else cancellable).add(executor)
        return executor
    }

    fun shutdown() {
        cancellable.forEach { it.shutdownNow() }
        draining.forEach { it.shutdown() }
    }
}

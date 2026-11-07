package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LatestTaskTest {
    @Test fun burstOfSnapshotsQueuesOneWriteAndKeepsOnlyTheLatestPendingState() {
        val jobs = arrayListOf<Runnable>(); val saved = arrayListOf<Int>()
        lateinit var task: LatestTask<Int>
        task = LatestTask(java.util.concurrent.Executor { jobs.add(it) }) { value -> saved.add(value); if (value == 3) { task.submit(4); task.submit(5) } }
        task.submit(1); task.submit(2); task.submit(3)
        assertEquals(1, jobs.size); jobs.removeAt(0).run()
        assertEquals(listOf(3, 5), saved); assertTrue(jobs.isEmpty())
        task.submit(6); jobs.removeAt(0).run(); assertEquals(listOf(3, 5, 6), saved)
    }
    @Test fun failedWriteDoesNotLeaveFutureSavesPermanentlyBusy() {
        val jobs = arrayListOf<Runnable>(); var saved = 0
        val task = LatestTask<Int>(java.util.concurrent.Executor { jobs.add(it) }) { if (it == 1) error("Test failure") else saved = it }
        task.submit(1); jobs.removeAt(0).run(); task.submit(2); jobs.removeAt(0).run(); assertEquals(2, saved)
    }
}

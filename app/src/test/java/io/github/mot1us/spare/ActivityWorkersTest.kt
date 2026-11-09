package io.github.mot1us.spare

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityWorkersTest {
    @Test fun shutdownDrainsCacheAndInterruptsOtherWorkers() {
        val workers = ActivityWorkers()
        val cache = workers.pool("cache", drain = true)
        val fetch = workers.pool("fetch")
        val finishWrite = CountDownLatch(1)
        val fetching = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        try {
            cache.submit { finishWrite.await() }
            val pending = cache.submit<String> { Thread.currentThread().name }
            fetch.execute {
                fetching.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
            }
            assertTrue(fetching.await(5, SECONDS))
            workers.shutdown()
            assertTrue(cache.isShutdown)
            assertTrue(fetch.isShutdown)
            assertTrue(interrupted.await(5, SECONDS))
            finishWrite.countDown()
            assertEquals("spare-cache-1", pending.get(5, SECONDS))
        } finally {
            finishWrite.countDown()
            workers.shutdown()
        }
    }
}

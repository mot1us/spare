package io.github.mot1us.spare

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SharedJobsTest {
    @Test fun concurrentConsumersShareOneProducerAndCancellingOneKeepsTheOtherAlive() {
        val jobs = SharedJobs<String, String>(Executors.newSingleThreadExecutor())
        val callers = Executors.newFixedThreadPool(2); val count = AtomicInteger()
        val published = CountDownLatch(1); val joined = CountDownLatch(1); val finish = CountDownLatch(1)
        val work: ((String) -> Unit) -> String = { update -> count.incrementAndGet(); update("partial"); published.countDown(); check(finish.await(5, TimeUnit.SECONDS)); "complete" }
        try {
            val first = callers.submit<String> { jobs.await("movie", {}, work) }
            assertTrue(published.await(5, TimeUnit.SECONDS))
            val second = callers.submit<String> { jobs.await("movie", { if (it == "partial") joined.countDown() }, work) }
            assertTrue(joined.await(5, TimeUnit.SECONDS)); first.cancel(true); finish.countDown()
            assertEquals("complete", second.get(5, TimeUnit.SECONDS)); assertEquals(1, count.get())
        } finally { finish.countDown(); jobs.close(); callers.shutdownNow() }
    }
    @Test fun differentKeysDoNotBlockEachOtherAndFailedWorkCanBeRetried() {
        val jobs = SharedJobs<String, String>(Executors.newFixedThreadPool(2))
        try {
            try { jobs.await("bad", {}) { error("fixture") }; fail("Expected fixture failure") } catch (_: IllegalStateException) { }
            assertEquals("recovered", jobs.await("bad", {}) { "recovered" })
            assertEquals("series", jobs.await("series", {}) { "series" })
        } finally { jobs.close() }
    }
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SportsCoverageLookupTest {
    private fun channel(id: String) = Channel(id, "NHL $id", "NHL", "https://example.com/$id")
    @Test fun aFastConfirmedGuideReplyDoesNotWaitForAnEarlierSlowRequest() {
        val pool = Executors.newFixedThreadPool(2)
        val slow = channel("slow"); val fast = channel("fast")
        val started = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val programme = Programme(fast.id, "Maple Leafs at Kraken", 1000, 2000)
        try {
            val began = System.nanoTime()
            val result = SportsCoverageLookup.load(listOf(slow, fast), pool, get = { channel, timeout ->
                assertTrue(timeout in 1..1000)
                if (channel == slow) {
                    started.countDown()
                    try { Thread.sleep(5000); emptyList() } finally { cancelled.countDown() }
                } else { assertTrue(started.await(1, TimeUnit.SECONDS)); listOf(programme) }
            }, confirmed = { channel, _ -> channel == fast })
            assertEquals(mapOf(fast to listOf(programme)), result)
            assertTrue("Fast confirmed metadata must not wait for the slow request or full deadline", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000)
            assertTrue("Pending lookups must be cancelled after a match", cancelled.await(1, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }
    @Test fun lowerResolutionReplyCannotCancelThePendingFourKGame() {
        val pool = Executors.newFixedThreadPool(2)
        val uhd = channel("uhd"); val hd = channel("hd")
        val first = CountDownLatch(1)
        val programme = Programme(hd.id, "Carolina Panthers at Green Bay Packers", 1000, 2000)
        try {
            val found = SportsCoverageLookup.load(listOf(uhd, hd), pool, get = { channel, _ ->
                if (channel == uhd) { assertTrue(first.await(1, TimeUnit.SECONDS)); Thread.sleep(30) } else first.countDown()
                listOf(programme.copy(channel = channel.id))
            }, confirmed = { channel, _ -> channel == uhd })
            assertEquals(setOf(hd, uhd), found.keys)
        } finally { pool.shutdownNow() }
    }
    @Test fun failedOrTimedOutRepliesCannotEscapeTheLookupBudgetOrConcurrencyBound() {
        val pool = Executors.newFixedThreadPool(2); val active = AtomicInteger(); val maximum = AtomicInteger(); val calls = AtomicInteger()
        try {
            val began = System.nanoTime()
            val result = SportsCoverageLookup.load((1..20).map { channel("$it") }, pool, budgetMs = 100, get = { _, timeout ->
                assertTrue(timeout in 1..100); calls.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try { Thread.sleep(5000); emptyList() } finally { active.decrementAndGet() }
            }, confirmed = { _, _ -> false })
            assertTrue(result.isEmpty()); assertTrue(calls.get() in 1..12); assertTrue(maximum.get() in 1..2)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000)
        } finally { pool.shutdownNow() }
    }
}

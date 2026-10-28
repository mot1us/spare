package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SportsReplayDiscoveryTest {
    private val initial = java.time.Instant.parse("2026-10-26T23:00:00Z").toEpochMilli()
    private val source = Source(id = "archive-fixture", name = "Fixture", kind = "xtream", url = "https://example.com", username = "user", password = "pass")
    private val game = SportsGame(listOf("nfl:SEA", "nfl:SF").map { SportsCatalog.byId.getValue(it) }, initial - 3 * 3600000,
        initial - 3600000, emptyList(), "replay", "post", networks = listOf("ESPN"), competition = "NFL")
    private val channel = Channel("xc:1", "ESPN FHD", "NFL", "https://example.com/live/user/pass/1.ts", catchupDays = 2, catchupMode = "xc")
    private fun listing(forGame: SportsGame = game) = Programme(channel.id, "NFL: Seattle Seahawks vs San Francisco 49ers", forGame.start, forGame.end)
    private data class Result(val generation: Int, val game: SportsGame, val replays: List<SportsReplay>, val checked: Boolean)
    private class MainQueue {
        val jobs = ConcurrentLinkedQueue<() -> Unit>()
        fun post(job: () -> Unit) { jobs.add(job) }
        fun drain() { while (true) (jobs.poll() ?: return).invoke() }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) { drain(); Thread.yield() }
            drain(); assertTrue("Timed out waiting for replay discovery", condition())
        }
    }

    @Test fun savedHistoricalGuideConfirmsWithoutProviderRequestsOrCallbackLoops() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val checking = mutableListOf<String?>(); val calls = AtomicInteger()
        val guide = Guide(listOf(channel), mapOf(channel.id to listOf(listing())))
        val discovery = SportsReplayDiscovery({ _, generation, game, replay, checked -> results.add(Result(generation, game, replay, checked)) },
            checking::add, main::post, { initial }, { _, _, _ -> calls.incrementAndGet(); error("Network is unnecessary") })
        try {
            discovery.update(source, 1, guide, listOf(game), game, true)
            main.await { results.size == 1 }
            assertEquals(listOf(channel), results.single().replays.map { it.channel })
            assertTrue(results.single().checked); assertEquals(setOf(game.key), discovery.checkedKeys)
            assertTrue(checking.contains(game.key)); assertNull(checking.last())
            repeat(3) { discovery.update(source, 1, guide, listOf(game), game, true); main.drain() }
            assertEquals(1, results.size); assertEquals(0, calls.get())
        } finally { discovery.close() }
    }

    @Test fun focusedFinalRunsFirstAndOnlyEightGamesAreCheckedUsingSharedMetadataCache() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val calls = AtomicInteger()
        val games = (0..11).map { game.copy(eventId = "final-$it", start = game.start - it * 600000, end = game.end - it * 600000) }
        val focused = games[10]; val guide = Guide(listOf(channel), emptyMap())
        val discovery = SportsReplayDiscovery({ _, generation, game, replays, checked -> results.add(Result(generation, game, replays, checked)) }, {},
            main::post, { initial }, { _, _, _ -> calls.incrementAndGet(); games.map(::listing) })
        try {
            discovery.update(source, 1, guide, games, focused, true)
            main.await { results.size == 8 }
            assertEquals((listOf(focused) + games.take(7)).map { it.key }, results.map { it.game.key })
            assertTrue(results.all { it.checked && it.replays.size == 1 }); assertEquals(1, calls.get())
            assertEquals(8, discovery.checkedKeys.size)
            assertTrue(SportsReplayDiscovery.targets(listOf(game.copy(state = "in"), game.copy(state = "pre")), null, initial).isEmpty())
        } finally { discovery.close() }
    }

    @Test fun sharedGuideIdentifiersNeverShareProviderArchiveEvidenceAcrossVariants() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val calls = AtomicInteger()
        val variants = (1..2).map { channel.copy(id = "xc:$it", guideId = "espn", url = "https://example.com/live/user/pass/$it.ts") }
        val guide = Guide(variants, emptyMap())
        val discovery = SportsReplayDiscovery({ _, generation, game, replay, checked -> results.add(Result(generation, game, replay, checked)) }, {},
            main::post, { initial }, { _, candidate, _ -> calls.incrementAndGet(); if (candidate.id == "xc:2") listOf(listing()) else emptyList() })
        try {
            discovery.update(source, 1, guide, listOf(game), game, true)
            main.await { results.size == 1 }
            assertEquals(listOf("xc:2"), results.single().replays.map { it.channel.id }); assertTrue(results.single().checked)
            val next = game.copy(eventId = "another-final")
            discovery.update(source, 1, guide, listOf(next), next, true)
            main.await { results.size == 2 }
            assertEquals(listOf("xc:2"), results.last().replays.map { it.channel.id }); assertEquals(2, calls.get())
        } finally { discovery.close() }
    }

    @Test fun providerFailuresRetryAfterOneMinuteAndNeverBecomeCheckedEmptyResults() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val calls = AtomicInteger(); var clock = initial
        val guide = Guide(listOf(channel), emptyMap())
        val discovery = SportsReplayDiscovery({ _, generation, game, replay, checked -> results.add(Result(generation, game, replay, checked)) }, {},
            main::post, { clock }, { _, _, _ -> if (calls.incrementAndGet() == 1) error("Offline fixture") else emptyList() })
        try {
            discovery.update(source, 1, guide, listOf(game), game, true); main.await { results.size == 1 }
            assertFalse(results.single().checked); assertTrue(discovery.checkedKeys.isEmpty())
            clock += 59999; discovery.update(source, 1, guide, listOf(game), game, true); main.drain()
            assertEquals(1, calls.get())
            clock += 2; discovery.update(source, 1, guide, listOf(game), game, true); main.await { results.size == 2 }
            assertTrue(results.last().checked); assertTrue(results.last().replays.isEmpty()); assertEquals(setOf(game.key), discovery.checkedKeys)
        } finally { discovery.close() }
    }

    @Test fun deadlineAndConcurrencyAreBoundedWithoutMarkingTimedOutSearchUnavailable() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val guide = Guide((1..12).map { channel.copy(id = "xc:$it", url = "https://example.com/live/user/pass/$it.ts") }, emptyMap())
        val discovery = SportsReplayDiscovery({ _, generation, game, replay, checked -> results.add(Result(generation, game, replay, checked)) }, {},
            main::post, { initial }, { _, _, timeout ->
                assertTrue(timeout in 1..1200); calls.incrementAndGet(); val concurrent = active.incrementAndGet()
                maximum.accumulateAndGet(concurrent, ::maxOf)
                try { Thread.sleep(2000); emptyList() } finally { active.decrementAndGet() }
            }, budgetMs = 120)
        try {
            val began = System.nanoTime()
            discovery.update(source, 1, guide, listOf(game), game, true); main.await { results.size == 1 }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000)
            assertFalse(results.single().checked); assertTrue(discovery.checkedKeys.isEmpty())
            assertTrue(calls.get() in 1..6); assertTrue(maximum.get() in 1..2)
        } finally { discovery.close() }
    }

    @Test fun pauseAndContextReplacementRejectStaleCallbacksAndHistoricalCache() {
        val main = MainQueue(); val results = mutableListOf<Result>(); val started = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val first = Guide(listOf(channel), emptyMap())
        val discovery = SportsReplayDiscovery({ _, generation, game, replay, checked -> results.add(Result(generation, game, replay, checked)) }, {},
            main::post, { initial }, { _, _, _ -> if (calls.incrementAndGet() == 1) { started.countDown(); release.await(2, TimeUnit.SECONDS) }; listOf(listing()) })
        try {
            discovery.update(source, 1, first, listOf(game), game, true)
            assertTrue(started.await(2, TimeUnit.SECONDS)); discovery.pause(); main.drain()
            assertNull(discovery.checkingKey); assertTrue(results.isEmpty())
            val second = Guide(listOf(channel.copy(url = "https://example.com/live/user/pass/changed.ts")), emptyMap())
            discovery.update(source, 2, second, listOf(game), game, true); release.countDown()
            main.await { results.size == 1 }
            assertEquals(2, results.single().generation); assertEquals(second.channels.single(), results.single().replays.single().channel)
            assertEquals(2, calls.get())
            // Even with the same generation, a new lineup discards prior checked/history state.
            val third = Guide(listOf(channel.copy(url = "https://example.com/live/user/pass/third.ts")), emptyMap())
            discovery.update(source, 2, third, listOf(game), game, true); main.await { results.size == 2 }
            assertEquals(third.channels.single(), results.last().replays.single().channel); assertEquals(3, calls.get())
        } finally { release.countDown(); discovery.close() }
    }
}

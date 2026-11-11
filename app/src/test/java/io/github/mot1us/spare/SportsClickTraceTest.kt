package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsClickTraceTest {
    private val time = 1_000_000L
    private val game = SportsGame(listOf(SportsCatalog.byId.getValue("nfl:SEA"), SportsCatalog.byId.getValue("nfl:SF")),
        time - 60_000, time + 60_000, emptyList())
    private val feed = Channel("private-id", "Private feed", "NFL", "https://example.com/secret")

    @Test fun tracingCountsExaminedRowsAndNearMissesWithoutChangingMatches() {
        val current = Programme(feed.id, "Seahawks at 49ers", time - 1000, time + 1000)
        val guide = Guide(listOf(feed), mapOf(feed.id to listOf(
            current, current.copy(start = time - 5000, end = time - 3000),
            current.copy(title = "Seahawks - 49ers"), current.copy(title = "Seahawks - 49ers Classic"),
            current.copy(title = "Seahawks - 49ersville"),
        )))
        val traces = SportsClickTraces({ 10 }) { _, _ -> }
        val trace = traces.begin(game.key, true)
        assertEquals(SportsSchedule.match(game, guide, emptySet(), time),
            SportsSchedule.match(game, guide, emptySet(), time, trace = trace))
        val report = traces.report()
        assertTrue(report, report.contains("1 channels / 1 keys / 5 programmes"))
        assertTrue(report, report.contains("near window 1 / separator 1 (0 rejected"))
    }

    @Test fun tracesExplainCacheDecisionsAndIgnoreFramesFromOldAttempts() {
        var now = 0L
        val journal = PlaybackJournal()
        val traces = SportsClickTraces({ now }, journal::sports)
        val guide = Guide(listOf(feed), emptyMap())
        val cache = SportsStreamCache(clock = { time })
        val cold = traces.begin(game.key, true)
        assertNull(cache.peek("private-source", 1, guide, emptySet(), game, time, trace = cold))
        val context = cache.use("private-source", 1, guide, emptySet())
        assertTrue(cache.put(context, SportsStreamCache.Match(game.copy(titleMatches = listOf(feed))), time))
        val trace = traces.begin(game.key, true)
        assertNotNull(cache.peek("private-source", 1, guide, emptySet(), game, time, trace = trace))
        now = 40
        traces.tune(trace, feed.id)
        trace.attempt("old-media")
        trace.outcome("startup timeout")
        now = 80
        traces.tune(trace, "private-alternate")
        trace.attempt("new-media")
        trace.firstFrame("old-media")
        assertNull(trace.snapshot().firstFrameMs)
        now = 140
        trace.firstFrame("new-media")
        assertEquals(40L, trace.snapshot().tuneMs)
        assertEquals(140L, trace.snapshot().firstFrameMs)
        val report = journal.report("fixture")
        for (expected in listOf("peek miss: no context", "peek hit", "candidate 1 startup timeout", "candidate 2 first frame +60ms")) {
            assertTrue(report, report.contains(expected))
        }
        for (secret in listOf(feed.id, feed.name, feed.url, "private-source", game.title, "new-media")) assertFalse(report, report.contains(secret))
        assertTrue(traces.report().contains("auto→frame 140/140ms (n=1)"))
        traces.close()
        assertNull(trace.matchStarted())
    }

    @Test fun percentilesKeepManualSelectionTimeOutOfAutoplayResults() {
        var now = 0L
        val traces = SportsClickTraces({ now }) { _, _ -> }
        for (duration in 1L..20L) {
            val trace = traces.begin(game.key, true)
            traces.tune(trace, feed.id)
            trace.attempt("media")
            now += duration
            trace.firstFrame("media")
        }
        val manual = traces.begin(game.key, false)
        now += 60_000
        traces.tune(manual, feed.id)
        manual.attempt("manual")
        manual.firstFrame("manual")
        assertTrue(traces.report().contains("auto→frame 10/19ms (n=20)"))
    }
}

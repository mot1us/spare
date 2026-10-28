package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun onlyConfirmedMissingEndpointsQualifyForHiding() {
        val errors = StartupFailures()
        assertFalse(errors.allEndpointsMissing())
        errors.record(404, "https://example.com/1.ts", "https://example.com/1.ts")
        errors.record(410, "https://example.com/1.m3u8", "https://example.com/1.m3u8")
        assertTrue(errors.allEndpointsMissing())
        listOf(401, 403, 429, 500, null).forEach { status ->
            val mixed = StartupFailures(); mixed.record(404, "https://example.com/1.ts", "https://example.com/1.ts")
            mixed.record(status, "https://example.com/1.m3u8", "https://example.com/1.m3u8")
            assertFalse("Do not hide a channel for $status", mixed.allEndpointsMissing())
        }
        val segment = StartupFailures(); segment.record(404, "https://example.com/segment.ts", "https://example.com/live.m3u8")
        assertFalse(segment.allEndpointsMissing())
    }
    @Test fun unavailableExpiresAndCanBeRestoredWithoutScanningStreams() {
        val unavailable = UnavailableChannels(); unavailable.hide("sports", 1000)
        assertTrue(unavailable.contains("sports", 899999))
        assertFalse(unavailable.contains("sports", 901000))
        assertTrue(unavailable.prune(901000)); assertTrue(unavailable.snapshot().isEmpty())
        unavailable.hide("sports", 2000); assertTrue(unavailable.restore("sports")); assertFalse(unavailable.contains("sports", 2001))
    }
    @Test fun reconnectsAreBoundedEvenWhenAReconnectRendersAFrame() {
        val budget = ReconnectBudget()
        assertTrue(budget.take(1000)); assertTrue(budget.take(2000)); assertFalse(budget.take(3000))
        assertFalse(budget.take(120999)); assertTrue(budget.take(121000))
    }
    @Test fun usaCategoriesComeFirstWithoutMatchingMusicOrRussia() {
        val us = listOf("US | Sports", "USA: News", "🇺🇸 MLB", "U.S.A. NFL", "United States - Local", "Sports [US]")
        val other = listOf("RUSSIA", "MUSIC", "AUS | Sports", "UK | News", "RUS: Movies")
        us.forEach { assertTrue(it, CategoryOrder.isUsa(it)) }; other.forEach { assertFalse(it, CategoryOrder.isUsa(it)) }
        val ordered = CategoryOrder.usaFirst(other + us)
        assertTrue(ordered.take(us.size).all(CategoryOrder::isUsa)); assertEquals((other + us).toSet(), ordered.toSet())
    }
    @Test fun progressDetectsReadyFreezesWithoutReconnectingShortBuffers() {
        val progress = PlaybackProgress()
        assertFalse(progress.stalled(1, 0, 0, 1, true, true))
        assertFalse(progress.stalled(1, 44000, 0, 1, true, true))
        assertTrue(progress.stalled(1, 45000, 0, 1, true, true))
        progress.reset()
        assertFalse(progress.stalled(2, 0, 0, 1, false, true))
        assertFalse(progress.stalled(2, 19999, 0, 1, false, true))
        assertTrue(progress.stalled(2, 20000, 0, 1, false, true))
        progress.reset()
        assertFalse(progress.stalled(3, 0, 0, 1, true, true))
        assertFalse(progress.stalled(3, 30000, 0, 1, false, true))
        assertFalse(progress.stalled(3, 31000, 1000, 2, false, true))
        assertFalse(progress.stalled(3, 34000, 4000, 3, false, true))
    }
    @Test fun pausesSuppressionAndNewPlaybackTokensCannotInheritAStall() {
        val progress = PlaybackProgress()
        progress.stalled(1, 0, 0, 1, false, true)
        assertFalse(progress.stalled(1, 60000, 0, 1, false, false))
        assertFalse(progress.stalled(1, 120000, 0, 1, false, true))
        assertFalse(progress.stalled(2, 150000, 0, 1, false, true))
        assertFalse(progress.stalled(2, 160000, 10000, 2, false, true))
    }
    @Test fun videoProgressIsCheckedWhileAudioOnlyPlaybackUsesItsPosition() {
        val video = PlaybackProgress(); video.stalled(1, 0, 0, 1, false, true)
        assertTrue(video.stalled(1, 20000, 20000, 1, false, true))
        val audio = PlaybackProgress(); audio.stalled(1, 0, 0, null, false, true)
        assertFalse(audio.stalled(1, 60000, 60000, null, false, true))
        assertTrue(audio.stalled(1, 81000, 60000, null, false, true))
    }
}

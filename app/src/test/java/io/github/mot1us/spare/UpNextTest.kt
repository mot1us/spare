package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class UpNextTest {
    private val series = OnDemand("series:1", "Fixture show", "Drama", kind = "series")
    private fun ep(id: Int, season: Int, number: Int) = OnDemand("episode:$id", "Episode $number", "Season $season", kind = "episode", seriesId = series.id, season = season, episode = number)
    private val episodes = listOf(ep(13, 2, 1), ep(11, 1, 1), ep(12, 1, 2), ep(14, 2, 2))

    @Test fun unfinishedEpisodeIsResumed() {
        val pick = UpNext.pick(series, episodes, emptySet(), listOf(WatchProgress(ep(12, 1, 2), 600_000, 2_400_000, 50)), emptySet())!!
        assertTrue(pick.resume); assertEquals("episode:12", pick.episode.id); assertEquals("Resume S1 E2", pick.label)
    }

    @Test fun finishedEpisodeOffersTheNextInSeasonOrder() {
        val pick = UpNext.pick(series, episodes, setOf("episode:12"), listOf(WatchProgress(ep(12, 1, 2), 2_390_000, 2_400_000, 50)), emptySet())!!
        assertFalse(pick.resume); assertEquals("episode:13", pick.episode.id); assertEquals("Next · S2 E1", pick.label)
    }

    @Test fun newlyReleasedNextEpisodeIsMarkedNew() {
        val pick = UpNext.pick(series, episodes, setOf("episode:11", "episode:12", "episode:13"), emptyList(), setOf("episode:14"))!!
        assertTrue(pick.fresh); assertEquals("New · S2 E2", pick.label)
    }

    @Test fun caughtUpOrUnstartedWithoutNewEpisodesOffersNothing() {
        assertNull(UpNext.pick(series, episodes, episodes.map { it.id }.toSet(), emptyList(), emptySet()))
        assertNull(UpNext.pick(series, episodes, emptySet(), emptyList(), emptySet()))
        assertEquals("episode:14", UpNext.pick(series, episodes, emptySet(), emptyList(), setOf("episode:14"))!!.episode.id)
    }

    @Test fun newEpisodesSortBeforeRecentlyWatched() {
        val old = UpNext.Pick(series, ep(11, 1, 1), "New", fresh = true, resume = false, recency = 1)
        val recent = UpNext.Pick(series.copy(id = "series:2"), ep(21, 1, 1), "Next", fresh = false, resume = false, recency = 99)
        assertEquals(listOf(old, recent), UpNext.order(listOf(recent, old)))
    }
}

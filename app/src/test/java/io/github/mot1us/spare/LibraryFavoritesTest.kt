package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LibraryFavoritesTest {
    private val series = OnDemand("series:1", "Fixture series", "Drama", kind = "series")
    private fun episode(id: Int) = OnDemand("episode:$id", "Episode $id", "Season 1", kind = "episode", seriesId = series.id, season = 1, episode = id)
    @Test fun firstCheckEstablishesBaselineAndLaterAdditionsStayNewUntilWatched() {
        val first = LibraryFavorites().save(series, true).update(series, listOf(episode(1), episode(2)), 1000)
        assertEquals("2 unwatched episodes", first.entries.single().label(first.watched))
        val later = first.update(series, listOf(episode(1), episode(2), episode(3)), 2000)
        assertEquals("1 new · 3 unwatched", later.entries.single().label(later.watched))
        val watched = later.completed(listOf("episode:3"))
        assertEquals("2 unwatched episodes", watched.entries.single().label(watched.watched))
        val all = watched.completed(listOf("episode:1", "episode:2"))
        assertEquals("All caught up", all.entries.single().label(all.watched))
        val undone = all.unwatched(listOf("episode:2"))
        assertEquals("1 unwatched episode", undone.entries.single().label(undone.watched))
    }
    @Test fun completedEpisodesSurviveRemovingAFavoriteAndRestarting() {
        val state = LibraryFavorites().completed(listOf("episode:1")).save(series, true).update(series, listOf(episode(1), episode(2)), 1000)
        val removed = LibraryFavorites.from(state.save(series, false).json())
        assertFalse(removed.contains(series.id)); assertEquals(setOf("episode:1"), removed.watched)
        val restored = LibraryFavorites.from(removed.save(series, true).update(series, listOf(episode(1), episode(2)), 2000).json())
        assertEquals("1 unwatched episode", restored.entries.single().label(restored.watched))
    }
    @Test fun duplicateAndOtherSeriesEpisodesCannotInflateTheBadges() {
        val episodes = listOf(episode(1), episode(1), episode(2).copy(seriesId = "series:2"))
        val state = LibraryFavorites().save(series, true).update(series, episodes, 1000)
        assertEquals(setOf("episode:1"), state.entries.single().episodes)
        assertEquals("1 unwatched episode", state.entries.single().label(state.watched))
        val movie = OnDemand("movie:1", "Fixture movie", "Cinema")
        assertEquals("Movie", state.save(movie, true).entries.last().label(state.watched))
    }
}

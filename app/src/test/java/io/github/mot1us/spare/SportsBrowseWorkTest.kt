package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsBrowseWorkTest {
    private val teams = listOf(SportsCatalog.byId.getValue("nfl:SEA"), SportsCatalog.byId.getValue("nfl:SF"))
    private val games = (1..240).map { SportsGame(teams, 1000L * it, 36000000, emptyList(), eventId = "fixture-$it", competition = "NFL") }
    @Test fun hundredsOfGamesOnlyWarmTheFocusedViewportAndABoundedFollowedSet() {
        val visible = games.drop(180).take(6)
        val work = SportsBrowseWork.targets(games, visible.map { it.key }.toSet(), visible[2], setOf(teams[0].id))
        assertEquals(8, work.size); assertEquals(visible[2], work.first())
        assertTrue(work.containsAll(visible)); assertEquals(240, games.size)
        assertTrue(work.drop(6).all { it in games.take(2) })
    }
    @Test fun largeCachedSchedulesRemainBrowsableWithoutTheOld256GameCutoff() {
        val now = 1800000000000L
        val events = (1..600).map { LiveEvent("large-$it", teams.map { it.id }.toSet(), now - 60000, league = "NFL") }
        val snapshot = LiveSportsSnapshot(events, now, setOf("NFL"))
        val saved = SportsSchedule.from(SportsSchedule.json(snapshot))
        assertEquals(600, saved.events.size)
        val slate = SportsSchedule.games(saved, Guide.EMPTY, "today", emptySet(), now, matchChannels = false)
        assertEquals(600, slate.size)
        assertTrue(SportsBrowseWork.targets(slate, slate.takeLast(6).map { it.key }.toSet(), slate.last(), emptySet()).size <= 8)
    }

    @Test fun leagueChangesRejectOldFocusedGamesAndAnEmptyViewportGetsOneRow() {
        val subset = games.drop(200)
        assertEquals(subset.take(3), SportsBrowseWork.targets(subset, emptySet(), games[0], emptySet()))
        assertTrue(SportsBrowseWork.targets(emptyList(), emptySet(), games[0], emptySet()).isEmpty())
        assertEquals(2, SportsBrowseWork.targets(games, emptySet(), null, emptySet(), limit = 2).size)
        assertTrue(SportsBrowseWork.targets(games, emptySet(), games[0], emptySet(), limit = 0).isEmpty())
    }
}

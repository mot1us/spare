package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsBrowseWorkTest {
    private val teams = listOf(SportsCatalog.byId.getValue("nfl:SEA"), SportsCatalog.byId.getValue("nfl:SF"))
    private val games = (1..240).map { SportsGame(teams, 1000L * it, 36000000, emptyList(), eventId = "fixture-$it", competition = "NFL") }
    @Test fun theCursorAndTheScreenGoFirstAndEveryOtherGameFollowsInOrder() {
        val visible = games.drop(180).take(6)
        val plan = SportsBrowseWork.plan(games.take(60), visible.take(3).map { it.key }.toSet() + games[10].key, games[12], setOf(teams[0].id))
        assertEquals(games[12], plan.near.first())
        assertEquals("Focused, on screen, then two followed games", listOf(games[12], games[10], games[0], games[1]), plan.near)
        assertEquals("Every other live game follows in the order shown", games.take(60).filter { it !in plan.near }, plan.later)
        assertEquals(60, plan.all.size); assertEquals(60, plan.all.distinct().size)
    }
    @Test fun aHugeSlateIsBoundedButFarLargerThanTheScreen() {
        val visible = games.drop(180).take(6)
        val plan = SportsBrowseWork.plan(games, visible.map { it.key }.toSet(), visible[2], setOf(teams[0].id))
        assertEquals(8, plan.near.size); assertEquals(visible[2], plan.near.first())
        assertTrue(plan.near.containsAll(visible)); assertTrue(plan.near.drop(6).all { it in games.take(2) })
        assertEquals(SportsBrowseWork.MAX_GAMES, plan.all.size)
        assertEquals(games.first { it !in plan.near }, plan.later.first())
    }
    @Test fun largeCachedSchedulesRemainBrowsableWithoutTheOld256GameCutoff() {
        val now = 1800000000000L
        val events = (1..600).map { LiveEvent("large-$it", teams.map { it.id }.toSet(), now - 60000, league = "NFL") }
        val snapshot = LiveSportsSnapshot(events, now, setOf("NFL"))
        val saved = SportsSchedule.from(SportsSchedule.json(snapshot))
        assertEquals(600, saved.events.size)
        val slate = SportsSchedule.games(saved, Guide.EMPTY, "today", emptySet(), now, matchChannels = false)
        assertEquals(600, slate.size)
        assertTrue(SportsBrowseWork.plan(slate, slate.takeLast(6).map { it.key }.toSet(), slate.last(), emptySet()).all.size <= SportsBrowseWork.MAX_GAMES)
    }

    @Test fun leagueChangesRejectOldFocusedGamesAndAnEmptyViewportStartsWithTheTopRow() {
        val subset = games.drop(200)
        val plan = SportsBrowseWork.plan(subset, emptySet(), games[0], emptySet())
        assertEquals(subset.take(3), plan.near); assertEquals(subset.drop(3), plan.later)
        assertTrue(SportsBrowseWork.plan(emptyList(), emptySet(), games[0], emptySet()).all.isEmpty())
        assertEquals(2, SportsBrowseWork.plan(games, emptySet(), null, emptySet(), limit = 2).all.size)
        assertTrue(SportsBrowseWork.plan(games, emptySet(), games[0], emptySet(), limit = 0).all.isEmpty())
    }
}

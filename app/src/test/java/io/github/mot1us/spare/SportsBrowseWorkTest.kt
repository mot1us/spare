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
    @Test fun everyGameGetsItsFirstLookBeforeTheCardsOnScreenAreLookedAtAgain() {
        val slate = games.take(40)
        val plan = SportsBrowseWork.plan(slate, slate.take(9).map { it.key }.toSet(), slate[0], emptySet())
        // A slow box: the nine cards on screen were looked up long ago and found nothing, so they are due again.
        val looked = slate.take(9).map { it.key }.toSet()
        val queue = SportsBrowseWork.queue(plan, looked = { it.key in looked }, upgrade = { false }, due = { game, _ -> game.key in looked }, lastLook = { 0L })
        assertEquals("Every unlooked game comes first, in order", slate.drop(9), queue.take(31).map { it.game })
        assertTrue(queue.take(31).all { it.look == SportsBrowseWork.Look.FIRST })
        assertEquals("Then the cards on screen are looked at again", slate.take(9).toSet(), queue.drop(31).map { it.game }.toSet())
        assertTrue(queue.drop(31).all { it.look == SportsBrowseWork.Look.AGAIN })
    }
    @Test fun theScreenStillGoesFirstAndRepeatLooksStartWithTheOldest() {
        val slate = games.take(12)
        val plan = SportsBrowseWork.plan(slate, slate.drop(6).take(3).map { it.key }.toSet(), null, emptySet())
        val first = SportsBrowseWork.queue(plan, looked = { false }, upgrade = { false }, due = { _, _ -> true }, lastLook = { 0L })
        assertEquals(slate.drop(6).take(3), first.take(3).map { it.game }); assertTrue(first.take(3).all { it.look == SportsBrowseWork.Look.FIRST_NEAR })
        val lastLook = slate.withIndex().associate { (i, game) -> game.key to 1000L - i }
        val again = SportsBrowseWork.queue(plan, looked = { true }, upgrade = { it == slate[7] }, due = { _, _ -> true }, lastLook = { lastLook.getValue(it.key) })
        assertEquals("A sharper feed for a card on screen comes before repeat looks", SportsBrowseWork.Step(slate[7], SportsBrowseWork.Look.UPGRADE), again.first())
        assertEquals("Repeat looks start with the game looked at longest ago", slate.filter { it != slate[7] }.reversed(), again.drop(1).map { it.game })
    }
    @Test fun theViewersCardAndEveryCardBeforeItKeepTheirPlaces() {
        val shown = games.take(9)
        val ended = shown[2].copy(state = "post", status = "Final")
        // Game 2 ended and left Live now; game 3 changed score; two new games start, one sorted before the cursor.
        val next = listOf(shown[0], shown[1], shown[3].copy(scores = mapOf("x" to "7")), games[100], shown[4], shown[5], shown[6], games[101], shown[7], shown[8])
        val held = SportsBrowseWork.hold(shown, next, shown[5].key, mapOf(ended.key to ended))
        assertEquals("The cursor's card does not move", 5, held.indexOfFirst { it.key == shown[5].key })
        assertEquals(listOf(shown[0], shown[1], ended, shown[3].copy(scores = mapOf("x" to "7")), shown[4], shown[5]), held.take(6))
        assertEquals("New games and the rest follow the cursor", listOf(games[100], shown[6], games[101], shown[7], shown[8]), held.drop(6))
        assertEquals("A card the scoreboard no longer lists cannot be held", next, SportsBrowseWork.hold(shown, next, shown[5].key))
        assertEquals("A card that is not shown holds nothing", next, SportsBrowseWork.hold(shown, next, "elsewhere"))
    }
    @Test fun theGameOnThePlayingChannelNamesThatChannelUntilItsSearchFindsOne() {
        val now = 2000L
        val live = games[0]; val teamsPlaying = live.teams.mapTo(hashSetOf()) { it.id }
        val playing = teamsPlaying to "ESPN+ 05: Seahawks vs 49ers"
        for (waiting in listOf("", SportsPanel.FIND_STREAM, "Finding stream…", SportsPanel.CHECKING_NAMES, "No stream names this game", "Select to finish stream search"))
            assertEquals(waiting, "Playing now · ESPN+ 05: Seahawks vs 49ers", SportsBrowseWork.withPlaying(waiting, live, playing, now))
        assertEquals("A card that found its stream keeps it", "FOX 4K", SportsBrowseWork.withPlaying("FOX 4K", live, playing, now))
        assertEquals("1 stream names this game\nSelect to choose", SportsBrowseWork.withPlaying("1 stream names this game\nSelect to choose", live, playing, now))
        assertEquals("Another game's card is unchanged", "No stream names this game", SportsBrowseWork.withPlaying("No stream names this game", live, setOf("nfl:SEA", "nfl:LAR") to "Other", now))
        assertEquals("Nothing playing", "No stream names this game", SportsBrowseWork.withPlaying("No stream names this game", live, null, now))
        assertEquals("A game that is not live", "", SportsBrowseWork.withPlaying("", live.copy(state = "pre"), playing, now))
    }
}

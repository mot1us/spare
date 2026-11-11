package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class GuideProgrammeIndexTest {
    @Test fun indexedSportsMatchesTheScanAcrossProvidersAndTimeWindows() {
        val now = 180_000_000L
        val teams = listOf("nfl:SEA", "nfl:SF").map(SportsCatalog.byId::getValue)
        val game = SportsGame(teams, now - 1_000, now + 7_200_000, emptyList(), networks = listOf("FOX"))
        val first = Channel("first", "FOX Sports HD", "Sports", "https://example.com/1", guideId = "shared")
        val duplicate = first.copy(id = "duplicate", name = "FOX Sports FHD")
        val named = first.copy(id = "named", name = "Seahawks vs 49ers", guideId = "", playlistId = "other")
        val radio = named.copy(id = "radio", name = "Seahawks vs 49ers Radio")
        val near = Programme("shared", "Seahawks at 49ers", now - 2_000, now + 1_000)
        val scheduled = Programme("named", "NFL: Seattle at San Francisco", now + 60_000, now + 7_200_000)
        val guide = Guide(listOf(first, duplicate, named, radio), mapOf(
            "shared" to listOf(near.copy(title = "Seahawks vs 49ers Classic"), near),
            "named" to listOf(scheduled),
        ))
        val builder = GuideProgrammeIndex.Builder()
        val index = builder.prepare(guide, now)
        for (target in listOf(game, game.copy(start = now + 60_000, state = "pre"), game.copy(start = now + 50 * 3_600_000L, state = "pre"))) {
            assertEquals(SportsSchedule.match(target, guide, setOf(first.id), now),
                SportsSchedule.match(target, guide, setOf(first.id), now, index = index))
        }
        val refreshed = guide.copy(programmes = guide.programmes + ("named" to listOf(scheduled.copy(start = now - 3_000))))
        assertFalse(index.isFor(refreshed))
        val next = builder.prepare(refreshed, now + 1)
        assertSame(index.programmesByKey["shared"], next.programmesByKey["shared"])
        assertEquals(SportsSchedule.match(game, refreshed, emptySet(), now), SportsSchedule.match(game, refreshed, emptySet(), now, index = next))
    }

    @Test fun sharedRowsKeepNormalisedTitlesAndOnlyThePreparedWindow() {
        val now = 200_000_000L
        val channel = Channel("one", "One", "", "https://example.com/live")
        val listing = Programme("one", "Café, on TV", now, now + 3_600_000L)
        val guide = Guide(listOf(channel), mapOf("one" to listOf(
            listing.copy(start = 0, end = now - GuideProgrammeIndex.PAST), listing,
            listing.copy(start = now + GuideProgrammeIndex.FUTURE, end = now + GuideProgrammeIndex.FUTURE + 1),
        )))
        val index = GuideProgrammeIndex.Builder().prepare(guide, now)
        assertEquals(listing, index.programmesByKey.getValue("one").single().programme)
        assertEquals("cafeontv", index.programmesByKey.getValue("one").single().titleKey)
    }
}

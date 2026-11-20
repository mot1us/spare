package io.github.mot1us.spare

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Made-up event-channel names in the common provider formats; no real provider data. */
class SportsFixtureNamesTest {
    private fun at(iso: String) = java.time.Instant.parse(iso).toEpochMilli()
    private val start = at("2026-12-05T18:00:00Z") // 1:00 PM Eastern
    private val nextWeek = at("2026-12-12T18:00:00Z")
    private fun team(id: String) = SportsCatalog.byId.getValue(id)
    // Richmond Spiders and Towson Tigers have the same catalog numbers in men's and women's basketball.
    private fun game(league: String, start: Long, networks: List<String> = listOf("ESPN+")) = SportsGame(
        listOf(team("${league.lowercase()}:257"), team("${league.lowercase()}:119")).sortedBy { it.id }, start, start + 8 * 3600000L,
        emptyList(), "fixture-$league-$start", "in", networks, competition = league)
    private fun channel(id: Int, name: String, group: String) = Channel("xc:$id", name, group, "https://provider.example.test/live/user/secret/$id.ts")
    @After fun reset() = EventChannelNames.clear()

    @Test fun eventNamesThatDeclareAnotherDayOrStartTimeDoNotCount() {
        val plus = "ESPN+ 21: Richmond vs. Towson @ Dec 5 1:00PM ET"
        val ncaab = "NCAAB 12: RICHMOND @ TOWSON (W) | 12.05 1:00 PM | ESPN+"
        assertFalse(EventChannelNames.declaresOtherTime(plus, start))
        assertTrue("Event slots keep an old game's name for days", EventChannelNames.declaresOtherTime(plus, nextWeek))
        assertTrue("The same day six hours later is another game", EventChannelNames.declaresOtherTime(plus, at("2026-12-06T00:00:00Z")))
        assertFalse(EventChannelNames.declaresOtherTime(ncaab, start))
        assertTrue(EventChannelNames.declaresOtherTime(ncaab, nextWeek))
        assertFalse("Day.month reads too", EventChannelNames.declaresOtherTime("EVENT 3: Richmond v Towson 05.12", start))
        assertFalse("12/5 is month/day", EventChannelNames.declaresOtherTime("Game 4: Richmond vs Towson 12/5", start))
        assertFalse("A dotted kick-off time is not a date", EventChannelNames.declaresOtherTime("EVENT 3: Richmond v Towson 7.30pm", nextWeek))
        assertFalse("A name without a date or time says nothing", EventChannelNames.declaresOtherTime("ESPN+ 12: Richmond vs. Towson", nextWeek))
        assertEquals('w', SportsGuide.gender(ncaab))
        assertNull(SportsGuide.gender("ESPN+ 12: Richmond vs. Towson"))
    }

    @Test fun womensAndMensGamesEachFindTheirOwnEventChannel() {
        val now = start + 30 * 60000L
        val womens = channel(12, "NCAAB 12: RICHMOND @ TOWSON (W) | 12.05 1:00 PM | ESPN+", "NCAAB")
        val mens = channel(13, "NCAAB 13: RICHMOND @ TOWSON (M) | 12.05 1:00 PM | ESPN+", "NCAAB")
        val bare = channel(7, "Unlimited 7: Basketball: Richmond vs. Towson (12.05 01:00 PM ET)", "Event Streams")
        val guide = Guide(listOf(womens, mens, bare), emptyMap())
        val women = SportsSchedule.match(game("NCAAW", start), guide, emptySet(), now)
        assertEquals("A bare \"Basketball:\" name counts in the game's own league", setOf(womens.id, bare.id), women.titleMatches.map { it.id }.toSet())
        assertEquals(setOf(mens.id, bare.id), SportsSchedule.match(game("NCAAM", start), guide, emptySet(), now).titleMatches.map { it.id }.toSet())
        assertTrue("The card names a stream that names the game", SportsStreams.cardSummary(women, GuideFilters(), emptyMap(), guide, now).let { it == womens.name || it == bare.name })
        assertTrue("Days-old names do not match a rematch", SportsSchedule.match(game("NCAAW", nextWeek), guide, emptySet(), nextWeek + 60000).titleMatches.isEmpty())
    }

    @Test fun onlyTheGamesBroadcasterChannelsArePossibleStreamsAndTheCardSaysNoneNamesIt() {
        val now = start + 30 * 60000L
        val plus = (1..5).map { channel(200 + it, "ESPN+ $it", "ESPN+") }
        val espn = listOf(channel(301, "US: ESPN HD", "Sports"), channel(302, "US: ESPN 2 FHD", "Sports"), channel(303, "ESPNews", "Sports"))
        val nicknames = listOf(channel(401, "MLB: Detroit Tigers", "MLB"), channel(402, "Spiders TV", "Locals"))
        val guide = Guide(plus + espn + nicknames, emptyMap())
        val matched = SportsSchedule.match(game("NCAAW", start), guide, emptySet(), now)
        assertEquals("ESPN+ is not ESPN, and a team nickname is not a broadcaster", plus.map { it.id }.toSet(), matched.candidates.map { it.id }.toSet())
        assertEquals("No stream names this game yet\nSelect to look through ESPN+ channels", SportsStreams.cardSummary(matched, GuideFilters(), emptyMap(), guide, now))
        val nothing = SportsSchedule.match(game("NCAAW", start, listOf("SECN+")), guide, emptySet(), now)
        assertEquals("No stream names this game", SportsStreams.cardSummary(nothing, GuideFilters(), emptyMap(), guide, now))
    }

    @Test fun eventCategoriesForTheChosenLeaguesAreReadFirst() {
        val events = (1..30).flatMap { group -> (1..3).map { channel(group * 10 + it, "PPV $group$it: No Event", "Events $group") } }
        val college = (1..3).map { channel(900 + it, "NCAAB 0$it: No Event", "NCAAB") }
        val channels = events + college
        assertFalse("In list order the college category was past the limit", "NCAAB" in EventChannelNames.eventGroups(channels))
        val chosen = EventChannelNames.eventGroups(channels, EventChannelNames.leagueWords("NCAAW") + "ESPN+")
        assertEquals("NCAAB", chosen.first())
        assertEquals(EventChannelNames.MAX_CATEGORIES, chosen.size)
    }
}

package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsGuideTest {
    private val now = 1800000000000L
    private fun programme(title: String, channel: String = "one", start: Long = now - 600000, description: String = "") = Programme(channel, title, start, start + 3 * 3600000L, description)
    private fun ids(title: String, description: String = "") = SportsGuide.teams(programme(title, description = description))?.map { it.id }?.toSet()
    @Test fun matchesFullNamesNicknamesAbbreviationsAndDescriptions() {
        assertEquals(setOf("mlb:NYY", "mlb:BOS"), ids("MLB: New York Yankees at Boston Red Sox"))
        assertEquals(setOf("mlb:LAD", "mlb:SF"), ids("Dodgers vs. Giants"))
        assertEquals(setOf("nfl:BUF", "nfl:NYG"), ids("Bills @ Giants"))
        assertEquals(setOf("nfl:BUF", "nfl:KC"), ids("NFL: BUF v KC"))
        assertEquals(setOf("mlb:NYY", "mlb:BOS"), ids("NYY @ BOS"))
        assertEquals(setOf("nfl:DAL", "nfl:PHI"), ids("NFL Football", "Dallas Cowboys at Philadelphia Eagles. Live coverage."))
        assertEquals(setOf("mlb:ATH", "mlb:SEA"), ids("MLB Baseball: Athletics vs Mariners"))
    }
    @Test fun ambiguousSharedNamesRequireLeagueOrSpecificNames() {
        assertNull(ids("Giants vs Cardinals"))
        assertNull(ids("SF @ SEA"))
        assertEquals(setOf("mlb:SF", "mlb:STL"), ids("MLB: Giants vs Cardinals"))
        assertEquals(setOf("nfl:NYG", "nfl:ARI"), ids("NFL: Giants vs Cardinals"))
        assertEquals(setOf("mlb:SF", "mlb:STL"), ids("San Francisco Giants vs St Louis Cardinals"))
    }
    @Test fun excludesShowsReplaysAndPartialWordMatches() {
        listOf("Yankees vs Red Sox Highlights", "MLB Classic: Dodgers at Giants", "NFL Pregame: Bills at Chiefs", "NFL Live", "Baseball Tonight", "Yankees Clubhouse", "MLB: Tigersharks vs Pirateship").forEach {
            assertNull(it, ids(it, "Yankees vs Red Sox"))
        }
    }
    @Test fun groupsAlternativeChannelsKeepsDoubleheadersAndFiltersFollowedTeams() {
        val channels = (1..4).map { Channel("$it", "Channel $it", "Sports", "https://example.com/$it", guideId = "guide$it") }
        val guide = Guide(channels, mapOf(
            "guide1" to listOf(programme("Yankees vs Red Sox", "guide1"), programme("Red Sox at Yankees", "guide1", now + 5 * 3600000)),
            "guide2" to listOf(programme("MLB: NYY @ BOS", "guide2", now - 300000)),
            "guide3" to listOf(programme("Bills at Chiefs", "guide3", now + 3600000)),
            "guide4" to listOf(programme("Bills at Chiefs Replay", "guide4")),
        ))
        val games = SportsGuide.find(guide, emptySet(), setOf("2"), now)
        assertEquals(3, games.size); assertTrue(games.first().live(now))
        assertEquals(listOf("2", "1"), games.first().broadcasts.map { it.channel.id })
        assertEquals(2, SportsGuide.find(guide, setOf("mlb:NYY"), emptySet(), now).size)
        assertEquals(1, SportsGuide.find(guide, setOf("nfl:BUF"), emptySet(), now).size)
    }
    @Test fun expiredAndBeyondHorizonListingsAreNotGamesAndSharedGuideIdsKeepAlternates() {
        val channels = (1..3).map { Channel("$it", "Channel $it", "Sports", "https://example.com/$it", guideId = "shared") }
        val guide = Guide(channels, mapOf("shared" to listOf(programme("Bills vs Chiefs", "shared"),
            programme("Bills vs Chiefs", "shared", now - 4 * 3600000), programme("Bills vs Chiefs", "shared", now + SportsGuide.HORIZON))))
        val games = SportsGuide.find(guide, emptySet(), emptySet(), now)
        assertEquals(1, games.size); assertEquals(3, games.single().broadcasts.size)
    }
    @Test fun warmupIsBoundedPrioritizesFollowedTeamsAndDeduplicatesEpgIds() {
        val channels = (1..25001).map { Channel("$it", "Sports $it", "MLB", "https://example.com/$it", guideId = "${it / 2}") } +
            Channel("followed", "New York Yankees", "USA", "https://example.com/nyy", guideId = "nyy")
        val warm = SportsGuide.warmChannels(channels, setOf("25000"), setOf("mlb:NYY"))
        assertEquals(160, warm.size); assertEquals("followed", warm.first().id)
        assertEquals("25000", warm[1].id); assertEquals(warm.size, warm.map { it.guideId }.distinct().size)
    }
    @Test fun catalogHasAllTeamsAndStableDistinctLeagueIds() {
        assertEquals(30, SportsCatalog.teams.count { it.league == "MLB" }); assertEquals(32, SportsCatalog.teams.count { it.league == "NFL" })
        assertEquals(62, SportsCatalog.byId.size)
    }
    @Test fun leagueContextSupportsCityListingsWithoutGuessingAmbiguousCities() {
        assertEquals(setOf("nfl:CHI", "nfl:MIN"), ids("NFL Football", "Chicago at Minnesota. Live."))
        assertEquals(setOf("mlb:BOS", "mlb:SEA"), ids("MLB: Boston vs Seattle"))
        assertNull(ids("MLB: New York vs Los Angeles"))
        assertEquals(setOf("mlb:SF", "mlb:STL"), SportsGuide.teams(programme("Giants vs Cardinals"), "MLB Extra Innings")!!.map { it.id }.toSet())
        assertNull(SportsGuide.teams(programme("Live Sports"), "MLB Yankees"))
    }
    @Test fun scanReachesOtherNetworksBeforeDuplicateQualityVariantsFillBudget() {
        val duplicates = (1..200).map { Channel("$it", "USA ESPN FHD $it", "Sports", "https://example.com/$it") }
        val cbs = Channel("cbs", "CBS", "USA", "https://example.com/cbs")
        assertTrue(SportsGuide.warmChannels(duplicates + cbs, emptySet(), emptySet()).contains(cbs))
    }
    @Test fun teamChannelFallbackUsesNamesAndDoesNotInventGames() {
        val channels = listOf(Channel("nyy", "MLB New York Yankees FHD", "MLB", "https://example.com/nyy"), Channel("news", "News", "USA", "https://example.com/news"))
        assertEquals(listOf(channels.first()), SportsGuide.teamChannels(channels, setOf("mlb:NYY")))
        assertTrue(SportsGuide.find(Guide(channels, emptyMap()), setOf("mlb:NYY"), emptySet(), now).isEmpty())
    }

}

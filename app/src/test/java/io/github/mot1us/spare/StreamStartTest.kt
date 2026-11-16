package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class StreamStartTest {
    @Test fun aBlockedRedirectExplainsTheHttpSetting() {
        val message = SourceErrors.message(HttpFailure(302))
        assertTrue(message, message.contains("Allow HTTP-only links"))
    }
    @Test fun alternateGameFeedsComeAfterTheMainBroadcast() {
        fun ch(id: String, name: String) = Channel(id, name, "NBA", "https://example.test/$id.ts")
        val studio = ch("1", "NBA LP 18 | Knicks @ Raptors (Raptors (Studio Show))")
        val mobile = ch("2", "NBA LP 20 | Knicks @ Raptors (Mobile View (In-Arena))")
        val main = ch("3", "NBA LP 17 | Knicks @ Raptors (Knicks)")
        assertTrue(SportsStreams.alternateFeed(studio)); assertTrue(SportsStreams.alternateFeed(mobile)); assertFalse(SportsStreams.alternateFeed(main))
        val game = SportsGame(listOf(SportsCatalog.byId.getValue("nba:NY"), SportsCatalog.byId.getValue("nba:TOR")), 0, 1, emptyList(), "g", "in",
            competition = "NBA", titleMatches = listOf(studio, mobile, main))
        assertEquals("3", SportsStreams.rank(game, GuideFilters(), emptyMap()).first().id)
    }
}

class ProviderRedirectRetryTest {
    @Test fun aRedirectReachingThePlayerIsNotRetried() {
        val policy = ProviderLoadErrors()
        listOf(301, 302, 307, 403, 404).forEach { assertTrue("$it", policy.final(it)) }
        listOf(408, 500, 503, null).forEach { assertFalse("$it", policy.final(it)) }
    }
}

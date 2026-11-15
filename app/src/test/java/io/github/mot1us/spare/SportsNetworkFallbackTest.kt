package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsNetworkFallbackTest {
    private val now = 1800000000000L
    @Test fun broadcasterChannelsShowingAnotherGameAreNotSuggested() {
        val channels = listOf(
            Channel("cbs-a", "US: CBS Buffalo", "US Locals", "https://example.com/a", guideId = "cbs.a"),
            Channel("cbs-b", "US: CBS Kansas City", "US Locals", "https://example.com/b", guideId = "cbs.b"),
            Channel("cbs-c", "US: CBS Boston", "US Locals", "https://example.com/c", guideId = "cbs.c"))
        fun listing(guideId: String, title: String) = guideId to listOf(Programme(guideId, title, now - 600000, now + 3 * 3600000L))
        val guide = Guide(channels, mapOf(
            listing("cbs.b", "NFL Football: Kansas City Chiefs at Atlanta Falcons"),
            listing("cbs.c", "NFL Football")))
        val game = SportsGame(listOf(SportsCatalog.byId.getValue("nfl:BUF"), SportsCatalog.byId.getValue("nfl:NYJ")), now - 600000, now + 3 * 3600000L,
            emptyList(), "fixture", "in", networks = listOf("CBS"), competition = "NFL")
        val matched = SportsSchedule.match(game, guide, emptySet(), now)
        assertEquals(setOf("cbs-a", "cbs-c"), matched.candidates.map { it.id }.toSet())
    }
}

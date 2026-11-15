package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class SportsGuideCoverageTest {
    private val now = 1800000000000L
    private fun channel(id: String, name: String, group: String = "Sports") = Channel(id, name, group, "https://example.test/$id", guideId = id)
    private fun on(id: String, title: String, description: String = "") = id to listOf(Programme(id, title, now - 600000, now + 3600000, description))

    @Test fun findsClearlyLiveGolfTennisRacingAndFights() {
        val channels = listOf(channel("g1", "Golf Channel"), channel("g2", "US: Golf HD"), channel("t1", "Tennis Channel"),
            channel("f1", "Sky Sports F1"), channel("u1", "ESPN+ 1"), channel("n1", "FOX"))
        val guide = Guide(channels, mapOf(
            on("g1", "LIVE: PGA Tour Golf - The RSM Classic, Round 3"), on("g2", "Live: PGA Tour Golf - The RSM Classic, Round 3"),
            on("t1", "ATP Finals: Sinner vs Alcaraz (Live)"), on("f1", "Live: Las Vegas Grand Prix"),
            on("u1", "UFC Fight Night: Live Prelims"), on("n1", "NASCAR Cup Series - Live")))
        val found = SportsGuideCoverage.find(guide, now)
        assertEquals(listOf("Golf", "Tennis", "Racing", "Racing", "Fights"), found.map { it.sport })
        val golf = found.first()
        assertEquals("PGA Tour Golf - The RSM Classic, Round 3", golf.title)
        assertEquals("Both golf channels show the one event", setOf("g1", "g2"), golf.channels.map { it.id }.toSet())
        assertEquals("ATP Finals: Sinner vs Alcaraz", found[1].title)
    }

    @Test fun leavesOutShowsReplaysUnmarkedListingsAndOtherSports() {
        val channels = listOf(channel("a", "Golf Channel"), channel("b", "Golf Channel 2"), channel("c", "Sky Sports F1"),
            channel("d", "Tennis Channel"), channel("e", "Sky Sports F1 UHD"), channel("f", "Golf Channel 3"), channel("g", "Boxing TV"))
        val guide = Guide(channels, mapOf(
            on("a", "Live From the Masters"), on("b", "Golf Central"), on("c", "LIVE: Premier League: Arsenal v Chelsea"),
            on("d", "Tennis Channel Live"), on("e", "F1 Grand Prix Highlights"), on("f", "PGA Tour Golf - Round 2"),
            on("g", "Boxing: Classic Fights Replay - Live")))
        assertTrue(SportsGuideCoverage.find(guide, now).isEmpty())
    }

    @Test fun usesTheChannelForTheSportOnlyWhenTheTitleNamesNoOtherSport() {
        val channels = listOf(channel("a", "Sky Sports Golf"), channel("b", "Sky Sports Golf 2"))
        val guide = Guide(channels, mapOf(on("a", "Live: Round 4"), on("b", "Live: NFL Football")))
        assertEquals(listOf("Round 4"), SportsGuideCoverage.find(guide, now).map { it.title })
    }

    @Test fun onlyWhatIsAiringNow() {
        val guide = Guide(listOf(channel("a", "Golf Channel")), mapOf("a" to listOf(Programme("a", "LIVE: LPGA Tour Golf", now + 600000, now + 7200000))))
        assertTrue(SportsGuideCoverage.find(guide, now).isEmpty())
    }
}

package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveSportsTest {
    private val now = 1800000000000L
    private fun event(state: String = "in", name: String = "STATUS_IN_PROGRESS", completed: Boolean = false, start: Long = now - 600000): String {
        val type = JSONObject().put("state", state).put("name", name).put("completed", completed)
        val teams = JSONArray(listOf("SEA", "SF").map { JSONObject().put("team", JSONObject().put("abbreviation", it)) })
        return JSONObject().put("events", JSONArray().put(JSONObject().put("id", "1").put("date", Instant.ofEpochMilli(start).toString()).put("status", JSONObject().put("type", type)).put("competitions", JSONArray().put(JSONObject().put("competitors", teams))))).toString()
    }
    @Test fun onlyConfirmedInProgressGamesAreLive() {
        assertEquals(setOf("nfl:SEA", "nfl:SF"), LiveSports.parse(event(), "NFL", now).single().teams)
        listOf("STATUS_HALFTIME", "STATUS_END_PERIOD", "STATUS_OVERTIME").forEach { assertEquals(1, LiveSports.parse(event(name = it), "NFL", now).size) }
        listOf("STATUS_SCHEDULED", "STATUS_DELAYED", "STATUS_SUSPENDED", "STATUS_POSTPONED", "STATUS_FINAL", "UNKNOWN").forEach { assertTrue(it, LiveSports.parse(event(name = it), "NFL", now).isEmpty()) }
        assertTrue(LiveSports.parse(event(state = "pre"), "NFL", now).isEmpty()); assertTrue(LiveSports.parse(event(completed = true), "NFL", now).isEmpty())
        assertTrue(LiveSports.parse(event(start = now + 3600000), "NFL", now).isEmpty())
    }
    @Test fun currentProviderListingCannotTurnFutureSeahawksGameIntoLiveMatch() {
        val channel = Channel("1", "NFL Seahawks", "Sports", "https://example.com/live")
        val guide = Guide(listOf(channel), mapOf("1" to listOf(Programme("1", "Seahawks vs 49ers", now - 3600000, now + 10 * 3600000))))
        val provider = SportsGuide.find(guide, emptySet(), emptySet(), now, liveOnly = true)
        assertEquals(1, provider.size)
        assertTrue(LiveSportsSnapshot(LiveSports.parse(event(state = "pre", name = "STATUS_SCHEDULED", start = now + 5 * 3600000), "NFL", now), now, setOf("NFL")).confirm(provider, now).isEmpty())
        val snapshot = LiveSportsSnapshot(LiveSports.parse(event(), "NFL", now), now, setOf("NFL"))
        assertEquals(1, snapshot.confirm(provider, now).size)
        assertTrue(snapshot.confirm(provider, now + 120001).isEmpty()); assertTrue(snapshot.copy(leagues = emptySet()).confirm(provider, now).isEmpty())
    }
    @Test fun networkFailuresDoNotFallBackToUnverifiedLiveClaimsAndRequestsContainNoPlaylist() {
        val urls = mutableListOf<String>()
        val snapshot = LiveSports.load(now) { url -> urls.add(url); if ("football/nfl" in url) event() else throw java.io.IOException("Offline") }
        assertEquals(setOf("NFL"), snapshot.leagues); assertEquals(1, snapshot.events.size)
        assertEquals(4, urls.size); assertTrue(urls.all { it.startsWith("https://site.api.espn.com/") && "username" !in it && "password" !in it })
        assertTrue(urls.all { "dates=" in it && "limit=100" in it })
        assertTrue(LiveSports.load(now) { throw java.io.IOException() }.leagues.isEmpty())
    }
}

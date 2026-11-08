package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsSituationTest {
    private val now = 1800000000000L
    private fun fixture(): JSONObject {
        val sides = JSONArray(listOf("nfl:SEA", "nfl:SF").map { id ->
            val team = SportsCatalog.byId.getValue(id)
            JSONObject().put("team", JSONObject().put("id", if (id == "nfl:SEA") "1001" else "1002").put("abbreviation", id.substringAfter(':')).put("displayName", team.name)).put("score", "7")
        })
        return JSONObject().put("events", JSONArray().put(JSONObject().put("id", "situation-fixture").put("date", Instant.ofEpochMilli(now - 60000).toString())
            .put("competitions", JSONArray().put(JSONObject().put("competitors", sides)
                .put("status", JSONObject().put("period", 2).put("displayClock", "8:24").put("type", JSONObject().put("name", "STATUS_IN_PROGRESS").put("state", "in").put("shortDetail", "8:24 - 2nd")))
                .put("situation", JSONObject().put("possession", "1001").put("downDistanceText", "3rd & 7 at SEA 28"))))))
    }
    private fun competition(json: JSONObject) = json.getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0)

    @Test fun footballSituationSurvivesScheduleCacheAndScoreDelayWithoutSpoilers() {
        val event = LiveSports.parse(fixture().toString(), "NFL", now).single()
        assertEquals("8:24 - 2nd", event.status)
        assertEquals("3rd & 7 at SEA 28", event.situation); assertEquals("nfl:SEA", event.possession)
        val initial = LiveSportsSnapshot(listOf(event), now, setOf("NFL"))
        assertEquals(initial, SportsSchedule.from(SportsSchedule.json(initial)))
        val game = SportsSchedule.games(initial, Guide.EMPTY, "live", emptySet(), now, matchChannels = false).single()
        assertEquals(event.situation, game.situation); assertEquals(event.possession, game.possession)
        val delay = SportsScoreDelay(); delay.observe(initial, 0)
        val next = event.copy(status = "7:55 - 2nd", situation = "1st & 10 at SF 30", possession = "nfl:SF")
        delay.observe(initial.copy(events = listOf(next)), 20000)
        val current = game.copy(status = next.status, situation = next.situation, possession = next.possession)
        assertEquals("", delay.display(current, 30, 10000, now).situation)
        assertEquals("", delay.display(current, 30, 10000, now).possession)
        val shown = delay.display(current, 30, 30000, now)
        assertEquals(game.status, shown.status); assertEquals(game.situation, shown.situation); assertEquals(game.possession, shown.possession)
        val advanced = delay.display(current, 30, 50000, now)
        assertEquals(next.situation, advanced.situation); assertEquals(next.possession, advanced.possession)
        val stale = SportsSchedule.games(initial, Guide.EMPTY, "today", emptySet(), now + 120001, matchChannels = false).single()
        assertEquals("", stale.situation); assertEquals("", stale.possession)
    }

    @Test fun absentFinalAndUnmatchedPossessionRemainUnknown() {
        val json = fixture(); val comp = competition(json)
        comp.remove("situation")
        assertEquals("", LiveSports.parse(json.toString(), "NFL", now).single().situation)
        comp.put("situation", JSONObject().put("possession", "unknown").put("shortDownDistanceText", "2nd & 4").put("possessionText", "SEA 40"))
        val unmatched = LiveSports.parse(json.toString(), "NFL", now).single()
        assertEquals("", unmatched.possession); assertEquals("2nd & 4 · SEA 40", unmatched.situation)
        comp.getJSONObject("status").getJSONObject("type").put("name", "STATUS_FINAL").put("state", "post").put("completed", true)
        val final = LiveSports.parse(json.toString(), "NFL", now, onlyLive = false, includeCompleted = true).single()
        assertEquals("", final.situation); assertEquals("", final.possession)
    }

    @Test fun periodClockAndBaseballDetailsUseOnlyValidSuppliedValues() {
        val status = JSONObject().put("state", "in").put("description", "In Progress")
        assertEquals("Q3 · 4:18", SportsSituation.status(JSONObject().put("period", 3).put("displayClock", "4:18"), status, "NCAAF"))
        assertEquals("Half 2 · 1:02", SportsSituation.status(JSONObject().put("period", 2).put("displayClock", "1:02"), status, "NCAAM"))
        assertEquals("In Progress", SportsSituation.status(JSONObject(), status, "NFL"))
        assertEquals("2 outs · 3–2 count", SportsSituation.detail(JSONObject().put("outs", 2).put("balls", 3).put("strikes", 2), "MLB"))
        assertEquals("", SportsSituation.detail(JSONObject().put("outs", 99).put("balls", -1), "MLB"))
        assertEquals("", SportsSituation.detail(null, "NFL"))
    }
}

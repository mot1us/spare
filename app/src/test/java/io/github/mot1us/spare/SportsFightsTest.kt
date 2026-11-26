package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsFightsTest {
    private val now = System.currentTimeMillis()
    private val hour = 3600000L

    /** One bout as ESPN's MMA scoreboard lists it. */
    private fun bout(red: Pair<String, String>, blue: Pair<String, String>, start: Long, state: String = "pre", winner: Int = -1, network: String = "Paramount+") = JSONObject()
        .put("date", Instant.ofEpochMilli(start).toString()).put("status", JSONObject().put("type", JSONObject().put("state", state)))
        .put("broadcasts", JSONArray().put(JSONObject().put("names", JSONArray().put(network))))
        .put("competitors", JSONArray(listOf(red, blue).mapIndexed { i, (id, name) -> JSONObject().put("id", id).put("winner", i == winner)
            .put("records", JSONArray().put(JSONObject().put("type", "total").put("summary", if (i == 0) "24-6-0" else "15-1-0")))
            .put("athlete", JSONObject().put("displayName", name).put("shortName", name.first() + ". " + name.substringAfterLast(' '))
                .put("flag", JSONObject().put("href", "https://a.espncdn.com/i/teamlogos/countries/500/ngr.png"))) }))
    private fun night(id: String, name: String, start: Long, state: String, bouts: List<JSONObject>, completed: Boolean = false) = JSONObject().put("id", id).put("name", name)
        .put("date", Instant.ofEpochMilli(start).toString()).put("status", JSONObject().put("type", JSONObject().put("state", state).put("completed", completed)))
        .put("competitions", JSONArray(bouts))
    private fun board(vararg nights: JSONObject) = JSONObject().put("events", JSONArray(nights.toList())).toString()

    @Test fun aFightNightIsOneCardNamedForItsMainEvent() {
        val start = now + 5 * 24 * hour
        val upcoming = night("600", "UFC Fight Night: Adesanya vs. Magomedov", start, "pre", listOf(
            bout("11" to "Mateusz Rebecki", "12" to "Elves Brener", start),
            bout("21" to "Israel Adesanya", "22" to "Shara Magomedov", start + 3 * hour)))
        val event = LiveSports.parse(board(upcoming), "UFC", now, onlyLive = false, includeCompleted = true).single()
        assertEquals("UFC Fight Night: Adesanya vs. Magomedov", event.event)
        assertEquals("The card starts with the first bout", start, event.start)
        val fighters = event.teams.map { SportsCatalog.byId.getValue(it) }
        assertEquals(setOf("Israel Adesanya", "Shara Magomedov"), fighters.map { it.name }.toSet())
        assertEquals("UFC", fighters.first().league)
        assertTrue("A fighter is found by surname", fighters.first { it.name == "Israel Adesanya" }.aliases.contains("Adesanya"))
        assertEquals(listOf("Paramount+"), event.networks); assertEquals(2, event.records.size); assertEquals(2, event.logos.size)
        assertEquals("Main card ${GuideClock.time(start + 3 * hour)}", event.status)
        // Fighters need no table: a schedule saved on this TV keeps its names and event after a restart.
        val snapshot = LiveSportsSnapshot(listOf(event), now, setOf("UFC"))
        assertEquals(snapshot, SportsSchedule.from(SportsSchedule.json(snapshot)))
        val game = SportsSchedule.games(snapshot, Guide.EMPTY, "league:UFC", emptySet(), now, matchChannels = false).single()
        assertEquals("UFC Fight Night", SportsLeagues.label(game)); assertEquals("UFC", game.leagueId)
        assertNull("A fighter id from another promotion or a team id is not a fighter", SportsFighters.team("nfl:fighter:1:Somebody"))
        assertNull("No fighters in the team chooser", SportsCatalog.teams.firstOrNull { it.league == "UFC" })
    }
    @Test fun aLiveFightNightSaysWhoIsFightingAndAFinishedOneWhoWon() {
        val start = now - 2 * hour
        val live = night("601", "PFL Lyon: Lapilus vs. McKee", start, "in", listOf(
            bout("31" to "Taylor Lapilus", "32" to "Mitch McKee", start, state = "in", network = "ESPN+"),
            bout("41" to "Cedric Doumbe", "42" to "Some Opponent", start + hour, network = "ESPN+")))
        val event = LiveSports.parse(board(live), "PFL", now).single()
        assertEquals("in", event.state); assertEquals("Now: T. Lapilus vs M. McKee", event.status)
        val over = night("602", "UFC 324: Gaethje vs. Pimblett", now - 8 * hour, "post", listOf(
            bout("51" to "Justin Gaethje", "52" to "Paddy Pimblett", now - 6 * hour, state = "post", winner = 1)), completed = true)
        val final = LiveSports.parse(board(over), "UFC", now, onlyLive = false, includeCompleted = true).single()
        assertEquals("post", final.state); assertEquals("Final · P. Pimblett won the main event", final.status)
        assertTrue("A finished night is left out where only live and upcoming games count", LiveSports.parse(board(over), "UFC", now, onlyLive = false).isEmpty())
        // The card shows the status where a team game shows its score.
        val game = SportsSchedule.games(LiveSportsSnapshot(listOf(final), now, setOf("UFC")), Guide.EMPTY, "league:UFC", emptySet(), now, matchChannels = false).single()
        assertEquals("Final · P. Pimblett won the main event", SportsCardContent.scoreboard(game, hidden = false, startTime = "").message)
        assertEquals("Score hidden", SportsCardContent.scoreboard(game, hidden = true, startTime = "").message)
    }
    @Test fun aFightNightsStreamsNameBothFightersOrComeFromItsBroadcaster() {
        val start = now - hour
        val event = LiveSports.parse(board(night("603", "UFC Fight Night: Adesanya vs. Magomedov", start, "in", listOf(
            bout("21" to "Israel Adesanya", "22" to "Shara Magomedov", start, state = "in")))), "UFC", now).single()
        val game = SportsSchedule.games(LiveSportsSnapshot(listOf(event), now, setOf("UFC")), Guide.EMPTY, "live", emptySet(), now, matchChannels = false).single()
        val listed = Channel("listed", "US: Fight Pass", "Sports", "https://example.test/1")
        val oneName = Channel("one", "US: Sports Extra", "Sports", "https://example.test/2")
        val named = Channel("named", "PPV 01: UFC Fight Night: Adesanya vs Magomedov", "PPV", "https://example.test/3")
        val broadcaster = Channel("paramount", "US: Paramount+ 4K", "Paramount+", "https://example.test/4")
        val unrelated = Channel("news", "US: Israel News 24", "News", "https://example.test/5")
        val guide = Guide(listOf(listed, oneName, named, broadcaster, unrelated), mapOf(
            listed.id to listOf(Programme(listed.id, "UFC Fight Night: Adesanya vs. Magomedov", start, start + 5 * hour)),
            oneName.id to listOf(Programme(oneName.id, "Adesanya: The Story", start, start + 5 * hour)),
            unrelated.id to listOf(Programme(unrelated.id, "Israel tonight", start, start + 5 * hour))))
        val matched = SportsSchedule.match(game, guide, emptySet(), now)
        assertEquals("A listing naming both fighters", listOf("listed"), matched.broadcasts.map { it.channel.id })
        assertEquals("An event channel named for the fight", listOf("named"), matched.titleMatches.map { it.id })
        assertEquals("The broadcaster's channel as a possible stream", listOf("paramount"), matched.candidates.map { it.id })
    }
}

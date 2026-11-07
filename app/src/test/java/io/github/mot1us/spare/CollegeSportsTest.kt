package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CollegeSportsTest {
    private val now = Instant.parse("2026-11-07T20:00:00Z").toEpochMilli()
    private fun teams(title: String, context: String = "") = SportsGuide.teams(Programme("channel", title, now - 1000, now + 3600000), context)?.map { it.id }?.toSet()
    private fun game(league: String) = SportsGame(listOf("Washington Huskies", "Washington State Cougars").map { name -> SportsCatalog.teams.single { it.league == league && it.name == name } }, now - 1000, now + 3600000, emptyList(), "college-game", competition = league)

    @Test fun directoryHasDistinctCollegeSportsAndRealFbsFcsSchools() {
        assertTrue(SportsCatalog.teams.count { it.league == "NCAAF" } >= 260)
        assertTrue(SportsCatalog.teams.count { it.league == "NCAAM" } >= 360)
        assertTrue(SportsCatalog.teams.count { it.league == "NCAAW" } >= 360)
        assertTrue(SportsCatalog.teams.count { it.league == "NCAABSB" } >= 290)
        assertEquals("UT Rio Grande Valley Vaqueros", SportsCatalog.byId.getValue("ncaaf:292").name)
        assertEquals(SportsCatalog.teams.size, SportsCatalog.byId.size)
        assertFalse(SportsCatalog.teams.any { it.name in setOf("TBA", "American", "National", "Team Gaither") })
    }
    @Test fun scoreboardsIncludeUnrankedDivisionOneAndBothFootballSubdivisions() {
        val day = LocalDate.of(2026, 11, 7)
        val football = SportsLeagues.byId.getValue("NCAAF").scoreboards(day)
        assertEquals(2, football.size); assertTrue(football.any { "groups=80" in it }); assertTrue(football.any { "groups=81" in it })
        for (league in listOf("NCAAF", "NCAAM", "NCAAW", "NCAABSB")) {
            assertTrue(SportsLeagues.byId.getValue(league).scoreboards(day).all { "dates=20261107" in it && "limit=500" in it })
        }
        assertTrue(SportsLeagues.byId.getValue("NCAAW").scoreboards(day).single().endsWith("groups=50"))
        assertFalse("Baseball does not use basketball's group 50", "groups=" in SportsLeagues.byId.getValue("NCAABSB").scoreboards(day).single())
        assertEquals(listOf("https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?dates=20261107&limit=100"), SportsLeagues.byId.getValue("NFL").scoreboards(day))
    }
    @Test fun collegeSportContextResolvesSharedSchoolNamesAndLongerSchoolAliases() {
        assertEquals(setOf("ncaaf:264", "ncaaf:265"), teams("College Football: Washington at Washington State"))
        assertEquals(setOf("ncaam:264", "ncaam:265"), teams("Basketball: Washington Huskies vs Washington State Cougars", "NCAAM"))
        assertEquals(setOf("ncaaw:264", "ncaaw:265"), teams("Women's College Basketball: Washington at Washington State"))
        assertEquals(setOf("ncaaw:264", "ncaaw:265"), teams("College Basketball: Washington at Washington State", "NCAAW"))
        assertNull("The same schools' football feed must not match a basketball game", teams("Football: Washington at Washington State", "NCAAW"))
        assertNull("An unspecified school matchup must not pick a men's or women's sport", teams("Washington vs Washington State"))
        val texasTech = SportsCatalog.teams.single { it.league == "NCAAF" && it.name == "Texas Tech Red Raiders" }
        val baylor = SportsCatalog.teams.single { it.league == "NCAAF" && it.name == "Baylor Bears" }
        assertEquals(setOf(texasTech.id, baylor.id), teams("NCAA Football: Texas Tech at Baylor"))
        assertEquals(setOf("ncaam:41", "ncaam:30"), teams("NCAAM: Connecticut Huskies at Southern California Trojans"))
        assertEquals(setOf("ncaabsb:133", "ncaabsb:134"), teams("NCAA Baseball: Washington at Washington State"))
        assertEquals("https://a.espncdn.com/i/teamlogos/ncaa/500/264.png", SportsCatalog.byId.getValue("ncaabsb:133").logo)
        assertEquals(setOf("nfl:SEA", "nfl:SF"), teams("NFL: Seattle Seahawks at San Francisco 49ers", "NCAAF"))
    }
    @Test fun collegeScoreboardsPopulateScoresRecordsCalendarAndReminders() {
        for (league in listOf("NCAAF", "NCAAM", "NCAAW", "NCAABSB")) {
            val clubs = game(league).teams
            val competitors = JSONArray(clubs.map { team -> JSONObject().put("score", "21").put("records", JSONArray().put(JSONObject().put("type", "total").put("summary", "6-2")))
                .put("team", JSONObject().put("id", team.apiId).put("displayName", team.name).put("logo", team.logo)) })
            val status = JSONObject().put("state", "pre").put("name", "STATUS_SCHEDULED")
            val event = JSONObject().put("id", "123").put("date", Instant.ofEpochMilli(now + 60000).toString())
                .put("competitions", JSONArray().put(JSONObject().put("competitors", competitors).put("status", JSONObject().put("type", status))))
            val parsed = LiveSports.parse(JSONObject().put("events", JSONArray().put(event)).toString(), league, now, onlyLive = false).single()
            assertEquals(clubs.map { it.id }.toSet(), parsed.teams); assertEquals(2, parsed.records.size); assertEquals(2, parsed.logos.size)
            val snapshot = LiveSportsSnapshot(listOf(parsed), now, setOf(league))
            val upcoming = SportsSchedule.games(snapshot, Guide.EMPTY, "today", emptySet(), now, matchChannels = false).single()
            assertEquals(league, upcoming.leagueId)
            val reminders = GameReminders(); assertTrue(reminders.toggle(upcoming))
            assertEquals(upcoming.key, reminders.due(now + 60000).single().key)
            assertEquals(snapshot, SportsSchedule.from(SportsSchedule.json(snapshot)))
        }
    }
    @Test fun matchingAndReplayUseTheRequestedCollegeSportAndExistingQualityRanking() {
        val channel = Channel("college", "ESPN FHD", "NCAA", "https://example.test/live", catchupDays = 2, catchupMode = "append", catchupTemplate = "?utc=\${start}")
        val wrong = channel.copy(id = "wrong", name = "ESPN 4K")
        val programme = Programme(channel.id, "Basketball: Washington at Washington State", now - 1000, now + 3600000)
        val guide = Guide(listOf(channel, wrong), mapOf(channel.id to listOf(programme), wrong.id to listOf(programme.copy(channel = wrong.id, title = "NBA: Boston Celtics at Los Angeles Lakers"))))
        val matched = SportsSchedule.match(game("NCAAM"), guide, emptySet(), now)
        assertEquals(listOf(channel.id), matched.broadcasts.map { it.channel.id })
        val ended = matched.copy(state = "post")
        assertEquals(channel.id, SportsReplays.find(ended, guide.copy(programmes = mapOf(channel.id to listOf(programme.copy(end = now - 1)))), now).single().channel.id)
    }
    @Test fun reminderRefreshChecksBothFootballDivisionsWithinTheExistingRequestPool() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        SportsSchedule.reminderEvents(setOf("NCAAF" to LocalDate.of(2026, 11, 8)), now) { url -> requests.add(url); "{\"events\":[]}" }
        assertEquals(2, requests.size); assertTrue(requests.any { "groups=81" in it })
    }
}

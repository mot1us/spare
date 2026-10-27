package io.github.mot1us.spare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsRebuildTest {
    @Test fun redZoneDestinationUsesPlaylistNamesHonorsFiltersAndDoesNotInventAnEvent() {
        val channels = listOf(Channel("one", "US: NFL RedZone FHD", "NFL", "https://example.test/one"), Channel("two", "NFL Red Zone 4K", "NFL", "https://example.test/two"), Channel("other", "NFL Network", "NFL", "https://example.test/three"))
        assertEquals(listOf("one", "two"), SportsDestinations.redZone(channels, GuideFilters(), emptyMap()).map { it.id })
        assertTrue(SportsDestinations.redZone(channels, GuideFilters(1080, FrameRateFilter.sixtyOrBetter), emptyMap()).isEmpty())
        assertEquals(listOf("two"), SportsDestinations.redZone(channels, GuideFilters(), emptyMap()) { it.id != "one" }.map { it.id })
    }
    @Test fun liveScopeContainsOnlyFreshInProgressEventsAcrossLeagues() {
        val events = listOf(LiveEvent("nfl", setOf("nfl:BUF", "nfl:KC"), now - 1000, league = "NFL"),
            LiveEvent("mlb", setOf("mlb:NYY", "mlb:BOS"), now - 1000, league = "MLB"),
            LiveEvent("next", setOf("nfl:SEA", "nfl:SF"), now + 3600000, "pre", league = "NFL"),
            LiveEvent("final", setOf("nfl:SEA", "nfl:SF"), now - 3600000, "post", league = "NFL"))
        val snapshot = LiveSportsSnapshot(events, now, setOf("NFL", "MLB"))
        assertEquals(setOf("nfl", "mlb"), SportsSchedule.games(snapshot, Guide.EMPTY, "live", emptySet(), now, false).map { it.eventId }.toSet())
        assertTrue(SportsSchedule.games(snapshot.copy(checked = now - 86400000), Guide.EMPTY, "live", emptySet(), now, false).isEmpty())
    }
    private val now = 1800000000000L
    @Test fun addedLeagueScoreboardsResolveTeamsAndShareSoccerClubIdentity() {
        listOf("NBA", "NHL", "EPL", "MLS", "UCL").forEach { league ->
            val clubs = SportsCatalog.teams.filter { league in it.competitions }.take(2)
            val competitors = JSONArray(clubs.map { JSONObject().put("score", "2").put("team", JSONObject().put("id", it.apiId).put("displayName", it.name)) })
            val competition = JSONObject().put("competitors", competitors).put("status", JSONObject().put("type", JSONObject().put("name", "STATUS_IN_PROGRESS").put("state", "in")))
            val event = JSONObject().put("id", "123").put("date", Instant.ofEpochMilli(now - 600000).toString()).put("competitions", JSONArray().put(competition))
            val parsed = LiveSports.parse(JSONObject().put("events", JSONArray().put(event)).toString(), league, now).single()
            assertEquals(clubs.map { it.id }.toSet(), parsed.teams); assertEquals(league, parsed.league)
            val snapshot = LiveSportsSnapshot(listOf(parsed), now, setOf(league))
            assertEquals(snapshot, SportsSchedule.from(SportsSchedule.json(snapshot)))
            assertEquals(league, SportsSchedule.games(snapshot, Guide.EMPTY, "league:$league", emptySet(), now).single().leagueId)
        }
    }
    @Test fun featuredGamePrefersMyLiveGameThenMyNextGameAndKeepsFinalScoresSeparate() {
        fun game(a: String, b: String, state: String, start: Long) = SportsGame(listOf(SportsCatalog.byId.getValue(a), SportsCatalog.byId.getValue(b)), start, start + 3600000, emptyList(), "$a:$state", state)
        val other = game("nfl:BUF", "nfl:KC", "in", now - 1000)
        val mineNext = game("nfl:SEA", "nfl:SF", "pre", now + 86400000)
        val mineLive = mineNext.copy(eventId = "my-live", state = "in", start = now - 1000)
        assertEquals(mineNext, SportsPanel.featured(listOf(other, mineNext), setOf("nfl:SEA"), now))
        assertEquals(mineNext, SportsPanel.ordered(listOf(other, mineNext), setOf("nfl:SEA"), now).first())
        assertEquals(mineLive, SportsPanel.featured(listOf(other, mineNext, mineLive), setOf("nfl:SEA"), now))
        assertEquals(other, SportsPanel.featured(listOf(other, mineNext.copy(state = "post")), setOf("nfl:SEA"), now))
        assertFalse(mineLive.copy(eventId = "", state = "post").live(now)); assertFalse(mineLive.copy(eventId = "", state = "hold").live(now))
    }
    @Test fun soccerFindsTheNextMatchdayBetweenRounds() {
        val date = Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC)
        val json = JSONObject().put("leagues", JSONArray().put(JSONObject().put("calendar", JSONArray().put(date.minusDays(1).toString()).put(date.plusDays(3).toString()).put(date.plusDays(7).toString()))))
        assertEquals(date.plusDays(3).toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), SportsSchedule.nextMatchday(json.toString(), now))
    }
    @Test fun programmeArtworkSurvivesCacheAndOldCacheTuplesRemainReadable() {
        val p = Programme("one", "A show", now, now + 3600000, "Details", "https://images.example/hero.jpg")
        assertEquals(p, Programme.from(p.json()))
        assertEquals("", Programme.from(JSONArray().put("one").put("A show").put(now).put(now + 3600000).put("Details")).artwork)
        val xml = "<tv><programme channel=\"one\" start=\"20270115080000 +0000\" stop=\"20270115100000 +0000\"><title>A show</title><icon src=\"https://images.example/poster.jpg\"/><image orient=\"L\">https://images.example/hero.jpg</image></programme></tv>"
        val start = Importers.timestamp("20270115080000 +0000")
        assertEquals("https://images.example/hero.jpg", Importers.xmltv(xml.toByteArray(), setOf("one"), start).getValue("one").single().artwork)
        val castOnly = xml.replace("<icon src=\"https://images.example/poster.jpg\"/><image orient=\"L\">https://images.example/hero.jpg</image>", "<credits><actor><image>https://images.example/person.jpg</image></actor></credits>")
        assertEquals("", Importers.xmltv(castOnly.toByteArray(), setOf("one"), start).getValue("one").single().artwork)
    }
    @Test fun gameArtworkMustBelongToTheRequestedEventAndUseAnAllowedUrl() {
        val json = """{"article":{"gameId":"123","images":[{"width":900,"url":"https://images.example/game.jpg","credit":"Photo agency"}]}}"""
        assertEquals("https://images.example/game.jpg" to "Photo agency", SportsArtwork.parse(json, "123"))
        assertEquals("" to "", SportsArtwork.parse(json, "456"))
        assertEquals("" to "", SportsArtwork.parse(json.replace("https://images.example/game.jpg", "file:///secret"), "123"))
    }
    @Test fun todayIncludesUnfollowedUpcomingGamesAcrossLeaguesAndKeepsOtherDaysSeparate() {
        val time = Instant.parse("2026-10-26T21:00:00Z").toEpochMilli()
        val tonight = LiveEvent("monday", setOf("nfl:DAL", "nfl:PHI"), time + 3 * 3600000L, "pre", league = "NFL")
        val basketball = LiveEvent("nba", setOf("nba:LAL", "nba:BOS"), time - 300000, "in", league = "NBA")
        val tomorrow = tonight.copy(id = "tomorrow", start = time + 30 * 3600000L)
        val saved = tonight.copy(id = "yesterday", start = time - 30 * 3600000L, state = "post")
        val snapshot = LiveSportsSnapshot(listOf(tonight, basketball, tomorrow, saved), time, setOf("NFL", "NBA"))
        val games = SportsSchedule.games(snapshot, Guide.EMPTY, "today", emptySet(), time, false, java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(setOf("monday", "nba"), games.map { it.eventId }.toSet())
        assertEquals(4, SportsSchedule.games(snapshot, Guide.EMPTY, null, emptySet(), time, false).size)
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        assertTrue(SportsSchedule.onDay(tonight.start, time, zone))
        assertFalse(SportsSchedule.onDay(Instant.parse("2026-10-27T08:00:00Z").toEpochMilli(), time, zone))
    }

}

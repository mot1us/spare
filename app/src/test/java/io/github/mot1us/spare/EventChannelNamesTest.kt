package io.github.mot1us.spare

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EventChannelNamesTest {
    private val source = Source("p1", "Fixture", "xtream", "https://provider.example.test", "user", "secret")
    private fun pass(n: Int, name: String = "NFL Game Pass $n: NO EVENT") = Channel("xc:${100 + n}", name, "NFL Game Pass", "https://provider.example.test/live/user/secret/${100 + n}.ts")
    @After fun reset() = EventChannelNames.clear()

    @Test fun recognisesProviderEventSlots() {
        assertTrue(EventChannelNames.looksLikeEvent("NFL Game Pass 11: NO EVENT"))
        assertTrue(EventChannelNames.looksLikeEvent("PPV 3 | UFC Fight Night"))
        assertTrue(EventChannelNames.looksLikeEvent("ESPN+ 07: Not Live"))
        assertFalse(EventChannelNames.looksLikeEvent("ESPN HD"))
        assertFalse(EventChannelNames.looksLikeEvent("US: Fox 5 New York"))
        val channels = (1..4).map { pass(it) } + Channel("xc:1", "ESPN HD", "Sports", "https://provider.example.test/1.ts") +
            listOf(Channel("xc:2", "Fox 5: News", "Locals", "https://provider.example.test/2.ts"))
        assertEquals("Only a category with several event slots counts", setOf("NFL Game Pass"), EventChannelNames.eventGroups(channels))
    }

    @Test fun readsOnlyTheEventCategories() {
        val requested = mutableListOf<String>()
        val fresh = EventChannelNames.fetch(source, setOf("NFL Game Pass")) { url ->
            requested += url.substringAfter("action=")
            when {
                "get_live_categories" in url -> """[{"category_id":"7","category_name":"NFL Game Pass"},{"category_id":"8","category_name":"Movies"}]"""
                else -> """[{"stream_id":"111","name":"NFL Game Pass 11: SEA @ LV"},{"stream_id":"bad","name":"x"}]"""
            }
        }
        assertEquals(mapOf("xc:111" to "NFL Game Pass 11: SEA @ LV"), fresh)
        assertEquals(listOf("get_live_categories", "get_live_streams&category_id=7"), requested)
        assertTrue("M3U playlists have no categories to ask for", EventChannelNames.fetch(source.copy(kind = "m3u"), setOf("NFL Game Pass")) { error("no request") }.isEmpty())
    }

    @Test fun aRenamedSlotIsFoundForItsLiveGame() {
        val now = 1800000000000L
        val slots = (1..11).map { pass(it) }
        val guide = Guide(slots, emptyMap())
        val game = SportsGame(listOf(SportsCatalog.byId.getValue("nfl:SEA"), SportsCatalog.byId.getValue("nfl:LV")), now - 600000, now + 3 * 3600000L,
            emptyList(), "fixture", "in", competition = "NFL")
        assertTrue("With the saved names nothing names the game", SportsSchedule.match(game, guide, emptySet(), now).titleMatches.isEmpty())
        assertTrue(EventChannelNames.update(slots, { "p1" }, mapOf("p1" to mapOf("xc:111" to "NFL Game Pass 11: SEA @ LV"))))
        assertEquals(listOf("xc:111"), SportsSchedule.match(game, guide, emptySet(), now).titleMatches.map { it.id })
        assertEquals("NFL Game Pass 11: SEA @ LV", EventChannelNames.name(slots.last()))
        assertFalse("The same names again change nothing", EventChannelNames.update(slots, { "p1" }, mapOf("p1" to mapOf("xc:111" to "NFL Game Pass 11: SEA @ LV"))))
    }
    @Test fun categoriesAreReadAFewAtATimeAndOneThatFailsKeepsTheOthers() {
        val source = Source(name = "Fixture", kind = "xtream", url = "https://provider.example", username = "u", password = "p")
        val categories = (1..9).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"Events $it"}""" }
        val inFlight = java.util.concurrent.atomic.AtomicInteger(); var most = 0
        val fresh = EventChannelNames.fetch(source, (1..9).map { "Events $it" }.toSet()) { url ->
            if ("get_live_categories" in url) categories else {
                val id = url.substringAfter("category_id=").toInt()
                val now = inFlight.incrementAndGet(); synchronized(this) { most = maxOf(most, now) }
                try { Thread.sleep(60); if (id == 4) error("timeout") else """[{"stream_id":${id * 10},"name":"NBA $id: Game"}]""" } finally { inFlight.decrementAndGet() }
            }
        }
        assertEquals((1..9).filter { it != 4 }.associate { "xc:${it * 10}" to "NBA $it: Game" }, fresh)
        assertTrue("Requests overlap, at most ${EventChannelNames.PARALLEL_CATEGORIES} at a time: $most", most in 2..EventChannelNames.PARALLEL_CATEGORIES)
    }
    @Test fun savedNamesComeBackForKnownPlaylistsWhileTheyAreRecent() {
        val now = 1_800_000_000_000L
        val saved = EventChannelNames.Saved(now - 3600000, mapOf("main" to mapOf("xc:5" to "NBA 05: Hawks vs Celtics"), "gone" to mapOf("xc:6" to "Old")))
        val back = EventChannelNames.Saved.from(saved.json(), setOf("main"), now)!!
        assertEquals(mapOf("main" to mapOf("xc:5" to "NBA 05: Hawks vs Celtics")), back.names); assertEquals(now - 3600000, back.at)
        assertNull("Names from yesterday are not used", EventChannelNames.Saved.from(saved.json(), setOf("main"), now + EventChannelNames.SAVED_MAX_MS))
        assertNull("Names for removed playlists are not used", EventChannelNames.Saved.from(saved.json(), setOf("other"), now))
        assertNull(EventChannelNames.Saved.from("not json", setOf("main"), now))
    }
}

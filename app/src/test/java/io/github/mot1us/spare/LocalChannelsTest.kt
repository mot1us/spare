package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LocalChannelsTest {
    private val local = LocalChannels("KING\tSeattle\tWA\tWashington\nKIRO\tSeattle\tWA\tWashington\nKAPP\tYakima\tWA\tWashington\nWABC\tNew York\tNY\tNew York\n")
    private fun channel(id: String, name: String, group: String = "US Locals", guide: String = "") = Channel(id, name, group, "https://example.com/$id", guideId = guide)
    private val channels = listOf(channel("king", "NBC KING5 FHD"), channel("kiro", "CBS HD", guide = "KIRODT.us"), channel("yakima", "KAPP HD"), channel("ny", "WABC FHD"), channel("label", "FOX Seattle 4K"), channel("sport", "Seattle Seahawks game", "NFL"))
    @Test fun cityAndCityStateMatchCallSignsAndProviderLabelsWithoutSportsFalsePositives() {
        assertEquals(setOf("king", "kiro", "label"), local.find(channels, "Seattle, WA"))
        assertEquals(local.find(channels, "Seattle"), local.find(channels, "Seattle, WA"))
    }
    @Test fun stateKeepsOtherCitiesAndQualityVariants() {
        assertEquals(setOf("king", "kiro", "yakima", "label", "king2"), local.find(channels + channel("king2", "KING 4K"), "Washington"))
        assertEquals(local.find(channels, "Washington"), local.find(channels, "WA"))
    }
    @Test fun emptyAndUnknownLocationsCannotExposeTheWholePlaylist() {
        assertTrue(local.find(channels, "").isEmpty()); assertTrue(local.find(channels, "Unknown place").isEmpty())
        assertTrue(local.find(channels.map { it.copy(media = true) }, "Seattle").isEmpty())
    }
    @Test fun offlineSuggestionsIncludeCanonicalCitiesAndStatesWithoutDuplicates() {
        assertEquals(listOf("Seattle, WA"), local.locations("sea"))
        assertEquals(listOf("Seattle, WA"), local.locations("Seattle, W"))
        assertEquals(listOf("Washington"), local.locations("wa"))
        assertEquals(listOf("New York", "New York, NY"), local.locations("new y"))
        assertEquals(listOf("New York"), local.locations("NY"))
        assertEquals(setOf("king", "kiro", "label"), local.find(channels, local.locations("sea").single()))
    }
    @Test fun suggestionsAreBoundedAndDoNotTurnUnknownPlacesIntoInventedReferenceData() {
        assertTrue(local.locations("").isEmpty()); assertTrue(local.locations("unknown").isEmpty())
        assertTrue(local.locations("Sea", 0).isEmpty()); assertTrue(local.locations("Sea", -1).isEmpty())
        assertEquals(1, local.locations("New", 1).size)
        val many = LocalChannels((1..30).joinToString("\n") { "CALL$it\tSpring City $it\tWA\tWashington" })
        assertEquals(6, many.locations("Spring").size)
        assertEquals(20, many.locations("Spring", Int.MAX_VALUE).size)
        assertEquals(many.locations("Spring"), many.locations("SPRING"))
    }
}

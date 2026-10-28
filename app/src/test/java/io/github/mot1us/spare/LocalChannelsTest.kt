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
}

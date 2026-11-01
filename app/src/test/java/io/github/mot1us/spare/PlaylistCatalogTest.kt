package io.github.mot1us.spare

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistCatalogTest {
    private val a = Source("a", "Main", "xtream", "https://a.example", "a", "secret-a")
    private val b = Source("b", "Other", "xtream", "https://b.example", "b", "secret-b")
    @Test fun duplicateProviderIdsKeepIndependentGuidesQualityAndOwners() {
        val catalog = PlaylistCatalog(a.id); catalog.configure(listOf(a, b))
        val first = Channel("xc:1", "KING 5", "USA", "https://a.example/1", guideId = "same", reportedQuality = VideoInfo(1280, 720, 30f))
        val second = first.copy(url = "https://b.example/1", reportedQuality = VideoInfo(1920, 1080, 60f))
        val pa = Programme("same", "Main programme", 1, 20); val pb = pa.copy(title = "Other programme")
        val ga = catalog.view(a, Guide(listOf(first), mapOf("same" to listOf(pa))))
        val gb = catalog.view(b, Guide(listOf(second), mapOf("same" to listOf(pb))))
        assertEquals("Legacy channel IDs stay valid", first, ga.channels.single())
        assertNotEquals(ga.channels.single().id, gb.channels.single().id)
        assertNotEquals(ga.key(ga.channels.single()), gb.key(gb.channels.single()))
        assertEquals(b, catalog.owner(gb.channels.single()))
        assertEquals(second, gb.channels.single().native())
        assertEquals("xc:1", Channel.from(gb.channels.single().json()).nativeId)
        val all = Guide(ga.channels + gb.channels, ga.programmes + gb.programmes)
        assertEquals("Main programme", all.at(ga.channels.single(), 5)?.title)
        assertEquals("Other programme", all.at(gb.channels.single(), 5)?.title)
        assertEquals(mapOf("same" to listOf(pb)), catalog.extract(b, all).programmes)
        assertEquals(1080, gb.channels.single().quality(null)?.height)
    }
    @Test fun sourcePreferencesRoundTripWithoutChangingCredentialIdentity() {
        val changed = a.copy(enabled = false, maxStreams = 2)
        assertEquals(a.providerKey(), changed.providerKey())
        assertEquals(changed, Source.from(changed.json()))
        val old = JSONObject(a.json().toString()).apply { remove("enabled"); remove("maxStreams") }
        assertTrue(Source.from(old).enabled); assertEquals(0, Source.from(old).maxStreams)
    }
}

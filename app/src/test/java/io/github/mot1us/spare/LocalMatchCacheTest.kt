package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LocalMatchCacheTest {
    private val channel = Channel("one", "KING5 HD", "Locals", "https://example.test/one", guideId = "KINGDT.us")
    @Test fun cachedResultsAndEmptyResultsRoundTrip() {
        val fingerprint = LocalMatchCache.fingerprint(listOf(channel), "Seattle WA")
        assertEquals(setOf("one"), LocalMatchCache.decode(LocalMatchCache.encode(fingerprint, setOf("one")), fingerprint))
        assertEquals(emptySet<String>(), LocalMatchCache.decode(LocalMatchCache.encode(fingerprint, emptySet()), fingerprint))
    }
    @Test fun editsToLocationStationIdentityOrMediaInvalidateCache() {
        val fingerprint = LocalMatchCache.fingerprint(listOf(channel), "Seattle WA")
        val saved = LocalMatchCache.encode(fingerprint, setOf("one"))
        listOf(channel.copy(name = "Other"), channel.copy(group = "24/7"), channel.copy(guideId = "Other"), channel.copy(id = "two"), channel.copy(media = true)).forEach {
            assertNull(LocalMatchCache.decode(saved, LocalMatchCache.fingerprint(listOf(it), "Seattle WA")))
        }
        assertNull(LocalMatchCache.decode(saved, LocalMatchCache.fingerprint(listOf(channel), "New York")))
    }
    @Test fun corruptOrOldCacheFallsBackToMatching() {
        assertNull(LocalMatchCache.decode(null, "key")); assertNull(LocalMatchCache.decode("bad json".toByteArray(), "key"))
        assertNull(LocalMatchCache.decode("{\"schema\":0,\"fingerprint\":\"key\",\"ids\":[]}".toByteArray(), "key"))
    }
}

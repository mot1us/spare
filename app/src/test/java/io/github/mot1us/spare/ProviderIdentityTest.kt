package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class ProviderIdentityTest {
    @Test fun cacheIdentityChangesOnlyWithProviderAccountAndPreservesSignificantM3uPaths() {
        val source = Source(name = "Fixture", kind = "xtream", url = "https://example.test/", username = "user", password = "private")
        assertEquals(source.providerKey(), source.copy(name = "Renamed", allowHttp = true, url = "https://example.test").providerKey())
        assertNotEquals(source.providerKey(), source.copy(password = "changed").providerKey())
        assertNotEquals(source.providerKey(), source.copy(url = "https://other.test").providerKey())
        val m3u = source.copy(kind = "m3u", url = "https://example.test/list/")
        assertNotEquals(m3u.providerKey(), m3u.copy(url = "https://example.test/list").providerKey())
    }
}

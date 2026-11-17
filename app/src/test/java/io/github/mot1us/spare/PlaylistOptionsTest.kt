package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class PlaylistOptionsTest {
    private val main = Source("p1", "Fixture", "xtream", "https://main.example.test:8443", "user", "secret",
        userAgent = "FixturePlayer/2.0", backupUrl = "https://backup.example.test")

    @Test fun userAgentAndBackupSurviveSavingAndStayOutOfTheCacheIdentity() {
        val restored = Source.from(main.json())
        assertEquals("FixturePlayer/2.0", restored.userAgent); assertEquals("https://backup.example.test", restored.backupUrl)
        assertEquals("Changing these options keeps saved channels and guide", main.copy(userAgent = "", backupUrl = "").providerKey(), main.providerKey())
    }

    @Test fun userAgentIsOneLineOfPlainText() {
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", Network.userAgent("  VLC/3.0.20 LibVLC/3.0.20 "))
        assertEquals("A line break could add request headers", "", Network.userAgent("Player\r\nX-Injected: 1"))
        assertEquals("", Network.userAgent("x".repeat(201)))
        assertEquals("Stored values are cleaned as well", "", Source.from(main.json().put("ua", "bad\nvalue")).userAgent)
    }

    @Test fun eachServerGetsItsPlaylistsUserAgent() {
        Network.useSources(listOf(main, main.copy(id = "p2", url = "https://other.example.test", userAgent = "", backupUrl = "")))
        try {
            assertEquals("FixturePlayer/2.0", Network.userAgentFor("https://main.example.test:8443/live/user/secret/1.ts"))
            assertEquals("FixturePlayer/2.0", Network.userAgentFor("https://backup.example.test/player_api.php"))
            assertNull("Android's own User-Agent", Network.userAgentFor("https://other.example.test/live/1.ts"))
            assertNull("Another port is another server", Network.userAgentFor("https://main.example.test/live/1.ts"))
        } finally { Network.useSources(emptyList()) }
    }

    @Test fun backupServerIsUsedOnlyWhenTheMainOneIsUnreachable() {
        val tried = mutableListOf<String>()
        fun attempt(failure: java.io.IOException?) = runCatching { Importers.withBackup(main) { tried += it.url; if (it.url == main.url && failure != null) throw failure; it.url } }
        assertEquals(main.backupUrl, attempt(java.net.ConnectException("refused")).getOrNull())
        assertEquals(main.backupUrl, attempt(HttpFailure(503)).getOrNull())
        assertEquals(main.url, attempt(null).getOrNull())
        tried.clear()
        assertTrue("Wrong credentials are not a reason to try another server", attempt(HttpFailure(401)).isFailure); assertEquals(listOf(main.url), tried)
        tried.clear()
        assertTrue(runCatching { Importers.withBackup(main.copy(backupUrl = "")) { tried += it.url; throw java.net.SocketTimeoutException() } }.isFailure); assertEquals(listOf(main.url), tried)
    }
}

package io.github.mot1us.spare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPlayerTest {
    @Test fun automaticIsLimitedToGoogleTvStreamerAndManualChoiceWins() {
        assertTrue(LiveTvPlayer.AUTO.prefersVlc("kirkwood", ""))
        assertTrue(LiveTvPlayer.AUTO.prefersVlc("", "Google TV Streamer"))
        assertFalse(LiveTvPlayer.AUTO.prefersVlc("onn", "onn. Streaming Device 4K pro"))
        assertFalse(LiveTvPlayer.AUTO.prefersVlc("other", "Google TV"))
        assertFalse(LiveTvPlayer.BUILT_IN.prefersVlc("kirkwood", "Google TV Streamer"))
        assertTrue(LiveTvPlayer.VLC.prefersVlc("onn", "onn"))
    }

    @Test fun externalPlaybackHonoursPlaylistConnectionSettings() {
        val url = "https://example.org/live.ts"
        val source = Source(name = "Fixture", kind = "m3u", url = url, allowHttp = true)
        assertTrue(LiveTvPlayer.canOpen(source, url))
        assertTrue(LiveTvPlayer.canOpen(source, "http://example.org/live.ts"))
        assertFalse(LiveTvPlayer.canOpen(source.copy(allowHttp = false), url))
        assertFalse(LiveTvPlayer.canOpen(source.copy(enabled = false), url))
        assertFalse(LiveTvPlayer.canOpen(source.copy(userAgent = "custom"), url))
        assertFalse(LiveTvPlayer.canOpen(source, "file:///tmp/live.ts"))
    }
}

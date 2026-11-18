package io.github.mot1us.spare

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamerQuirksTest {
    @Test fun matchesTheStreamerByCodenameOrGoogleModelOnly() {
        assertTrue(StreamerQuirks.matches("Google", "Google TV Streamer", "kirkwood"))
        assertTrue(StreamerQuirks.matches("", "", " Kirkwood "))
        assertTrue(StreamerQuirks.matches("google", "Google TV Streamer 4K", "other"))
        assertFalse(StreamerQuirks.matches("onn", "onn. 4K Pro Streaming Device", "yoc"))
        assertFalse(StreamerQuirks.matches("Google", "Chromecast", "sabrina"))
        assertFalse(StreamerQuirks.matches("Other", "Google TV Streamer clone", "box"))
        assertFalse(StreamerQuirks.matches(null, null, null))
    }

    @Test fun onlyTheStreamerWaitsForAFullRestartFrame() {
        val fast = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        assertEquals(fast, StreamerQuirks.tsFlags(false))
        assertEquals(0, StreamerQuirks.tsFlags(true))
    }

    @Test fun theStreamerAsksForTheFullModeInsteadOfHalfOfIt() {
        assertEquals(50f, StreamerQuirks.displayRate(25f, true), .001f)
        assertEquals(59.94f, StreamerQuirks.displayRate(29.97f, true), .001f)
        assertEquals(60f, StreamerQuirks.displayRate(30f, true), .001f)
        assertEquals(23.976f, StreamerQuirks.displayRate(23.976f, true), .001f)
        assertEquals(24f, StreamerQuirks.displayRate(24f, true), .001f)
        assertEquals(50f, StreamerQuirks.displayRate(50f, true), .001f)
        assertEquals(59.94f, StreamerQuirks.displayRate(59.94f, true), .001f)
        assertEquals("Other TVs keep the video's own rate", 30f, StreamerQuirks.displayRate(30f, false), .001f)
        assertEquals(25f, StreamerQuirks.displayRate(25f, false), .001f)
    }
}

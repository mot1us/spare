package io.github.mot1us.spare

import org.junit.Assert.*
import org.junit.Test

class LiveAvcDecoderTest {
    @Test fun usesCompatibilityOnlyForLiveTransportStreamsOnGoogleTvStreamer() {
        val streamer = LiveAvcDecoder("kirkwood")
        assertTrue(streamer.prefersSoftware(true, "/live/1.TS"))
        assertFalse(streamer.prefersSoftware(false, "/movie.ts"))
        assertFalse(streamer.prefersSoftware(true, "/live.m3u8"))
        assertFalse(streamer.prefersSoftware(true, null))
        assertFalse(LiveAvcDecoder("onn").prefersSoftware(true, "/live.ts"))
    }

    @Test fun prefersSoftwareAndPreservesHardwareForCodecFallback() {
        val policy = LiveAvcDecoder("kirkwood")
        val hardware = listOf("hardware1", "hardware2")
        val software = listOf("software1", "software2")
        val isSoftware: (String) -> Boolean = { it.startsWith("software") }
        assertEquals(software + hardware, policy.preferSoftware(hardware + software, isSoftware))
        assertSame(hardware, policy.preferSoftware(hardware, isSoftware))
        assertSame(software, policy.preferSoftware(software, isSoftware))
    }
}

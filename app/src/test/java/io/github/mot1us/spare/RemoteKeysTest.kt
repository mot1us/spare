package io.github.mot1us.spare

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeysTest {
    private val channels = List(3) { Channel("c$it", "Channel ${it + 1}", "News", "https://example.test/$it.ts") }

    @Test fun channelAndPageKeysStepThroughTheList() {
        assertEquals(1, RemoteKeys.zap(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(-1, RemoteKeys.zap(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(1, RemoteKeys.zap(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(-1, RemoteKeys.zap(KeyEvent.KEYCODE_PAGE_DOWN))
        assertNull(RemoteKeys.zap(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun numberKeysIncludingTheNumberPadAreDigits() {
        assertEquals(0, RemoteKeys.digit(KeyEvent.KEYCODE_0))
        assertEquals(7, RemoteKeys.digit(KeyEvent.KEYCODE_7))
        assertEquals(9, RemoteKeys.digit(KeyEvent.KEYCODE_NUMPAD_9))
        assertNull(RemoteKeys.digit(KeyEvent.KEYCODE_A))
    }

    @Test fun typedNumbersPickThatPositionAndNothingOutsideTheList() {
        assertEquals("c0", RemoteKeys.channelAt(channels, "1")?.id)
        assertEquals("c2", RemoteKeys.channelAt(channels, "3")?.id)
        assertNull(RemoteKeys.channelAt(channels, "4"))
        assertNull(RemoteKeys.channelAt(channels, "0"))
        assertNull(RemoteKeys.channelAt(channels, ""))
    }
}

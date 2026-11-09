package io.github.mot1us.spare

import android.view.KeyEvent.*
import org.junit.Assert.*
import org.junit.Test

class TvFocusTest {
    @Test fun pickerStepsOnPressAndClosesOnLeftRelease() {
        val steps = mutableListOf<Int>()
        var closes = 0
        fun key(key: Int, action: Int) = TvFocus.pickerKey(key, action, { steps.add(it) }) { closes++ }
        assertTrue(key(KEYCODE_DPAD_DOWN, ACTION_DOWN))
        assertTrue(key(KEYCODE_DPAD_DOWN, ACTION_DOWN))
        assertTrue(key(KEYCODE_DPAD_DOWN, ACTION_UP))
        assertTrue(key(KEYCODE_DPAD_UP, ACTION_DOWN))
        assertTrue(key(KEYCODE_DPAD_RIGHT, ACTION_DOWN))
        assertTrue(key(KEYCODE_DPAD_RIGHT, ACTION_UP))
        assertTrue(key(KEYCODE_DPAD_LEFT, ACTION_DOWN))
        assertTrue(key(KEYCODE_DPAD_LEFT, ACTION_DOWN))
        assertEquals(0, closes)
        assertTrue(key(KEYCODE_DPAD_LEFT, ACTION_UP))
        assertFalse(key(KEYCODE_BACK, ACTION_DOWN))
        assertEquals(listOf(1, 1, -1), steps)
        assertEquals(1, closes)
    }
}

package io.github.mot1us.spare

import android.view.KeyEvent

/** Remote keys beyond the D-pad. Pure mapping, so the rules are unit-testable. */
object RemoteKeys {
    /** Channel and Page keys step through the current list: +1 is the next channel. */
    fun zap(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> 1
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> -1
        else -> null
    }

    fun digit(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    /** "12" means the 12th channel shown; anything outside the list matches nothing. */
    fun channelAt(channels: List<Channel>, typed: String): Channel? {
        val number = typed.toIntOrNull() ?: return null
        return channels.getOrNull(number - 1)
    }
}

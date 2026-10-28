package io.github.mot1us.spare

import android.view.View

/** Native settings content can keep its own stable focus targets. */
interface SettingsContent {
    fun focusContent(): Boolean
    fun handlesLeft(focused: View?): Boolean = false
}

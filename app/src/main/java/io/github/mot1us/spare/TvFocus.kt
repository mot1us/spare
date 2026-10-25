package io.github.mot1us.spare

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One scroll owner: native focus must not start a second edge scroll during D-pad navigation. */
object TvFocus {
    class Layout(context: Context) : LinearLayoutManager(context) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean = false
    }
    fun center(list: RecyclerView, row: View) {
        if (list.isComputingLayout || list.height == 0) return
        val delta = row.top + row.height / 2 - list.height / 2
        if (kotlin.math.abs(delta) > row.height / 3) list.smoothScrollBy(0, delta, android.view.animation.DecelerateInterpolator(), 100)
    }
}

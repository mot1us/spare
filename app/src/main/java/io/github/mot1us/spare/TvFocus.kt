package io.github.mot1us.spare

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** One frame-paced scroll owner, retargeted rather than restarted for each remote repeat. */
object TvFocus {
    class Layout(context: Context) : LinearLayoutManager(context) {
        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean = false
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = height / 2; extraLayoutSpace[1] = height / 2
        }
    }
    private class Motion(list: RecyclerView) : Runnable {
        private val list = WeakReference(list)
        var row = WeakReference<View>(null)
        var posted = false
        override fun run() {
            posted = false
            val list = list.get() ?: return; val row = row.get() ?: return
            if (!list.isShown || !row.hasFocus() || row.parent !== list) return
            if (!list.isComputingLayout) {
                val delta = row.top + row.height / 2 - list.height / 2
                if (abs(delta) <= 1) return
                val step = (delta * .42f).roundToInt().let { if (it == 0) delta.compareTo(0) else it }
                list.scrollBy(0, step)
                // At either end the list may have no scroll space left.
                if (list.canScrollVertically(delta.compareTo(0))) schedule()
            } else schedule()
        }
        fun schedule() { if (!posted) { posted = true; list.get()?.postOnAnimation(this) } }
    }
    private val motions = WeakHashMap<RecyclerView, Motion>()
    fun center(list: RecyclerView, row: View) {
        if (list.height == 0) return
        val motion = motions.getOrPut(list) { Motion(list) }
        motion.row = WeakReference(row); motion.schedule()
    }
}

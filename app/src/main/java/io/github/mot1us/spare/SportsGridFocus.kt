package io.github.mot1us.spare

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** A single, retargetable scroll owner for both directions of the matchup grid. */
internal class SportsGridFocus(private val list: RecyclerView) : Runnable {
    var targetPosition: Int? = null
        private set
    private var targetId = RecyclerView.NO_ID
    private var posted = false

    fun move(position: Int) {
        val adapter = list.adapter ?: return
        if (position !in 0 until adapter.itemCount) return
        targetPosition = position; targetId = adapter.getItemId(position)
        list.stopScroll()
        val child = list.findViewHolderForAdapterPosition(position)?.itemView
        if (child != null) child.requestFocus()
        else if (!list.hasFocus()) {
            // Restoring a far-away remembered card is a pane entry, not a remote
            // row transition. Put it in place before focus enters the grid.
            val height = list.getChildAt(0)?.height ?: 0
            (list.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(position, ((list.height - height) / 2).coerceAtLeast(0))
            focusList()
        }
        schedule()
    }

    fun cancel() {
        list.removeCallbacks(this); posted = false; targetPosition = null; targetId = RecyclerView.NO_ID
    }

    private fun schedule() { if (!posted) { posted = true; list.postOnAnimation(this) } }
    private fun focusList() {
        val previous = list.descendantFocusability
        list.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        list.requestFocus(); list.descendantFocusability = previous
    }

    override fun run() {
        posted = false
        val position = targetPosition ?: return
        val adapter = list.adapter ?: return cancel()
        if (!list.isShown || !list.hasFocus() || position !in 0 until adapter.itemCount || adapter.getItemId(position) != targetId) { cancel(); return }
        if (list.isComputingLayout) { schedule(); return }
        val child = list.findViewHolderForAdapterPosition(position)?.itemView
        if (child == null) {
            val layout = list.layoutManager as? GridLayoutManager ?: return cancel()
            val first = layout.findFirstVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) { schedule(); return }
            val direction = if (position < first) -1 else 1
            if (!list.canScrollVertically(direction)) { cancel(); return }
            // Adjacent rows are laid out ahead of time. This path handles rapid
            // repeats that retarget beyond them without an instantaneous jump.
            focusList()
            list.scrollBy(0, (list.height * .30f).roundToInt().coerceAtLeast(1) * direction)
            schedule(); return
        }
        if (!child.hasFocus() && !child.requestFocus()) { cancel(); return }
        val center = list.paddingTop + (list.height - list.paddingTop - list.paddingBottom) / 2
        val delta = child.top + child.height / 2 - center
        if (abs(delta) <= 1 || !list.canScrollVertically(delta.compareTo(0))) { cancel(); return }
        val step = (delta * .36f).roundToInt().let { if (it == 0) delta.compareTo(0) else it }
        list.scrollBy(0, step)
        schedule()
    }
}

package xyz.nekobyte.nekobox.ktx

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.core.view.isEmpty
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import xyz.nekobyte.nekobox.database.DataStore

internal fun itemMoveIndices(from: Int, to: Int) = if (from < to) from until to else from downTo (to + 1)

class FixedLinearLayoutManager(val recyclerView: RecyclerView) : LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    private var listenerDisabled = false

    override fun scrollVerticallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (!DataStore.showBottomBar) return super.scrollVerticallyBy(dx, recycler, state)

        // Hides the FAB once overscroll at the end leaves it covering the last item.
        val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
        if (listenerDisabled) return scrollRange
        val activity = recyclerView.context as? FabContainer
        if (activity == null) {
            listenerDisabled = true
            return scrollRange
        }

        val overscroll = dx - scrollRange
        if (overscroll > 0) {
            val view =
                (
                    recyclerView.findViewHolderForAdapterPosition(findLastVisibleItemPosition())
                        ?: return scrollRange
                    ).itemView
            val itemLocation = Rect().also { view.getGlobalVisibleRect(it) }
            val fabLocation = Rect().also { activity.fabView.getGlobalVisibleRect(it) }
            if (!itemLocation.contains(fabLocation.left, fabLocation.top) && !itemLocation.contains(
                    fabLocation.right,
                    fabLocation.bottom,
                )
            ) {
                return scrollRange
            }
            if (activity.fabView.isShown) activity.hideFab()
        } else {
            /*val screen = Rect().also { activity.window.decorView.getGlobalVisibleRect(it) }
            val location = Rect().also { activity.stats.getGlobalVisibleRect(it) }
            if (screen.bottom < location.bottom) {
                return scrollRange
            }
            val height = location.bottom - location.top
            val mH = activity.stats.measuredHeight

            if (mH > height) {
                return scrollRange
            }*/

            if (!activity.fabView.isShown) activity.showFab()
        }
        return scrollRange
    }
}

class FixedGridLayoutManager(val recyclerView: RecyclerView, spanCount: Int) : GridLayoutManager(recyclerView.context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    private var listenerDisabled = false

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun scrollVerticallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (!DataStore.showBottomBar) return super.scrollVerticallyBy(dx, recycler, state)

        // Hides the FAB once overscroll at the end leaves it covering the last item.
        val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
        if (listenerDisabled) return scrollRange
        val activity = recyclerView.context as? FabContainer
        if (activity == null) {
            listenerDisabled = true
            return scrollRange
        }

        val overscroll = dx - scrollRange
        if (overscroll > 0) {
            val view =
                (
                    recyclerView.findViewHolderForAdapterPosition(findLastVisibleItemPosition())
                        ?: return scrollRange
                    ).itemView
            val itemLocation = Rect().also { view.getGlobalVisibleRect(it) }
            val fabLocation = Rect().also { activity.fabView.getGlobalVisibleRect(it) }
            if (!itemLocation.contains(fabLocation.left, fabLocation.top) && !itemLocation.contains(
                    fabLocation.right,
                    fabLocation.bottom,
                )
            ) {
                return scrollRange
            }
            if (activity.fabView.isShown) activity.hideFab()
        } else {
            if (!activity.fabView.isShown) activity.showFab()
        }
        return scrollRange
    }
}

private const val STILL_MS = 500L

/**
 * Runs [step] while the main thread is idle and this view's window has drawn nothing for [STILL_MS], again
 * until it returns false: deferred work in pieces that never land under a scroll, a fling or an animation.
 */
fun View.whenStill(step: () -> Boolean) {
    val wake = Runnable {}
    Looper.myQueue().addIdleHandler {
        // A view that is not attached has drawn nothing: its drawing time reads 0.
        if (SystemClock.uptimeMillis() - drawingTime >= STILL_MS) return@addIdleHandler step()
        // Something drew lately: look again once it may have stopped, even if no other message wakes the queue.
        handler?.run {
            removeCallbacks(wake)
            postDelayed(wake, STILL_MS)
        }
        true
    }
}

/**
 * Makes rows of [viewType] for up to [count] of the adapter's items, one at a time while the window is still
 * (see [whenStill]), and keeps them in this list's pool, so a screen built ahead of its first visit binds them
 * then instead of making each one. Stops once the list has laid out rows of its own, or when it leaves the window
 * or its adapter changes.
 */
fun RecyclerView.prefill(viewType: Int, count: Int) {
    val adapter = adapter ?: return
    recycledViewPool.setMaxRecycledViews(viewType, maxOf(count, 5))
    var made = 0
    whenStill {
        val more = made < minOf(count, adapter.itemCount) && isEmpty() && isAttachedToWindow && this.adapter === adapter
        if (more) recycledViewPool.putRecycledView(adapter.createViewHolder(this, viewType))
        more && ++made < minOf(count, adapter.itemCount)
    }
}

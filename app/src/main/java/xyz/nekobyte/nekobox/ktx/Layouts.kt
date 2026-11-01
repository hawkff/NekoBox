package xyz.nekobyte.nekobox.ktx

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
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
    // An idle handler runs again only once the queue has handled a message, so it posts an empty one to come back.
    val queue = Handler(Looper.myLooper()!!)
    val wake = Runnable {}
    Looper.myQueue().addIdleHandler {
        // A view that is not attached has drawn nothing: its drawing time reads 0.
        val still = SystemClock.uptimeMillis() - drawingTime
        queue.removeCallbacks(wake)
        if (still >= STILL_MS) {
            step().also { if (it) queue.post(wake) }
        } else {
            queue.postDelayed(wake, STILL_MS - still)
            true
        }
    }
}

/**
 * Makes rows of [viewType] for up to [count] of the adapter's items of that type, one at a time while the window
 * is still (see [whenStill]), and keeps them in this list's pool, so a screen built ahead of its first visit binds
 * them then instead of making each one. Starts once the adapter has such items; stops once the list has laid out
 * rows of its own, or when it leaves the window or its adapter changes.
 */
fun RecyclerView.prefill(viewType: Int, count: Int) {
    val adapter = adapter ?: return
    recycledViewPool.setMaxRecycledViews(viewType, maxOf(count, 5))
    val wanted = { minOf(count, (0 until adapter.itemCount).count { adapter.getItemViewType(it) == viewType }) }
    val start = {
        var made = 0
        whenStill {
            val more = made < wanted() && isEmpty() && isAttachedToWindow && this.adapter === adapter
            if (more) recycledViewPool.putRecycledView(adapter.createViewHolder(this, viewType))
            more && ++made < wanted()
        }
    }
    if (wanted() > 0) return start()
    // The items are still loading: start when they come.
    adapter.registerAdapterDataObserver(
        object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (wanted() == 0) return
                adapter.unregisterAdapterDataObserver(this)
                start()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onChanged()
        },
    )
}

/**
 * An inflater without AppCompat's view factory, which serves the main thread only: it lets rows be inflated off it.
 * A layout it inflates names classes the factory would swap in full, MaterialTextView for TextView, so both make the
 * same views.
 */
class PlainInflater(context: Context) : LayoutInflater(context) {
    override fun cloneInContext(newContext: Context): LayoutInflater = PlainInflater(newContext)

    override fun onCreateView(name: String, attrs: AttributeSet): View {
        for (prefix in arrayOf("android.widget.", "android.webkit.", "android.app.")) {
            try {
                return createView(name, prefix, attrs) ?: continue
            } catch (_: ClassNotFoundException) {
            }
        }
        return super.onCreateView(name, attrs)
    }
}

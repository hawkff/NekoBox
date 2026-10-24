package xyz.nekobyte.nekobox.widget

import android.os.Bundle
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import xyz.nekobyte.nekobox.R

/**
 * Attaches [callback] for dragging and swiping the list's rows, and gives screen readers, which can
 * do neither, the same moves as actions on each row: Move up and Move down where the row may be
 * dragged, Remove where it may be swiped away. The callback decides each, as it does for a touch.
 */
fun RecyclerView.attachItemTouch(callback: ItemTouchHelper.Callback): ItemTouchHelper {
    setAccessibilityDelegateCompat(RowActions(this, callback))
    return ItemTouchHelper(callback).also { it.attachToRecyclerView(this) }
}

private class RowActions(private val list: RecyclerView, private val callback: ItemTouchHelper.Callback) : RecyclerViewAccessibilityDelegate(list) {

    private val rows: ItemDelegate = object : ItemDelegate(this) {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val holder = list.findContainingViewHolder(host) ?: return
            val position = holder.absoluteAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val context = host.context
            if (canMove(holder, position - 1)) info.addAction(AccessibilityActionCompat(R.id.row_move_up, context.getString(R.string.move_up)))
            if (canMove(holder, position + 1)) info.addAction(AccessibilityActionCompat(R.id.row_move_down, context.getString(R.string.move_down)))
            if (swipeDirection(holder) != 0) info.addAction(AccessibilityActionCompat(R.id.row_remove, context.getString(R.string.delete)))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val holder = list.findContainingViewHolder(host) ?: return super.performAccessibilityAction(host, action, args)
            val position = holder.absoluteAdapterPosition
            return when (action) {
                R.id.row_move_up -> position != RecyclerView.NO_POSITION && move(holder, position - 1)
                R.id.row_move_down -> position != RecyclerView.NO_POSITION && move(holder, position + 1)
                R.id.row_remove -> position != RecyclerView.NO_POSITION && remove(holder)
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    // The base constructor asks for this before [rows] is set, gets null and makes a delegate of its own.
    override fun getItemDelegate(): AccessibilityDelegateCompat = rows

    private fun flags(holder: RecyclerView.ViewHolder) = callback.getMovementFlags(list, holder)

    private fun swipeDirection(holder: RecyclerView.ViewHolder) = (flags(holder) shr 8 and 0xFF).takeHighestOneBit()

    // A row moves among rows of its own kind; the callback may still refuse the drop.
    private fun canMove(holder: RecyclerView.ViewHolder, to: Int): Boolean {
        val adapter = list.adapter ?: return false
        return flags(holder) shr 16 and 0xFF != 0 && to in 0 until adapter.itemCount && adapter.getItemViewType(to) == holder.itemViewType
    }

    private fun move(holder: RecyclerView.ViewHolder, to: Int): Boolean {
        if (!canMove(holder, to)) return false
        val target = list.findViewHolderForAdapterPosition(to)
        if (target != null) return drop(holder, target)
        // The neighbor is out of view: bring it in, then move.
        list.scrollToPosition(to)
        list.doOnNextLayout { list.findViewHolderForAdapterPosition(to)?.let { drop(holder, it) } }
        return true
    }

    // The calls a touch makes, in its order: pick the row up, move it over the target, let go.
    private fun drop(holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        if (!callback.canDropOver(list, holder, target)) return false
        val from = holder.absoluteAdapterPosition
        val to = target.absoluteAdapterPosition
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_DRAG)
        val moved = callback.onMove(list, holder, target)
        if (moved) callback.onMoved(list, holder, from, target, to, target.itemView.left, target.itemView.top)
        callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE)
        callback.clearView(list, holder)
        return moved
    }

    private fun remove(holder: RecyclerView.ViewHolder): Boolean {
        val direction = swipeDirection(holder)
        if (direction == 0) return false
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE)
        callback.onSwiped(holder, direction)
        callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE)
        return true
    }
}

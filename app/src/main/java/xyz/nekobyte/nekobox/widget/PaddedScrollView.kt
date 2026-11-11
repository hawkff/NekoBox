package xyz.nekobyte.nekobox.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A scroll view whose padding is room kept clear, as for the bars that lie over it. When it scrolls for the focus,
 * what it brings into view stays out of that room. The framework's takes the whole view for the screen: a child
 * taller than the view that takes the focus at the end of the scroll, as selectable text does on a tap, is moved
 * back until its end lies under the bar.
 */
class PaddedScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect.left, rect.top - paddingTop, rect.right, rect.bottom + paddingBottom))
}

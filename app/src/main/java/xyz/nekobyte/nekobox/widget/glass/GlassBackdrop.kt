package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout

/**
 * The content behind glass. While liquid glass is on, the container records its children into a
 * render node, so glass drawn in front of it can draw them again, blurred. Glass must not sit
 * inside the backdrop it shows.
 */
class GlassBackdrop(val view: ViewGroup) {
    private val recorder = if (Build.VERSION.SDK_INT >= 31) Recorder() else null
    private val hosts = LinkedHashSet<View>()
    private val visible = Rect()

    /** The children as drawn in the last frame; null until the container has drawn with glass on. */
    @get:RequiresApi(31)
    val node: RenderNode? get() = recorder?.node?.takeIf { it.hasDisplayList() }

    /** The frosted copy of the content that all the glass in front of it shares; made by the first to draw. */
    internal var frost: SharedFrost? = null

    /** Counts changes behind the glass: a copy of the content made at one count is current while it stays. */
    var changes = 0L
        private set

    /**
     * While glass sweeps over content that stays still, as the drawer does, the content is drawn into a layer:
     * the screen and every pane's frost then reuse the texture instead of drawing the content again each frame.
     * Scrolling content does better without it, since the layer would be drawn again on every frame anyway.
     */
    var still = false
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT >= 31) recorder?.node?.setUseCompositingLayer(value, null)
        }

    fun dispatchDraw(canvas: Canvas, drawChildren: (Canvas) -> Unit) {
        val recorder = recorder
        if (Build.VERSION.SDK_INT < 31 || recorder == null || !Glass.enabled || !canvas.isHardwareAccelerated || view.width == 0 || view.height == 0) {
            drawChildren(canvas)
        } else {
            changes++
            recorder.draw(canvas, view, still, drawChildren)
        }
    }

    /**
     * Something behind the glass changed: the glass that shows it draws again in the same frame. A scroll
     * invalidates many views a frame; glass already waiting to draw is passed over.
     */
    fun onDescendantInvalidated() {
        changes++
        for (host in hosts) if (!host.isDirty && host.isShown && host.getGlobalVisibleRect(visible)) host.invalidate()
    }

    fun register(host: View) {
        hosts += host
    }

    fun unregister(host: View) {
        hosts -= host
    }

    @RequiresApi(31)
    private class Recorder {
        val node = RenderNode("glass backdrop")

        fun draw(canvas: Canvas, view: View, still: Boolean, drawChildren: (Canvas) -> Unit) {
            node.setUseCompositingLayer(still, null)
            node.setPosition(0, 0, view.width, view.height)
            drawChildren(node.beginRecording(view.width, view.height))
            node.endRecording()
            canvas.drawRenderNode(node)
        }
    }
}

class GlassBackdropFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    val backdrop = GlassBackdrop(this)

    override fun dispatchDraw(canvas: Canvas) = backdrop.dispatchDraw(canvas) { super.dispatchDraw(it) }

    @RequiresApi(26)
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        backdrop.onDescendantInvalidated()
    }
}

class GlassBackdropCoordinatorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CoordinatorLayout(context, attrs) {
    val backdrop = GlassBackdrop(this)

    override fun dispatchDraw(canvas: Canvas) = backdrop.dispatchDraw(canvas) { super.dispatchDraw(it) }

    @RequiresApi(26)
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        backdrop.onDescendantInvalidated()
    }
}

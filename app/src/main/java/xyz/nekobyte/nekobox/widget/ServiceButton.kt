package xyz.nekobyte.nekobox.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.PointerIcon
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable

/** The connect button: an orb with a black hole at its heart ([OrbRing]), which shows the connection's state. */
class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private val orb = OrbRing(this).apply {
        offColor = context.getColorAttr(android.R.attr.textColorSecondary)
        dark = ColorUtils.calculateLuminance(context.getColorAttr(android.R.attr.textColorPrimary)) > 0.5
    }
    private var checked = false
    private var glass: GlassDrawable? = null
    private val bounds = Rect()

    /** Liquid glass: the orb is a pane of glass. */
    fun useGlass(drawable: GlassDrawable) {
        glass = drawable
        drawable.callback = this
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                getContentRect(bounds)
                drawable.bounds = bounds
                drawable.getOutline(outline)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        getContentRect(bounds)
        glass?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
        orb.draw(canvas, bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() / 2f)
        super.onDraw(canvas)
    }

    override fun verifyDrawable(who: Drawable) = who == glass || super.verifyDrawable(who)

    // The orb moves only while the button is on screen: other screens and a scrolling list hide it, and the app in
    // the background hides its window.
    private fun updateMotion() {
        if (isAttachedToWindow && windowVisibility == VISIBLE && isOrWillBeShown) orb.resume() else orb.pause()
    }

    override fun hide(listener: OnVisibilityChangedListener?) {
        super.hide(listener)
        updateMotion()
    }

    override fun show(listener: OnVisibilityChangedListener?) {
        super.show(listener)
        updateMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        orb.pause()
        super.onDetachedFromWindow()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) {
            View.mergeDrawableStates(
                drawableState,
                intArrayOf(android.R.attr.state_checked),
            )
        }
        return drawableState
    }

    fun changeState(state: BaseService.State, animate: Boolean) {
        orb.show(state, animate)
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        val description = context.getText(if (state.canStop) R.string.stop else R.string.connect)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        val enabled = state.canStop || state == BaseService.State.Stopped
        isEnabled = enabled
        pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (enabled) PointerIcon.TYPE_HAND else PointerIcon.TYPE_WAIT,
        )
    }
}

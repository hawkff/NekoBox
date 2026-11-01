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
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable
import java.util.*

/**
 * The connect button: an orb ([OrbRing]) around the plane. Off, a grey ring and the plane struck through; connecting,
 * the strike slides off and a lit arc sweeps the ring; connected, the plane fills and the ring lights up; stopping, it
 * all goes back.
 */
class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private val callback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            super.onAnimationEnd(drawable)
            var next = animationQueue.peek() ?: return
            if (next.icon.current == drawable) {
                animationQueue.pop()
                next = animationQueue.peek() ?: return
            }
            next.start()
        }
    }

    private inner class AnimatedState(@DrawableRes resId: Int) {
        val icon: AnimatedVectorDrawableCompat =
            AnimatedVectorDrawableCompat.create(context, resId)!!.apply {
                registerAnimationCallback(this@ServiceButton.callback)
            }

        fun start() {
            setImageDrawable(icon)
            icon.start()
        }

        fun stop() = icon.stop()
    }

    private val iconStopped by lazy { AnimatedState(R.drawable.ic_service_stopped) }
    private val iconConnecting by lazy { AnimatedState(R.drawable.ic_service_connecting) }
    private val iconConnected by lazy { AnimatedState(R.drawable.ic_service_connected) }
    private val iconStopping by lazy { AnimatedState(R.drawable.ic_service_stopping) }
    private val animationQueue = ArrayDeque<AnimatedState>()

    private val orb = OrbRing(this).apply {
        offColor = context.getColorAttr(android.R.attr.textColorSecondary)
        litColor = context.getColorAttr(R.attr.primaryOrTextPrimary)
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

    // The sweep runs only while the button shows: other screens and a scrolling list hide it.
    override fun hide(listener: OnVisibilityChangedListener?) {
        super.hide(listener)
        orb.pause()
    }

    override fun show(listener: OnVisibilityChangedListener?) {
        super.show(listener)
        orb.resume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isOrWillBeShown) orb.resume()
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

    fun changeState(state: BaseService.State, previousState: BaseService.State, animate: Boolean) {
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animate)

            BaseService.State.Connected -> changeState(iconConnected, animate)

            BaseService.State.Stopping -> {
                changeState(iconStopping, animate && previousState == BaseService.State.Connected)
            }

            else -> changeState(iconStopped, animate)
        }
        orb.show(state, animate)
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        // Off, the struck-through plane reads in the text color; from connecting on it takes the ring's light.
        imageTintList = ColorStateList.valueOf(if (state == BaseService.State.Stopped || state == BaseService.State.Idle) context.getColorAttr(android.R.attr.textColorPrimary) else orb.litColor)
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

    private fun changeState(icon: AnimatedState, animate: Boolean) {
        fun counters(a: AnimatedState, b: AnimatedState): Boolean = (a == iconStopped && b == iconConnecting) ||
            (a == iconConnecting && b == iconStopped) ||
            (a == iconConnected && b == iconStopping) ||
            (a == iconStopping && b == iconConnected)
        if (animate) {
            if (animationQueue.size < 2 || !counters(animationQueue.last, icon)) {
                animationQueue.add(icon)
                if (animationQueue.size == 1) icon.start()
            } else {
                animationQueue.removeLast()
            }
        } else {
            animationQueue.peekFirst()?.stop()
            animationQueue.clear()
            icon.start() // force ensureAnimatorSet to be called so that stop() will work
            icon.stop()
        }
    }
}

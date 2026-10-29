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
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.BaseProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.ktx.animationsOn
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.ktx.unwrap
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable
import java.util.*

class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FloatingActionButton(context, attrs, defStyleAttr),
    DynamicAnimation.OnAnimationEndListener {

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

    private inner class AnimatedState(
        @DrawableRes resId: Int,
        private val onStart: BaseProgressIndicator<*>.() -> Unit = { hideProgress() },
    ) {
        val icon: AnimatedVectorDrawableCompat =
            AnimatedVectorDrawableCompat.create(context, resId)!!.apply {
                registerAnimationCallback(this@ServiceButton.callback)
            }

        fun start() {
            setImageDrawable(icon)
            icon.start()
            progress.onStart()
        }

        fun stop() = icon.stop()
    }

    private val iconStopped by lazy { AnimatedState(R.drawable.ic_service_stopped) }
    private val iconConnecting by lazy {
        AnimatedState(R.drawable.ic_service_connecting) {
            hideProgress()
            val owner = context.unwrap<LifecycleOwner>()
            delayedAnimation = owner.lifecycleScope.launch {
                delay(context.resources.getInteger(android.R.integer.config_mediumAnimTime) + 1000L)
                // Gate the UI mutation on STARTED so a delayed progress reveal doesn't run
                // while the activity is stopped (the old launchWhenStarted suspended here).
                owner.withStarted {
                    // A button hidden meanwhile (another screen, a scrolled list) shows its ring when it comes back.
                    if (this@ServiceButton.isOrWillBeShown) {
                        isIndeterminate = true
                        show()
                    }
                }
            }
        }
    }
    private val iconConnected by lazy {
        AnimatedState(R.drawable.ic_service_connected) {
            delayedAnimation?.cancel()
            // The ring fills, and the end of the fill hides it. With animations off nothing fills, so it goes now.
            if (context.animationsOn()) setProgressCompat(1, true) else hide()
        }
    }
    private val iconStopping by lazy { AnimatedState(R.drawable.ic_service_stopping) }
    private val animationQueue = ArrayDeque<AnimatedState>()

    private var checked = false
    private var state = BaseService.State.Idle
    private var glass: GlassDrawable? = null
    private var glassTint = 0
    private val glassBounds = Rect()

    /** Liquid glass: the button becomes a glass orb, filled with its color once connected. */
    fun useGlass(drawable: GlassDrawable) {
        glass = drawable
        glassTint = drawable.tintColor
        drawable.callback = this
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                getContentRect(glassBounds)
                drawable.bounds = glassBounds
                drawable.getOutline(outline)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        glass?.let {
            getContentRect(glassBounds)
            it.bounds = glassBounds
            it.draw(canvas)
        }
        super.onDraw(canvas)
    }

    override fun verifyDrawable(who: Drawable) = who == glass || super.verifyDrawable(who)

    // The ring is part of the button: it leaves with it, on the other screens and while a list scrolls.
    override fun hide(listener: FloatingActionButton.OnVisibilityChangedListener?) {
        super.hide(listener)
        if (::progress.isInitialized) hideProgress()
    }

    override fun show(listener: FloatingActionButton.OnVisibilityChangedListener?) {
        super.show(listener)
        if (::progress.isInitialized && state == BaseService.State.Connecting) {
            progress.isIndeterminate = true
            progress.show()
        }
    }
    private var delayedAnimation: Job? = null
    private lateinit var progress: BaseProgressIndicator<*>
    fun initProgress(progress: BaseProgressIndicator<*>) {
        this.progress = progress
        progress.progressDrawable?.addSpringAnimationEndListener(this)
    }

    override fun onAnimationEnd(
        animation: DynamicAnimation<out DynamicAnimation<*>>?,
        canceled: Boolean,
        value: Float,
        velocity: Float,
    ) {
        if (!canceled) progress.hide()
    }

    private fun hideProgress() {
        delayedAnimation?.cancel()
        progress.hide()
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
        this.state = state
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animate)

            BaseService.State.Connected -> changeState(iconConnected, animate)

            BaseService.State.Stopping -> {
                changeState(iconStopping, animate && previousState == BaseService.State.Connected)
            }

            else -> changeState(iconStopped, animate)
        }
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        applyStateTint(state)
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

    private fun applyStateTint(state: BaseService.State) {
        glass?.let { glass ->
            // Clear glass with the icon in the button's color; connected, the color fills the orb
            // and the icon takes the connected color the theme sets against it.
            val connected = state == BaseService.State.Connected
            val color = context.getColorAttr(R.attr.fabColorBackground)
            glass.tintColor = if (connected) ColorUtils.setAlphaComponent(color, 0xD9) else glassTint
            imageTintList = ColorStateList.valueOf(if (connected) context.getColorAttr(R.attr.fabConnectedColor) else color)
            return
        }
        // Tint the connect FAB icon by state: fabConnectedColor when connected, fabStoppedColor when stopped,
        // colorOnPrimary in between. Both default to colorOnPrimary; the dark palettes whose green status color
        // equals their primary set them to their own on-primary color, so the icon always reads on the button.
        val attr = when (state) {
            BaseService.State.Connected -> R.attr.fabConnectedColor
            BaseService.State.Stopped -> R.attr.fabStoppedColor
            else -> com.google.android.material.R.attr.colorOnPrimary
        }
        imageTintList = ColorStateList.valueOf(context.getColorAttr(attr))
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

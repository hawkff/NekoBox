package xyz.nekobyte.nekobox.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.exp
import xyz.nekobyte.nekobox.ktx.EaseOut

/**
 * Back from anywhere in the left part of the window, not only from its edge: a quick, clearly
 * horizontal swipe to the right that starts in the left [START_ZONE], or wherever the activity's
 * [ThemedActivity.wideSwipeZone] allows. The content follows the
 * finger a little; on release far or fast enough, [ThemedActivity.onWideSwipe] runs. Touches that
 * begin on something that moves sideways itself (a scrolled strip, a slider, a text field) or
 * that become a long press stay with the views below.
 */
internal class WideSwipeBack(private val activity: ThemedActivity) {
    private val config = ViewConfiguration.get(activity)
    private val slop = config.scaledTouchSlop.toFloat()
    private val density = activity.resources.displayMetrics.density
    private val onScreen = IntArray(2)
    private var tracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var state = IDLE

    private val content: View get() = activity.findViewById(android.R.id.content)

    /** True while the swipe owns the touch; the views below then see nothing of it. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                val width = content.width
                if (width == 0 || event.x > width * activity.wideSwipeZone() || event.x < EDGE * density || !activity.canSwipeBack()) return false
                if (movesSideways(activity.window.decorView, event.rawX, event.rawY)) return false
                startX = event.x
                startY = event.y
                downTime = event.eventTime
                state = WATCHING
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (state == IDLE) return false
                tracker?.addMovement(event)
                val dx = event.x - startX
                if (state == WATCHING) {
                    when (decide(dx, event.y - startY, slop, event.eventTime - downTime > ViewConfiguration.getLongPressTimeout())) {
                        DROP -> {
                            reset()
                            return false
                        }

                        WATCH -> return false
                    }
                    // A settle from the last swipe would pull against the finger.
                    content.animate().cancel()
                    state = DRAGGING
                    activity.cancelTouch(event)
                }
                content.translationX = follow(dx, FOLLOW * density)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (state != DRAGGING) {
                    reset()
                    return false
                }
                val velocity = tracker?.run {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                    xVelocity
                } ?: 0f
                val dx = event.x - startX
                settle()
                reset()
                if (goes(dx, velocity / density, content.width.toFloat(), density)) activity.onWideSwipe()
                return true
            }

            // A second finger makes a gesture of its own, such as a pinch.
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val owned = state == DRAGGING
                if (owned) settle()
                reset()
                return owned
            }
        }
        return state == DRAGGING
    }

    private fun settle() {
        content.animate().translationX(0f).setDuration(200).setInterpolator(EaseOut).start()
    }

    private fun reset() {
        state = IDLE
        tracker?.recycle()
        tracker = null
    }

    /** Whether the view under the finger moves sideways on its own, so a right swipe belongs to it. */
    private fun movesSideways(view: View, x: Float, y: Float): Boolean {
        if (!view.isShown) return false
        // Raw touch coordinates are on the screen; in split screen the window does not start at its corner.
        view.getLocationOnScreen(onScreen)
        if (x < onScreen[0] || y < onScreen[1] || x >= onScreen[0] + view.width || y >= onScreen[1] + view.height) return false
        if (view is AbsSeekBar || view is Slider || view.onCheckIsTextEditor()) return true
        // A pager whose swiping is off still reports scrollable pages, itself and through its inner
        // list; its pages do not move under a finger.
        val pagerOff = (view as? ViewPager2 ?: view.parent as? ViewPager2)?.isUserInputEnabled == false
        if (!pagerOff && view.canScrollHorizontally(-1)) return true
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) if (movesSideways(view.getChildAt(i), x, y)) return true
        return false
    }

    companion object {
        /** The swipe starts in this share of the window's width, from the left. */
        const val START_ZONE = 0.45f

        /** Starts closer to the edge than this (dp) belong to the system's edge gesture and the drawer. */
        const val EDGE = 24

        /** How far (dp) the content follows the finger, with growing resistance. */
        const val FOLLOW = 56

        const val IDLE = 0
        const val WATCHING = 1
        const val DRAGGING = 2

        const val WATCH = 0
        const val CLAIM = 1
        const val DROP = 2

        /**
         * After a move of [dx], [dy] from the down point: a vertical or leftward move, or one after a
         * long press, belongs to the views below; a rightward move at least twice as wide as tall
         * and past twice the touch [slop] is a swipe.
         */
        fun decide(dx: Float, dy: Float, slop: Float, longPress: Boolean): Int = when {
            longPress -> DROP
            abs(dy) > slop && abs(dy) > abs(dx) -> DROP
            dx < -slop -> DROP
            dx > 2 * slop && dx > 2 * abs(dy) -> CLAIM
            else -> WATCH
        }

        /** Released after [dx] px at [velocity] dp/s in a window [width] px wide: a third of it, or a fling past 48 dp. */
        fun goes(dx: Float, velocity: Float, width: Float, density: Float): Boolean = dx > width / 3 || (velocity > 600 && dx > 48 * density)

        /** The content's offset for a finger [dx] px along: one to one at first, never past [max]. */
        fun follow(dx: Float, max: Float): Float = if (dx <= 0f) 0f else max * (1 - exp(-dx / max))
    }
}

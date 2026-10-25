package xyz.nekobyte.nekobox.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import xyz.nekobyte.nekobox.ktx.animationsOn
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holds the button that runs a network check, declared inside it: a capsule centered at three quarters
 * of the width. While the check runs the capsule draws in to a round spinner, so it can't be tapped
 * twice, and grows back with its new label when the check is done. With animations off it switches
 * at once. The button keeps saying what happens: its label, or while it spins, its description.
 */
class CheckButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val widest = (MAX_WIDTH * density).roundToInt()
    private val ring = CircularProgressIndicator(context).apply {
        isIndeterminate = true
        indicatorSize = (RING * density).roundToInt()
        trackThickness = (RING_TRACK * density).roundToInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }
    private lateinit var button: MaterialButton
    private var icon: Drawable? = null

    // 0 is the capsule, 1 the circle.
    private var round = 0f
    private var morph: ValueAnimator? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        button = getChildAt(0) as MaterialButton
        icon = button.icon
        ring.setIndicatorColor(button.currentTextColor)
        ring.translationZ = button.elevation + RING_Z * density
        addView(ring)
    }

    /** Shows the check [running] or not, with [label] as what the button says. */
    fun show(running: Boolean, label: CharSequence) {
        // A morph cut short never finishes its own state; this call sets the state instead.
        val old = morph
        morph = null
        old?.cancel()
        val target = if (running) 1f else 0f
        if (running) {
            button.text = null
            button.icon = null
            button.contentDescription = label
            button.isClickable = false
        } else {
            ring.isVisible = false
        }
        val done = {
            if (running) {
                ring.isVisible = true
            } else {
                button.text = label
                button.icon = icon
                button.contentDescription = null
                button.isClickable = true
            }
        }
        if (round == target || !context.animationsOn()) {
            round = target
            requestLayout()
            return done()
        }
        morph = ValueAnimator.ofFloat(round, target).apply {
            duration = if (running) SHRINK else GROW
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                round = it.animatedValue as Float
                requestLayout()
            }
            doOnEnd { if (morph === it) done() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val capsule = min(((width - paddingLeft - paddingRight) * SHARE).roundToInt(), widest)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        button.measure(MeasureSpec.makeMeasureSpec(capsule, MeasureSpec.EXACTLY), free)
        val height = button.measuredHeight
        val now = (capsule + (height - capsule) * round).roundToInt()
        if (now != capsule) {
            button.measure(MeasureSpec.makeMeasureSpec(now, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        ring.measure(free, free)
        setMeasuredDimension(width, height + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (right - left - button.measuredWidth) / 2
        button.layout(x, paddingTop, x + button.measuredWidth, paddingTop + button.measuredHeight)
        val ringX = x + (button.measuredWidth - ring.measuredWidth) / 2
        val ringY = paddingTop + (button.measuredHeight - ring.measuredHeight) / 2
        ring.layout(ringX, ringY, ringX + ring.measuredWidth, ringY + ring.measuredHeight)
    }

    private companion object {
        const val SHARE = 0.75f
        const val MAX_WIDTH = 400
        const val RING = 24
        const val RING_TRACK = 3
        const val RING_Z = 2
        const val SHRINK = 240L
        const val GROW = 280L
    }
}

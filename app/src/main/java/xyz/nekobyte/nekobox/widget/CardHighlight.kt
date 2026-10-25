package xyz.nekobyte.nekobox.widget

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.animationsOn
import xyz.nekobyte.nekobox.ktx.getColorAttr

/**
 * The look of a picked-out card: a thicker outline in the primary color over a faint tint of it, as
 * the selected profile has, faded in and out. One per card; it remembers where it stopped. While
 * work is under way for the card, its outline can breathe.
 */
class CardHighlight(private val card: MaterialCardView) {
    private val context = card.context
    private val primary = context.getColorAttr(R.attr.colorPrimary)
    private val surface = context.getColorAttr(R.attr.colorSurface)
    private val stroke = context.getColor(R.color.card_stroke)
    private val tint = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(primary, 26), surface)
    private val thin = context.resources.getDimensionPixelSize(R.dimen.card_stroke_width)
    private val thick = context.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected)

    // Where the look is, and where it is going: a card that rebinds unchanged starts nothing.
    private var lit = -1f
    private var aim = -1f
    private var animator: ValueAnimator? = null

    // How strong the outline is while it breathes: 1 at rest.
    private var strength = 1f
    private var breathing = false
    private var breath: ValueAnimator? = null

    // Animations may be switched on or off while the app is away: the breath follows when the window is back.
    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused || !breathing) return@OnWindowFocusChangeListener
        breath?.cancel()
        breath = null
        strength = 1f
        startBreath()
    }

    init {
        // A card off screen stops breathing, and picks it up again when it comes back.
        card.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
                    if (breathing && breath == null) startBreath()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                    breath?.cancel()
                    breath = null
                }
            },
        )
    }

    /** Lights the card up or down; without [animate], or the first time, at once. */
    fun show(on: Boolean, animate: Boolean, delay: Long = 0) {
        val target = if (on) 1f else 0f
        // At once: a card shown for something else, mid-fade or not, takes its own look now.
        if (!animate || lit < 0) {
            if (animator == null && lit == target) return
            animator?.cancel()
            animator = null
            aim = target
            return paint(target)
        }
        if (target == aim) return
        aim = target
        animator?.cancel()
        animator = ValueAnimator.ofFloat(lit, target).apply {
            startDelay = delay
            duration = DURATION
            addUpdateListener { paint(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * While [on], the outline fades slowly between full and faint and back, until the work is done.
     * With animations off it holds at the faint end, which still differs from the solid outline.
     */
    fun breathe(on: Boolean) {
        if (on == breathing) return
        breathing = on
        breath?.cancel()
        breath = null
        if (!on) {
            strength = 1f
        } else if (card.isAttachedToWindow) {
            startBreath()
        }
        paint(lit.coerceAtLeast(0f))
    }

    private fun startBreath() {
        if (!context.animationsOn()) {
            strength = FAINT
            return paint(lit.coerceAtLeast(0f))
        }
        breath = ValueAnimator.ofFloat(1f, FAINT).apply {
            duration = BREATH
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                strength = it.animatedValue as Float
                paint(lit.coerceAtLeast(0f))
            }
            start()
        }
    }

    private fun paint(fraction: Float) {
        lit = fraction
        card.strokeWidth = (thin + (thick - thin) * fraction).toInt()
        card.strokeColor = ColorUtils.blendARGB(stroke, primary, fraction * strength)
        card.setCardBackgroundColor(ColorUtils.blendARGB(surface, tint, fraction))
    }

    private companion object {
        const val DURATION = 220L

        // One breath takes twice this: out to faint, back to full.
        const val BREATH = 900L
        const val FAINT = 0.3f
    }
}

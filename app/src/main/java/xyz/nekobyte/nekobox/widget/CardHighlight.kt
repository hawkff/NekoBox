package xyz.nekobyte.nekobox.widget

import android.animation.ValueAnimator
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr

/**
 * The look of a picked-out card: a thicker outline in the primary color over a faint tint of it, as
 * the selected profile has, faded in and out. One per card; it remembers where it stopped.
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

    private fun paint(fraction: Float) {
        lit = fraction
        card.strokeWidth = (thin + (thick - thin) * fraction).toInt()
        card.strokeColor = ColorUtils.blendARGB(stroke, primary, fraction)
        card.setCardBackgroundColor(ColorUtils.blendARGB(surface, tint, fraction))
    }

    private companion object {
        const val DURATION = 220L
    }
}

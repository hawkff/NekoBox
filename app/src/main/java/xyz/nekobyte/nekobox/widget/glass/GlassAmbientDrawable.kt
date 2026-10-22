package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import xyz.nekobyte.nekobox.ktx.getColorAttr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The light glass bends: two soft glows in the palette's colors over its canvas. */
class GlassAmbientDrawable(
    @ColorInt private val canvasColor: Int,
    @ColorInt private val first: Int,
    @ColorInt private val second: Int,
) : Drawable() {
    private val firstPaint = Paint()
    private val secondPaint = Paint()

    override fun onBoundsChange(bounds: Rect) {
        // A relaunch keeps the old window and wraps this background before it has a size.
        if (bounds.isEmpty) return
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val reach = max(width, height)
        firstPaint.shader = RadialGradient(
            bounds.left + width * 0.1f,
            bounds.top + height * 0.12f,
            reach * 0.75f,
            first,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        secondPaint.shader = RadialGradient(
            bounds.left + width * 0.95f,
            bounds.top + height * 0.72f,
            reach * 0.65f,
            second,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        canvas.drawRect(bounds, firstPaint)
        canvas.drawRect(bounds, secondPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /** The theme's canvas: its window background color, or the background color. */
        @ColorInt
        fun canvasColor(context: Context): Int {
            val value = TypedValue()
            return if (
                context.theme.resolveAttribute(android.R.attr.windowBackground, value, true) &&
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            ) {
                value.data
            } else {
                context.getColorAttr(android.R.attr.colorBackground)
            }
        }

        fun of(context: Context): GlassAmbientDrawable {
            val canvasColor = canvasColor(context)
            val luminance = ColorUtils.calculateLuminance(canvasColor)
            // Black canvases keep near-black corners; light ones need less to show.
            val strength = when {
                luminance < 0.02 -> 0.2f
                luminance < 0.5 -> 0.24f
                else -> 0.16f
            }
            val primary = context.getColorAttr(androidx.appcompat.R.attr.colorPrimary)
            val accent = context.getColorAttr(androidx.appcompat.R.attr.colorAccent)
            return GlassAmbientDrawable(
                canvasColor,
                ColorUtils.setAlphaComponent(primary, (strength * 255).toInt()),
                ColorUtils.setAlphaComponent(secondGlow(primary, accent), (strength * 0.8f * 255).toInt()),
            )
        }
    }
}

/** The second glow: the accent, or the primary turned along the color wheel when the two look alike. */
@ColorInt
internal fun secondGlow(@ColorInt primary: Int, @ColorInt accent: Int): Int {
    val primaryHsl = FloatArray(3)
    val accentHsl = FloatArray(3)
    ColorUtils.colorToHSL(primary, primaryHsl)
    ColorUtils.colorToHSL(accent, accentHsl)
    val apart = abs(primaryHsl[0] - accentHsl[0]).let { min(it, 360 - it) }
    if (apart >= 25f) return accent
    primaryHsl[0] = (primaryHsl[0] + 50f) % 360f
    return ColorUtils.HSLToColor(primaryHsl)
}

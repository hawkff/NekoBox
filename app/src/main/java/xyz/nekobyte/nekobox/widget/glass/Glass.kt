package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr

/**
 * Liquid glass, the opt-in look from Settings > Appearance. Theme.apply records the setting when an
 * activity starts, so drawing never reads the setting store; a change recreates the activity.
 */
object Glass {
    @Volatile
    var enabled = false
        internal set

    /** From Android 12 glass blurs the content behind it; earlier versions keep the tint and rim. */
    val live get() = enabled && Build.VERSION.SDK_INT >= 31

    /** Glass for an app bar whose content does not scroll beneath it: the tint and rim over the canvas. */
    fun styleAppBar(appBar: View) {
        if (!enabled || appBar.background is GlassDrawable) return
        appBar.background = GlassDrawable(appBar, null, barTint(appBar.context), GlassShape.BAR)
        appBar.findViewById<View>(R.id.toolbar)?.background = null
    }

    /** The app bar's color as glass, opaque enough for its title over whatever scrolls beneath. */
    @ColorInt
    fun barTint(context: Context): Int = tint(context.getColorAttr(R.attr.appBarBackgroundColor))

    /** The surface as glass, for panels that carry body text. */
    @ColorInt
    fun surfaceTint(context: Context): Int = tint(context.getColorAttr(com.google.android.material.R.attr.colorSurface))

    /** Clearer glass for a control that carries only an icon in a color drawn for the surface. */
    @ColorInt
    fun orbTint(context: Context): Int {
        val surface = ColorUtils.setAlphaComponent(context.getColorAttr(com.google.android.material.R.attr.colorSurface), 0xFF)
        val alpha = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.4f else 0.55f
        return ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt())
    }

    @ColorInt
    private fun tint(@ColorInt color: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(color, 0xFF)
        val text = if (ColorUtils.calculateLuminance(opaque) < 0.5) Color.WHITE else Color.BLACK
        return ColorUtils.setAlphaComponent(opaque, (glassAlpha(opaque, text) * 255).toInt())
    }
}

/**
 * The lowest opacity of the opaque [tint] at which [text] keeps [target] contrast over the worst
 * content that can pass behind the glass: white behind light text, black behind dark text. Some
 * palettes miss the target even when opaque, so the result stops at [max].
 */
internal fun glassAlpha(@ColorInt tint: Int, @ColorInt text: Int, min: Float = 0.5f, max: Float = 0.92f, target: Double = 4.5): Float {
    val worst = if (ColorUtils.calculateLuminance(text) > 0.5) Color.WHITE else Color.BLACK
    var alpha = min
    while (alpha < max) {
        if (ColorUtils.calculateContrast(text, ColorUtils.blendARGB(worst, tint, alpha)) >= target) return alpha
        alpha += 0.02f
    }
    return max
}

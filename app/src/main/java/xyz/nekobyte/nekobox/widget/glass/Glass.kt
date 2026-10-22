package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
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
        appBar.background = GlassDrawable(appBar, null, barTint(appBar), GlassShape.BAR)
        appBar.findViewById<View>(R.id.toolbar)?.background = null
    }

    /** The color of [appBar] as glass, opaque enough for its title and the tab labels over whatever scrolls beneath. */
    @ColorInt
    fun barTint(appBar: View): Int {
        val context = appBar.context
        // The toolbar's title view carries the color its action bar theme gives it.
        val title = appBar.findViewById<Toolbar>(R.id.toolbar)?.children?.filterIsInstance<TextView>()?.firstOrNull()?.currentTextColor
            ?: context.getColorAttr(android.R.attr.textColorPrimary)
        return tint(
            context.getColorAttr(R.attr.appBarBackgroundColor),
            title to TEXT,
            context.getColorAttr(R.attr.tabSelectedTextColor) to LABEL,
            context.getColorAttr(R.attr.tabTextColor) to LABEL,
        )
    }

    /** The bottom bar's color as glass, opaque enough for the connection status and the speeds. */
    @ColorInt
    fun statsTint(context: Context): Int = tint(
        context.getColorAttr(R.attr.appBarBackgroundColor),
        *listOf(
            com.google.android.material.R.attr.colorOnPrimary,
            R.attr.statusConnectedColor,
            R.attr.statusStoppedColor,
            R.attr.statusConnectingColor,
            R.attr.statusTestingColor,
        ).map { context.getColorAttr(it) to TEXT }.toTypedArray(),
        context.getColorAttr(R.attr.statusDetailColor) to LABEL,
        context.getColorAttr(R.attr.speedTextColor) to LABEL,
    )

    /** The surface as glass, for panels that carry body text. */
    @ColorInt
    fun surfaceTint(context: Context): Int = tint(
        context.getColorAttr(com.google.android.material.R.attr.colorSurface),
        context.getColorAttr(android.R.attr.textColorPrimary) to TEXT,
        context.getColorAttr(android.R.attr.textColorSecondary) to LABEL,
    )

    /** Clearer glass for a control that carries only an icon in a color drawn for the surface. */
    @ColorInt
    fun orbTint(context: Context): Int {
        val surface = ColorUtils.setAlphaComponent(context.getColorAttr(com.google.android.material.R.attr.colorSurface), 0xFF)
        val alpha = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.4f else 0.55f
        return ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt())
    }

    // Contrast targets: titles and body text, and the shorter tab labels and secondary text.
    private const val TEXT = 4.5
    private const val LABEL = 3.0

    @ColorInt
    private fun tint(@ColorInt color: Int, vararg texts: Pair<Int, Double>): Int {
        val opaque = ColorUtils.setAlphaComponent(color, 0xFF)
        val alpha = texts.maxOf { (text, target) -> glassAlpha(opaque, text, target = target) }
        return ColorUtils.setAlphaComponent(opaque, (alpha * 255).toInt())
    }
}

/**
 * The lowest opacity of the opaque [tint] at which [text] keeps [target] contrast over the worst
 * content that can pass behind the glass: white behind text lighter than the tint, black behind
 * darker text. Text that misses the target on the solid tint keeps what it has there, which only
 * a nearly solid tint does, so the result stops at [max].
 */
internal fun glassAlpha(@ColorInt tint: Int, @ColorInt text: Int, min: Float = 0.5f, max: Float = 0.92f, target: Double = 4.5): Float {
    val worst = if (ColorUtils.calculateLuminance(text) > ColorUtils.calculateLuminance(tint)) Color.WHITE else Color.BLACK
    val goal = target.coerceAtMost(ColorUtils.calculateContrast(text, tint))
    var alpha = min
    while (alpha < max) {
        if (ColorUtils.calculateContrast(text, ColorUtils.blendARGB(worst, tint, alpha)) >= goal) return alpha
        alpha += 0.02f
    }
    return max
}

package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import androidx.annotation.ColorInt
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.view.updateLayoutParams
import com.google.android.material.tabs.TabLayout
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr
import kotlin.math.roundToInt

/**
 * Liquid glass, the opt-in look from Settings > Appearance: controls float over the content as
 * panes of glass. Theme.apply records the setting when an activity starts, so drawing never reads
 * the setting store; a change recreates the activity.
 */
object Glass {
    @Volatile
    var enabled = false
        internal set

    private val drawables = ArrayList<GlassDrawable>()

    internal fun register(drawable: GlassDrawable) {
        if (drawable !in drawables) drawables += drawable
    }

    internal fun unregister(drawable: GlassDrawable) {
        drawables -= drawable
    }

    /** Lets the glass panes in [root]'s window answer a touch; the touch then goes on as usual. */
    fun onTouch(event: MotionEvent, root: View) {
        if (!enabled) return
        for (drawable in drawables.toTypedArray()) if (drawable.host.rootView === root) drawable.onTouch(event)
    }

    /** Clear glass: the surface color, thin enough that the content shows through. */
    @ColorInt
    fun tint(context: Context): Int {
        val surface = ColorUtils.setAlphaComponent(context.getColorAttr(com.google.android.material.R.attr.colorSurface), 0xFF)
        val alpha = if (ColorUtils.calculateLuminance(surface) < 0.5) 0.24f else 0.42f
        return ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt())
    }

    /** Glass for a panel of body text, such as the drawer: opaque enough for its labels over anything behind. */
    @ColorInt
    fun surfaceTint(context: Context): Int {
        val surface = ColorUtils.setAlphaComponent(context.getColorAttr(com.google.android.material.R.attr.colorSurface), 0xFF)
        val alpha = maxOf(
            glassAlpha(surface, context.getColorAttr(android.R.attr.textColorPrimary), target = 4.5),
            glassAlpha(surface, context.getColorAttr(android.R.attr.textColorSecondary), target = 3.0),
        )
        return ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt())
    }

    /** An app bar whose content does not scroll beneath it: a clear bar with its buttons as glass. */
    fun styleAppBar(appBar: View) {
        if (!enabled || appBar !is ViewGroup || appBar.background is GlassDrawable) return
        val toolbar = appBar.findViewById<Toolbar>(R.id.toolbar) ?: return
        floatToolbar(toolbar)
        appBar.background = GlassDrawable(appBar, null, tint(appBar.context)) { toolbarPanes(appBar, toolbar) }
    }

    /** A toolbar whose buttons float as glass: no fill, and room at the sides so the panes clear the screen edges. */
    fun floatToolbar(toolbar: Toolbar) {
        toolbar.background = null
        val side = (8 * toolbar.resources.displayMetrics.density).roundToInt()
        toolbar.setPadding(side, toolbar.paddingTop, side, toolbar.paddingBottom)
    }

    /** The navigation (or collapse) button as a glass circle; the actions, or an expanded search, as a capsule. */
    fun GlassLayout.toolbarPanes(host: ViewGroup, toolbar: Toolbar) {
        val inset = 4 * toolbar.resources.displayMetrics.density
        for (child in toolbar.children) {
            when (child) {
                is ImageButton -> paneOver(host, child, inset, inset, circle = true)
                is ActionMenuView -> if (child.isNotEmpty()) paneOver(host, child, 0f, inset)
                is SearchView -> paneOver(host, child, 0f, inset)
            }
        }
    }

    /** Group tabs in a glass capsule; the selected tab sits in a clearer pill that stretches as it slides. */
    fun styleTabs(tabLayout: TabLayout, tabBar: View) {
        val context = tabLayout.context
        val density = context.resources.displayMetrics.density
        val pill = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(Color.WHITE)
        }
        val side = (2 * density).roundToInt()
        val gap = (5 * density).roundToInt()
        tabLayout.setSelectedTabIndicator(InsetDrawable(pill, side, gap, side, gap))
        val dark = ColorUtils.calculateLuminance(GlassAmbientDrawable.canvasColor(context)) < 0.5
        val onSurface = context.getColorAttr(com.google.android.material.R.attr.colorOnSurface)
        tabLayout.setSelectedTabIndicatorColor(ColorUtils.setAlphaComponent(onSurface, if (dark) 0x2E else 0x17))
        tabLayout.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
        tabLayout.isTabIndicatorFullWidth = true
        tabLayout.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
        tabLayout.tabRippleColor = null
        val margin = (12 * density).roundToInt()
        tabBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { setMargins(margin, gap, margin, 0) }
        // Tabs scrolling past the capsule's round ends stay inside it.
        tabBar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
        }
        tabBar.clipToOutline = true
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

package xyz.nekobyte.nekobox.widget.glass

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlassTest {

    @Test
    fun glassKeepsItsTextReadableOverAnyContent() {
        // Light text on a dark bar: the worst content behind is white.
        val dark = glassAlpha(Color.BLACK, Color.WHITE)
        assertTrue(ColorUtils.calculateContrast(Color.WHITE, ColorUtils.blendARGB(Color.WHITE, Color.BLACK, dark)) >= 4.5)
        // And no more opaque than that needs.
        assertTrue(ColorUtils.calculateContrast(Color.WHITE, ColorUtils.blendARGB(Color.WHITE, Color.BLACK, dark - 0.02f)) < 4.5)
        // Dark text on a light bar: the worst content behind is black.
        val light = glassAlpha(Color.WHITE, Color.BLACK)
        assertTrue(ColorUtils.calculateContrast(Color.BLACK, ColorUtils.blendARGB(Color.BLACK, Color.WHITE, light)) >= 4.5)
    }

    @Test
    fun aPaletteKeepsItsOwnTextReadable() {
        // Catppuccin's bar with its mauve selected tab and subtext unselected tabs, over white content:
        // mid-tone text lighter than the bar needs the bar dark enough, not a fixed white-text alpha.
        val bar = 0xFF1E1E2E.toInt()
        for ((text, target) in listOf(Color.WHITE to 4.5, 0xFFCBA6F7.toInt() to 3.0, 0xFFBAC2DE.toInt() to 3.0)) {
            val alpha = glassAlpha(bar, text, target = target)
            assertTrue(ColorUtils.calculateContrast(text, ColorUtils.blendARGB(Color.WHITE, bar, alpha)) >= target)
        }
        // Translucent labels count as composited over the glass: 60% white tabs on indigo.
        val indigo = 0xFF3F51B5.toInt()
        val faded = 0x99FFFFFF.toInt()
        assertTrue(ColorUtils.calculateContrast(faded, ColorUtils.blendARGB(Color.WHITE, indigo, glassAlpha(indigo, faded, target = 3.0))) >= 3.0)
    }

    @Test
    fun aPaletteThatMissesTheTargetStaysNearlyOpaque() {
        // White on Material pink 500 stays under 4.5:1 even when opaque.
        assertEquals(0.92f, glassAlpha(0xFFE91E63.toInt(), Color.WHITE), 0f)
    }

    @Test
    fun toolbarButtonsFloatAsACircleAndACapsule() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(ContextThemeWrapper(activity, R.style.Theme_NekoBox))
        val toolbar = Toolbar(host.context)
        host.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        toolbar.navigationIcon = ColorDrawable(Color.WHITE)
        repeat(2) { toolbar.menu.add("action $it").setIcon(ColorDrawable(Color.WHITE)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        activity.setContentView(host)
        fun lay(): GlassLayout {
            host.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 1080, 400)
            return GlassLayout().also { with(Glass) { it.toolbarPanes(host, toolbar) } }
        }

        val layout = lay()
        assertEquals(2, layout.count)
        val button = layout.panes[0]
        assertEquals(button.rect.width(), button.rect.height(), 0.01f)
        assertEquals(button.rect.width() / 2, button.radius, 0.01f)
        val actions = layout.panes[1]
        assertTrue(actions.anchor is ActionMenuView)
        assertEquals(actions.rect.height() / 2, actions.radius, 0.01f)
        // One capsule holds both actions.
        assertTrue(actions.rect.width() > 1.5f * actions.rect.height())
        assertTrue(button.rect.right < actions.rect.left)

        // A button that is gone gets no pane.
        toolbar.navigationIcon = null
        assertEquals(1, lay().count)
    }

    @Test
    fun onlyATrueBlackCanvasStaysFlat() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun canvas(palette: Int) = GlassAmbientDrawable.of(ContextThemeWrapper(activity, R.style.Theme_NekoBox).apply { theme.applyStyle(palette, true) })
        assertEquals(Color.BLACK, (canvas(R.style.ThemeOverlay_NekoBox_Palette_DarkHighContrast) as ColorDrawable).color)
        // The darkest canvas that is not black keeps its glows.
        for (palette in listOf(R.style.ThemeOverlay_NekoBox_Palette_Catppuccin, R.style.ThemeOverlay_NekoBox_Palette_Nord)) {
            assertTrue(canvas(palette) is GlassAmbientDrawable)
        }
    }

    @Test
    fun theSecondGlowStandsApartFromTheFirst() {
        val green = 0xFF4CAF50.toInt()
        val primary = FloatArray(3)
        val second = FloatArray(3)
        ColorUtils.colorToHSL(green, primary)
        ColorUtils.colorToHSL(secondGlow(green, 0xFF66BB6A.toInt()), second)
        assertEquals((primary[0] + 50f) % 360f, second[0], 1f)
        // An accent of its own stays.
        assertEquals(0xFF2196F3.toInt(), secondGlow(green, 0xFF2196F3.toInt()))
    }
}

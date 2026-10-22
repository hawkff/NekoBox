package xyz.nekobyte.nekobox.widget.glass

import android.app.Application
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun aPaletteThatMissesTheTargetStaysNearlyOpaque() {
        // White on Material pink 500 stays under 4.5:1 even when opaque.
        assertEquals(0.92f, glassAlpha(0xFFE91E63.toInt(), Color.WHITE), 0f)
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

package xyz.nekobyte.nekobox.utils

import android.app.Application
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.getColorAttr

// A floating action button's icon takes colorOnPrimary over fabColorBackground (Widget.NekoBox.FloatingActionButton).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThemePaletteTest {
    private fun fabContrast(theme: Int): Double {
        // Layered as Theme.apply layers a screen's theme, without the glass, which reads the settings.
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_NekoBox).apply {
            this.theme.applyStyle(R.style.ThemeOverlay_NekoBox_Common, true)
            this.theme.applyStyle(Theme.palette(theme), true)
        }
        return ColorUtils.calculateContrast(context.getColorAttr(com.google.android.material.R.attr.colorOnPrimary), context.getColorAttr(R.attr.fabColorBackground))
    }

    @Test
    @Config(qualifiers = "night")
    fun blackFabIconsStandOutAtNight() {
        assertTrue(fabContrast(Theme.BLACK) >= 4.5)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun blackFabIconsStandOutByDay() {
        assertTrue(fabContrast(Theme.BLACK) >= 4.5)
    }
}

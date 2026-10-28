package xyz.nekobyte.nekobox.utils

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeNightModeTest {
    @Test
    fun darkOnlyThemesStayDarkWhateverTheNightSettingSays() {
        for (theme in Theme.DARK_ONLY_THEMES + 0) {
            for (setting in 0..3) {
                assertEquals("theme $theme, setting $setting", AppCompatDelegate.MODE_NIGHT_YES, Theme.nightMode(theme, setting))
            }
        }
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, Theme.nightMode(Theme.BLUE, 2))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, Theme.nightMode(Theme.DYNAMIC, 0))
    }

    @Test
    fun anUnsetThemeIsTheDarkOnlyDefault() {
        assertTrue(Theme.darkOnly(0))
        assertTrue(Theme.darkOnly(Theme.DARK_HIGH_CONTRAST))
        assertFalse(Theme.darkOnly(Theme.BLUE))
        assertFalse(Theme.darkOnly(Theme.DYNAMIC))
    }
}

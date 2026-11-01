package xyz.nekobyte.nekobox.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.bg.BaseService.State

// What each state shows, and what a change under way does when the orb hides or its connection ends. The tests never
// step frames: the animations themselves, the turning disk and the twinkle run on the device, and the emulator records
// them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OrbRingTest {
    private val orb = OrbRing(View(RuntimeEnvironment.getApplication())).apply { offColor = Color.GRAY }

    // Hue, ring, rim, twinkle, turns a second.
    private fun shows(vararg levels: Float) = assertArrayEquals(levels, orb.levels, 0.001f)

    @Test
    fun eachStateShowsAtOnceWhenTheChangeIsNotAnimated() {
        orb.show(State.Connecting, animate = false)
        shows(1f, 0.35f, 0f, 0f, 1f)
        orb.show(State.Connected, animate = false)
        shows(1f, 1f, 1f, 1f, 0.15f)
        // Opening on a connection shows it connected, without the burst of connecting.
        assertEquals(-1f, orb.burst, 0f)
        orb.show(State.Stopping, animate = false)
        shows(0f, 0f, 0f, 0f, 0f)
        orb.show(State.Connected, animate = false)
        orb.show(State.Stopped, animate = false)
        shows(0f, 0f, 0f, 0f, 0f)
    }

    @Test
    fun aHiddenOrbLandsAChangeUnderWay_andShowsLaterOnesAtOnce() {
        orb.show(State.Connecting, animate = true)
        orb.pause()
        shows(1f, 0.35f, 0f, 0f, 1f)
        orb.show(State.Connected, animate = true)
        shows(1f, 1f, 1f, 1f, 0.15f)
        // No burst plays where nobody sees it.
        assertEquals(-1f, orb.burst, 0f)
    }

    @Test
    fun aBurstEndsWithItsConnection() {
        orb.show(State.Connecting, animate = true)
        orb.show(State.Connected, animate = true)
        assertTrue(orb.burst >= 0f)
        orb.show(State.Stopping, animate = true)
        assertEquals(-1f, orb.burst, 0f)
    }

    @Test
    fun everyStateDraws_onDarkAndLightThemes() {
        val canvas = Canvas(Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888))
        for (dark in listOf(true, false)) {
            // A fresh orb a theme: the black hole makes its textures and shaders for the theme it first draws on.
            val orb = OrbRing(View(RuntimeEnvironment.getApplication())).apply {
                offColor = Color.GRAY
                this.dark = dark
            }
            for (state in listOf(State.Idle, State.Connecting, State.Connected, State.Stopping, State.Stopped)) {
                orb.show(state, animate = false)
                orb.draw(canvas, 108f, 108f, 108f)
            }
        }
    }
}

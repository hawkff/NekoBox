package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.CLAIM
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.DROP
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.WATCH
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.decide
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.follow
import xyz.nekobyte.nekobox.ui.WideSwipeBack.Companion.goes

class WideSwipeBackTest {

    @Test
    fun onlyAClearlyHorizontalRightwardMoveIsASwipe() {
        val slop = 10f
        assertEquals(WATCH, decide(15f, 2f, slop, longPress = false))
        assertEquals(CLAIM, decide(25f, 5f, slop, longPress = false))
        // Wide, but not twice as wide as tall: keep watching.
        assertEquals(WATCH, decide(25f, 15f, slop, longPress = false))
        assertEquals(DROP, decide(5f, 20f, slop, longPress = false))
        assertEquals(DROP, decide(-15f, 0f, slop, longPress = false))
        // A long press first, as when reordering profiles, leaves the touch to the list.
        assertEquals(DROP, decide(25f, 0f, slop, longPress = true))
    }

    @Test
    fun releaseGoesBackOnDistanceOrFling() {
        assertTrue(goes(400f, 0f, 1000f, 2f))
        assertFalse(goes(200f, 100f, 1000f, 2f))
        assertTrue(goes(200f, 800f, 1000f, 2f))
        // A flick shorter than 48 dp is not a swipe.
        assertFalse(goes(60f, 2000f, 1000f, 2f))
    }

    @Test
    fun theContentFollowsWithResistance() {
        assertEquals(0f, follow(-10f, 112f), 0f)
        assertEquals(1f, follow(1f, 112f), 0.02f)
        assertTrue(follow(10_000f, 112f) <= 112f)
    }
}

package xyz.nekobyte.nekobox.ui

import android.view.View
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.widget.CheckButton
import kotlin.math.abs

/** The check button is a centered capsule, three quarters wide, that becomes a round spinner while a check runs. */
@RunWith(AndroidJUnit4::class)
class CheckButtonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theCapsuleDrawsInWhileTheCheckRunsAndGrowsBack() {
        ActivityScenario.launch(StunActivity::class.java).use { scenario ->
            settle()
            lateinit var frame: CheckButton
            lateinit var button: MaterialButton
            scenario.onActivity {
                frame = it.findViewById(R.id.nat_start_frame)
                button = it.findViewById(R.id.nat_start)
            }
            fun ring() = frame.children.filterIsInstance<CircularProgressIndicator>().single()
            fun centered() = abs((button.left + button.right) / 2 - frame.width / 2) <= 1

            instrumentation.runOnMainSync {
                assertTrue(abs(button.width - (frame.width - frame.paddingLeft - frame.paddingRight) * 3 / 4) <= 1)
                assertTrue(centered())
                assertEquals(View.GONE, ring().visibility)
                frame.show(running = true, "Checking…")
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(button.height, button.width)
                // Drawn edge to edge, so the square is a circle.
                assertEquals(0, button.insetTop + button.insetBottom)
                assertTrue(centered())
                assertEquals("", button.text.toString())
                assertEquals("Checking…", button.contentDescription)
                assertFalse(button.isClickable)
                assertEquals(View.VISIBLE, ring().visibility)
                frame.show(running = false, "Check again")
            }
            settle()
            instrumentation.runOnMainSync {
                assertTrue(button.width > button.height)
                assertEquals("Check again", button.text.toString())
                assertTrue(button.isClickable)
                assertEquals(View.GONE, ring().visibility)
            }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1200)
    }
}

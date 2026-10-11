package xyz.nekobyte.nekobox.ui

import android.app.Activity
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = android.app.Application::class)
class ShortcutConfirmationTest {
    @Before fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun everyActionWaitsForExplicitConfirmation() {
        for ((action, message) in listOf(
            R.string.quick_enable to R.string.shortcut_enable_confirmation,
            R.string.quick_disable to R.string.shortcut_disable_confirmation,
            R.string.quick_toggle to R.string.shortcut_toggle_confirmation,
        )) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            var calls = 0
            val dialog = controller.get().confirmVpnShortcut(action, message) { calls++ }
            try {
                assertEquals(0, calls)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, calls)
            } finally {
                dialog.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun cancelAndBackNeverExecuteTheAction() {
        for (back in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            var calls = 0
            val activity = controller.get()
            val dialog = activity.confirmVpnShortcut(R.string.quick_disable, R.string.shortcut_disable_confirmation) { calls++ }
            try {
                if (back) dialog.cancel() else dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, calls)
                assertTrue(activity.isFinishing)
            } finally {
                dialog.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }
}

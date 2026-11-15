package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.io.File
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AssetsActivityTest {

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        // One process here; Robolectric cannot connect Room's remote invalidation service.
        shadowOf(application).declareComponentUnbindable(ComponentName(application, "androidx.room.MultiInstanceInvalidationService"))
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun reloadFromAWorkerChangesTheListOnTheMainThread() {
        val controller = Robolectric.buildActivity(AssetsActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val shown = activity.adapter.assets.toList()
            File(activity.getExternalFilesDir(null) ?: activity.filesDir, "custom.db").writeText("")

            thread { activity.adapter.reloadAssets() }.join()
            assertEquals(shown, activity.adapter.assets)

            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.adapter.assets.any { it.name == "custom.db" })
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

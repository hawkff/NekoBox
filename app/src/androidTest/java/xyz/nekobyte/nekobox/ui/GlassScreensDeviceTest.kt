package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.widget.glass.Glass
import xyz.nekobyte.nekobox.widget.glass.GlassDrawable

/** With liquid glass on, every drawer screen scrolls beneath its floating bars, as Home does. */
@RunWith(AndroidJUnit4::class)
class GlassScreensDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var savedGlass = false

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGlass = DataStore.liquidGlass
        DataStore.liquidGlass = true
    }

    @After
    fun tearDown() {
        DataStore.liquidGlass = savedGlass
    }

    @Test
    fun everyDrawerScreenScrollsBeneathItsFloatingBars() {
        val screens = listOf(R.id.nav_configuration, R.id.nav_group, R.id.nav_route, R.id.nav_settings, R.id.nav_logcat, R.id.nav_connections, R.id.nav_tools, R.id.nav_about)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (screen in screens) {
                scenario.onActivity { it.displayFragmentWithId(screen) }
                instrumentation.waitForIdleSync()
                Thread.sleep(1500)
                scenario.onActivity { activity ->
                    val name = activity.resources.getResourceEntryName(screen)
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
                    val root = fragment.requireView()
                    val bars = root.findViewById<View>(R.id.glass_bars)
                    val content = root.findViewById<View>(R.id.glass_content)
                    assertTrue(name, bars.background is GlassDrawable)
                    // The content fills the screen, from the top, beneath the bars.
                    assertEquals(name, 0, content.top)
                    val fade = activity.resources.getDimensionPixelSize(R.dimen.glass_edge_fade)
                    assertEquals(name, bars.height - fade, fragment.glassInset)
                    // Its lists start below the bars and scroll up beneath them.
                    val pages = fragment.childFragmentManager.fragments.mapNotNull { it.view }
                    val scrollers = (listOf(content) + pages).flatMap { Glass.verticalScrollers(it) }.distinct()
                    assertFalse(name, scrollers.isEmpty())
                    for (scroller in scrollers) assertTrue("$name: ${scroller.javaClass.simpleName}", scroller.paddingTop >= fragment.glassInset)
                }
            }
        }
    }
}

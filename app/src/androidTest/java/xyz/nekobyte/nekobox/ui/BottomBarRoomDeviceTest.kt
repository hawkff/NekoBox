package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.widget.glass.Glass

/** With the connect button on every screen, each screen's lists can scroll their last row above it. */
@RunWith(AndroidJUnit4::class)
class BottomBarRoomDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var savedBottomBar = false

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedBottomBar = DataStore.showBottomBar
        DataStore.showBottomBar = true
    }

    @After
    fun tearDown() {
        DataStore.showBottomBar = savedBottomBar
    }

    @Test
    fun everyDrawerScreenKeepsRoomBelowItsLastRow() {
        val screens = listOf(
            R.id.nav_configuration, R.id.nav_group, R.id.nav_route, R.id.nav_settings, R.id.nav_logcat,
            R.id.nav_connections, R.id.nav_tools, R.id.nav_local_api, R.id.nav_about,
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (screen in screens) {
                scenario.onActivity { it.displayFragmentWithId(screen) }
                instrumentation.waitForIdleSync()
                Thread.sleep(1500)
                scenario.onActivity { activity ->
                    val name = activity.resources.getResourceEntryName(screen)
                    val room = activity.resources.getDimensionPixelSize(R.dimen.bottom_bar_room)
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
                    val pages = fragment.childFragmentManager.fragments.mapNotNull { it.view }
                    val scrollers = (listOf(fragment.requireView()) + pages).flatMap { Glass.verticalScrollers(it) }.distinct()
                    assertFalse(name, scrollers.isEmpty())
                    for (scroller in scrollers) assertTrue("$name: ${scroller.javaClass.simpleName}", scroller.paddingBottom >= room)
                }
            }
        }
    }
}

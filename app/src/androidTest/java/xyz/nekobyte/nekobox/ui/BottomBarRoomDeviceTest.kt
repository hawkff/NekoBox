package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
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
    fun everyDrawerScreenScrollsItsLastRowAboveTheConnectButton() {
        val screens = listOf(
            R.id.nav_configuration, R.id.nav_group, R.id.nav_route, R.id.nav_settings, R.id.nav_logcat,
            R.id.nav_connections, R.id.nav_tools, R.id.nav_local_api, R.id.nav_about,
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (screen in screens) {
                scenario.onActivity { it.displayFragmentWithId(screen) }
                instrumentation.waitForIdleSync()
                Thread.sleep(1500)
                var scrollers = emptyList<View>()
                scenario.onActivity { activity ->
                    val name = activity.resources.getResourceEntryName(screen)
                    val room = activity.resources.getDimensionPixelSize(R.dimen.bottom_bar_room)
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
                    val pages = fragment.childFragmentManager.fragments.mapNotNull { it.view }
                    scrollers = (listOf(fragment.requireView()) + pages).flatMap { Glass.verticalScrollers(it) }.distinct().filter { it.isShown }
                    assertFalse(name, scrollers.isEmpty())
                    for (scroller in scrollers) {
                        assertTrue("$name: ${scroller.javaClass.simpleName}", scroller.paddingBottom >= room)
                        scrollToEnd(scroller)
                    }
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    val name = activity.resources.getResourceEntryName(screen)
                    val button = activity.findViewById<View>(R.id.fab)
                    assertTrue("$name: the connect button shows", button.isShown)
                    val buttonTop = top(button)
                    for (scroller in scrollers) {
                        val last = lastRowBottom(scroller) ?: continue
                        assertTrue("$name: ${scroller.javaClass.simpleName} ends at $last, under the button at $buttonTop", last <= buttonTop)
                    }
                }
            }
        }
    }

    @Test
    fun theConnectButtonStaysOffSettingsAfterTheScreenIsRecreated() {
        DataStore.showBottomBar = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_settings) }
            instrumentation.waitForIdleSync()
            // A theme or glass change recreates the activity the same way.
            scenario.recreate()
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)
            scenario.onActivity { activity ->
                assertFalse("the connect button shows over Settings", activity.findViewById<View>(R.id.fab).isShown)
            }
        }
    }

    private fun scrollToEnd(scroller: View) {
        when (scroller) {
            is RecyclerView -> scroller.adapter?.itemCount?.takeIf { it > 0 }?.let {
                scroller.scrollToPosition(it - 1)
                scroller.post { scroller.scrollBy(0, Int.MAX_VALUE / 2) }
            }

            is NestedScrollView -> scroller.fullScroll(View.FOCUS_DOWN)

            is ScrollView -> scroller.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Where the list's content ends on screen: its lowest row, or a scroll view's content. */
    private fun lastRowBottom(scroller: View): Int? {
        val rows = (scroller as? ViewGroup)?.children?.filter { it.isShown }?.toList().orEmpty()
        return rows.maxOfOrNull { top(it) + it.height }
    }

    private fun top(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[1]
}

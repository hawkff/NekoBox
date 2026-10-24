package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

/** Swiping between groups on the profile list, with touches the way a finger sends them. */
@RunWith(AndroidJUnit4::class)
class GroupPagingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var groups: List<Long>
    private var savedGroup = 0L

    @Before
    fun setUp() {
        // On a fresh device the launch asks for this, and the prompt would take the swipes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        // Two groups after any the device already has, each with a profile.
        groups = List(2) { index ->
            ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Paging ${index + 1}", userOrder = 1_000_000L + index)).also { id ->
                ProfileDatabase.proxyDao.addProxy(
                    ProxyEntity(groupId = id).putBean(
                        SOCKSBean().applyDefaultValues().apply {
                            name = "paging-${index + 1}"
                            serverAddress = "192.0.2.1"
                            serverPort = 1080
                        },
                    ),
                )
            }
        }
        DataStore.selectedGroup = groups[0]
    }

    @After
    fun tearDown() {
        for (id in groups) {
            ProfileDatabase.proxyDao.deleteAll(id)
            ProfileDatabase.groupDao.deleteById(id)
        }
        DataStore.selectedGroup = savedGroup
    }

    @Test
    fun aSwipeMovesBetweenGroupsButNotWhileSearching() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var pager: ViewPager2
            lateinit var search: SearchView
            scenario.onActivity {
                pager = it.findViewById(R.id.group_pager)
                search = it.findViewById(R.id.action_search)
            }
            assertTrue(pager.isUserInputEnabled)

            // A drag that rests before lifting springs back and selects nothing.
            drag(pager, from = 0.8f, to = 0.55f, rest = true)
            assertEquals(groups[0], DataStore.selectedGroup)

            // A fling moves to the next group.
            drag(pager, from = 0.8f, to = 0.2f, rest = false)
            assertEquals(groups[1], DataStore.selectedGroup)

            // With the search open the pager stays put; its close button lets it page again.
            instrumentation.runOnMainSync { search.isIconified = false }
            settle()
            assertFalse(pager.isUserInputEnabled)
            drag(pager, from = 0.2f, to = 0.8f, rest = false)
            assertEquals(groups[1], DataStore.selectedGroup)
            instrumentation.runOnMainSync { search.isIconified = true }
            settle()
            assertTrue(pager.isUserInputEnabled)
        }
    }

    @Test
    fun fromTheFirstGroupASwipeOpensTheDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var activity: MainActivity
            lateinit var pager: ViewPager2
            scenario.onActivity {
                activity = it
                pager = it.findViewById(R.id.group_pager)
            }
            instrumentation.runOnMainSync { pager.setCurrentItem(1, false) }
            settle()

            // From the second group a rightward swipe pages back.
            drag(pager, from = 0.6f, to = 0.95f, rest = false)
            assertEquals(0, pager.currentItem)
            assertFalse(activity.binding.drawerLayout.isOpen)

            // On the first nothing lies to the left: the same swipe, from the middle of the list, opens the drawer.
            drag(pager, from = 0.6f, to = 0.95f, rest = false)
            assertTrue(activity.binding.drawerLayout.isOpen)
            instrumentation.runOnMainSync { activity.binding.drawerLayout.close() }
        }
    }

    @Test
    fun backClosesAnOpenDrawerBeforeAnythingElse() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var activity: MainActivity
            scenario.onActivity {
                activity = it
                it.binding.drawerLayout.open()
            }
            settle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            assertFalse(activity.binding.drawerLayout.isOpen)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // On another screen too: the drawer closes and the screen stays.
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_route) }
            settle()
            scenario.onActivity { it.binding.drawerLayout.open() }
            settle()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            scenario.onActivity {
                assertFalse(it.binding.drawerLayout.isOpen)
                assertTrue(it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is RouteFragment)
            }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
    }

    /** A horizontal drag across [view] from and to fractions of its width; with [rest] the finger stops before lifting. */
    private fun drag(view: View, from: Float, to: Float, rest: Boolean) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val y = location[1] + view.height / 2f
        val start = location[0] + view.width * from
        val end = location[0] + view.width * to
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, start)
        for (step in 1..10) {
            Thread.sleep(10)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 10)
        }
        if (rest) {
            repeat(10) {
                Thread.sleep(60)
                send(MotionEvent.ACTION_MOVE, end)
            }
        }
        send(MotionEvent.ACTION_UP, end)
        settle()
    }
}

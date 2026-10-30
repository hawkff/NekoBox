package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.utils.toBytesString

/**
 * The groups list opens a row in place with what the provider reports and removes held rows together,
 * and the editor says what a group is.
 */
@RunWith(AndroidJUnit4::class)
class GroupsScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = listOf("Groups test subscription", "Groups test own")
    private val used = 1_500_000L
    private val total = 50_000_000L
    private val groups = ArrayList<Long>()
    private var savedGroup = 0L

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        val now = System.currentTimeMillis() / 1000
        // Straight into the database: through the group manager a new subscription would start updating.
        groups += ProfileDatabase.groupDao.createGroup(
            ProxyGroup(name = names[0], type = GroupType.SUBSCRIPTION, userOrder = 1_000_000L).apply {
                subscription = SubscriptionBean().applyDefaultValues().apply {
                    link = "https://sub.example.org/s"
                    lastUpdated = (now - 3 * 86_400).toInt()
                    // As providers report it: bytes up and down, the total and when it ends.
                    subscriptionUserinfo = "upload=500000; download=1000000; total=$total; expire=${now + 27 * 86_400 + 3_600}"
                    // The provider proposes a new link and a backup, both waiting for approval.
                    offeredLink = "https://new.example.org/s"
                    offeredBackupLink = "https://backup.example.org/s"
                }
            },
        )
        groups += ProfileDatabase.groupDao.createGroup(ProxyGroup(name = names[1], userOrder = 1_000_001L)).also { id ->
            ProfileDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = id).putBean(
                    SOCKSBean().applyDefaultValues().apply {
                        name = "groups-test"
                        serverAddress = "192.0.2.1"
                        serverPort = 1080
                    },
                ),
            )
        }
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
    fun aRowOpensInPlaceWithWhatTheProviderReports() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_group) }
            settle()
            lateinit var list: RecyclerView
            scenario.onActivity { list = it.findViewById(R.id.group_list) }
            instrumentation.runOnMainSync { list.scrollToPosition(list.adapter!!.itemCount - 1) }
            settle()
            fun row(name: String) = list.children.first { it.findViewById<TextView>(R.id.name).text.toString() == name }

            instrumentation.runOnMainSync {
                val subscription = row(names[0])
                assertEquals(View.VISIBLE, subscription.findViewById<View>(R.id.meter).visibility)
                assertEquals(context.getString(R.string.group_days_short, 27), subscription.findViewById<TextView>(R.id.days).text.toString())
                assertEquals(View.GONE, subscription.findViewById<View>(R.id.details).visibility)
                // A group of your own has nothing to update.
                val own = row(names[1])
                assertEquals("1", own.findViewById<TextView>(R.id.count).text.toString())
                assertEquals(View.GONE, own.findViewById<View>(R.id.update).visibility)
                subscription.performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                val subscription = row(names[0])
                assertEquals(View.VISIBLE, subscription.findViewById<View>(R.id.details).visibility)
                val traffic = context.getString(R.string.group_traffic_of, used.toBytesString(), total.toBytesString())
                assertEquals(traffic, subscription.findViewById<TextView>(R.id.traffic).text.toString())
                // Every action stays reachable: the ⋮ keeps its square inside the row.
                val more = subscription.findViewById<View>(R.id.more)
                assertTrue(more.width > 0 && more.width == more.height && more.right <= (more.parent as View).width)
                // The small meter has become the full bar.
                assertEquals(View.GONE, subscription.findViewById<View>(R.id.meter).visibility)
                assertEquals(View.VISIBLE, subscription.findViewById<View>(R.id.traffic_meter).visibility)
                // Opening another row closes this one.
                row(names[1]).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, row(names[0]).findViewById<View>(R.id.details).visibility)
                assertEquals(View.VISIBLE, row(names[1]).findViewById<View>(R.id.details).visibility)
            }
        }
    }

    @Test
    fun heldRowsAreRemovedTogether() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_group) }
            settle()
            lateinit var activity: MainActivity
            lateinit var screen: GroupFragment
            scenario.onActivity {
                activity = it
                screen = it.supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
            }
            val list = screen.groupListView
            instrumentation.runOnMainSync { list.scrollToPosition(list.adapter!!.itemCount - 1) }
            settle()
            fun row(name: String) = list.children.first { it.findViewById<TextView>(R.id.name).text.toString() == name }
            fun shown() = screen.groupAdapter.groupList.map { it.name }.filter { it in names }
            fun selected(count: Int) = context.resources.getQuantityString(R.plurals.group_selected, count, count)

            // A held row that follows the finger was dragged, even if it never reached another row, and even
            // if the finger lifted right after it moved. The first move only picks the row up.
            lateinit var first: View
            for (steps in listOf(5, 2)) {
                instrumentation.runOnMainSync { first = row(names[0]) }
                hold(first, by = 2f * ViewConfiguration.get(context).scaledTouchSlop, steps = steps)
                settle()
                instrumentation.runOnMainSync {
                    assertFalse("$steps steps", row(names[0]).isSelected)
                    assertEquals(context.getString(R.string.menu_group), screen.toolbar.title.toString())
                }
            }
            instrumentation.runOnMainSync { first = row(names[0]) }

            // A finger that rests on a row and lifts without moving selects it, and does not open it.
            hold(first)
            settle()
            instrumentation.runOnMainSync {
                assertTrue(row(names[0]).isSelected)
                assertEquals(View.GONE, row(names[0]).findViewById<View>(R.id.details).visibility)
                assertEquals(selected(1), screen.toolbar.title.toString())
                // While one is selected, a tap selects another instead of opening it.
                row(names[1]).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                assertTrue(row(names[1]).isSelected)
                assertEquals(View.GONE, row(names[1]).findViewById<View>(R.id.details).visibility)
                assertEquals(selected(2), screen.toolbar.title.toString())
                assertTrue(screen.toolbar.menu.performIdentifierAction(R.id.action_remove_selected, 0))
            }
            settle()

            // Remove asks first, naming the groups, then takes both off at once.
            val dialog = instrumentation.uiAutomation.rootInActiveWindow
            assertEquals(1, dialog.findAccessibilityNodeInfosByText(context.resources.getQuantityString(R.plurals.group_remove, 2, 2)).size)
            for (name in names) assertTrue(name, dialog.findAccessibilityNodeInfosByText(name).isNotEmpty())
            val remove = dialog.findAccessibilityNodeInfosByText(context.getString(R.string.delete)).first { it.className?.toString() == Button::class.java.name }
            assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            instrumentation.runOnMainSync {
                assertEquals(emptyList<String>(), shown())
                assertEquals(context.getString(R.string.menu_group), screen.toolbar.title.toString())
                // One undo brings both back, in their places.
                activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            settle()
            instrumentation.runOnMainSync { assertEquals(names, shown()) }

            // A screen reader selects with the row's long press action. Without Undo, the snackbar going away
            // deletes the groups, off the main thread.
            instrumentation.runOnMainSync {
                assertTrue(row(names[0]).performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                row(names[1]).performClick()
                assertEquals(selected(2), screen.toolbar.title.toString())
                screen.groupAdapter.removeSelected()
                screen.undoManager.flush()
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            fun left() = ProfileDatabase.groupDao.allGroups().map { it.id }.filter { it in groups }
            while (left().isNotEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
            assertEquals(emptyList<Long>(), left())
        }
    }

    @Test
    fun theEditorSaysWhatTheGroupIsAndAsksForALink() {
        val edit = Intent(context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, groups[0])
        ActivityScenario.launch<GroupSettingsActivity>(edit).use { scenario ->
            settle()
            scenario.onActivity {
                val sentence = it.findViewById<TextView>(R.id.sentence).text.toString()
                assertTrue(sentence, sentence.startsWith(context.getString(R.string.group_editor_sentence_subscription, "sub.example.org", "").substringBefore(" · ")))
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.provider).visibility)
                assertEquals(2, it.findViewById<ViewGroup>(R.id.offers).childCount)
                // Nothing under More options is in use, so it stays folded.
                assertEquals(View.GONE, it.findViewById<View>(R.id.more).visibility)
                it.findViewById<View>(R.id.kind_own).performClick()
            }
            settle()
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<View>(R.id.subscription).visibility)
                assertEquals(context.getString(R.string.group_editor_sentence_own), it.findViewById<TextView>(R.id.sentence).text.toString())
            }
        }

        // A new subscription waits for its link, and Save says so instead of storing an empty one.
        val create = Intent(context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_SUBSCRIPTION, true)
        ActivityScenario.launch<GroupSettingsActivity>(create).use { scenario ->
            settle()
            scenario.onActivity {
                assertTrue(it.findViewById<com.google.android.material.chip.Chip>(R.id.kind_subscription).isChecked)
                assertEquals(context.getString(R.string.group_editor_sentence_new), it.findViewById<TextView>(R.id.sentence).text.toString())
                it.findViewById<View>(R.id.action_apply).performClick()
            }
            settle()
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertNotNull(it.findViewById<TextInputLayout>(R.id.link_layout).error)
            }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
    }

    /** A finger resting on [view] past the long press timeout, then moving [by] pixels down in [steps] before it lifts. */
    private fun hold(view: View, by: Float = 0f, steps: Int = 5) {
        val location = IntArray(2)
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 4f
            y = location[1] + view.height / 2f
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, moved: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + moved, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() * 2L)
        // Without a move, the lift is the first event after the long press, as from a finger held still.
        if (by != 0f) {
            for (step in 1..steps) {
                send(MotionEvent.ACTION_MOVE, by * step / steps)
                if (step < steps) Thread.sleep(50)
            }
        }
        send(MotionEvent.ACTION_UP, by)
    }
}

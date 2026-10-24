package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyGroup

/** A screen reader moves and removes rows through actions, which do what a drag or a swipe does. */
@RunWith(AndroidJUnit4::class)
class RowActionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val names = (1..3).map { "Row action test $it" }

    @Test
    fun groupsMoveAndGoAwayWithoutDragging() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking { names.forEach { GroupManager.createGroup(ProxyGroup(name = it)) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.displayFragmentWithId(R.id.nav_group) }
                settle()
                lateinit var list: RecyclerView
                scenario.onActivity { list = it.findViewById(R.id.group_list) }
                val rows = list.compatAccessibilityDelegate!!.itemDelegate
                fun row(name: String): View? = list.children.firstOrNull { (list.getChildViewHolder(it) as GroupFragment.GroupHolder).proxyGroup.name == name }
                fun actions(view: View) = AccessibilityNodeInfoCompat.obtain().also { rows.onInitializeAccessibilityNodeInfo(view, it) }.actionList.map { it.id }

                instrumentation.runOnMainSync {
                    val first = row(names[0])!!
                    assertTrue(actions(first).containsAll(listOf(R.id.row_move_down, R.id.row_remove)))
                    // The groups made last are at the end: the last row has nowhere to go down to.
                    assertFalse(R.id.row_move_down in actions(row(names[2])!!))
                    assertTrue(rows.performAccessibilityAction(first, R.id.row_move_down, null))
                }
                settle()
                assertEquals(listOf(names[1], names[0], names[2]), ProfileDatabase.groupDao.allGroups().map { it.name }.filter { it in names })

                instrumentation.runOnMainSync { assertTrue(rows.performAccessibilityAction(row(names[2])!!, R.id.row_remove, null)) }
                settle()
                instrumentation.runOnMainSync { assertEquals(null, row(names[2])) }
            }
        } finally {
            runBlocking { GroupManager.deleteGroup(ProfileDatabase.groupDao.allGroups().filter { it.name in names }) }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1200)
    }
}

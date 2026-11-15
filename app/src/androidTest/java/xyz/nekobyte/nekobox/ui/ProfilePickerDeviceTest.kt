package xyz.nekobyte.nekobox.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyGroup

/** A picker Android rebuilds, after a rotation or a dark mode switch, is still a picker. */
@RunWith(AndroidJUnit4::class)
class ProfilePickerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "Picker rebuild test"

    @Test
    fun aRebuiltPickerStaysAPicker() {
        runBlocking { GroupManager.createGroup(ProxyGroup(name = name)) }
        try {
            ActivityScenario.launch(ProfileSelectActivity::class.java).use { scenario ->
                settle()
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    val pickers = activity.supportFragmentManager.fragments.filterIsInstance<ConfigurationFragment>()
                    assertEquals(1, pickers.size)
                    assertTrue(pickers.single().select)
                }
            }
        } finally {
            runBlocking { GroupManager.deleteGroup(ProfileDatabase.groupDao.allGroups().filter { it.name == name }) }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1200)
    }
}

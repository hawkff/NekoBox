package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ui.profile.ProfileSettingsActivity
import xyz.nekobyte.nekobox.ui.profile.SocksSettingsActivity

/**
 * What a profile's row and its editor do with what is stored: a move stores the new order alone, the Share menu copies
 * the stored profile's config, built off the main thread, a picker's row copies too and opens no editor, and an editor
 * reads its profile off the main thread.
 */
@RunWith(AndroidJUnit4::class)
class ProfileRowsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = listOf("Row test alpha", "Row test bravo", "Row test charlie")
    private var group = 0L
    private var savedGroup = 0L
    private var savedLayout: String? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        savedLayout = DataStore.configurationStore.getString(Key.GROUP_LAYOUT_MODE)
        group = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Row test servers", userOrder = 1_000_000L))
        names.forEachIndexed { index, name ->
            ProfileDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = group, userOrder = index + 1L).putBean(
                    SOCKSBean().applyDefaultValues().apply {
                        this.name = name
                        serverAddress = "192.0.2.${index + 1}"
                        serverPort = 1080
                    },
                ),
            )
        }
        DataStore.selectedGroup = group
        // Compact rows keep their actions under the menu, in a picker as well.
        DataStore.groupLayoutMode = GroupLayout.COMPACT
    }

    @After
    fun tearDown() {
        ProfileDatabase.proxyDao.deleteAll(group)
        ProfileDatabase.groupDao.deleteById(group)
        DataStore.selectedGroup = savedGroup
        savedLayout.let { if (it == null) DataStore.configurationStore.remove(Key.GROUP_LAYOUT_MODE) else DataStore.configurationStore.putString(Key.GROUP_LAYOUT_MODE, it) }
    }

    private fun profile(name: String) = ProfileDatabase.proxyDao.getByGroup(group).first { it.displayName() == name }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_500)
        instrumentation.waitForIdleSync()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $what" }
            SystemClock.sleep(200)
        }
    }

    private fun row(list: RecyclerView, name: String) = (0 until list.childCount).map(list::getChildAt).first { it.findViewById<TextView>(R.id.profile_name)?.text?.toString() == name }

    // The row of [name] on [screen]'s page of the test group.
    private fun holder(screen: ConfigurationFragment, name: String): ConfigurationGroupFragment.ConfigurationHolder {
        val list = screen.adapter.groupFragments.getValue(group).configurationListView
        return list.getChildViewHolder(row(list, name)) as ConfigurationGroupFragment.ConfigurationHolder
    }

    // Picks [item] from the row's Share menu, as a tap on it does.
    private fun share(holder: ConfigurationGroupFragment.ConfigurationHolder, item: Int) {
        assertTrue(holder.shareMenu(holder.view, holder.entity.id)!!.menu.performIdentifierAction(item, 0))
    }

    // Waits for the screen to say that something was copied.
    private fun <A : Activity> awaitCopied(scenario: ActivityScenario<A>) = await("the message") {
        var message: String? = null
        scenario.onActivity { message = it.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString() }
        message == context.getString(R.string.action_export_msg)
    }

    // The list on show was read before the service stored a session's traffic; the move must not write that row back.
    @Test
    fun aMovedProfile_storesItsNewOrderAlone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var list: RecyclerView
            scenario.onActivity { list = (it.currentScreen() as ConfigurationFragment).adapter.groupFragments.getValue(group).configurationListView }
            // Rows move only once the service has said whether it runs.
            await("the service's state") { DataStore.serviceState != BaseService.State.Idle }
            val moved = profile(names[0])
            ProfileDatabase.proxyDao.addLifetimeTraffic(moved.id, 100, 200)
            instrumentation.runOnMainSync {
                assertTrue(list.compatAccessibilityDelegate!!.itemDelegate.performAccessibilityAction(row(list, names[0]), R.id.row_move_down, null))
            }
            settle()
            assertEquals(listOf(names[1], names[0], names[2]), ProfileDatabase.proxyDao.getByGroup(group).map { it.displayName() })
            val stored = ProfileDatabase.proxyDao.getById(moved.id)!!
            assertEquals(100L to 200L, stored.lifetimeRx to stored.lifetimeTx)
        }
    }

    // The config copied is the stored profile's, built off the main thread: this build refuses the database there, and
    // the list on show was read before the server moved.
    @Test
    fun theShareMenuCopiesTheStoredProfilesConfig() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            ProfileDatabase.proxyDao.updateProxy(profile(names[0]).apply { socksBean!!.serverAddress = "198.51.100.9" })
            // An earlier run's copy is not this one's.
            NekoBox.trySetPrimaryClip("nothing copied yet")
            scenario.onActivity { share(holder(it.currentScreen() as ConfigurationFragment, names[0]), R.id.action_config_export_clipboard) }
            await("the copied config") { "198.51.100.9" in NekoBox.getClipboardText() }
            awaitCopied(scenario)
            assertTrue("198.51.100.9" in NekoBox.getClipboardText())
        }
    }

    // One export waits for its file at a time, so the file picker's answer is for that profile; the answer, here a
    // cancel, lets the next export in.
    @Test
    fun aFileExportKeepsItsProfile_untilTheFilePickerAnswers() {
        val first = profile(names[0]).id
        val second = profile(names[1]).id
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var home: ConfigurationFragment
            fun exporting(): Long {
                var id = 0L
                instrumentation.runOnMainSync { id = home.exportingProfile }
                return id
            }
            scenario.onActivity {
                home = it.currentScreen() as ConfigurationFragment
                home.saveConfigAs(first, "first.json")
                home.saveConfigAs(second, "second.json")
            }
            assertEquals(first, exporting())
            // Back leaves the file picker once it covers the screen; a first one may only fold its keyboard away.
            await("the file picker's answer") {
                val waiting = exporting() != 0L
                if (waiting && runCatching { scenario.state }.getOrNull()?.isAtLeast(Lifecycle.State.RESUMED) == false) {
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(1_000)
                }
                !waiting
            }
        }
    }

    @Test
    fun aPickersRowCopiesItsLink_andOpensNoEditor() {
        val link = profile(names[0]).toStdLink()
        ActivityScenario.launch(ProfileSelectActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val holder = holder(activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment, names[0])
                val menu = holder.rowMenu(holder.view, holder.entity.id)!!.menu
                // The screen that asked for a profile is often an editor, and editors share one store for their fields.
                assertNull(menu.findItem(R.id.action_edit))
                assertNotNull(menu.findItem(R.id.action_share))
                share(holder, R.id.action_standard_clipboard)
            }
            assertEquals(link, NekoBox.getClipboardText())
            awaitCopied(scenario)
        }
    }

    @Test
    fun anEditor_readsItsProfileOffTheMainThread() {
        // A new profile has none to read.
        ActivityScenario.launch(SocksSettingsActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { assertNull(it.proxyEntity) }
        }
        val stored = profile(names[0])
        val edit = Intent(context, SocksSettingsActivity::class.java).putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, stored.id)
        ActivityScenario.launch<SocksSettingsActivity>(edit).use { scenario ->
            settle()
            // A recreated editor reads it again, before a menu asks for it on the main thread.
            scenario.recreate()
            settle()
            scenario.onActivity { assertEquals(stored.id, it.proxyEntity?.id) }
        }
    }
}

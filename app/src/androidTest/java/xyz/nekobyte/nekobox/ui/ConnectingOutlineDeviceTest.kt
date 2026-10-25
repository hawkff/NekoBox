package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

/** The profile the service is connecting to says so, to the eye and to TalkBack, until the connection is up. */
@RunWith(AndroidJUnit4::class)
class ConnectingOutlineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "connecting-1"
    private var group = 0L
    private var profile = 0L
    private var savedGroup = 0L
    private var savedProxy = 0L
    private var savedCurrent = 0L

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        savedProxy = DataStore.selectedProxy
        savedCurrent = DataStore.currentProfile
        group = ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "Connecting", userOrder = 1_000_000L))
        profile = ProfileDatabase.proxyDao.addProxy(
            ProxyEntity(groupId = group).putBean(
                SOCKSBean().applyDefaultValues().apply {
                    name = this@ConnectingOutlineDeviceTest.name
                    serverAddress = "192.0.2.1"
                    serverPort = 1080
                },
            ),
        )
        DataStore.selectedGroup = group
        DataStore.selectedProxy = profile
    }

    @After
    fun tearDown() {
        DataStore.serviceState = BaseService.State.Stopped
        ProfileDatabase.proxyDao.deleteAll(group)
        ProfileDatabase.groupDao.deleteById(group)
        DataStore.selectedGroup = savedGroup
        DataStore.selectedProxy = savedProxy
        DataStore.currentProfile = savedCurrent
    }

    @Test
    fun theProfileBeingConnectedSaysSoUntilItIsUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var root: View
            scenario.onActivity { root = it.window.decorView }
            DataStore.currentProfile = profile
            val connecting = instrumentation.targetContext.getString(R.string.connecting)

            state(scenario, BaseService.State.Connecting)
            instrumentation.runOnMainSync {
                val card = card(root)
                assertEquals(connecting, ViewCompat.getStateDescription(card)?.toString())
                assertTrue(card.isSelected)
            }

            state(scenario, BaseService.State.Connected)
            instrumentation.runOnMainSync {
                val card = card(root)
                assertNull(ViewCompat.getStateDescription(card))
                assertTrue(card.isSelected)
            }
        }
    }

    // The app sets the state and asks Home to refresh, as a connection's progress does.
    private fun state(scenario: ActivityScenario<MainActivity>, state: BaseService.State) {
        DataStore.serviceState = state
        scenario.onActivity { (it.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment).refreshProfileState() }
        settle()
    }

    private fun card(root: View): MaterialCardView = find(root) ?: error("no card for $name")

    private fun find(view: View): MaterialCardView? {
        if (view is MaterialCardView && named(view)) return view
        return (view as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
    }

    private fun named(view: View): Boolean = (view as? TextView)?.text?.toString() == name ||
        ((view as? ViewGroup)?.children?.any(::named) == true)

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
    }
}

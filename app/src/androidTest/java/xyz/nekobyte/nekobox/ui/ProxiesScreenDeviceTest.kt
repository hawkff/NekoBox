package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

/** Lists of proxies live on the Proxies screen, which shows them alone; Home and Groups leave them out. */
@RunWith(AndroidJUnit4::class)
class ProxiesScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val serversName = "Proxies test servers"
    private val listName = "Proxies test residential"
    private val groups = ArrayList<Long>()
    private var savedGroup = 0L
    private var savedList = 0L

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        savedList = DataStore.selectedProxyList
        groups += group(serversName, GroupType.BASIC, "192.0.2.10")
        groups += group(listName, GroupType.PROXIES, "198.51.100.7", "198.51.100.8")
        // A checked proxy shows the address it leaves from.
        val first = ProfileDatabase.proxyDao.getByGroup(groups[1]).first { it.requireBean().serverAddress == "198.51.100.7" }
        DataStore.rememberProxyExits(mapOf(first.id to "203.0.113.77"))
    }

    private fun group(name: String, type: Int, vararg servers: String): Long = ProfileDatabase.groupDao.createGroup(
        ProxyGroup(name = name, type = type, userOrder = 1_000_000L + groups.size),
    ).also { id ->
        for (server in servers) {
            ProfileDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = id).putBean(
                    SOCKSBean().applyDefaultValues().apply {
                        serverAddress = server
                        serverPort = 1080
                        username = "user"
                        password = "pw"
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
        DataStore.selectedProxyList = savedList
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_500)
        instrumentation.waitForIdleSync()
    }

    private fun tabs(screen: ConfigurationFragment) = screen.adapter.groupList.map { it.displayName() }

    @Test
    fun theProxiesScreenShowsOnlyProxyListsAndHomeNone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val home = activity.currentScreen() as ConfigurationFragment
                assertTrue("Home shows the servers", serversName in tabs(home))
                assertFalse("Home leaves the proxy list out", listName in tabs(home))
                activity.displayFragmentWithId(R.id.nav_proxies)
            }
            settle()
            scenario.onActivity { activity ->
                val proxies = activity.currentScreen() as ConfigurationFragment
                assertTrue(proxies.proxies)
                assertEquals(listOf(listName), tabs(proxies).filter { it.startsWith("Proxies test") })
                assertFalse(serversName in tabs(proxies))
                assertEquals(context.getString(R.string.menu_proxies), activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title.toString())
                assertEquals(View.GONE, proxies.requireView().findViewById<View>(R.id.group_empty).visibility)
                proxies.groupPager.setCurrentItem(proxies.adapter.groupList.indexOfFirst { it.displayName() == listName }, false)
            }
            settle()
            scenario.onActivity { activity ->
                val proxies = activity.currentScreen() as ConfigurationFragment
                val list = proxies.getCurrentGroupFragment()!!.configurationListView
                val texts = allTexts(list)
                assertTrue("a checked proxy names its exit: $texts", texts.any { context.getString(R.string.proxies_exit, "203.0.113.77") in it })
                // Proxy rows keep Connect and Use as exit under ⋮: the card buttons stay hidden.
                assertTrue(list.findViewById<View>(R.id.double_column_menu).visibility == View.VISIBLE)
                activity.displayFragmentWithId(R.id.nav_group)
            }
            settle()
            scenario.onActivity { activity ->
                val rows = allTexts(activity.findViewById(R.id.group_list))
                assertTrue("Groups lists the servers", rows.any { it == serversName })
                assertFalse("Groups leaves the proxy list out", rows.any { it == listName })
            }
        }
    }

    private fun allTexts(list: RecyclerView): List<String> {
        val texts = ArrayList<String>()
        fun walk(view: View) {
            if (view is TextView && view.isShown) texts += view.text.toString()
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(list)
        return texts
    }
}

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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.group.MullvadRelays
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
    private var savedExits: String? = null
    private var savedFolds: String? = null

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        savedList = DataStore.selectedProxyList
        savedExits = DataStore.configurationStore.getString(Key.PROXY_EXITS)
        savedFolds = DataStore.configurationStore.getString(Key.FOLDED_SECTIONS)
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
        DataStore.configurationStore.putString(Key.PROXY_EXITS, savedExits.orEmpty())
        DataStore.configurationStore.putString(Key.FOLDED_SECTIONS, savedFolds.orEmpty())
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
                // Home stays under it with a toolbar of its own: this screen's is the one in its view.
                assertEquals(context.getString(R.string.menu_proxies), proxies.requireView().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title.toString())
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

    /**
     * Mullvad's list opens by country, then city, as Mullvad's app does; a lone city opens with its country. 280
     * servers, each with an exit on record, and each fold still answers at once.
     */
    @Test
    fun mullvadsListOpensByCountryThenCity_andFoldsAtOnce() {
        val servers = JSONArray()
        for ((c, country) in listOf("se", "al", "de", "fr", "gb", "nl", "no", "pl", "ro", "us").withIndex()) {
            // Albania has one city; the others three, of ten servers each. City codes are letters, as Mullvad's are.
            val letter = 'a' + c
            val cities = if (country == "al") listOf("tia" to "Tirana") else listOf("${letter}a" to "Alpha$c", "${letter}b" to "Bravo$c", "${letter}c" to "Charlie$c")
            for ((city, name) in cities) {
                for (n in 1..10) {
                    val number = "%03d".format(n)
                    servers.put(
                        JSONObject().put("country_code", country).put("city_code", city).put("city_name", name).put("active", true)
                            .put("socks_name", "$country-$city-wg-socks5-$number.relays.mullvad.net").put("socks_port", 1080).put("pubkey", ""),
                    )
                }
            }
        }
        val parsed = MullvadRelays.parse(servers.toString())
        assertEquals(280, parsed.relays.size)
        // A list of the test's own: sync without one would fill, and the teardown delete, a Mullvad list already there.
        val list = ProfileDatabase.groupDao.createGroup(
            ProxyGroup(name = "Proxies test Mullvad", type = GroupType.PROXIES, subscription = MullvadRelays.subscription(null), userOrder = 1_000_000L + groups.size),
        )
        groups += list
        runBlocking { MullvadRelays.sync(list, parsed) }
        DataStore.rememberProxyExits(ProfileDatabase.proxyDao.getByGroup(list).associate { it.id to "2001:db8::${it.id}" })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { activity -> activity.displayFragmentWithId(R.id.nav_proxies) }
            settle()
            lateinit var screen: ConfigurationFragment
            scenario.onActivity { activity ->
                screen = activity.currentScreen() as ConfigurationFragment
                screen.groupPager.setCurrentItem(screen.adapter.groupList.indexOfFirst { it.id == list }, false)
            }
            settle()
            fun list() = screen.getCurrentGroupFragment()!!.configurationListView
            fun texts(): List<String> {
                lateinit var texts: List<String>
                instrumentation.runOnMainSync { texts = allTexts(list()) }
                return texts
            }
            fun tap(title: String) {
                var before = 0
                val started = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync {
                    before = list().adapter!!.itemCount
                    (headerView(list(), title) ?: error("no header $title in ${allTexts(list())}")).performClick()
                }
                // The whole fold: its click, the diff behind it, and the layout that shows its rows.
                var done = false
                while (!done && SystemClock.uptimeMillis() - started < 5_000) {
                    SystemClock.sleep(5)
                    instrumentation.runOnMainSync { done = list().adapter!!.itemCount != before && !list().hasPendingAdapterUpdates() && !list().isLayoutRequested }
                }
                val took = SystemClock.uptimeMillis() - started
                assertTrue("folding $title took $took ms", done && took < 500)
                settle()
            }
            // Countries start folded.
            assertTrue(texts().containsAll(listOf("\uD83C\uDDF8\uD83C\uDDEA Sweden", "\uD83C\uDDE6\uD83C\uDDF1 Albania")))
            assertFalse("001" in texts())
            tap("\uD83C\uDDE6\uD83C\uDDF1 Albania")
            assertTrue("a lone city opens with its country: ${texts()}", texts().containsAll(listOf("Tirana", "001", "002")))
            tap("\uD83C\uDDE6\uD83C\uDDF1 Albania")
            assertFalse("Tirana" in texts())
            tap("\uD83C\uDDF8\uD83C\uDDEA Sweden")
            assertTrue(texts().containsAll(listOf("Alpha0", "Bravo0", "Charlie0")))
            assertFalse("cities start folded", "001" in texts())
            tap("Bravo0")
            assertTrue(texts().containsAll(listOf("001", "002")))
            // The server rows leave the country, city and protocol to the headers: no SOCKS5, no exit line.
            assertFalse(texts().any { it == "SOCKS5" || it.startsWith("exit ") })
        }
    }

    // The header row whose title reads [title].
    private fun headerView(list: RecyclerView, title: String): View? = (0 until list.childCount).map(list::getChildAt).firstOrNull { row ->
        row.findViewById<TextView>(R.id.title)?.text?.toString() == title
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

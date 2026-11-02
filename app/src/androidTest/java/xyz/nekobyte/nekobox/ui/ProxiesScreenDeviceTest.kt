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
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
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
    private var savedSelected = 0L
    private var savedFavorites: String? = null
    private var savedPicks: String? = null
    private var savedRecentsOn = true
    private val savedLayouts = HashMap<String, String?>()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        savedGroup = DataStore.selectedGroup
        savedList = DataStore.selectedProxyList
        savedExits = DataStore.configurationStore.getString(Key.PROXY_EXITS)
        savedFolds = DataStore.configurationStore.getString(Key.FOLDED_SECTIONS)
        for (key in listOf(Key.GROUP_LAYOUT_MODE, Key.FAVORITES_LAYOUT_MODE)) savedLayouts[key] = DataStore.configurationStore.getString(key)
        savedSelected = DataStore.selectedProxy
        savedFavorites = DataStore.configurationStore.getString(Key.FAVORITE_PROFILES)
        savedPicks = DataStore.configurationStore.getString(Key.RECENT_PICKS)
        savedRecentsOn = DataStore.recentsOn
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
        DataStore.selectedProxy = savedSelected
        DataStore.recentsOn = savedRecentsOn
        DataStore.configurationStore.putString(Key.FAVORITE_PROFILES, savedFavorites.orEmpty())
        DataStore.configurationStore.putString(Key.RECENT_PICKS, savedPicks.orEmpty())
        for ((key, value) in savedLayouts) if (value == null) DataStore.configurationStore.remove(key) else DataStore.configurationStore.putString(key, value)
    }

    /**
     * Favorites' ⋮ holds a layout of its own, on Home and on the Proxies screen alike: picked there it leaves the other
     * tabs as they are, and their layout leaves Favorites as it is. A screen kept in the background follows on return.
     */
    @Test
    fun favoritesKeepsALayoutOfItsOwn_onHomeAndTheProxiesScreen() {
        val server = ProfileDatabase.proxyDao.getByGroup(groups[0]).first()
        DataStore.updateFavoriteProfiles { it + server.id }
        DataStore.groupLayoutMode = GroupLayout.TWO_COLUMNS
        DataStore.configurationStore.remove(Key.FAVORITES_LAYOUT_MODE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            lateinit var home: ConfigurationFragment
            lateinit var proxies: ConfigurationFragment
            // The Proxies screen is built first, on Favorites, and stays in the background as a visit leaves it.
            scenario.onActivity { activity ->
                home = activity.currentScreen() as ConfigurationFragment
                activity.displayFragmentWithId(R.id.nav_proxies)
            }
            settle()
            scenario.onActivity { activity ->
                proxies = activity.currentScreen() as ConfigurationFragment
                proxies.groupPager.setCurrentItem(proxies.adapter.groupList.indexOfFirst { it.id == FAVORITES_GROUP_ID }, false)
            }
            settle()
            assertEquals("Favorites shows the tabs' layout until it has its own", GroupLayout.TWO_COLUMNS, rowType(proxies, FAVORITES_GROUP_ID))
            scenario.onActivity { activity -> activity.displayFragmentWithId(R.id.nav_configuration) }
            settle()
            scenario.onActivity { home.groupPager.setCurrentItem(home.adapter.groupList.indexOfFirst { it.id == FAVORITES_GROUP_ID }, false) }
            settle()
            instrumentation.runOnMainSync {
                val menu = home.toolbar.menu
                val layout = menu.findItem(R.id.action_order_layout)
                assertTrue(layout.isVisible)
                assertEquals(context.getString(R.string.favorites_layout), layout.title.toString())
                assertFalse("Favorites has no order to sort", menu.findItem(R.id.action_order_origin).isVisible)
                assertFalse("nor sections", menu.findItem(R.id.action_sections).isVisible)
                assertTrue(menu.findItem(R.id.action_layout_double).isChecked)
                assertTrue(menu.performIdentifierAction(R.id.action_layout_compact, 0))
            }
            settle()
            assertEquals(GroupLayout.COMPACT, rowType(home, FAVORITES_GROUP_ID))
            assertEquals(GroupLayout.TWO_COLUMNS, DataStore.groupLayoutMode)
            // The servers' tab keeps its layout and its own menu; changing it leaves Favorites alone.
            scenario.onActivity { home.groupPager.setCurrentItem(home.adapter.groupList.indexOfFirst { it.id == groups[0] }, false) }
            settle()
            assertEquals(GroupLayout.TWO_COLUMNS, rowType(home, groups[0]))
            instrumentation.runOnMainSync {
                val menu = home.toolbar.menu
                assertEquals(context.getString(R.string.sort_and_layout), menu.findItem(R.id.action_order_layout).title.toString())
                assertTrue(menu.findItem(R.id.action_order_origin).isVisible)
                assertTrue(menu.findItem(R.id.action_layout_double).isChecked)
                assertTrue(menu.performIdentifierAction(R.id.action_layout_single, 0))
            }
            settle()
            assertEquals(GroupLayout.CARDS, rowType(home, groups[0]))
            assertEquals(GroupLayout.COMPACT, rowType(home, FAVORITES_GROUP_ID))
            // Back on the kept Proxies screen, Favorites shows its own layout.
            scenario.onActivity { activity -> activity.displayFragmentWithId(R.id.nav_proxies) }
            settle()
            assertEquals(GroupLayout.COMPACT, rowType(proxies, FAVORITES_GROUP_ID))
        }
    }

    // The view type of the first row of [screen]'s page for [groupId]: its layout, as neither page here has headers.
    private fun rowType(screen: ConfigurationFragment, groupId: Long): Int {
        var type = -1
        instrumentation.runOnMainSync { type = screen.adapter.groupFragments.getValue(groupId).configurationListView.adapter!!.getItemViewType(0) }
        return type
    }

    /**
     * A pick leads its list under Recent, and stays in its place below too; a star brings Favorites to the Proxies
     * screen; turning Recent off drops the fold and forgets the picks.
     */
    @Test
    fun aPickLeadsTheListUnderRecent_andAStarBringsFavoritesHere() {
        val name = "Proxies test recent"
        val list = group(name, GroupType.PROXIES, "198.51.100.11", "198.51.100.12", "198.51.100.13", "198.51.100.14", "198.51.100.15")
        groups += list
        DataStore.turnRecents(true)
        val picked = ProfileDatabase.proxyDao.getByGroup(list).first { it.requireBean().serverAddress == "198.51.100.13" }
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
            fun titles() = texts(list(), R.id.title)
            fun names() = texts(list(), R.id.profile_name)
            assertFalse("nothing picked yet: ${titles()}", context.getString(R.string.recent) in titles())
            instrumentation.runOnMainSync {
                (0 until list().childCount).map(list()::getChildAt).first { it.findViewById<TextView>(R.id.profile_name)?.text?.toString() == picked.displayName() }.performClick()
            }
            settle()
            // Recent grows above the rows on screen, which stay put while picking.
            instrumentation.runOnMainSync { list().scrollToPosition(0) }
            settle()
            // The rest of a list without sections goes under All, so it does not read as Recent's.
            assertEquals(listOf(context.getString(R.string.recent), context.getString(R.string.recent_all)), titles())
            assertEquals("the pick leads, and keeps its place: ${names()}", listOf(picked.displayName()), names().take(1))
            assertEquals(2, names().count { it == picked.displayName() })
            assertEquals(listOf(picked.id), DataStore.recentPicks[list])

            DataStore.updateFavoriteProfiles { it + picked.id }
            runBlocking {
                GroupManager.postReload(FAVORITES_GROUP_ID)
                ProfileManager.postUpdate(picked.id, noTraffic = true)
            }
            settle()
            assertEquals("Favorites joins the Proxies screen", context.getString(R.string.favorites), tabs(screen).first())
            val stars = ArrayList<Boolean>()
            instrumentation.runOnMainSync {
                (0 until list().childCount).map(list()::getChildAt).filter { it.findViewById<TextView>(R.id.profile_name)?.text?.toString() == picked.displayName() }
                    .mapTo(stars) { it.findViewById<View>(R.id.favorite_mark).isShown }
            }
            assertEquals("a star wherever the favorite shows", listOf(true, true), stars)

            // All folds like a section, and a jump to a profile under it opens it again.
            val other = ProfileDatabase.proxyDao.getByGroup(list).first { it.requireBean().serverAddress == "198.51.100.15" }
            instrumentation.runOnMainSync {
                (0 until list().childCount).map(list()::getChildAt).first { it.findViewById<TextView>(R.id.title)?.text?.toString() == context.getString(R.string.recent_all) }.performClick()
            }
            settle()
            assertFalse("All folds: ${names()}", other.displayName() in names())
            instrumentation.runOnMainSync { screen.getCurrentGroupFragment()!!.scrollToProfile(other.id) }
            settle()
            assertTrue("a jump opens All: ${names()}", other.displayName() in names())

            instrumentation.runOnMainSync {
                DataStore.turnRecents(false)
                screen.switchAllGroupFragmentsLayout()
            }
            settle()
            assertFalse(context.getString(R.string.recent) in titles())
            assertTrue(DataStore.recentPicks.isEmpty())
        }
    }

    // The text of each [id] view on screen in [list], in order.
    private fun texts(list: RecyclerView, id: Int): List<String> {
        val found = ArrayList<String>()
        instrumentation.runOnMainSync {
            (0 until list.childCount).map(list::getChildAt).sortedBy { it.top }.forEach { row ->
                row.findViewById<TextView>(id)?.takeIf { it.isShown }?.let { found += it.text.toString() }
            }
        }
        return found
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
            // A pick leads the list under Recent, named whole: no country or city stands above it there.
            instrumentation.runOnMainSync {
                (0 until list().childCount).map(list()::getChildAt).first { it.findViewById<TextView>(R.id.profile_name)?.text?.toString() == "001" }.performClick()
            }
            settle()
            instrumentation.runOnMainSync { list().scrollToPosition(0) }
            settle()
            assertEquals(context.getString(R.string.recent), texts(list(), R.id.title).first())
            assertEquals("\uD83C\uDDF8\uD83C\uDDEA Bravo0 001", texts(list(), R.id.profile_name).first())
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

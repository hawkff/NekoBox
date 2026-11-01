package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MullvadRelaysTest {
    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    // One entry of the server list, as Mullvad's API writes it.
    private fun server(
        host: String,
        city: String = "Amsterdam",
        active: Boolean = true,
        socks: String = host.replace("-wg-", "-wg-socks5-") + ".relays.mullvad.net",
        port: Int = 1080,
    ) = JSONObject().apply {
        put("hostname", host)
        put("country_code", host.substring(0, 2))
        put("city_code", host.split('-')[1])
        put("city_name", city)
        put("active", active)
        put("socks_name", socks)
        put("socks_port", port)
        put("pubkey", "key-$host")
    }

    private fun relays(vararg servers: JSONObject) = MullvadRelays.parse(JSONArray(servers.toList()).toString(), Locale.ENGLISH)

    private fun names(listId: Long) = ProfileDatabase.proxyDao.getByGroup(listId).map { it.displayName() }

    @Test
    fun parse_keepsServersInServiceUnderMullvadsRelayDomain_byCountryNameCityAndHost() {
        val parsed = relays(
            server("se-sto-wg-002", "Stockholm"),
            server("nl-ams-wg-002"),
            server("de-fra-wg-001", "Frankfurt"),
            server("de-ber-wg-001", "Berlin"),
            server("nl-ams-wg-001"),
            server("nl-ams-wg-003", active = false),
            server("nl-ams-wg-004", socks = "nl-ams-wg-socks5-004.relays.example.com"),
            server("nl-ams-wg-005", socks = "se-sto-wg-socks5-005.relays.mullvad.net"),
            server("nl-ams-wg-006", port = 0),
        )
        assertEquals(
            listOf("de-ber-wg-socks5-001", "de-fra-wg-socks5-001", "nl-ams-wg-socks5-001", "nl-ams-wg-socks5-002", "se-sto-wg-socks5-002"),
            parsed.map { it.host.substringBefore('.') },
        )
        assertTrue(MullvadRelays.parse("<html>Service unavailable</html>").isEmpty())
    }

    @Test
    fun aPick_holdsWholeCountriesAndSingleCities_orEveryServer() {
        val parsed = relays(server("us-nyc-wg-001", "New York, NY"), server("us-lax-wg-001", "Los Angeles, CA"), server("nl-ams-wg-001"), server("se-sto-wg-001", "Stockholm"))
        fun held(codes: Set<String>?) = parsed.filter(MullvadRelays.holds(MullvadRelays.subscription(codes))).mapTo(HashSet()) { it.place }
        assertEquals(parsed.mapTo(HashSet()) { it.place }, held(null))
        assertEquals(setOf("nl-ams", "us-nyc"), held(setOf("nl", "us-nyc")))
        assertEquals(setOf("us-nyc", "us-lax"), held(setOf("us")))
    }

    @Test
    fun aNewList_holdsThePickedServersNamedByCity_andConnectsThroughAStoredMullvadTunnel() = runTest {
        withContext(Dispatchers.IO) {
            val home = ProxyGroup(name = "Servers").also { it.id = ProfileDatabase.groupDao.createGroup(it) }
            val tunnel = ProfileManager.createProfile(
                home.id,
                WireGuardBean().applyDefaultValues().apply {
                    name = "Mullvad Stockholm"
                    serverAddress = "192.0.2.1"
                    serverPort = 51820
                    peerPublicKey = "key-se-sto-wg-001"
                },
            )
            val parsed = relays(server("se-sto-wg-001", "Stockholm"), server("nl-ams-wg-002"), server("nl-ams-wg-001"))

            val result = MullvadRelays.sync(0L, parsed, MullvadRelays.subscription(setOf("nl")))

            assertTrue(result.created)
            assertEquals(2, result.added)
            assertEquals(tunnel.id, result.through?.id)
            val list = ProfileDatabase.groupDao.getById(result.list.id)!!
            assertEquals(GroupType.PROXIES, list.type)
            assertTrue(MullvadRelays.isRelayList(list))
            assertEquals(tunnel.id, list.frontProxy)
            assertEquals(listOf("\uD83C\uDDF3\uD83C\uDDF1 Amsterdam 001", "\uD83C\uDDF3\uD83C\uDDF1 Amsterdam 002"), names(list.id))
            val bean = ProfileDatabase.proxyDao.getByGroup(list.id)[0].requireBean() as SOCKSBean
            assertEquals("nl-ams-wg-socks5-001.relays.mullvad.net", bean.serverAddress)
            assertEquals(1080, bean.serverPort)
            assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
            assertFalse(list.id in GroupUpdater.updating)
        }
    }

    @Test
    fun anUpdate_addsNewServers_dropsRetiredOnes_andKeepsTheUsersProxiesAndProxiesInUse() = runTest {
        withContext(Dispatchers.IO) {
            val listId = MullvadRelays.sync(0L, relays(server("nl-ams-wg-001"), server("nl-ams-wg-002"), server("nl-ams-wg-003")), MullvadRelays.subscription(null)).list.id
            val first = ProfileDatabase.proxyDao.getByGroup(listId)
            // The user's own proxies: another provider's, and one of Mullvad's servers outside the server list.
            for ((name, address) in listOf("Mine" to "192.0.2.7", "By hand" to "nl-ams-wg-socks5-009.relays.mullvad.net")) {
                ProfileManager.createProfile(
                    listId,
                    SOCKSBean().applyDefaultValues().apply {
                        this.name = name
                        serverAddress = address
                        serverPort = 1080
                    },
                )
            }
            // Server 002 leaves the server list while it is selected.
            DataStore.selectedProxy = first[1].id

            val result = MullvadRelays.sync(listId, relays(server("nl-ams-wg-004"), server("nl-ams-wg-001")))

            assertFalse(result.created)
            assertEquals(1, result.added)
            assertEquals(1, result.removed)
            val flag = "\uD83C\uDDF3\uD83C\uDDF1"
            assertEquals(listOf("$flag Amsterdam 001", "$flag Amsterdam 004", "$flag Amsterdam 002", "Mine", "By hand"), names(listId))
            assertEquals(first[0].id, ProfileDatabase.proxyDao.getByGroup(listId)[0].id)
        }
    }

    @Test
    fun anUpdate_keepsAPickSavedWhileItFetched() = runTest {
        withContext(Dispatchers.IO) {
            val parsed = relays(server("nl-ams-wg-001"), server("se-sto-wg-001", "Stockholm"))
            val listId = MullvadRelays.sync(0L, parsed, MullvadRelays.subscription(setOf("nl"))).list.id
            MullvadRelays.sync(listId, parsed, MullvadRelays.subscription(setOf("se")))

            // What an update started before the new pick does once its server list arrives.
            MullvadRelays.sync(listId, parsed)

            assertEquals(listOf("\uD83C\uDDF8\uD83C\uDDEA Stockholm 001"), names(listId))
            val kept = ProfileDatabase.groupDao.getById(listId)!!.subscription
            assertEquals(setOf("se-sto"), parsed.filter(MullvadRelays.holds(kept)).mapTo(HashSet()) { it.place })
        }
    }

    @Test
    fun anotherPick_changesWhatTheListHolds_andKeepsItsFirstHop() = runTest {
        withContext(Dispatchers.IO) {
            val parsed = relays(server("nl-ams-wg-001"), server("se-sto-wg-001", "Stockholm"))
            val listId = MullvadRelays.sync(0L, parsed, MullvadRelays.subscription(setOf("nl"))).list.id
            ProfileDatabase.groupDao.getById(listId)!!.let { ProfileDatabase.groupDao.updateGroup(it.apply { frontProxy = 42L }) }
            val published = ArrayList<ProxyGroup>()
            val listener = object : GroupManager.Listener {
                override suspend fun groupAdd(group: ProxyGroup) = Unit

                override suspend fun groupUpdated(group: ProxyGroup) {
                    published += group
                }

                override suspend fun groupRemoved(groupId: Long) = Unit

                override suspend fun groupUpdated(groupId: Long) = Unit
            }
            GroupManager.addListener(listener)

            val result = try {
                MullvadRelays.sync(listId, parsed, MullvadRelays.subscription(setOf("se")))
            } finally {
                GroupManager.removeListener(listener)
            }

            assertEquals(1, result.added)
            assertEquals(1, result.removed)
            val list = ProfileDatabase.groupDao.getById(listId)!!
            assertEquals(42L, list.frontProxy)
            assertEquals(listOf("\uD83C\uDDF8\uD83C\uDDEA Stockholm 001"), names(listId))
            assertEquals(setOf("se-sto"), parsed.filter(MullvadRelays.holds(list.subscription)).mapTo(HashSet()) { it.place })
            // The tabs and the page get the stored list with its new pick.
            assertEquals(list.subscription?.filterRegex, published.single().subscription?.filterRegex)
            assertEquals(42L, published.single().frontProxy)
        }
    }

    @Test
    fun aServerListThatDidNotArrive_changesNothing() = runTest {
        withContext(Dispatchers.IO) {
            val listId = MullvadRelays.sync(0L, relays(server("nl-ams-wg-001")), MullvadRelays.subscription(null)).list.id

            val failure = runCatching { MullvadRelays.sync(listId, MullvadRelays.parse("<html>"), MullvadRelays.subscription(null)) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, ProfileDatabase.proxyDao.getByGroup(listId).size)
            assertEquals(1, ProfileDatabase.groupDao.allGroups().size)
        }
    }
}

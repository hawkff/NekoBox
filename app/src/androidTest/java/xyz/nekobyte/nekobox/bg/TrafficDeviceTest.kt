package xyz.nekobyte.nekobox.bg

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.aidl.TrafficData
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ui.MainActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The traffic totals of profiles while the proxy runs. The lists are sent the profile in use and not the rest of its
 * group, and totals cleared on a screen stay cleared: the service counts from zero again and stores that when it stops.
 */
@RunWith(AndroidJUnit4::class)
class TrafficDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val upstream = SocksUpstream("ok")
    private var group = 0L
    private var used = 0L
    private var idle = 0L
    private var saved: Saved? = null

    /** The settings the test changes, as they were. */
    private class Saved(val mode: String, val login: Boolean, val resolving: Boolean, val profile: Long, val asking: Boolean, val counting: Boolean, val interval: Int)

    // What the lists are told about traffic, each round as it comes.
    private val rounds = CopyOnWriteArrayList<List<TrafficData>>()
    private val listening = object : ProfileManager.Listener {
        override suspend fun onAdd(profile: ProxyEntity) = Unit
        override suspend fun onUpdated(data: TrafficData) = Unit
        override suspend fun onUpdated(data: List<TrafficData>) {
            rounds += data
        }
        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit
        override suspend fun onRemoved(groupId: Long, profileId: Long) = Unit
    }

    // The service's states, each change as it reports it: looking now and then could miss a restart.
    private val states = CopyOnWriteArrayList<BaseService.State>()
    private val watching = NekoBoxConnection(NekoBoxConnection.CONNECTION_ID_SHORTCUT)

    // The service reports to this connection: from then on no change of state is missed.
    @Volatile private var watched = false
    private val watcher = object : NekoBoxConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            states += state
        }

        override fun onServiceConnected(service: INekoBoxService) {
            watched = true
        }
    }

    private fun server(name: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = "127.0.0.1"
        serverPort = upstream.port
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        saved = Saved(DataStore.serviceMode, DataStore.proxyModeInboundAuth, DataStore.resolveDestination, DataStore.selectedProxy, DataStore.askForBackgroundUse, DataStore.profileTrafficStatistics, DataStore.speedInterval)
        runBlocking(Dispatchers.IO) {
            // A group that switches between its servers while it runs: the running config holds both of them.
            group = GroupManager.createGroup(ProxyGroup(name = "Traffic servers", isSelector = true)).id
            used = ProfileManager.createProfile(group, server("Traffic used")).id
            idle = ProfileManager.createProfile(group, server("Traffic idle")).id
            // Totals from earlier sessions.
            ProfileDatabase.proxyDao.updateTraffic(used, 1_000, 2_000)
            ProfileDatabase.proxyDao.updateTraffic(idle, 30, 40)
            DataStore.selectedProxy = used
            DataStore.serviceMode = Key.MODE_PROXY
            // The test's own request gives the local port no login.
            DataStore.proxyModeInboundAuth = false
            // The name reaches the proxy's server as it is: resolved on the device first, .invalid would end there.
            DataStore.resolveDestination = false
            // A connected app on screen asks for background use, in a dialog over the screen.
            DataStore.askForBackgroundUse = false
            DataStore.profileTrafficStatistics = true
            // The service counts and tells the screen once a second.
            DataStore.speedInterval = 1_000
            DataStore.configurationStore.awaitWrites()
        }
        ProfileManager.addListener(listening)
        watching.connect(context, watcher)
        await("the service to report its states") { watched }
    }

    @After
    fun tearDown() {
        ProfileManager.removeListener(listening)
        watching.disconnect(context)
        try {
            // A test that failed halfway left the proxy running: a screen binds, stops it and sees it stopped.
            ActivityScenario.launch(MainActivity::class.java).use {
                await("the screen to learn the service's state") { DataStore.serviceState != BaseService.State.Idle }
                NekoBox.stopService(byUser = false)
                await("the service to stop") { DataStore.serviceState == BaseService.State.Stopped }
            }
        } finally {
            upstream.close()
            runBlocking(Dispatchers.IO) {
                try {
                    if (group != 0L) GroupManager.deleteGroup(group)
                } finally {
                    saved?.let {
                        DataStore.serviceMode = it.mode
                        DataStore.proxyModeInboundAuth = it.login
                        DataStore.resolveDestination = it.resolving
                        DataStore.selectedProxy = it.profile
                        DataStore.askForBackgroundUse = it.asking
                        DataStore.profileTrafficStatistics = it.counting
                        DataStore.speedInterval = it.interval
                        DataStore.configurationStore.awaitWrites()
                    }
                }
            }
        }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $what" }
            SystemClock.sleep(300)
        }
    }

    // A request through the local port, so through the server in use.
    private fun request() {
        val client = Libcore.newHttpClient()
        try {
            client.trySocks5(DataStore.mixedPort, "", "")
            client.newRequest().apply { setURL("http://traffic.invalid/") }.execute()
        } finally {
            client.close()
        }
    }

    private fun stored(id: Long) = ProfileDatabase.proxyDao.getById(id)!!

    @Test
    fun clearedTotals_stayCleared_andTheListsAreSentOnlyTheProfileInUse() {
        var atSwitch = 0L to 0L
        ActivityScenario.launch(MainActivity::class.java).use {
            NekoBox.startService(byUser = false)
            await("the proxy to connect") { states.lastOrNull() == BaseService.State.Connected }
            request()
            assertEquals("the proxy's server had ${upstream.trouble}", listOf("traffic.invalid:80"), upstream.asked.toList())
            // The service tells the screen on show what it counted, on top of the totals it started from.
            await("the traffic to be counted") { rounds.any { round -> round.any { it.id == used && it.rx > 1_000 && it.tx > 2_000 } } }
            // The other server of the group carried nothing, so the lists keep what they read of it.
            assertFalse(rounds.flatten().any { it.id == idle })

            runBlocking(Dispatchers.IO) { ProfileManager.clearTraffic(group) }
            // The rounds that follow come from the service: it keeps running, and sends the profile in use from zero.
            val cleared = rounds.size
            await("the service's rounds after the clear") { rounds.size >= cleared + 2 }
            assertEquals(0L to 0L, rounds.last().first { it.id == used }.let { it.rx to it.tx })
            assertEquals(BaseService.State.Connected, DataStore.serviceState)
            // And counts on from there.
            request()
            await("the traffic after the clear") { rounds.last().any { it.id == used && it.rx in 1L..999L && it.tx in 1L..1_999L } }

            // A switch to the other server, which the service makes as it runs: it stores what the first one counted,
            // at once and in its own row.
            val reported = states.size
            NekoBox.reloadService(idle)
            await("the switch to store the totals") { stored(used).rx in 1L..999L && stored(used).tx in 1L..1_999L }
            atSwitch = stored(used).let { it.rx to it.tx }
            assertEquals(0L to 0L, stored(idle).rx to stored(idle).tx)
            // What goes through the proxy from here on counts for the other server.
            request()
            await("the other server to count") { rounds.last().any { it.id == idle && it.rx > 0 && it.tx > 0 } }
            assertEquals("the service changed state for the switch", emptyList<BaseService.State>(), states.drop(reported))

            NekoBox.stopService(byUser = false)
            await("the service to stop") { DataStore.serviceState == BaseService.State.Stopped }
        }
        // What the service stored when it stopped: for the first server what it counted between the clear and the
        // switch, for the other what came after. The lifetime totals have the whole session all the same.
        assertEquals(atSwitch, stored(used).let { it.rx to it.tx })
        assertTrue(stored(idle).rx > 0 && stored(idle).tx > 0)
        assertTrue(stored(used).lifetimeRx > stored(used).rx && stored(used).lifetimeTx > stored(used).tx)
    }

    // The core keeps the server a group was switched to while it ran. The next start is for the server selected
    // by then, which can be another one.
    @Test
    fun aStart_usesTheSelectedServer_notTheOneTheGroupWasLastSwitchedTo() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NekoBox.startService(byUser = false)
            await("the proxy to connect") { states.lastOrNull() == BaseService.State.Connected }
            NekoBox.reloadService(idle)
            await("the other server to count") {
                request()
                rounds.lastOrNull().orEmpty().any { it.id == idle && it.rx > 30 }
            }
            NekoBox.stopService(byUser = false)
            await("the service to stop") { states.lastOrNull() == BaseService.State.Stopped }

            // What the first start counted is in the rows now, and the lists were sent as much: from here on only
            // more than that is this start's.
            val usedBefore = stored(used).rx
            val idleBefore = stored(idle).let { it.rx to it.tx }
            // The screens followed the switch. The server is selected again as a tap on its row does.
            runBlocking(Dispatchers.IO) {
                DataStore.selectedProxy = used
                DataStore.configurationStore.awaitWrites()
            }
            NekoBox.startService(byUser = false)
            await("the proxy to connect again") { states.lastOrNull() == BaseService.State.Connected }
            // The service starts counting a moment after it reports itself connected.
            await("the selected server to count") {
                request()
                rounds.last().any { it.id == used && it.rx > usedBefore }
            }
            assertEquals("the screens were told of another server", used, DataStore.selectedProxy)

            NekoBox.stopService(byUser = false)
            await("the service to stop again") { states.lastOrNull() == BaseService.State.Stopped }
            assertEquals("the server switched to earlier counted for this start", idleBefore, stored(idle).let { it.rx to it.tx })
        }
    }
}

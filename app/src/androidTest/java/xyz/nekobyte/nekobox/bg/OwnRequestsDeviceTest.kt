package xyz.nekobyte.nekobox.bg

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.HTTPClient
import libcore.Libcore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.aidl.LocalProxy
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.group.GroupUpdater
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ui.MainActivity
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What the app does for itself while its proxy runs, in Proxy mode with a login on the local port. Its own requests
 * go in through that port and on through the proxy, with no screen bound to the service, as the subscription job
 * runs. Whoever else listens on that port while the service is down gets no request and no login from a client set
 * up then. A client set up before gives it the login of the session that ended, which opens nothing.
 */
@RunWith(AndroidJUnit4::class)
class OwnRequestsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val upstream = SocksUpstream("ss://YWVzLTI1Ni1nY206cGFzcw@192.0.2.20:8388#Berlin\n")

    // 127.0.0.1 by its bytes: Android's loopback address is the IPv6 one, and the local port is on the IPv4 one.
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private var servers = 0L
    private var subscription = 0L
    private var saved: Saved? = null

    /** The settings the tests change, as they were. */
    private class Saved(val mode: String, val login: Boolean, val resolving: Boolean, val profile: Long, val asking: Boolean, val custom: String)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // The proxy has to send the tests' requests on to its server, and a routing rule of the app's could send them
        // elsewhere. Nothing is changed, and nothing stopped afterwards, in an app that is not as the tests need it.
        check(runBlocking(Dispatchers.IO) { ProfileDatabase.rulesDao.enabledRules() }.isEmpty()) { "These tests need an app without routing rules" }
        val services = setOf(ProxyService::class.java.name, VpnService::class.java.name)

        @Suppress("DEPRECATION") // It still tells an app of its own services.
        val started = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .filter { it.started && it.service.packageName == context.packageName && it.service.className in services }
        check(started.isEmpty()) { "These tests need the service stopped" }
        saved = Saved(DataStore.serviceMode, DataStore.proxyModeInboundAuth, DataStore.resolveDestination, DataStore.selectedProxy, DataStore.askForBackgroundUse, DataStore.globalCustomConfig)
        runBlocking(Dispatchers.IO) {
            servers = GroupManager.createGroup(ProxyGroup(name = "Own requests servers")).id
            DataStore.selectedProxy = ProfileManager.createProfile(
                servers,
                SOCKSBean().apply {
                    name = "Own requests upstream"
                    serverAddress = "127.0.0.1"
                    serverPort = upstream.port
                },
            ).id
            // Straight into the database: through the group manager a new subscription would start updating.
            subscription = ProfileDatabase.groupDao.createGroup(
                ProxyGroup(name = "Own requests subscription", type = GroupType.SUBSCRIPTION, userOrder = 1_000_000L).apply {
                    subscription = SubscriptionBean().applyDefaultValues().apply { link = "http://own-requests.invalid/list" }
                },
            )
            DataStore.serviceMode = Key.MODE_PROXY
            DataStore.proxyModeInboundAuth = true
            // The name reaches the proxy's server as it is: resolved on the device first, .invalid would end there.
            DataStore.resolveDestination = false
            // A connected app on screen asks for background use, in a dialog over the screen.
            DataStore.askForBackgroundUse = false
            // Custom JSON for all profiles could move the local port or take it out.
            DataStore.globalCustomConfig = ""
            DataStore.configurationStore.awaitWrites()
        }
    }

    @After
    fun tearDown() {
        // A set-up that refused the app as it found it changed nothing and started nothing.
        val saved = saved ?: return upstream.close()
        try {
            // A screen binds, stops what a test left running and sees it stopped.
            ActivityScenario.launch(MainActivity::class.java).use {
                await("the screen to learn the service's state") { DataStore.serviceState != BaseService.State.Idle }
                NekoBox.stopService(byUser = false)
                await("the service to stop") { DataStore.serviceState == BaseService.State.Stopped }
            }
        } finally {
            upstream.close()
            runBlocking(Dispatchers.IO) {
                try {
                    if (servers != 0L) GroupManager.deleteGroup(servers)
                    if (subscription != 0L) GroupManager.deleteGroup(subscription)
                } finally {
                    DataStore.serviceMode = saved.mode
                    DataStore.proxyModeInboundAuth = saved.login
                    DataStore.resolveDestination = saved.resolving
                    DataStore.selectedProxy = saved.profile
                    DataStore.askForBackgroundUse = saved.asking
                    DataStore.globalCustomConfig = saved.custom
                    DataStore.configurationStore.awaitWrites()
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

    // What a started service says it does, asked with nothing of this process bound to it, as the subscription job
    // asks whether one is connected.
    private fun state() = runBlocking { NekoBoxConnection.ask(context) { BaseService.State.values()[it.state] } }

    // Starts the proxy with the app on screen, as a tap on Connect does but without marking the start as the user's,
    // and leaves the screen again: nothing of this process stays bound to the service.
    private fun connect() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NekoBox.startService(byUser = false)
            await("the proxy to connect") { DataStore.serviceState == BaseService.State.Connected }
        }
    }

    private fun stop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NekoBox.stopService(byUser = false)
            await("the service to stop") { DataStore.serviceState == BaseService.State.Stopped }
        }
    }

    // What the local port answers a client that offers no login: 0 takes it, 255 refuses it.
    private fun answerToNoLogin(port: Int) = Socket(loopback, port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
        val input = DataInputStream(socket.getInputStream())
        check(input.readUnsignedByte() == 5)
        input.readUnsignedByte()
    }

    // What the local port answers a login: 0 takes it.
    private fun answerToLogin(port: Int, user: String, password: String) = Socket(loopback, port).use { socket ->
        socket.soTimeout = 10_000
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        // The greeting offers a login and nothing else.
        output.write(byteArrayOf(5, 1, 2))
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
        output.write(byteArrayOf(1, user.length.toByte()) + user.toByteArray() + byteArrayOf(password.length.toByte()) + password.toByteArray())
        check(input.readUnsignedByte() == 1)
        input.readUnsignedByte()
    }

    private fun updateTheSubscription(watching: GroupManager.Interface? = null, byUser: Boolean = false) = runBlocking(Dispatchers.IO) {
        GroupUpdater.executeUpdate(ProfileDatabase.groupDao.getById(subscription)!!, byUser = byUser, userInterface = watching)
    }

    @Test
    fun aSubscriptionUpdate_goesThroughTheProtectedLocalPort_withNoScreenBound() {
        connect()
        assertEquals(BaseService.State.Connected, state())
        val proxy = localProxy()!!
        assertEquals(DataStore.mixedPort to Key.MIXED_USERNAME, proxy.port to proxy.user)
        assertEquals("the local port asks for a login", 255, answerToNoLogin(proxy.port))
        assertEquals("the local port takes the login the service gives the app", 0, answerToLogin(proxy.port, proxy.user, proxy.password))

        var failure: String? = null
        val watching = object : GroupManager.Interface {
            override suspend fun confirm(message: String) = true
            override suspend fun alert(message: String) = Unit
            override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) = Unit
            override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
                failure = message
            }
        }
        assertTrue("the update failed with $failure; the proxy's server was asked for ${upstream.asked} and had ${upstream.trouble}", updateTheSubscription(watching))
        // The proxy's server was asked for the subscription's address: the request went in through the local port.
        assertTrue("asked for ${upstream.asked}", "own-requests.invalid:80" in upstream.asked)
        assertEquals(listOf("Berlin"), ProfileDatabase.proxyDao.getByGroup(subscription).map { it.displayName() })
    }

    // An update by hand over plain HTTP asks first when no proxy is connected. Whether one is, is asked of the
    // service: this process remembers what its last screen saw, and the local API starts updates long after that.
    @Test
    fun anUpdateByHand_asksBeforeItGoesOutWithNoProxy_whateverTheLastScreenSaw() {
        // A screen saw the proxy connected and left; the proxy has stopped since.
        DataStore.serviceState = BaseService.State.Connected
        var asked = 0
        val watching = object : GroupManager.Interface {
            override suspend fun confirm(message: String) = false.also { asked++ }
            override suspend fun alert(message: String) = Unit
            override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) = Unit
            override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = Unit
        }
        assertTrue("the update went out and failed", updateTheSubscription(watching, byUser = true))
        assertEquals("the update did not ask", 1, asked)
        assertTrue(upstream.asked.isEmpty())
    }

    @Test
    fun anotherAppOnThePort_getsNeitherTheRequestsNorTheLogin_whileTheServiceIsDown() {
        // Nothing runs: no proxy to go through, and no wait for a service.
        assertNull(state())
        assertNull(localProxy())
        // Something else listens on the port the settings name.
        val received = whileSomethingElseListensOn(DataStore.mixedPort) {
            assertNull(localProxy())
            // A real request of the app's own meanwhile: it has nowhere to go and fails, but not into that port.
            updateTheSubscription()
        }
        assertEquals("the app's request connected to it too", listOf(listOf<Byte>(127)), received)
        assertTrue(upstream.asked.isEmpty())
    }

    // Something else listens on the port while the block runs. What each of its clients sent it, one after the
    // other; the test's own client comes last with one byte, 127: once the listener has seen that one, it has seen
    // all that came before.
    private fun whileSomethingElseListensOn(port: Int, block: () -> Unit): List<List<Byte>> {
        val received = CopyOnWriteArrayList<List<Byte>>()
        ServerSocket(port, 50, loopback).use { other ->
            thread(isDaemon = true) {
                while (true) {
                    val client = runCatching { other.accept() }.getOrNull() ?: break
                    client.use {
                        it.soTimeout = 2_000
                        val sent = ArrayList<Byte>()
                        runCatching {
                            while (true) {
                                val next = it.getInputStream().read()
                                if (next < 0) break
                                sent += next.toByte()
                            }
                        }
                        received += sent
                    }
                }
            }
            block()
            Socket(loopback, other.localPort).use { it.getOutputStream().write(127) }
            await("the listener to see the test's own client") { received.lastOrNull() == listOf<Byte>(127) }
        }
        return received.toList()
    }

    // How a request of the client's ends: null when it was answered.
    private fun failureOf(client: HTTPClient) = runCatching { client.newRequest().apply { setURL("http://own-requests.invalid/list") }.execute() }.exceptionOrNull()?.message

    // A client set up while the service runs keeps the port and the login for its later requests, and asks for each
    // of them whether that start still runs. After a stop its requests fail: whatever took the port is sent nothing,
    // and the next start, which is another one with another login, is not asked to serve them.
    @Test
    fun aClientSetUpInOneStart_sendsNothingToWhatTakesThePortAfterIt_norToTheNextStart() {
        connect()
        val first = localProxy()!!
        val client = Libcore.newHttpClient().apply { throughRunningProxy() }
        try {
            assertNull("the proxy's server had ${upstream.trouble}", failureOf(client))
            assertEquals(listOf("own-requests.invalid:80"), upstream.asked.toList())

            stop()
            assertNull(localProxy())
            var failure: String? = null
            val received = whileSomethingElseListensOn(first.port) { failure = failureOf(client) }
            assertTrue("the request ended with $failure", failure.orEmpty().contains("no longer runs"))
            // The client connected, asked the service, and left without a byte.
            assertEquals(listOf(emptyList(), listOf<Byte>(127)), received)

            connect()
            val second = localProxy()!!
            assertEquals(first.port, second.port)
            assertNotEquals(first.start, second.start)
            assertNotEquals(first.password, second.password)
            assertTrue(failureOf(client).orEmpty().contains("no longer runs"))
            assertEquals("the next start served the client", 1, upstream.asked.size)
            assertEquals("the local port takes the login of this start", 0, answerToLogin(second.port, second.user, second.password))
            assertNotEquals("the local port still takes the login of the start before", 0, answerToLogin(second.port, first.user, first.password))
        } finally {
            client.close()
        }
    }

    // A local port with no login has nothing secret to tell one start from the next by. A start is told all the same.
    @Test
    fun withNoLoginOnThePort_aClientSetUpInOneStart_isNotServedByTheNext() {
        runBlocking(Dispatchers.IO) {
            DataStore.proxyModeInboundAuth = false
            DataStore.configurationStore.awaitWrites()
        }
        connect()
        val first = localProxy()!!
        assertEquals("", first.password)
        val client = Libcore.newHttpClient().apply { throughRunningProxy() }
        try {
            assertNull("the proxy's server had ${upstream.trouble}", failureOf(client))
            stop()
            connect()
            val second = localProxy()!!
            assertEquals(first.port to "", second.port to second.password)
            assertNotEquals(first.start, second.start)
            assertTrue(failureOf(client).orEmpty().contains("no longer runs"))
            assertEquals("the next start served the client", 1, upstream.asked.size)
            // A client set up in this start is served.
            val fresh = Libcore.newHttpClient().apply { throughRunningProxy() }
            try {
                assertNull("the proxy's server had ${upstream.trouble}", failureOf(fresh))
            } finally {
                fresh.close()
            }
        } finally {
            client.close()
        }
    }

    // A request through the client, which ends at the proxy's server: that stands in for the site and speaks no TLS.
    // How often the local port has let one through to it by now.
    private fun reached(client: OkHttpClient): Int {
        runCatching { client.newCall(Request.Builder().url("https://own-requests.invalid/").build()).execute().close() }
        return upstream.asked.count { it == "own-requests.invalid:443" }
    }

    // The connection check sets a client up from what the service reports, for a check and again for a lookup asked
    // for later.
    @Test
    fun aClientOfTheConnectionCheck_isGivenItsLoginOnlyInTheStartItWasSetUpIn() {
        connect()
        val first = localProxy()!!
        val kept = OkHttpClient.Builder().through(first).callTimeout(5, TimeUnit.SECONDS).build()
        val before = reached(kept)
        assertTrue("the local port refused the client; the proxy's server had ${upstream.trouble}", before >= 1)

        stop()
        connect()
        val second = localProxy()!!
        assertNotEquals(first.password, second.password)
        assertEquals("the local port let the client of the start before through", before, reached(kept))
        val fresh = OkHttpClient.Builder().through(second).callTimeout(5, TimeUnit.SECONDS).build()
        assertTrue("the local port refused a client of this start; the proxy's server had ${upstream.trouble}", reached(fresh) > before)
    }

    // A client of the connection check kept from a start that ended connects to whatever took the port, asks the
    // service, and leaves without a byte: not the address it was asked for, and not the login. The next start is
    // not asked to serve it either.
    @Test
    fun aKeptClientOfTheConnectionCheck_sendsNothingToWhatTakesThePortAfterItsStart() {
        connect()
        val first = localProxy()!!
        val kept = OkHttpClient.Builder().through(first).callTimeout(20, TimeUnit.SECONDS).build()
        val before = reached(kept)
        assertTrue("the local port refused the client; the proxy's server had ${upstream.trouble}", before >= 1)

        stop()
        var failure: Throwable? = null
        val received = whileSomethingElseListensOn(first.port) {
            failure = runCatching { kept.newCall(Request.Builder().url("https://own-requests.invalid/").build()).execute().close() }.exceptionOrNull()
        }
        assertTrue("the request ended with $failure", generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("no longer runs") })
        // The test's own client came last; before it, the kept client's connections, one or more.
        assertTrue("the port's new holder was sent $received", received.size >= 2 && received.last() == listOf<Byte>(127) && received.dropLast(1).all { it.isEmpty() })

        connect()
        assertEquals("the next start served the client", before, reached(kept))
    }
}

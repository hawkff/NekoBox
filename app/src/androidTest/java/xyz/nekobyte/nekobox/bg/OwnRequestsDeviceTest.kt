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
import libcore.Libcore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    private fun updateTheSubscription(watching: GroupManager.Interface? = null) = runBlocking(Dispatchers.IO) {
        GroupUpdater.executeUpdate(ProfileDatabase.groupDao.getById(subscription)!!, byUser = false, userInterface = watching)
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

    @Test
    fun anotherAppOnThePort_getsNeitherTheRequestsNorTheLogin_whileTheServiceIsDown() {
        // Nothing runs: no proxy to go through, and no wait for a service.
        assertNull(state())
        assertNull(localProxy())
        // Something else listens on the port the settings name. What each of its clients sent it, one after the other.
        val received = CopyOnWriteArrayList<List<Byte>>()
        ServerSocket(DataStore.mixedPort, 50, loopback).use { other ->
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
            assertNull(localProxy())
            // A real request of the app's own meanwhile: it has nowhere to go and fails, but not into that port.
            updateTheSubscription()
            // The test's own client comes last: once the listener has seen it, it has seen all that came before.
            Socket(loopback, other.localPort).use { it.getOutputStream().write(127) }
            await("the listener to see the test's own client") { received.lastOrNull() == listOf<Byte>(127) }
        }
        assertEquals("the app's request connected to it too", 1, received.size)
        assertTrue(upstream.asked.isEmpty())
    }

    @Test
    fun aLoginGivenToWhateverTookThePortAfterAStop_opensNothingAtTheNextStart() {
        connect()
        val first = localProxy()!!
        // A client set up while the service runs keeps the port and the login for its later requests.
        val client = Libcore.newHttpClient().apply { trySocks5(first.port, first.user, first.password) }
        val taken = CopyOnWriteArrayList<Pair<String, String>>()
        try {
            stop()
            assertNull(localProxy())
            // Something else takes the port and asks whoever connects for a login.
            ServerSocket(first.port, 50, loopback).use { other ->
                thread(isDaemon = true) {
                    runCatching {
                        other.accept().use {
                            it.soTimeout = 10_000
                            val input = DataInputStream(it.getInputStream())
                            check(input.readUnsignedByte() == 5)
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            it.getOutputStream().write(byteArrayOf(5, 2))
                            check(input.readUnsignedByte() == 1)
                            val user = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                            taken += user to String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                        }
                    }
                }
                runCatching { client.newRequest().apply { setURL("http://own-requests.invalid/list") }.execute() }
                await("the client's login to reach the other listener") { taken.isNotEmpty() }
            }
        } finally {
            client.close()
        }
        assertEquals(first.user to first.password, taken.single())

        connect()
        val second = localProxy()!!
        assertEquals(first.port, second.port)
        assertNotEquals(first.password, second.password)
        assertEquals("the local port takes the login of this start", 0, answerToLogin(second.port, second.user, second.password))
        assertNotEquals("the local port still takes the login of the start before", 0, answerToLogin(second.port, taken.single().first, taken.single().second))
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

    // A client kept from the start before can have reached something else that took the port in between, on a
    // connection that is still open when the service runs again. Asked for a login there, it gives none.
    @Test
    fun whatHeldAConnectionOfAKeptClientAcrossARestart_isGivenNoLogin() {
        connect()
        // It waits for its answer for as long as the service takes to start again.
        val kept = OkHttpClient.Builder().through(localProxy()!!).readTimeout(60, TimeUnit.SECONDS).build()
        stop()
        // Something else takes the port and the client's next connection, then stops listening and keeps that one.
        val failure = AtomicReference<Throwable>()
        val held = ServerSocket(DataStore.mixedPort, 50, loopback).use { other ->
            thread(isDaemon = true) {
                failure.set(runCatching { kept.newCall(Request.Builder().url("https://own-requests.invalid/").build()).execute().close() }.exceptionOrNull())
            }
            other.soTimeout = 20_000
            try {
                other.accept()
            } catch (e: SocketTimeoutException) {
                throw AssertionError("the client did not connect; its request ended with ${failure.get()}", e)
            }
        }
        held.use {
            it.soTimeout = 5_000
            val asked = StringBuilder()
            while (!asked.endsWith("\r\n\r\n")) asked.append(it.getInputStream().read().also { next -> check(next >= 0) }.toChar())
            assertFalse("with no service running the client sent $asked", asked.contains("Proxy-Authorization", ignoreCase = true))

            connect()
            assertTrue(localProxy()!!.password.isNotEmpty())
            it.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            // Whatever the client sends until it gives the connection up.
            val answered = StringBuilder()
            runCatching {
                while (true) {
                    val next = it.getInputStream().read()
                    if (next < 0) break
                    answered.append(next.toChar())
                }
            }
            assertFalse("asked for a login, the client sent $answered", answered.contains("Proxy-Authorization", ignoreCase = true))
        }
        // The client was asked and declined, rather than having given up before.
        await("the client's request to end") { failure.get() != null }
        assertEquals("Failed to authenticate with proxy", failure.get().message)
    }
}

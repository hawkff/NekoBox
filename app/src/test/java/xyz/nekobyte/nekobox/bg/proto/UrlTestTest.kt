package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardIdentities
import xyz.nekobyte.nekobox.plugin.PluginManager
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UrlTestTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val profile = ProxyEntity(id = 7, groupId = 1)
    private val wireGuard = prepared(WireGuardIdentities(setOf("aa-bb"), false, mapOf("wg-7" to setOf("aa-bb"))))
    private val unreadable = prepared(WireGuardIdentities(emptySet(), true, emptyMap()))
    private val calls = mutableListOf<Long>()
    private val fingerprints = mutableListOf<String>()
    private val answerUrls = mutableListOf<String>()
    private val originalDirectory = WireGuardLease.directory

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        val directory = temporary.newFolder()
        WireGuardLease.directory = { directory }
    }

    @After
    fun tearDown() {
        WireGuardLease.directory = originalDirectory
    }

    // The VPN's core holds the profile's session, as it does from before it starts until after it closed.
    private suspend fun <T> whileTheVpnHoldsIt(block: suspend () -> T): T = WireGuardLease.tryAcquire(setOf("aa-bb"))!!.use { block() }

    @Test
    fun aProfileWhoseSessionTheVpnHoldsIsTestedThroughItsCore() = runTest {
        val service = binder({ BaseService.State.Connected }) { tested(42) }
        whileTheVpnHoldsIt {
            assertEquals(42, UrlTest { service }.doTest(profile, wireGuard))
            // The Proxies check reads its answer through the same outbound.
            assertEquals(42 to "ip=203.0.113.7", UrlTest { service }.check(profile, "https://trace.example/", wireGuard))
        }
        assertEquals(listOf(7L, 7L), calls)
        // The service compares the path prepared for this test, not one read again.
        assertEquals(listOf("path-7", "path-7"), fingerprints)
        assertEquals(listOf("", "https://trace.example/"), answerUrls)
    }

    @Test
    fun aTestHoldsNoWireGuardSessionWhileItWaitsForTheService() = runTest {
        // A path with a Tailscale hop may wait for the VPN's start: the VPN takes the sessions after the Tailscale
        // state, as a test's core does, so the test must not hold them meanwhile.
        val mixed = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, tailscaleEndpoints = mapOf(9L to ConfigBuildResult.TailscaleEndpoint("ts-9", false)), wireGuardIdentities = wireGuard.wireGuardIdentities, paths = wireGuard.paths)
        val sessionsFree = mutableListOf<Boolean>()
        val service = binder({ BaseService.State.Connected }, onCall = { sessionsFree += WireGuardLease.tryAcquire(setOf("aa-bb"))?.also { it.close() } != null }) { tested(42) }
        assertEquals(42, UrlTest { service }.doTest(profile, mixed))
        // And through the same path check as any WireGuard profile, not by its id alone.
        assertEquals(listOf(true), sessionsFree)
        assertEquals(listOf("path-7"), fingerprints)
    }

    @Test
    fun anotherKeyOfTheConnectedProfileIsReconnectedForNotTestedOnItsOwn() = runTest {
        // The edited profile's sessions are free, but the VPN runs it with the old key.
        val service = binder({ BaseService.State.Connected }) { if (it == "path-7") reply("other_settings") else tested(42) }
        assertEquals("reconnect_required", failure { UrlTest { service }.doTest(profile, wireGuard) }.code)
        assertEquals("reconnect_required", failure { UrlTest { service }.check(profile, "https://trace.example/", wireGuard) }.code)
    }

    @Test
    fun aKeyThatCannotBeReadFailsTheTestWithoutAskingTheConnectedVpn() = runTest {
        // A reconnect would not make the key readable: the VPN would refuse to start with it.
        val service = binder({ BaseService.State.Connected }) { reply("other_settings") }
        assertEquals("wireguard_unknown_key", failure { UrlTest { service }.doTest(profile, unreadable) }.code)
        assertEquals("wireguard_unknown_key", failure { UrlTest { service }.check(profile, "https://trace.example/", unreadable) }.code)
        // A config whose outbounds are no list of objects is a configuration error, not a key's.
        val malformed = prepared(WireGuardIdentities(emptySet(), true, emptyMap(), malformed = true))
        assertEquals("invalid_config", failure { UrlTest { service }.doTest(profile, malformed) }.code)
        assertEquals(emptyList<Long>(), calls)
    }

    @Test
    fun onlyAStoppedServiceOrOneThatDoesNotRunTheProfileLeavesTheTestItsOwnCore() = runTest {
        assertNull(UrlTest { binder({ BaseService.State.Connected }) { reply("not_running") } }.viaRunningCore(profile, wireGuard))
        assertNull(UrlTest { binder({ BaseService.State.Stopped }) { tested(42) } }.viaRunningCore(profile, wireGuard))
        // That core still finds the sessions held (another test, a VPN with another profile of the key pair) and does
        // not start.
        val busy = whileTheVpnHoldsIt { failure { UrlTest { binder({ BaseService.State.Connected }) { reply("not_running") } }.doTest(profile, wireGuard) } }
        assertEquals("wireguard_in_use", busy.code)
        // Keys that cannot be read tell no session.
        assertEquals("wireguard_unknown_key", failure { UrlTest { binder({ BaseService.State.Stopped }) { tested(42) } }.doTest(profile, unreadable) }.code)
        assertEquals(listOf(7L, 7L), calls)
    }

    @Test
    fun aServiceThatCannotTestTheSavedSettingsFailsTheTest() = runTest {
        // No binder yet, starting or stopping: its core may run the profile.
        for (service in listOf<INekoBoxService?>(null, binder({ BaseService.State.Connecting }) { tested(42) }, binder({ BaseService.State.Stopping }) { tested(42) })) {
            assertEquals("vpn_changing", failure { UrlTest { service }.viaRunningCore(profile, wireGuard) }.code)
        }
        // The core runs another path for the profile.
        assertEquals("reconnect_required", failure { UrlTest { binder({ BaseService.State.Connected }) { reply("other_settings") } }.viaRunningCore(profile, wireGuard) }.code)
        // A path that cannot be compared, which a reconnect would not change: the advice is to test it apart.
        assertEquals("disconnect_required", failure { UrlTest { binder({ BaseService.State.Connected }) { if (it.isEmpty()) reply("unverifiable") else tested(1) } }.viaRunningCore(profile, unfingerprinted) }.code)
        // The core's server of the profile completed no handshake before the test timed out.
        assertEquals("no_handshake", failure { UrlTest { binder({ BaseService.State.Connected }) { reply("no_handshake") } }.viaRunningCore(profile, wireGuard) }.code)
        // A service that stops under the test.
        assertEquals("vpn_changing", failure { UrlTest { binder({ BaseService.State.Connected }) { reply("stopping") } }.viaRunningCore(profile, wireGuard) }.code)
        assertEquals(listOf(7L, 7L, 7L, 7L), calls)
    }

    @Test
    fun aTestsOwnCoreThatFailsWhileTheVpnStartsOrStopsSaysSo() = runTest {
        // A request that timed out while the VPN was starting or stopping as it was sent; the state now does not count.
        val failed = ProbeFailure("Get \"http://10.66.0.1:8080/\": context deadline exceeded", timedOut = true, duringVpnChange = true)
        val now = binder({ BaseService.State.Stopped }) { tested(1) }
        for (thrown in listOf(failed, NoHandshakeException(5000, failed))) {
            val reason = failure { UrlTest { now }.ownCore(true) { throw thrown } }
            assertEquals("vpn_changing", reason.code)
            assertEquals(thrown, reason.cause)
        }
        // A VPN that started or stopped only after the request was sent proves nothing: the timeout stays. So does a
        // test without WireGuard hops.
        val earlier = ProbeFailure("Get \"http://10.66.0.1:8080/\": context deadline exceeded", timedOut = true, duringVpnChange = false)
        for (state in listOf(BaseService.State.Connecting, BaseService.State.Stopping)) {
            assertEquals(earlier, runCatching { UrlTest { binder({ state }) { tested(1) } }.ownCore(true) { throw earlier } }.exceptionOrNull())
        }
        assertEquals(failed, runCatching { UrlTest { now }.ownCore(false) { throw failed } }.exceptionOrNull())
        val held = WireGuardSessionsUnavailable("wireguard_in_use", "held")
        assertEquals(held, runCatching { UrlTest { now }.ownCore(true) { throw held } }.exceptionOrNull())
        // A request that failed with a reason of its own, as one outside the profile's allowed IPs.
        val outside = ProbeFailure("dial tcp 10.99.0.1:8090: destination 10.99.0.1 is outside the allowed IPs", timedOut = false, duringVpnChange = true)
        assertEquals(outside, runCatching { UrlTest { now }.ownCore(true) { throw outside } }.exceptionOrNull())
        // A core that could not be set up: a plugin missing, a config the core refuses. No retry mends that.
        for (setup in listOf(PluginManager.PluginNotFoundException("naive-plugin"), IOException("decode config: unknown field"), IllegalStateException("Missing Mieru SOCKS credentials"))) {
            assertEquals(setup, runCatching { UrlTest { now }.ownCore(true) { throw setup } }.exceptionOrNull())
        }
        // A cancellation is no failure of the test.
        val cancelled = CancellationException("dialog closed")
        assertEquals(cancelled, runCatching { UrlTest { now }.ownCore(true) { throw cancelled } }.exceptionOrNull())
    }

    @Test
    fun theVpnChangesWhileTheServiceStartsOrStops() {
        for (state in BaseService.State.values()) {
            val changing = state == BaseService.State.Connecting || state == BaseService.State.Stopping
            assertEquals(state.name, changing, UrlTest { binder({ state }) { tested(1) } }.serviceChanging())
        }
        assertEquals(false, UrlTest { null }.serviceChanging())
    }

    @Test
    fun aServiceThatSettlesInAMomentIsWaitedFor() = runTest {
        var states = listOf(BaseService.State.Connecting, BaseService.State.Connecting, BaseService.State.Connected)
        val service = binder({ states.first().also { if (states.size > 1) states = states.drop(1) } }) { tested(42) }
        assertEquals(42 to null, UrlTest { service }.viaRunningCore(profile, wireGuard))
        // One that connects during the last wait is still read: 50 reads 100 ms apart, then once more; still
        // starting then, the VPN is changing.
        var rounds = 0
        val late = binder({ if (rounds++ < 50) BaseService.State.Connecting else BaseService.State.Connected }) { tested(7) }
        assertEquals(7 to null, UrlTest { late }.viaRunningCore(profile, wireGuard))
        rounds = -1
        assertEquals("vpn_changing", failure { UrlTest { late }.viaRunningCore(profile, wireGuard) }.code)
    }

    @Test
    fun aFailureOfTheRunningCoreIsTheTestsResult() = runTest {
        val service = binder({ BaseService.State.Connected }) { """{"result":"failed","error":"Timeout"}""" }
        val failure = runCatching { UrlTest { service }.viaRunningCore(profile, wireGuard) }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals("Timeout", failure?.message)
    }

    private suspend fun failure(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() as ConnectionTestFailure

    private val unfingerprinted = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, wireGuardIdentities = wireGuard.wireGuardIdentities)

    private fun prepared(identities: WireGuardIdentities) = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, wireGuardIdentities = identities, paths = mapOf(7L to ConfigBuildResult.PathSnapshot("path-7", listOf("wg-7"))))

    private fun reply(result: String) = """{"result":"$result"}"""

    private fun tested(latency: Int) = """{"result":"tested","latencyMs":$latency}"""

    private fun binder(state: () -> BaseService.State, onCall: () -> Unit = {}, testWireGuardProfile: (String) -> String) = Proxy.newProxyInstance(
        INekoBoxService::class.java.classLoader,
        arrayOf(INekoBoxService::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getState" -> state().ordinal

            "testWireGuardProfile" -> {
                onCall()
                calls += arguments[0] as Long
                fingerprints += arguments[1] as String
                val answerUrl = arguments[2] as String
                answerUrls += answerUrl
                val reply = JSONObject(testWireGuardProfile(arguments[1] as String))
                if (answerUrl.isNotEmpty() && reply.optString("result") == "tested") reply.put("answer", "ip=203.0.113.7")
                reply.toString()
            }

            else -> error("Unexpected service call: ${method.name}")
        }
    } as INekoBoxService
}

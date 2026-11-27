package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.CORE_NOT_STARTED
import xyz.nekobyte.nekobox.bg.NO_COMPLETED_HANDSHAKE
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
import xyz.nekobyte.nekobox.bg.PROFILE_RUNS_OTHER_SETTINGS
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardIdentities
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UrlTestTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val profile = ProxyEntity(id = 7, groupId = 1)
    private val wireGuard = prepared(WireGuardIdentities(mapOf("wg-7" to setOf("aa-bb"))))
    private val unreadable = prepared(WireGuardIdentities(mapOf("wg-7" to null)))
    private val calls = mutableListOf<Long>()
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
        val service = binder({ BaseService.State.Connected }) { 42 }
        whileTheVpnHoldsIt {
            assertEquals(42, UrlTest { service }.doTest(profile, wireGuard))
            // The Proxies check reads its answer through the same outbound.
            assertEquals(42 to "ip=203.0.113.7", UrlTest { service }.check(profile, "https://trace.example/", wireGuard))
        }
        assertEquals(listOf(7L, 7L), calls)
        assertEquals(listOf("", "https://trace.example/"), answerUrls)
    }

    @Test
    fun aHeldSessionTheServiceDoesNotRunIsNoCoreOfTheTestsOwn() = runTest {
        // Another test, or a VPN with another profile of the key pair.
        assertEquals("wireguard_in_use", failure { UrlTest { binder({ BaseService.State.Connected }) { error(PROFILE_NOT_RUNNING) } }.viaRunningCore(profile, wireGuard) }.code)
        assertEquals("wireguard_in_use", failure { UrlTest { binder({ BaseService.State.Stopped }) { 42 } }.viaRunningCore(profile, wireGuard) }.code)
        // Keys that cannot be read tell no session.
        assertEquals("wireguard_unknown_key", failure { UrlTest { binder({ BaseService.State.Stopped }) { 42 } }.doTest(profile, unreadable) }.code)
        assertEquals(listOf(7L), calls)
    }

    @Test
    fun aServiceThatCannotTestTheSavedSettingsFailsTheTest() = runTest {
        // No binder yet, starting or stopping: its core may run the profile.
        for (service in listOf<INekoBoxService?>(null, binder({ BaseService.State.Connecting }) { 42 }, binder({ BaseService.State.Stopping }) { 42 })) {
            assertEquals("vpn_changing", failure { UrlTest { service }.viaRunningCore(profile, wireGuard) }.code)
        }
        // The core runs other settings than the saved ones.
        assertEquals("reconnect_required", failure { UrlTest { binder({ BaseService.State.Connected }) { error(PROFILE_RUNS_OTHER_SETTINGS) } }.viaRunningCore(profile, wireGuard) }.code)
        // The core's server of the profile completed no handshake before the test timed out.
        assertEquals("no_handshake", failure { UrlTest { binder({ BaseService.State.Connected }) { error(NO_COMPLETED_HANDSHAKE) } }.viaRunningCore(profile, wireGuard) }.code)
        // A service that stops under the test.
        assertEquals("vpn_changing", failure { UrlTest { binder({ BaseService.State.Connected }) { error(CORE_NOT_STARTED) } }.viaRunningCore(profile, wireGuard) }.code)
        assertEquals(listOf(7L, 7L, 7L), calls)
    }

    @Test
    fun aServiceThatSettlesInAMomentIsWaitedFor() = runTest {
        var states = listOf(BaseService.State.Connecting, BaseService.State.Connecting, BaseService.State.Connected)
        val service = binder({ states.first().also { if (states.size > 1) states = states.drop(1) } }) { 42 }
        assertEquals(42 to null, UrlTest { service }.viaRunningCore(profile, wireGuard))
    }

    @Test
    fun aFailureOfTheRunningCoreIsTheTestsResult() = runTest {
        val service = binder({ BaseService.State.Connected }) { error("Timeout") }
        val failure = runCatching { UrlTest { service }.viaRunningCore(profile, wireGuard) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals("Timeout", failure?.message)
    }

    private suspend fun failure(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() as ConnectionTestFailure

    private fun prepared(identities: WireGuardIdentities) = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, wireGuardIdentities = identities)

    private fun binder(state: () -> BaseService.State, urlTestProfile: () -> Int) = Proxy.newProxyInstance(
        INekoBoxService::class.java.classLoader,
        arrayOf(INekoBoxService::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getState" -> state().ordinal

            "testWireGuardProfile" -> {
                calls += arguments[0] as Long
                val answerUrl = arguments[1] as String
                answerUrls += answerUrl
                val latency = urlTestProfile()
                if (answerUrl.isEmpty()) """{"latencyMs":$latency}""" else """{"latencyMs":$latency,"answer":"ip=203.0.113.7"}"""
            }

            else -> error("Unexpected service call: ${method.name}")
        }
    } as INekoBoxService
}

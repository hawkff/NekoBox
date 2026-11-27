package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.CORE_NOT_STARTED
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
import xyz.nekobyte.nekobox.bg.PROFILE_RUNS_OTHER_SETTINGS
import xyz.nekobyte.nekobox.bg.WIREGUARD_SESSION_IN_USE
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UrlTestTest {
    private val profile = ProxyEntity(id = 7, groupId = 1)
    private val wireGuard = prepared(mapOf(7L to listOf(ConfigBuildResult.WireGuardInstance("wg-7", 7))))
    private val calls = mutableListOf<Long>()

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun aWireGuardProfileTheConnectedServiceRunsIsTestedThroughItsCore() = runTest {
        val service = binder({ BaseService.State.Connected }) { 42 }
        assertEquals(42, UrlTest { service }.viaRunningCore(profile, wireGuard))
        assertEquals(listOf(7L), calls)
    }

    @Test
    fun onlyAStoppedServiceOrAProfileItDoesNotRunLeavesTheTestACoreOfItsOwn() = runTest {
        assertNull(UrlTest { binder({ BaseService.State.Connected }) { error(PROFILE_NOT_RUNNING) } }.viaRunningCore(profile, wireGuard))
        assertNull(UrlTest { binder({ BaseService.State.Stopped }) { 42 } }.viaRunningCore(profile, wireGuard))
        // No WireGuard or AmneziaWG hop: the service is not asked.
        assertNull(UrlTest { binder({ BaseService.State.Connecting }) { 42 } }.viaRunningCore(profile, prepared(emptyMap())))
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
        // A hop of the profile opens a session the core opens.
        assertEquals("wireguard_in_use", failure { UrlTest { binder({ BaseService.State.Connected }) { error(WIREGUARD_SESSION_IN_USE) } }.viaRunningCore(profile, wireGuard) }.code)
        // A service that stops under the test: no core of the test's own.
        assertEquals("vpn_changing", failure { UrlTest { binder({ BaseService.State.Connected }) { error(CORE_NOT_STARTED) } }.viaRunningCore(profile, wireGuard) }.code)
        assertEquals(listOf(7L, 7L, 7L), calls)
    }

    @Test
    fun aServiceThatSettlesInAMomentIsWaitedFor() = runTest {
        var states = listOf(BaseService.State.Connecting, BaseService.State.Connecting, BaseService.State.Connected)
        val service = binder({ states.first().also { if (states.size > 1) states = states.drop(1) } }) { 42 }
        assertEquals(42, UrlTest { service }.viaRunningCore(profile, wireGuard))
    }

    @Test
    fun aFailureOfTheRunningCoreIsTheTestsResult() = runTest {
        val service = binder({ BaseService.State.Connected }) { error("Timeout") }
        val failure = runCatching { UrlTest { service }.viaRunningCore(profile, wireGuard) }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals("Timeout", failure?.message)
    }

    private suspend fun failure(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() as ConnectionTestFailure

    private fun prepared(wireguardInstances: Map<Long, List<ConfigBuildResult.WireGuardInstance>>) = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, wireguardInstances = wireguardInstances)

    private fun binder(state: () -> BaseService.State, urlTestProfile: () -> Int) = Proxy.newProxyInstance(
        INekoBoxService::class.java.classLoader,
        arrayOf(INekoBoxService::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getState" -> state().ordinal

            "testWireGuardProfile" -> {
                calls += arguments[0] as Long
                """{"latencyMs":${urlTestProfile()}}"""
            }

            else -> error("Unexpected service call: ${method.name}")
        }
    } as INekoBoxService
}

package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
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
    fun aWireGuardProfileTheConnectedServiceRunsIsTestedThroughItsCore() {
        val service = binder(BaseService.State.Connected) { 42 }
        assertEquals(42, UrlTest { service }.viaRunningCore(profile, wireGuard))
        assertEquals(listOf(7L), calls)
    }

    @Test
    fun otherProfilesKeepTheirOwnCore() {
        val connected = binder(BaseService.State.Connected) { 42 }
        // Not in the running configuration.
        assertNull(UrlTest { binder(BaseService.State.Connected) { error(PROFILE_NOT_RUNNING) } }.viaRunningCore(profile, wireGuard))
        // No WireGuard or AmneziaWG hop.
        assertNull(UrlTest { connected }.viaRunningCore(profile, prepared(emptyMap())))
        // No connected service.
        assertNull(UrlTest { null }.viaRunningCore(profile, wireGuard))
        assertNull(UrlTest { binder(BaseService.State.Connecting) { 42 } }.viaRunningCore(profile, wireGuard))
        assertEquals(listOf(7L), calls)
    }

    @Test
    fun aFailureOfTheRunningCoreIsTheTestsResult() {
        val service = binder(BaseService.State.Connected) { error("timeout") }
        assertEquals("timeout", assertThrows(IllegalStateException::class.java) { UrlTest { service }.viaRunningCore(profile, wireGuard) }.message)
    }

    private fun prepared(wireguardInstances: Map<Long, List<ConfigBuildResult.WireGuardInstance>>) = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), emptyMap(), -1, wireguardInstances = wireguardInstances)

    private fun binder(state: BaseService.State, urlTestProfile: () -> Int) = Proxy.newProxyInstance(
        INekoBoxService::class.java.classLoader,
        arrayOf(INekoBoxService::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getState" -> state.ordinal

            "urlTestProfile" -> {
                calls += arguments[0] as Long
                urlTestProfile()
            }

            else -> error("Unexpected service call: ${method.name}")
        }
    } as INekoBoxService
}

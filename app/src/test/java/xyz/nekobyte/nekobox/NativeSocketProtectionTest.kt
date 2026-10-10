package xyz.nekobyte.nekobox

import android.app.Application
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.VpnService
import xyz.nekobyte.nekobox.bg.proto.mieruEnvironment
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SocketProtectionShadow::class])
class NativeSocketProtectionTest {
    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Stopped
        DataStore.vpnService = null
        SocketProtectionShadow.allowed = true
    }

    @After
    fun tearDown() {
        DataStore.vpnService = null
        DataStore.serviceState = BaseService.State.Stopped
    }

    @Test
    fun rejectsAFailedAndroidProtectionCall() {
        DataStore.vpnService = Robolectric.buildService(VpnService::class.java).get()
        SocketProtectionShadow.allowed = false
        assertThrows(IOException::class.java) { NativeInterface().autoDetectInterfaceControl(123) }
    }

    @Test
    fun acceptsSuccessfulProtection() {
        DataStore.vpnService = Robolectric.buildService(VpnService::class.java).get()
        NativeInterface().autoDetectInterfaceControl(123)
    }

    @Test
    fun mieruRequiresProtectionUnlessTheServiceIsKnownStopped() {
        val config = File("fixture.json")
        for (state in BaseService.State.entries) {
            DataStore.serviceState = state
            val required = mieruEnvironment(config).containsKey("MIERU_PROTECT_PATH")
            if (state == BaseService.State.Stopped) assertFalse(required) else assertTrue(required)
        }
        DataStore.serviceState = BaseService.State.Stopped
        DataStore.vpnService = Robolectric.buildService(VpnService::class.java).get()
        assertTrue(mieruEnvironment(config).containsKey("MIERU_PROTECT_PATH"))
    }

    @Test
    fun aStoppedServiceDoesNotRequireTheProtectionSocket() {
        for (mode in listOf(Key.MODE_VPN, Key.MODE_PROXY)) {
            DataStore.serviceMode = mode
            NativeInterface().autoDetectInterfaceControl(123)
        }
    }
}

@Implements(android.net.VpnService::class)
class SocketProtectionShadow {
    @Implementation
    fun protect(socket: Int) = allowed

    companion object {
        var allowed = true
    }
}

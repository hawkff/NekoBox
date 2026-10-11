package xyz.nekobyte.nekobox.fmt

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean
import xyz.nekobyte.nekobox.fmt.mieru.buildMieruConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MieruAuthenticationTest {
    @Before fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun serviceAndProbeUseMatchingLocalCredentials() {
        for (probe in listOf(false, true)) {
            val bean = bean()
            val profile = ProxyEntity(id = 1, groupId = 1).putBean(bean)
            val built = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = probe) }
            val (port, credentials) = built.localProxyCredentials.entries.single()
            val outbounds = JSONObject(built.config).getJSONArray("outbounds")
            val socks = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                .single { it.optString("type") == "socks" }
            assertEquals(credentials.first, socks.getString("username"))
            assertEquals(credentials.second, socks.getString("password"))
            assertTrue(credentials.second.length >= 32)
            val local = JSONObject(bean.buildMieruConfig(port, credentials.first, credentials.second))
                .getJSONArray("socks5Authentication").getJSONObject(0)
            assertEquals(credentials.first, local.getString("user"))
            assertEquals(credentials.second, local.getString("password"))
        }
    }

    @Test
    fun exportedConfigsRemainCredentialFreeAndPartialCredentialsAreRejected() {
        val bean = bean()
        val built = ConfigBuilderTestEnv.io { buildConfig(ProxyEntity(id = 1, groupId = 1).putBean(bean), forExport = true) }
        assertTrue(built.localProxyCredentials.isEmpty())
        assertFalse(JSONObject(bean.buildMieruConfig(1080)).has("socks5Authentication"))
        assertThrows(IllegalArgumentException::class.java) { bean.buildMieruConfig(1080, "local", null) }
        assertThrows(IllegalArgumentException::class.java) { bean.buildMieruConfig(1080, null, "secret") }
    }

    private fun bean() = MieruBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 443
        username = "remote-user"
        password = "remote-password"
        initializeDefaultValues()
    }
}

package xyz.nekobyte.nekobox.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileWordsTest {
    private val originalLookup = ServerCountries.lookup

    @After
    fun tearDown() {
        ServerCountries.lookup = originalLookup
    }

    private fun entity(bean: AbstractBean) = ProxyEntity().apply { putBean(bean.applyDefaultValues()) }

    private fun vmess(vless: Boolean, configure: VMessBean.() -> Unit) = entity(
        VMessBean().apply {
            serverAddress = "192.0.2.1"
            serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"
            if (vless) alterId = -1
            configure()
        },
    )

    @Test
    fun rowsNameTheTransportAndKeepProtocolsOtherThanVless() {
        val reality = vmess(true) {
            security = "tls"
            realityPubKey = "key"
        }
        assertEquals("Reality", ProfileWords.transport(reality))
        val webSocket = vmess(true) {
            type = "ws"
            security = "tls"
        }
        assertEquals("WebSocket · TLS", ProfileWords.transport(webSocket))
        assertEquals("TCP", ProfileWords.transport(vmess(true) {}))
        assertEquals("VMess · WebSocket", ProfileWords.transport(vmess(false) { type = "ws" }))
        val trojan = TrojanBean().apply {
            serverAddress = "192.0.2.2"
            serverPort = 443
            password = "secret"
        }
        assertEquals("Trojan · TLS", ProfileWords.transport(entity(trojan)))
    }

    @Test
    fun aRowNamesTheCountryOnlyWhenItsNameDoesNotFlagIt() {
        ServerCountries.lookup = { addresses -> addresses.split('\n').joinToString("\n") { if (it == "192.0.2.77") "de" else "" } }
        ServerCountries.lookUp(listOf("192.0.2.77", "node.example"))
        fun named(name: String, address: String) = vmess(true) {
            this.name = name
            serverAddress = address
        }
        assertEquals("🇩🇪 Germany", ProfileWords.country(named("Auto proxy-5", "192.0.2.77")))
        assertNull(ProfileWords.country(named("🇩🇪 Frankfurt", "192.0.2.77")))
        assertNull(ProfileWords.country(named("Auto proxy-6", "node.example")))
    }
}

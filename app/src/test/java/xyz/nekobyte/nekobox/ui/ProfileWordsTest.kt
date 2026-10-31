package xyz.nekobyte.nekobox.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfileWordsTest {
    private val originalLookup = ServerCountries.lookup
    private val originalDatabase = ServerCountries.database

    @Before
    fun setUp() = ConfigBuilderTestEnv.reset()

    @After
    fun tearDown() {
        ServerCountries.lookup = originalLookup
        ServerCountries.database = originalDatabase
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
    fun rowsNameTheProtocolAndHowItConnects() {
        val reality = vmess(true) {
            security = "tls"
            realityPubKey = "key"
        }
        assertEquals("VLESS · Reality", ProfileWords.connection(reality))
        val webSocket = vmess(true) {
            type = "ws"
            security = "tls"
        }
        assertEquals("VLESS · WS · TLS", ProfileWords.connection(webSocket))
        assertEquals("VLESS", ProfileWords.connection(vmess(true) {}))
        assertEquals("VMess · WS", ProfileWords.connection(vmess(false) { type = "ws" }))
        val trojan = TrojanBean().apply {
            serverAddress = "192.0.2.2"
            serverPort = 443
            password = "secret"
        }
        assertEquals("Trojan · TLS", ProfileWords.connection(entity(trojan)))
    }

    @Test
    fun aRowNamesTheCountryItsAddressIsIn() {
        ServerCountries.database = { 1L }
        ServerCountries.lookup = { addresses -> addresses.split('\n').joinToString("\n") { if (it == "192.0.2.77") "de" else "" } }
        // An address with a line break is never sent: it would shift the answers after it.
        ServerCountries.lookUp(listOf("bad\naddress", "192.0.2.77", "node.example"))
        fun named(name: String, address: String) = vmess(true) {
            this.name = name
            serverAddress = address
        }
        assertEquals("🇩🇪 Germany", ProfileWords.country(named("Auto proxy-5", "192.0.2.77")))
        assertEquals("🇩🇪 Germany", ProfileWords.country(named("🇩🇪 Frankfurt", "192.0.2.77")))
        // A name's flag may name the exit while the connection goes through a relay elsewhere.
        assertEquals("🇩🇪 Germany", ProfileWords.country(named("🇺🇸 New York", "192.0.2.77")))
        assertNull(ProfileWords.country(named("Auto proxy-6", "node.example")))
        // A server given by name takes the country of the address a test or a connection resolved it to.
        DataStore.rememberServerAddresses(mapOf("node.example" to "192.0.2.77"))
        assertEquals("🇩🇪 Germany", ProfileWords.country(named("Auto proxy-6", "node.example")))
        // A replaced database answers again for every known address, not only the one asked about.
        ServerCountries.database = { 2L }
        ServerCountries.lookup = { addresses -> addresses.split('\n').joinToString("\n") { "fr" } }
        ServerCountries.lookUp(listOf("192.0.2.88"))
        assertEquals("🇫🇷 France", ProfileWords.country(named("Auto proxy-5", "192.0.2.77")))
        assertEquals("🇫🇷 France", ProfileWords.country(named("Auto proxy-7", "192.0.2.88")))
    }
}

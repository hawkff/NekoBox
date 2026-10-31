package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ProxyLines.Plain
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.v2ray.isTLS

/** Proxy lists in the shapes providers hand them out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProxyLinesTest {

    private fun socks(line: String) = ProxyLines.parseLine(line, Plain.SOCKS5) as SOCKSBean

    @Test
    fun hostPortUserPassword() {
        val bean = socks("198.51.100.7:8000:alice:s3cret")
        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(8000, bean.serverPort)
        assertEquals("alice", bean.username)
        assertEquals("s3cret", bean.password)
        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
    }

    @Test
    fun passwordKeepsItsColons() {
        val bean = socks("gate.example.net:7000:user-country-us:pa:ss")
        assertEquals("user-country-us", bean.username)
        assertEquals("pa:ss", bean.password)
    }

    @Test
    fun userPasswordAtHostPort() {
        val bean = socks("bob:pw@proxy.example.org:1080")
        assertEquals("proxy.example.org", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("bob", bean.username)
        assertEquals("pw", bean.password)
    }

    @Test
    fun userPasswordHostPort() {
        val bean = socks("carol:pw:203.0.113.9:3128")
        assertEquals("203.0.113.9", bean.serverAddress)
        assertEquals(3128, bean.serverPort)
        assertEquals("carol", bean.username)
        assertEquals("pw", bean.password)
    }

    @Test
    fun aNumericPasswordStillReadsAsHostPortUserPassword() {
        val bean = socks("203.0.113.9:3128:dave:12345")
        assertEquals("203.0.113.9", bean.serverAddress)
        assertEquals(3128, bean.serverPort)
        assertEquals("12345", bean.password)
    }

    @Test
    fun bareHostPort() {
        val bean = socks("10.64.0.1:1080")
        assertEquals("10.64.0.1", bean.serverAddress)
        assertEquals("", bean.username)
    }

    @Test
    fun bracketedIpv6() {
        val bean = socks("[2001:db8::1]:1080:erin:pw")
        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("erin", bean.username)
        assertEquals("2001:db8::5", socks("frank:pw@[2001:db8::5]:443").serverAddress)
    }

    @Test
    fun plainLinesTakeThePickedKind() {
        val bean = ProxyLines.parseLine("198.51.100.7:8080:alice:pw", Plain.HTTP)
        assertTrue(bean is HttpBean)
        assertEquals("alice", (bean as HttpBean).username)
    }

    @Test
    fun linksKeepTheirScheme() {
        assertEquals(SOCKSBean.PROTOCOL_SOCKS4, (ProxyLines.parseLine("socks4://203.0.113.1:1080", Plain.HTTP) as SOCKSBean).protocol)
        val https = ProxyLines.parseLine("https://u:p@secure.example.com:443#Paid%20US", Plain.SOCKS5) as HttpBean
        assertTrue(https.isTLS())
        assertEquals("Paid US", https.name)
        assertEquals("u", https.username)
        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, (ProxyLines.parseLine("socks5h://h.example:1080", Plain.HTTP) as SOCKSBean).protocol)
    }

    @Test
    fun nonsenseIsSkipped() {
        assertNull(ProxyLines.parseLine("not a proxy", Plain.SOCKS5))
        assertNull(ProxyLines.parseLine("host:99999", Plain.SOCKS5))
        assertNull(ProxyLines.parseLine("vmess://abc", Plain.SOCKS5))
        assertNull(ProxyLines.parseLine("justahost", Plain.SOCKS5))
    }

    @Test
    fun countsWhatItSkipsAndIgnoresCommentsAndBlankLines() {
        val result = ProxyLines.parse(
            """
            # US pool, bought today
            198.51.100.7:8000:alice:pw

            garbage line
            198.51.100.8:8000:bob:pw
            """.trimIndent(),
            Plain.SOCKS5,
        )
        assertEquals(2, result.proxies.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun sessionsOnOneGatewayAreNamedByLogin() {
        val result = ProxyLines.parse(
            "gate.example.net:7000:user-session-a:pw\ngate.example.net:7000:user-session-b:pw\n203.0.113.4:1080:solo:pw",
            Plain.SOCKS5,
        )
        assertEquals("gate.example.net:7000 · user-session-a", result.proxies[0].name)
        assertEquals("gate.example.net:7000 · user-session-b", result.proxies[1].name)
        // A proxy alone on its address keeps the address as its name.
        assertTrue(result.proxies[2].name.isNullOrBlank())
    }
}

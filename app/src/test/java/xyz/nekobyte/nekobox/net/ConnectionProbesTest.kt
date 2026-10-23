package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer

// org.json needs the Android implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConnectionProbesTest {
    private val id = ByteArray(12) { (it + 1).toByte() }

    private fun response(transaction: ByteArray, vararg attributes: ByteArray): ByteArray {
        val body = attributes.fold(ByteArray(0)) { all, next -> all + next }
        return ByteBuffer.allocate(20 + body.size).putShort(0x0101).putShort(body.size.toShort()).putInt(0x2112A442).put(transaction).put(body).array()
    }

    private fun attribute(type: Int, value: ByteArray): ByteArray {
        val padded = (value.size + 3) and 3.inv()
        return ByteBuffer.allocate(4 + padded).putShort(type.toShort()).putShort(value.size.toShort()).put(value).array()
    }

    private fun mapped(address: String, port: Int, xor: Boolean): ByteArray {
        val raw = InetAddress.getByName(address).address
        val key = ByteBuffer.allocate(16).putInt(0x2112A442).put(id).array()
        val bytes = if (xor) ByteArray(raw.size) { (raw[it].toInt() xor key[it].toInt()).toByte() } else raw
        val value = ByteBuffer.allocate(4 + raw.size).put(0).put(if (raw.size == 4) 1 else 2).putShort((if (xor) port xor 0x2112 else port).toShort()).put(bytes).array()
        return attribute(if (xor) 0x0020 else 0x0001, value)
    }

    @Test
    fun stunAnswersGiveTheAddressTheServerSaw() {
        assertEquals(InetSocketAddress(InetAddress.getByName("203.0.113.7"), 54321), ConnectionProbes.parseStun(response(id, mapped("203.0.113.7", 54321, xor = true)), id))
        assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8::7"), 443), ConnectionProbes.parseStun(response(id, mapped("2001:db8::7", 443, xor = true)), id))
        // Another transaction's answer, or a cut one, is not ours.
        assertNull(ConnectionProbes.parseStun(response(ByteArray(12), mapped("203.0.113.7", 54321, xor = true)), id))
        assertNull(ConnectionProbes.parseStun(response(id, mapped("203.0.113.7", 54321, xor = true)).copyOf(30), id))
        // The older MAPPED-ADDRESS counts when nothing better comes, after a padded attribute of another kind.
        val software = attribute(0x8022, "abc".toByteArray())
        assertEquals(InetSocketAddress(InetAddress.getByName("198.51.100.4"), 3478), ConnectionProbes.parseStun(response(id, software, mapped("198.51.100.4", 3478, xor = false)), id))
        assertEquals(
            InetSocketAddress(InetAddress.getByName("203.0.113.7"), 54321),
            ConnectionProbes.parseStun(response(id, mapped("198.51.100.4", 3478, xor = false), mapped("203.0.113.7", 54321, xor = true)), id),
        )
    }

    @Test
    fun ipleakAnswersGiveTheAddressAndItsNetwork() {
        assertEquals(
            ConnectionProbes.PublicAddress("203.0.113.9", "Example ISP", 64500, "Netherlands"),
            ConnectionProbes.parsePublicAddress("""{"as_number": 64500, "isp_name": "Example ISP", "country_name": "Netherlands", "ip": "203.0.113.9", "city_name": null}"""),
        )
        assertEquals(
            ConnectionProbes.PublicAddress("2001:db8::9", "", null, ""),
            ConnectionProbes.parsePublicAddress("""{"ip": "2001:db8::9", "isp_name": null, "as_number": null, "country_name": null}"""),
        )
        assertNull(ConnectionProbes.parsePublicAddress("{}"))
    }

    private val vpn = ConnectionProbes.PublicAddress("203.0.113.9", "Example VPN", 64500, "Netherlands")
    private val home = ConnectionProbes.PublicAddress("2001:db8::9", "Home ISP", 64511, "Netherlands")

    @Test
    fun throughNekoBoxIpv6HasToLeaveThroughTheNetworkOfIpv4() {
        assertEquals(ConnectionProbes.Ipv6.NONE, ConnectionProbes.ipv6(direct = false, vpn, null))
        assertEquals(ConnectionProbes.Ipv6.CONNECTED, ConnectionProbes.ipv6(direct = true, vpn, home))
        assertEquals(ConnectionProbes.Ipv6.SAME_NETWORK, ConnectionProbes.ipv6(direct = false, vpn, home.copy(asn = 64500)))
        assertEquals(ConnectionProbes.Ipv6.OTHER_NETWORK, ConnectionProbes.ipv6(direct = false, vpn, home))
        // Without both networks there is nothing to compare, and no warning.
        assertEquals(ConnectionProbes.Ipv6.UNCOMPARED, ConnectionProbes.ipv6(direct = false, null, home))
        assertEquals(ConnectionProbes.Ipv6.UNCOMPARED, ConnectionProbes.ipv6(direct = false, vpn, home.copy(asn = null)))
    }

    @Test
    fun udpExitsAreComparedByAddressAndByNetwork() {
        val tcp = listOf(vpn)
        fun udp(ip: String) = InetSocketAddress(InetAddress.getByName(ip), 40000)
        assertEquals(ConnectionProbes.Udp.NOT_CHECKED, ConnectionProbes.udp(false, tcp, null, null))
        assertEquals(ConnectionProbes.Udp.NO_ANSWER, ConnectionProbes.udp(true, tcp, null, null))
        assertEquals(ConnectionProbes.Udp.SAME_ADDRESS, ConnectionProbes.udp(true, tcp, udp("203.0.113.9"), null))
        // A carrier NAT or a VPN server may hand UDP another address of the same network.
        assertEquals(ConnectionProbes.Udp.SAME_NETWORK, ConnectionProbes.udp(true, tcp, udp("203.0.113.10"), vpn.copy(ip = "203.0.113.10")))
        assertEquals(ConnectionProbes.Udp.OTHER_NETWORK, ConnectionProbes.udp(true, tcp, udp("198.51.100.4"), home.copy(ip = "198.51.100.4")))
        assertEquals(ConnectionProbes.Udp.OTHER_ADDRESS, ConnectionProbes.udp(true, tcp, udp("198.51.100.4"), null))
        assertEquals(ConnectionProbes.Udp.OTHER_ADDRESS, ConnectionProbes.udp(true, listOf(vpn.copy(asn = null)), udp("198.51.100.4"), home.copy(ip = "198.51.100.4")))
    }

    @Test
    fun aResolverThatAcceptsTheForgedNameDoesNotValidate() = runTest {
        // Plays dnscheck.tools: the nth request for a name answers with a status or fails with an error.
        fun client(control: (Int) -> Any, forged: (Int) -> Any): OkHttpClient {
            val asked = mutableMapOf<Boolean, Int>()
            return OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    val isForged = chain.request().url.host.substringBefore('.').endsWith("-badsig")
                    val attempt = asked.merge(isForged, 1, Int::plus)!!
                    when (val outcome = (if (isForged) forged else control)(attempt)) {
                        is IOException -> throw outcome
                        else -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(outcome as Int).message("").body("".toResponseBody()).build()
                    }
                },
            ).build()
        }
        val refused = UnknownHostException("refused")
        val closed = IOException("closed")
        assertEquals(ConnectionProbes.Dnssec.VALIDATED, ConnectionProbes.dnssec(client({ 200 }, { refused })))
        // Through FakeIP or the local proxy a refusal closes the connection.
        assertEquals(ConnectionProbes.Dnssec.VALIDATED, ConnectionProbes.dnssec(client({ 200 }, { closed })))
        // Any answer means the forged name resolved, also after a failed first try.
        assertEquals(ConnectionProbes.Dnssec.NOT_VALIDATED, ConnectionProbes.dnssec(client({ 200 }, { 200 })))
        assertEquals(ConnectionProbes.Dnssec.NOT_VALIDATED, ConnectionProbes.dnssec(client({ 200 }, { 404 })))
        assertEquals(ConnectionProbes.Dnssec.NOT_VALIDATED, ConnectionProbes.dnssec(client({ 200 }, { if (it == 1) closed else 200 })))
        // Without both controls, or with a timeout, a failure proves nothing.
        assertEquals(ConnectionProbes.Dnssec.UNKNOWN, ConnectionProbes.dnssec(client({ refused }, { refused })))
        assertEquals(ConnectionProbes.Dnssec.UNKNOWN, ConnectionProbes.dnssec(client({ if (it == 1) 200 else closed }, { closed })))
        assertEquals(ConnectionProbes.Dnssec.UNKNOWN, ConnectionProbes.dnssec(client({ 200 }, { SocketTimeoutException("slow") })))
        assertEquals(ConnectionProbes.Dnssec.UNKNOWN, ConnectionProbes.dnssec(client({ 200 }, { if (it == 1) closed else SocketTimeoutException("slow") })))
    }

    @Test
    fun theTraceTellsTheTlsVersionAndWhetherTheNameWasEncrypted() {
        assertEquals(ConnectionProbes.Trace("TLSv1.3", false), ConnectionProbes.parseTrace("fl=1\nh=www.cloudflare.com\nip=203.0.113.9\ntls=TLSv1.3\nsni=plaintext\nwarp=off\n"))
        assertTrue(ConnectionProbes.parseTrace("tls=TLSv1.3\nsni=encrypted").encryptedSni)
        assertEquals(ConnectionProbes.Trace(null, false), ConnectionProbes.parseTrace(""))
    }
}

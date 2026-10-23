package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

/** What services on the internet see of this connection, besides the DNS leak test. */
object ConnectionProbes {
    // ipleak.net (run by AirVPN) answers on one address family per host name.
    const val IPV4 = "https://ipv4.ipleak.net/json/"
    const val IPV6 = "https://ipv6.ipleak.net/json/"
    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    // dnscheck.tools serves every name under these zones over HTTPS. The plain name is signed correctly;
    // the -badsig one carries a forged signature, which a validating resolver refuses.
    private const val DNSSEC_ZONE = "test-alg13.dnscheck.tools"
    private const val STUN_HOST = "stun.cloudflare.com"
    private const val STUN_PORT = 3478
    private const val STUN_COOKIE = 0x2112A442

    data class PublicAddress(val ip: String, val isp: String, val asn: Long?, val country: String)

    /** The TLS this app's own client negotiates, as Cloudflare reports it. */
    data class Trace(val tls: String?, val encryptedSni: Boolean)

    enum class Dnssec { VALIDATED, NOT_VALIDATED, UNKNOWN }

    enum class Ipv6 { NONE, CONNECTED, SAME_NETWORK, OTHER_NETWORK, UNCOMPARED }

    enum class Udp { NOT_CHECKED, NO_ANSWER, SAME_ADDRESS, SAME_NETWORK, OTHER_NETWORK, OTHER_ADDRESS }

    // What ipleak.net knows about another address, such as the one UDP left from.
    fun lookupUrl(address: String) = "https://ipleak.net/json/$address"

    /** The address an ipleak.net host sees, or null when that address family does not get through. */
    suspend fun publicAddress(client: OkHttpClient, url: String): PublicAddress? = withContext(Dispatchers.IO) {
        try {
            parsePublicAddress(client.text(url))
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    suspend fun trace(client: OkHttpClient): Trace? = withContext(Dispatchers.IO) {
        try {
            parseTrace(client.text(TRACE))
        } catch (_: IOException) {
            null
        }
    }

    // Any HTTP answer from the forged name means the resolver on the way accepted it. A refusal fails the
    // lookup, or, through FakeIP and the local proxy, closes the connection early, which a transport
    // failure can do too. So the forged name is asked twice between two requests for the correctly
    // signed one, and only failures of both, with both controls answered, count as a refusal. A timeout
    // says nothing about the resolver.
    suspend fun dnssec(client: OkHttpClient): Dnssec = withContext(Dispatchers.IO) {
        val id = Integer.toHexString(SecureRandom().nextInt())
        suspend fun reached(name: String): Boolean? = try {
            client.newCall(Request.Builder().url("https://$name.$DNSSEC_ZONE/").build()).await().close()
            true
        } catch (_: InterruptedIOException) {
            null
        } catch (_: IOException) {
            false
        }
        if (reached(id) != true) return@withContext Dnssec.UNKNOWN
        val first = reached("$id-badsig")
        if (first == true) return@withContext Dnssec.NOT_VALIDATED
        val second = reached("$id-badsig")
        when {
            second == true -> Dnssec.NOT_VALIDATED
            first == null || second == null || reached(id) != true -> Dnssec.UNKNOWN
            else -> Dnssec.VALIDATED
        }
    }

    /** Through NekoBox, the IPv6 exit has to be in the network (AS) of the IPv4 exit. Without both networks there is no verdict. */
    fun ipv6(direct: Boolean, ipv4: PublicAddress?, ipv6: PublicAddress?) = when {
        ipv6 == null -> Ipv6.NONE
        direct -> Ipv6.CONNECTED
        ipv4?.asn == null || ipv6.asn == null -> Ipv6.UNCOMPARED
        ipv4.asn == ipv6.asn -> Ipv6.SAME_NETWORK
        else -> Ipv6.OTHER_NETWORK
    }

    /**
     * Where UDP left compared with TCP, by address and by network (AS). Carrier NATs and VPN servers may
     * give UDP another address of the same network; [network] is what ipleak.net knows of that address.
     */
    fun udp(checked: Boolean, tcp: List<PublicAddress>, udp: InetSocketAddress?, network: PublicAddress?) = when {
        !checked -> Udp.NOT_CHECKED
        udp == null -> Udp.NO_ANSWER
        tcp.any { AddressReputation.numeric(it.ip)?.contentEquals(udp.address.address) == true } -> Udp.SAME_ADDRESS
        network?.asn == null || tcp.none { it.asn != null } -> Udp.OTHER_ADDRESS
        tcp.any { it.asn == network.asn } -> Udp.SAME_NETWORK
        else -> Udp.OTHER_NETWORK
    }

    /**
     * The address and port a STUN server sees for this app's UDP, sent the way other apps send theirs,
     * or null without an answer. Three tries, two seconds each.
     */
    suspend fun udpAddress(): InetSocketAddress? = withContext(Dispatchers.IO) {
        val id = ByteArray(12).also(SecureRandom()::nextBytes)
        val request = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(STUN_COOKIE).put(id).array()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2_000
                val server = InetSocketAddress(InetAddress.getByName(STUN_HOST), STUN_PORT)
                repeat(3) {
                    socket.send(DatagramPacket(request, request.size, server))
                    val buffer = ByteArray(576)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        parseStun(buffer.copyOf(packet.length), id)?.let { return@withContext it }
                    } catch (_: SocketTimeoutException) {
                    }
                }
                null
            }
        } catch (_: IOException) {
            null
        }
    }

    internal fun parsePublicAddress(json: String): PublicAddress? {
        val root = JSONObject(json)
        val ip = root.optString("ip").ifEmpty { return null }
        return PublicAddress(
            ip,
            root.optString("isp_name").takeUnless { root.isNull("isp_name") }.orEmpty(),
            root.optLong("as_number", -1).takeIf { it >= 0 },
            root.optString("country_name").takeUnless { root.isNull("country_name") }.orEmpty(),
        )
    }

    internal fun parseTrace(text: String): Trace {
        val fields = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return Trace(fields["tls"], fields["sni"] == "encrypted")
    }

    // A Binding success response for our transaction: XOR-MAPPED-ADDRESS, else the older MAPPED-ADDRESS.
    internal fun parseStun(message: ByteArray, id: ByteArray): InetSocketAddress? {
        if (message.size < 20) return null
        fun u16(at: Int) = ((message[at].toInt() and 0xff) shl 8) or (message[at + 1].toInt() and 0xff)
        val end = 20 + u16(2)
        if (u16(0) != 0x0101 || ByteBuffer.wrap(message, 4, 4).int != STUN_COOKIE || !message.copyOfRange(8, 20).contentEquals(id) || message.size < end) return null
        val key = ByteBuffer.allocate(16).putInt(STUN_COOKIE).put(id).array()
        var plain: InetSocketAddress? = null
        var at = 20
        while (at + 4 <= end) {
            val attribute = u16(at)
            val size = u16(at + 2)
            val value = at + 4
            if (value + size > end) break
            val bytes = when (message.getOrNull(value + 1)?.toInt()) {
                0x01 -> 4
                0x02 -> 16
                else -> 0
            }
            if ((attribute == 0x0020 || attribute == 0x0001) && bytes > 0 && size >= 4 + bytes) {
                val raw = message.copyOfRange(value + 4, value + 4 + bytes)
                if (attribute == 0x0020) {
                    val ip = ByteArray(bytes) { (raw[it].toInt() xor key[it].toInt()).toByte() }
                    return InetSocketAddress(InetAddress.getByAddress(ip), u16(value + 2) xor (STUN_COOKIE ushr 16))
                }
                plain = InetSocketAddress(InetAddress.getByAddress(raw), u16(value + 2))
            }
            at = value + ((size + 3) and 3.inv())
        }
        return plain
    }
}

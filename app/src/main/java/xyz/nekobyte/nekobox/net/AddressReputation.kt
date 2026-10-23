package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * Whether addresses appear in public lists of VPN providers, datacenters and Tor exits. The lists are
 * downloaded and matched on the device, so their hosts never learn which address is checked:
 * X4BNet lists_vpn (github.com/X4BNet/lists_vpn, MIT), built from the networks of VPN providers and
 * datacenters, and the Tor Project's exit list. proxycheck.io is asked only on request.
 */
object AddressReputation {
    enum class Kind { VPN, DATACENTER, TOR }

    private const val X4B = "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output"

    // Each list with the address family it covers; the Tor Project lists IPv4 exits only.
    private class Source(val kind: Kind, val url: String, val ipv6: Boolean)

    private val SOURCES = listOf(
        Source(Kind.VPN, "$X4B/vpn/ipv4.txt", ipv6 = false),
        Source(Kind.VPN, "$X4B/vpn/ipv6.txt", ipv6 = true),
        Source(Kind.DATACENTER, "$X4B/datacenter/ipv4.txt", ipv6 = false),
        Source(Kind.DATACENTER, "$X4B/datacenter/ipv6.txt", ipv6 = true),
        Source(Kind.TOR, "https://check.torproject.org/torbulkexitlist", ipv6 = false),
    )
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * The lists an address is on. Without every list for its address family, an empty set proves nothing;
     * [uncovered] are the kinds no list covers for that family.
     */
    data class Match(val kinds: Set<Kind>, val complete: Boolean, val uncovered: Set<Kind> = emptySet())

    /** proxycheck.io's view of an address. */
    data class Lookup(val vpn: Boolean, val proxy: Boolean, val tor: Boolean, val hosting: Boolean, val network: String?, val operator: String?)

    suspend fun match(client: OkHttpClient, cache: File, addresses: List<String>): Map<String, Match> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyMap()
        val loaded = coroutineScope {
            SOURCES.map { source -> async { source to lines(client, cache, source.url)?.mapNotNull(::network) } }.awaitAll()
        }
        addresses.associateWith { text ->
            val address = numeric(text)
            val ipv6 = address?.size == 16
            val applicable = loaded.filter { (source, _) -> source.ipv6 == ipv6 }
            Match(
                applicable.filter { (_, networks) -> address != null && networks?.any { it.contains(address) } == true }.map { it.first.kind }.toSet(),
                address != null && applicable.all { it.second != null },
                Kind.entries.toSet() - applicable.map { it.first.kind }.toSet(),
            )
        }
    }

    // Sends the address to proxycheck.io, keyless: 100 lookups a day per asking address.
    suspend fun proxycheck(client: OkHttpClient, address: String): Lookup = withContext(Dispatchers.IO) {
        parseProxyCheck(client.text("https://proxycheck.io/v3/$address"), address)
    }

    // A copy younger than a day is used as it is; a failed download falls back to any older copy.
    private suspend fun lines(client: OkHttpClient, cache: File, url: String): List<String>? {
        val file = File(cache, "address-lists/" + url.substringAfter("://").replace('/', '_'))
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return file.readLines()
        return try {
            val text = client.text(url)
            // An error page or a changed format has content but no networks; it must not replace a good copy.
            val lines = text.lines()
            if (lines.none { network(it) != null } && lines.any { it.substringBefore('#').isNotBlank() }) throw IOException("unreadable list")
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            partial.writeText(text)
            if (!partial.renameTo(file)) partial.delete()
            lines
        } catch (_: IOException) {
            if (file.isFile) file.readLines() else null
        }
    }

    internal class Network(private val prefix: ByteArray, private val bits: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != prefix.size) return false
            for (i in 0 until bits / 8) if (address[i] != prefix[i]) return false
            val rest = bits % 8
            if (rest == 0) return true
            val mask = (0xff shl (8 - rest)) and 0xff
            return (address[bits / 8].toInt() and mask) == (prefix[bits / 8].toInt() and mask)
        }
    }

    // "203.0.113.0/24", "2001:db8::/32" or a bare address; anything else is skipped.
    internal fun network(line: String): Network? {
        val text = line.substringBefore('#').trim().ifEmpty { return null }
        val address = numeric(text.substringBefore('/')) ?: return null
        val bits = if ('/' in text) text.substringAfter('/').toIntOrNull() ?: return null else address.size * 8
        return Network(address, bits).takeIf { bits in 0..address.size * 8 }
    }

    // Only address literals: InetAddress would look a host name up.
    internal fun numeric(text: String): ByteArray? {
        val literal = if (':' in text) {
            text.all { it.isLetterOrDigit() || it == ':' || it == '.' }
        } else {
            text.count { it == '.' } == 3 && text.all { it.isDigit() || it == '.' }
        }
        return if (literal) runCatching { InetAddress.getByName(text).address }.getOrNull() else null
    }

    internal fun parseProxyCheck(json: String, address: String): Lookup {
        val root = JSONObject(json)
        val entry = root.optJSONObject(address)
            ?: root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull { it.has("detections") }
            ?: throw IOException(root.optString("message").ifEmpty { "proxycheck.io sent no answer for the address" })
        val detections = entry.optJSONObject("detections") ?: JSONObject()
        fun JSONObject.text(name: String) = optString(name).takeIf { it.isNotEmpty() && !isNull(name) }
        val network = entry.optJSONObject("network")?.let { listOfNotNull(it.text("type"), it.text("provider")).joinToString(" · ") }
        return Lookup(
            vpn = detections.optBoolean("vpn"),
            proxy = detections.optBoolean("proxy"),
            tor = detections.optBoolean("tor"),
            hosting = detections.optBoolean("hosting"),
            network = network?.ifEmpty { null },
            operator = entry.optJSONObject("operator")?.text("name"),
        )
    }
}

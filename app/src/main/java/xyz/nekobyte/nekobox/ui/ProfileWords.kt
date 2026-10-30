package xyz.nekobyte.nekobox.ui

import libcore.Libcore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.Logs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What a profile row says about its server, beside the name. */
object ProfileWords {
    private val TRANSPORTS = mapOf(
        "ws" to "WebSocket",
        "grpc" to "gRPC",
        "http" to "HTTP",
        "httpupgrade" to "HTTPUpgrade",
        "xhttp" to "XHTTP",
        "splithttp" to "XHTTP",
        "kcp" to "mKCP",
        "quic" to "QUIC",
    )

    /**
     * How the connection looks on the wire: "Reality", "WebSocket · TLS", "Trojan · TLS". VLESS names
     * nothing a server is told apart by, so its rows name only the transport; other protocols keep
     * their names.
     */
    fun transport(profile: ProxyEntity): String {
        val bean = profile.requireBean()
        if (bean !is VMessBean && bean !is TrojanBean) return profile.displayType()
        bean as StandardV2RayBean
        val parts = listOfNotNull(
            profile.displayType().takeUnless { bean.isVLESS },
            bean.type?.let { TRANSPORTS[it] ?: it.takeUnless { type -> type == "tcp" } },
            when {
                !bean.realityPubKey.isNullOrBlank() -> "Reality"
                bean.security == "tls" -> "TLS"
                else -> null
            },
        )
        return parts.ifEmpty { listOf("TCP") }.joinToString(" · ")
    }

    /**
     * "🇩🇪 Germany" for the country the server's address is in, when its name does not already
     * show that flag; null while the address is unknown or a name.
     */
    fun country(profile: ProxyEntity): String? {
        val code = ServerCountries.cached(profile.requireBean().serverAddress ?: return null)?.takeIf { it.length == 2 } ?: return null
        if (nameFlag(profile.displayName()) == code) return null
        return flag(code) + " " + Locale.Builder().setRegion(code).build().displayCountry
    }

    /** The flag emoji of a two-letter country code. */
    fun flag(code: String): String = code.uppercase().map { String(Character.toChars(REGIONAL_A + (it - 'A'))) }.joinToString("")

    /** The country code of the first flag in [name], lowercase, or null. */
    fun nameFlag(name: String): String? {
        val points = name.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            if (points[i] in REGIONAL && points[i + 1] in REGIONAL) {
                return "${'a' + (points[i] - REGIONAL_A)}${'a' + (points[i + 1] - REGIONAL_A)}"
            }
        }
        return null
    }

    private const val REGIONAL_A = 0x1F1E6
    private val REGIONAL = REGIONAL_A..REGIONAL_A + 25
}

/**
 * Countries of server addresses, from the GeoIP database the app ships, kept for the life of the
 * process. Lookups read the database, so they run off the main thread; rows read [cached].
 */
object ServerCountries {
    private val codes = ConcurrentHashMap<String, String>()

    // Tests replace the core.
    internal var lookup: (String) -> String = { Libcore.serverCountries(it) }

    fun cached(address: String): String? = codes[address]

    /** Looks up the addresses not seen yet; true when any was added. */
    fun lookUp(addresses: Collection<String?>): Boolean {
        val missing = addresses.filterNotNull().filter { it.isNotBlank() && !codes.containsKey(it) }.distinct()
        if (missing.isEmpty()) return false
        val found = try {
            lookup(missing.joinToString("\n")).split('\n')
        } catch (e: Exception) {
            // The database may not be unpacked yet; the next load asks again.
            Logs.w(e)
            return false
        }
        missing.forEachIndexed { index, address -> codes[address] = found.getOrElse(index) { "" } }
        return true
    }
}

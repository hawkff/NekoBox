package xyz.nekobyte.nekobox.ui

import libcore.Libcore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What a profile row says about its server, beside the name. */
object ProfileWords {
    // The transports as share links and panels shorten them; TCP is the default and goes unsaid.
    private val TRANSPORTS = mapOf(
        "ws" to "WS",
        "grpc" to "gRPC",
        "http" to "HTTP",
        "httpupgrade" to "HTTPUpgrade",
        "xhttp" to "XHTTP",
        "splithttp" to "XHTTP",
        "kcp" to "mKCP",
        "quic" to "QUIC",
    )

    /**
     * The protocol and how its connection looks on the wire: "VLESS · Reality", "VLESS · WS · TLS",
     * "Trojan · TLS". Every row names its protocol, so one VMess server among VLESS ones stands out.
     */
    fun connection(profile: ProxyEntity): String {
        val bean = profile.requireBean()
        val protocol = profile.displayType()
        if (bean !is VMessBean && bean !is TrojanBean) return protocol
        bean as StandardV2RayBean
        return listOfNotNull(
            protocol,
            bean.type?.let { TRANSPORTS[it] ?: it.takeUnless { type -> type == "tcp" } },
            when {
                !bean.realityPubKey.isNullOrBlank() -> "Reality"
                bean.security == "tls" -> "TLS"
                else -> null
            },
        ).joinToString(" · ")
    }

    /**
     * "🇩🇪 Germany" for the country the server's address is in, when its name shows no flag; null
     * while the address is unknown or a name. A flag in the name names the exit, and the address
     * may be a relay elsewhere, so the row does not contradict it.
     */
    fun country(profile: ProxyEntity): String? {
        if (nameFlag(profile.displayName()) != null) return null
        val code = ServerCountries.cached(profile.requireBean().serverAddress ?: return null)?.takeIf { it.length == 2 } ?: return null
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

    // When the database file the answers came from was written; the Assets screen can replace it.
    @Volatile
    private var answeredFrom = 0L

    // Tests replace the core and the database file.
    internal var lookup: (String) -> String = { Libcore.serverCountries(it) }
    internal var database: () -> Long = { File(app.externalAssets, "geoip.db").lastModified() }

    fun cached(address: String): String? = codes[address]

    /** Looks up the addresses not seen yet; true when any answer was added or dropped. */
    fun lookUp(addresses: Collection<String?>): Boolean {
        val written = database()
        val renewed = written != answeredFrom && codes.isNotEmpty()
        if (written != answeredFrom) {
            codes.clear()
            answeredFrom = written
        }
        // A line break would shift every answer after it.
        val missing = addresses.filterNotNull().filter { it.isNotBlank() && '\n' !in it && '\r' !in it && !codes.containsKey(it) }.distinct()
        if (missing.isEmpty()) return renewed
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

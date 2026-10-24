package xyz.nekobyte.nekobox.fmt

import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.makeSingBoxRule
import xyz.nekobyte.nekobox.utils.listByLineOrComma
import java.net.URI

/** What the route tester asks about: a site or an address, with a port when known, or an app; over [network] when known. */
data class RouteProbe(val host: String? = null, val port: Int? = null, val packageName: String? = null, val network: String? = null) {
    val isAddress get() = host != null && (IPV4.matches(host) || ':' in host)

    companion object {
        private val IPV4 = Regex("""((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)""")
        private val IPV6 = Regex("""[0-9a-f.]*:[0-9a-f:.]*""")
        private val NAME = Regex("""[\p{L}\p{N}_-]+(\.[\p{L}\p{N}_-]+)+""")
        private val BRACKETED = Regex("""\[([^]]+)](?::(\d+))?""")
        private val SCHEME_PORTS = mapOf("https" to "443", "http" to "80")

        /** Reads example.com, example.com:443, 192.0.2.1, [2001:db8::1]:443 or a link; null for anything else. */
        fun parse(text: String): RouteProbe? {
            val input = text.trim()
            if (input.isEmpty() || input.any(Char::isWhitespace)) return null
            if ("://" in input) {
                val uri = runCatching { URI(input) }.getOrNull() ?: return null
                return of(uri.host?.removeSurrounding("[", "]"), uri.port.takeIf { it > 0 }?.toString() ?: SCHEME_PORTS[uri.scheme?.lowercase()])
            }
            BRACKETED.matchEntire(input)?.let { return of(it.groupValues[1], it.groupValues[2]) }
            if (input.count { it == ':' } == 1) return input.split(':').let { (host, port) -> of(host, port) }
            return of(input, null)
        }

        private fun of(host: String?, port: String?): RouteProbe? {
            val name = host?.lowercase()?.removeSuffix(".") ?: return null
            val number = if (port.isNullOrEmpty()) null else port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (!IPV4.matches(name) && !IPV6.matches(name) && !NAME.matches(name)) return null
            return RouteProbe(name, number)
        }
    }
}

/** A condition of a rule that the probe leaves open: the rule catches only part of its traffic. */
sealed class RouteCondition {
    data class Ports(val ports: String) : RouteCondition()
    data class Network(val network: String) : RouteCondition()
    data class Protocols(val protocols: List<String>) : RouteCondition()
    data class Apps(val packages: Set<String>) : RouteCondition()
    data class Source(val source: String) : RouteCondition()
    data class SourcePorts(val ports: String) : RouteCondition()

    /** A rule's addresses, asked about a site: the core sees the address the site resolves to. */
    data class Address(val addresses: List<String>) : RouteCondition()

    /** A rule's sites, asked about an address: the core sees the site's name when the app sends it. */
    data class Site(val sites: List<String>) : RouteCondition()

    /** A rule's sites and addresses, asked about an app: only that part of the app's traffic. */
    data class Destination(val sites: List<String>, val addresses: List<String>, val downloaded: Int) : RouteCondition()
}

/** Why a rule can't be judged on the phone. */
sealed class RouteUnknown {
    object CustomJson : RouteUnknown()
    object DownloadedList : RouteUnknown()
    object ListsNotLoaded : RouteUnknown()
    data class Broken(val message: String) : RouteUnknown()
}

/** How one rule treats the probe. */
sealed class RouteVerdict {
    object Catches : RouteVerdict()
    object Passes : RouteVerdict()
    data class CatchesIf(val conditions: List<RouteCondition>) : RouteVerdict()
    data class Unknown(val reason: RouteUnknown) : RouteVerdict()
}

/** What decides where the probe goes. */
sealed class RouteDecision {
    data class Rule(val index: Int) : RouteDecision()

    /** DNS queries go to the app's DNS, whatever the rules say. */
    object Dns : RouteDecision()

    /** The per-app proxy setting leaves the app out of the VPN. */
    object AppOutsideVpn : RouteDecision()

    /** Bypass LAN leaves local addresses out of the VPN. */
    object LocalOutsideVpn : RouteDecision()

    /** Global mode skips the rules. */
    object GlobalMode : RouteDecision()

    /** No rule caught it, and local addresses go direct. */
    object LocalNetwork : RouteDecision()

    /** No rule caught it: the selected profile. */
    object Proxy : RouteDecision()
}

/** The settings that act before or after the rules. */
data class RouteSettings(
    val globalMode: Boolean = false,
    val vpn: Boolean = true,
    val bypassLan: Boolean = false,
    val bypassLanInCore: Boolean = false,
    /** The per-app proxy list when it is on: [bypassApps] leaves the listed apps out, else only they go in. */
    val perAppPackages: Set<String>? = null,
    val bypassApps: Boolean = true,
    /** Whether the bundled site and address lists are unpacked; the service unpacks them when it first runs. */
    val listsLoaded: Boolean = true,
)

/** [verdicts] has one entry per rule, in order: null for a rule that is off or never reached. */
class RouteAnswer(val verdicts: List<RouteVerdict?>, val decision: RouteDecision)

/**
 * Walks the rules the way the core does, as ConfigBuilder writes them, and says where a probe goes.
 * [matchDestination] matches sites and addresses with the core's own code: it takes a rule's
 * destination conditions as JSON and throws for one the core would refuse.
 */
class RouteTester(private val settings: RouteSettings, private val matchDestination: (json: String, host: String) -> Boolean) {

    fun test(rules: List<RuleEntity>, probe: RouteProbe): RouteAnswer {
        val verdicts = MutableList<RouteVerdict?>(rules.size) { null }
        fun answer(decision: RouteDecision) = RouteAnswer(verdicts, decision)
        val local = probe.isAddress && runCatching { matchDestination(PRIVATE, probe.host!!) }.getOrDefault(false)
        if (probe.port == 53) return answer(RouteDecision.Dns)
        if (settings.vpn && probe.packageName != null && settings.perAppPackages != null &&
            (probe.packageName in settings.perAppPackages) == settings.bypassApps
        ) {
            return answer(RouteDecision.AppOutsideVpn)
        }
        if (settings.vpn && settings.bypassLan && local) return answer(RouteDecision.LocalOutsideVpn)
        if (settings.globalMode) return answer(if (settings.bypassLan && local) RouteDecision.LocalNetwork else RouteDecision.GlobalMode)
        for ((index, rule) in rules.withIndex()) {
            // Outside the VPN the core can't tell which app a connection comes from, so app rules never match.
            if (!rule.enabled || (!settings.vpn && rule.packages.isNotEmpty())) continue
            val verdict = judge(rule, probe)
            verdicts[index] = verdict
            if (verdict == RouteVerdict.Catches) return answer(RouteDecision.Rule(index))
        }
        return answer(if (settings.bypassLanInCore && local) RouteDecision.LocalNetwork else RouteDecision.Proxy)
    }

    /** How [rule] alone treats [probe], whether or not it is on. */
    fun judge(rule: RuleEntity, probe: RouteProbe): RouteVerdict {
        // Custom JSON can replace or invert any condition below.
        if (rule.config.isNotBlank()) return RouteVerdict.Unknown(RouteUnknown.CustomJson)
        val open = mutableListOf<RouteCondition>()
        if (rule.packages.isNotEmpty()) {
            if (probe.packageName == null) {
                open += RouteCondition.Apps(rule.packages)
            } else if (probe.packageName !in rule.packages) {
                return RouteVerdict.Passes
            }
        }
        if (rule.port.isNotBlank()) {
            if (probe.port == null) {
                open += RouteCondition.Ports(rule.port)
            } else if (!portsContain(rule.port, probe.port)) {
                return RouteVerdict.Passes
            }
        }
        if (rule.network.isNotBlank()) {
            if (probe.network == null) {
                open += RouteCondition.Network(rule.network)
            } else if (probe.network != rule.network) {
                return RouteVerdict.Passes
            }
        }
        if (rule.protocol.isNotBlank()) open += RouteCondition.Protocols(rule.protocol.listByLineOrComma())
        if (rule.source.isNotBlank()) open += RouteCondition.Source(rule.source)
        if (rule.sourcePort.isNotBlank()) open += RouteCondition.SourcePorts(rule.sourcePort)

        val sites = rule.domains.listByLineOrComma()
        val addresses = rule.ip.listByLineOrComma()
        val downloaded = rule.ruleset.listByLineOrComma()
        var unknown: RouteUnknown? = null
        val host = probe.host
        if (sites.isNotEmpty() || addresses.isNotEmpty() || downloaded.isNotEmpty()) {
            if (host == null) {
                open += RouteCondition.Destination(sites, addresses, downloaded.size)
            } else if (!settings.listsLoaded && (sites + addresses).any { it.startsWith("geosite:") || (it.startsWith("geoip:") && it != "geoip:private") }) {
                unknown = RouteUnknown.ListsNotLoaded
            } else {
                val matched = try {
                    (sites.isNotEmpty() || addresses.isNotEmpty()) && matchDestination(destinationJson(sites, addresses), host)
                } catch (e: Exception) {
                    return RouteVerdict.Unknown(RouteUnknown.Broken(e.readableMessage))
                }
                when {
                    matched -> Unit
                    downloaded.isNotEmpty() -> unknown = RouteUnknown.DownloadedList
                    !probe.isAddress && addresses.isNotEmpty() -> open += RouteCondition.Address(addresses)
                    probe.isAddress && sites.isNotEmpty() -> open += RouteCondition.Site(sites)
                    else -> return RouteVerdict.Passes
                }
            }
        }
        return when {
            unknown != null -> RouteVerdict.Unknown(unknown)
            open.isNotEmpty() -> RouteVerdict.CatchesIf(open)
            else -> RouteVerdict.Catches
        }
    }

    companion object {
        private const val PRIVATE = """{"ip_is_private":true}"""

        /** A rule's destination conditions as JSON, made the way ConfigBuilder makes them for the core. */
        fun destinationJson(sites: List<String>, addresses: List<String>): String {
            val rule = SingBoxOptions.Rule_DefaultOptions().apply {
                if (sites.isNotEmpty()) makeSingBoxRule(sites, false)
                if (addresses.isNotEmpty()) makeSingBoxRule(addresses, true)
            }
            return SingBoxOptions.toJsonTree(rule).toString()
        }

        /** Whether [ports], written as a rule writes them (443, 1000:2000, :1024, 8000:), include [port]. */
        fun portsContain(ports: String, port: Int) = ports.listByLineOrComma().any {
            if (':' in it) {
                val (low, high) = it.split(':', limit = 2)
                port >= (low.toIntOrNull() ?: 0) && port <= (high.toIntOrNull() ?: 65535)
            } else {
                it.toIntOrNull() == port
            }
        }
    }
}

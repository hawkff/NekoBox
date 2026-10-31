package xyz.nekobyte.nekobox.fmt

import android.os.Build
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nekobyte.nekobox.*
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.SingBoxOptions.*
import xyz.nekobyte.nekobox.TrafficSniffing
import xyz.nekobyte.nekobox.bg.VpnService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyEntity.Companion.TYPE_CONFIG
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult.IndexEntity
import xyz.nekobyte.nekobox.fmt.amneziawg.AmneziaWGBean
import xyz.nekobyte.nekobox.fmt.amneziawg.buildSingBoxOutboundAmneziaWGBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import xyz.nekobyte.nekobox.fmt.internal.chainHops
import xyz.nekobyte.nekobox.fmt.juicity.JuicityBean
import xyz.nekobyte.nekobox.fmt.juicity.buildSingBoxOutboundJuicityBean
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean
import xyz.nekobyte.nekobox.fmt.shadowsocks.ShadowsocksBean
import xyz.nekobyte.nekobox.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import xyz.nekobyte.nekobox.fmt.shadowsocksr.ShadowsocksRBean
import xyz.nekobyte.nekobox.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import xyz.nekobyte.nekobox.fmt.snell.SnellBean
import xyz.nekobyte.nekobox.fmt.snell.buildSingBoxOutboundSnellBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.socks.buildSingBoxOutboundSocksBean
import xyz.nekobyte.nekobox.fmt.ssh.SSHBean
import xyz.nekobyte.nekobox.fmt.ssh.buildSingBoxOutboundSSHBean
import xyz.nekobyte.nekobox.fmt.tailscale.TailscaleBean
import xyz.nekobyte.nekobox.fmt.tailscale.buildSingBoxEndpointTailscaleBean
import xyz.nekobyte.nekobox.fmt.tuic.TuicBean
import xyz.nekobyte.nekobox.fmt.tuic.buildSingBoxOutboundTuicBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardDnsMode
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardProfileDns
import xyz.nekobyte.nekobox.fmt.wireguard.buildSingBoxOutboundWireguardBean
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardProfileDns
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.isIpAddress
import xyz.nekobyte.nekobox.ktx.mkPort
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher
import xyz.nekobyte.nekobox.ktx.unwrapIPV6Host
import xyz.nekobyte.nekobox.proxy.anytls.AnyTLSBean
import xyz.nekobyte.nekobox.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import xyz.nekobyte.nekobox.proxy.shadowtls.ShadowTLSBean
import xyz.nekobyte.nekobox.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import xyz.nekobyte.nekobox.utils.JavaUtil.gson
import xyz.nekobyte.nekobox.utils.PackageCache
import xyz.nekobyte.nekobox.utils.Telemetry
import xyz.nekobyte.nekobox.utils.Util
import xyz.nekobyte.nekobox.utils.listByLineOrComma
import java.net.IDN
import java.util.UUID

private fun sanitizeDnsEntry(value: String): String = value.filterNot { it.isISOControl() }.trim()

// Validate a hosts address token strictly enough for sing-box's netip-based
// parser: the app-wide isIpAddress() regex is looser (it allows IPv4 leading
// zeros and malformed IPv6 forms) and one bad value would fail the whole config
// load. Embedded-IPv4 IPv6 forms (::ffff:1.2.3.4) are not supported; write the
// plain IPv4 address instead. Returns the address, or null when not usable.
internal fun parseHostsAddress(token: String): String? {
    val value = token.unwrapIPV6Host()
    if (!value.isIpAddress()) return null
    if (value.contains(':')) {
        // Pure-hex IPv6 (the regex rejects embedded IPv4 forms). Reject the empty
        // groups Go's netip parser refuses but the app regex tolerates: more than
        // one "::" (a ":::" run counts twice via overlap), a bare leading or
        // trailing ":", and more than 7 explicit groups alongside "::" (without
        // "::" the regex already enforces exactly 8).
        if (value.indexOf("::") != value.lastIndexOf("::")) return null
        if (value.startsWith(":") && !value.startsWith("::")) return null
        if (value.endsWith(":") && !value.endsWith("::")) return null
        if (value.contains("::") && value.split(':').count { it.isNotEmpty() } > 7) return null
    } else {
        // IPv4: reject leading zeros, which Go's netip parser refuses.
        if (value.split('.').any { it.length > 1 && it.startsWith('0') }) return null
    }
    return value
}

internal fun parseHostsDomain(token: String): String? {
    var domain = sanitizeDnsEntry(token).removeSuffix(".")
    if (domain.isEmpty()) return null
    // Internationalized domains: convert to punycode, which is what arrives in
    // actual DNS queries and what sing-box matches against.
    if (domain.any { it.code >= 0x80 }) {
        domain = try {
            IDN.toASCII(domain)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    domain = domain.lowercase()
    if (domain.isEmpty() || domain.isIpAddress() || domain.length > 253) return null
    val labels = domain.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 }) return null
    // Underscore is permitted: it is common in DNS names (service labels) even
    // though it is invalid in strict hostnames.
    if (labels.any { label ->
            label.startsWith('-') ||
                label.endsWith('-') ||
                label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }
        }
    ) {
        return null
    }
    return domain
}

// Token separator for hosts entries: ASCII whitespace plus NBSP, which pasted web
// content often contains and which neither Java's \s (ASCII-only) nor the
// ISO-control sanitization covers.
private val hostsSeparator = "[\\s\u00A0]+".toRegex()

// Parse the user DNS hosts rewrite list: one "domain ip [ip ...]" entry per line,
// separated by any whitespace. Blank lines, comments (#) and malformed lines are
// ignored instead of failing the config build. Non-IP tokens after the domain are
// dropped, and IPv6 addresses may be written with or without brackets.
internal fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val tokens = line.split(hostsSeparator)
            .map { sanitizeDnsEntry(it) }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens.first().startsWith("#")) return@forEach
        val domain = parseHostsDomain(tokens.first()) ?: return@forEach
        val addresses = tokens.drop(1)
            .takeWhile { !it.startsWith("#") }
            .mapNotNull { parseHostsAddress(it) }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

// Extract the server hostname for a bean, mirroring the bypassDNSBeans logic
// (ConfigBean stores its address inside the parsed JSON "server" field). Falls back to
// bean.serverAddress when the JSON has no usable "server" so custom-resolver host mapping
// is preserved for those configs.
private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_NEBULA = "nebula"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

// Outbounds whose TLS handshake the core cannot split: QUIC carries its own, and naive refuses the option.
private val UNFRAGMENTED_TLS_TYPES = setOf("hysteria", "hysteria2", "tuic", "juicity", "naive")

// Mirrors libcore/box_include.go, except the legacy DNS type rejected by the core decoder.
private val coreOutboundTypes = setOf(
    "direct", "block", "selector", "urltest", "socks", "http", "shadowsocks", "shadowsocksr",
    "vmess", "trojan", "tor", "ssh", "shadowtls", "vless", "anytls", "snell",
    "tuic", "hysteria2", "juicity", "wireguard", "amneziawg",
)

// IANA STUN/TURN ports (plain and TLS) plus Google's 19302-19309 STUN/TURN range. Only
// the fallback for what the STUN sniffer cannot see: TURN over TCP or TLS.
val WEBRTC_STUN_PORTS = listOf(3478, 3479, 5349, 5350)
const val WEBRTC_STUN_PORT_RANGE = "19302:19309"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    // Per-port credentials for authenticated local SOCKS loopbacks of external
    // plugins (e.g. naive). Android does not isolate 127.0.0.1 per app, so an
    // unauthenticated plugin SOCKS listener could be reached by any local app and
    // leak the egress IP (issue #1166). The plugin listens with these creds and the
    // sing-box socks outbound dials with them.
    val localProxyCredentials: Map<Int, Pair<String, String>> = emptyMap(),
    // True when the selector group is driven by sing-box urltest instead of the user.
    val autoSelect: Boolean = false,
    // Tailscale profile id -> its endpoint in this config, for readiness waits and peer queries.
    val tailscaleEndpoints: Map<Long, TailscaleEndpoint> = emptyMap(),
    // Profile id -> the Tailscale profiles among its hops (group front/landing included).
    val profileTailscaleNodes: Map<Long, Set<Long>> = emptyMap(),
    // WireGuard/AmneziaWG profile id -> its outbounds. A profile that is a hop of several chains
    // runs once per chain, each instance with its own handshakes.
    val wireguardInstances: Map<Long, List<WireGuardInstance>> = emptyMap(),
    // The config joins the Nebula network; full custom configs run as written, without it.
    val nebula: Boolean = false,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)

    data class TailscaleEndpoint(val tag: String, val waitForExitNode: Boolean)

    // ownerProfileId: the profile whose chain built this outbound.
    data class WireGuardInstance(val tag: String, val ownerProfileId: Long)
}

// Extracted from buildConfig as pure, capture-free helpers.
// Behavior-preserving moves: same inputs -> same outputs.

// buildChain walks hops exit-first: index 0 is the hop that reaches the destination.
private fun resolveChainInternal(entity: ProxyEntity): MutableList<ProxyEntity> = chainHops(entity).asReversed().toMutableList()

private class BuildLookupCache {
    private val groups = HashMap<Long, ProxyGroup?>()
    private val proxies = HashMap<Long, ProxyEntity?>()

    fun group(groupId: Long): ProxyGroup? {
        if (groups.containsKey(groupId)) return groups[groupId]
        return ProfileDatabase.groupDao.getById(groupId).also { groups[groupId] = it }
    }

    fun proxy(proxyId: Long): ProxyEntity? {
        val cached = if (proxies.containsKey(proxyId)) {
            proxies[proxyId]
        } else {
            ProfileDatabase.proxyDao.getById(proxyId).also { proxies[proxyId] = it }
        }
        return cached?.copy()?.putBean(cached.requireBean().clone())
    }
}

private fun resolveChain(entity: ProxyEntity, lookupCache: BuildLookupCache): MutableList<ProxyEntity> {
    val thisGroup = lookupCache.group(entity.groupId)
    fun requiredHop(id: Long?) = id?.takeIf { it > 0 }?.let {
        requireNotNull(lookupCache.proxy(it)) {
            NekoBox.application.getString(R.string.chain_missing_hop, entity.displayName())
        }
    }
    val frontProxy = requiredHop(thisGroup?.frontProxy)
    val landingProxy = requiredHop(thisGroup?.landingProxy)

    // A proxy from a list of proxies keeps the list's first hop wherever it is dialed: as a group's first hop or
    // exit, a Mullvad SOCKS5 relay still goes through Mullvad's WireGuard. In this list's order, the last first.
    fun withListPath(hop: ProxyEntity): List<ProxyEntity> {
        val path = arrayListOf(hop)
        val seen = hashSetOf(entity.id, hop.id)
        var list = lookupCache.group(hop.groupId)?.takeIf { it.type == GroupType.PROXIES }
        while (list != null) {
            val first = requiredHop(list.frontProxy)?.takeIf { seen.add(it.id) } ?: break
            path += first
            list = lookupCache.group(first.groupId)?.takeIf { it.type == GroupType.PROXIES }
        }
        return path
    }

    val list = resolveChainInternal(entity)
    if (frontProxy != null) {
        list.addAll(withListPath(frontProxy))
    }
    if (landingProxy != null) {
        list.addAll(0, withListPath(landingProxy))
    }
    // A hop dialed twice would share one outbound tag and loop: the path is refused, naming the hop.
    val seen = HashSet<Long>()
    val twice = list.firstOrNull { !seen.add(it.id) }
    require(twice == null) { NekoBox.application.getString(R.string.chain_repeated_hop, entity.displayName(), twice?.displayName()) }
    return list
}

private fun genDomainStrategy(noAsIs: Boolean, ipv6Mode: Int): String = when {
    !noAsIs -> ""
    ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
    ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
    ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
    else -> "prefer_ipv4"
}

private fun autoDnsDomainStrategy(s: String, ipv6Mode: Int): String? {
    if (s.isNotEmpty()) {
        return s
    }
    return when (ipv6Mode) {
        IPv6Mode.DISABLE -> "ipv4_only"
        IPv6Mode.ENABLE -> "prefer_ipv4"
        IPv6Mode.PREFER -> "prefer_ipv6"
        IPv6Mode.ONLY -> "ipv6_only"
        else -> null
    }
}

fun buildConfig(proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false, reportHosts: List<String> = Telemetry.reportHosts()): ConfigBuildResult {
    require(proxy.canBuild()) {
        NekoBox.application.getString(if (proxy.isHysteria1()) R.string.hysteria1_retired else R.string.profile_unsupported)
    }
    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config!!,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L,
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    // Per-port credentials for authenticated external-plugin SOCKS loopbacks (#1166).
    val localProxyCredentials = HashMap<Int, Pair<String, String>>()
    // The Nebula network runs beside whatever profile is selected. Exports leave it out: its config
    // holds this device's key.
    val joinNebula = !forTest && !forExport && DataStore.nebulaEnabled && DataStore.nebulaConfig.isNotBlank()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_MIXED, TAG_PROXY)
    if (joinNebula) readableNames += TAG_NEBULA
    val lookupCache = BuildLookupCache()
    val group = lookupCache.group(proxy.groupId)

    fun readableTag(name_: String, changes: ChainBuildChanges): String {
        var name = name_
        var count = 0
        while (!changes.add(readableNames, name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> = resolveChain(this, lookupCache)

    val extraRules = if (forTest) listOf() else ProfileDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) {
            mapOf()
        } else {
            ProfileDatabase.proxyDao.getEntities(
                extraRules.mapNotNull { rule ->
                    rule.outbound.takeIf { it > 0 && it != proxy.id }
                }.toHashSet().toList(),
            ).associateBy { it.id }
        }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    // A Tailscale node has one saved identity, so one config can run one instance of a profile.
    // Every selector member would get its own copy of a Tailscale landing proxy (each with a
    // different detour).
    val tailscaleProfiles = hashSetOf<Long>()
    val tailscaleEndpoints = HashMap<Long, ConfigBuildResult.TailscaleEndpoint>()
    val profileTailscaleNodes = HashMap<Long, Set<Long>>()
    val wireguardInstances = LinkedHashMap<Long, MutableList<ConfigBuildResult.WireGuardInstance>>()
    // Profile DNS of each WireGuard/AmneziaWG outbound, and whether a selected chain built it.
    val profileDnsList = mutableListOf<Pair<Boolean, WireGuardProfileDns>>()
    if (buildSelector) {
        require(group.landingProxy?.let(lookupCache::proxy)?.requireBean() !is TailscaleBean) {
            NekoBox.application.getString(R.string.tailscale_selector_landing)
        }
    }
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    // Per-subscription custom resolver (#71). Maps a subscription group's resolver to the
    // exact server hostnames imported from that subscription, so the resolver is used ONLY
    // for those domains.
    val perGroupResolver = HashMap<Long, String>() // groupId -> resolver address
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>() // groupId -> server hosts
    // host -> set of resolver addresses requested for it (across all custom-resolver groups).
    // A host is only routed to a custom resolver when it maps to exactly one resolver; hosts
    // claimed by multiple subscriptions with different resolvers are ambiguous and fall back
    // to the global DNS.
    val hostResolvers = HashMap<String, MutableSet<String>>()
    // Every final-hop server host that does NOT belong to a custom-resolver subscription.
    // If a host appears here too, it is shared with a normal profile, so we must NOT hijack
    // it onto a per-subscription resolver (keep it on the global direct path).
    val nonCustomFinalHosts = hashSetOf<String>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    // Exported configs run elsewhere: keep their listener on loopback and leave this device's
    // inbound credentials out of them.
    val bind = if (!forTest && !forExport && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    // Whether the local mixed (SOCKS/HTTP) inbound is present in the final config.
    // In VPN/TUN mode it is omitted unless the user opts in (requireProxyInVPN), shares the
    // connection with other devices (allowAccess) or the system HTTP proxy needs it
    // (appendHttpProxy). See issue #1197 / PR #1154.
    val keepMixedInbound = !forTest && (
        !isVPN ||
            DataStore.requireProxyInVPN ||
            DataStore.allowAccess ||
            (DataStore.appendHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        )
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> sanitizeDnsEntry(dns).takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts = if (forTest) emptyMap() else parseDnsHosts(DataStore.dnsHosts)
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val sniffing = DataStore.trafficSniffing
    val needSniff = sniffing != TrafficSniffing.OFF
    // The inbounds apps reach. Plugin mapping inbounds are not among them: they forward a plugin's
    // own connection to the user's server, which must not be sniffed and sent somewhere else.
    val appInbounds = listOfNotNull("tun-in".takeIf { isVPN && !forTest }, TAG_MIXED.takeIf { keepMixedInbound })
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String = genDomainStrategy(noAsIs, ipv6Mode)

    val routeRules = mutableListOf<Rule>()
    val routeRuleSets = mutableListOf<RuleSet>()
    val dnsServers = mutableListOf<DNSServerOptions>()
    val dnsRules = mutableListOf<DNSRule_DefaultOptions>()
    val outboundHosts = linkedMapOf<SingBoxOption, String?>()
    val mappingResolvers = linkedMapOf<Rule_DefaultOptions, String?>()
    val directStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"), ipv6Mode)
    val remoteStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"), ipv6Mode)

    return MyOptions().apply {
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    store_fakeip = true
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        // Android before 9 lacks roots that current servers chain to, the default direct DNS
        // server's among them, so the core uses its own copy of Mozilla's list there. Exported
        // configs run on other devices.
        if (!forExport && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _hack_config_map["certificate"] = mapOf("store" to "mozilla")
        }

        val dns = DNSOptions().apply {
            servers = dnsServers
            rules = dnsRules
            strategy = when (val selected = if (forTest) directStrategy else remoteStrategy) {
                "ipv4_only" -> "prefer_ipv4"
                "ipv6_only" -> "prefer_ipv6"
                else -> selected
            }
        }.also { dns = it }

        val inbounds = mutableListOf<Inbound>().also { inbounds = it }

        if (!forTest) {
            if (isVPN) {
                inbounds.add(
                    Inbound_TunOptions().apply {
                        type = "tun"
                        tag = "tun-in"
                        interface_name = "tun0"
                        stack = when (DataStore.tunImplementation) {
                            TunImplementation.GVISOR -> "gvisor"
                            TunImplementation.SYSTEM -> "system"
                            else -> "mixed"
                        }
                        endpoint_independent_nat = true
                        mtu = DataStore.mtu
                        auto_route = true
                        // For exported configs: the core reads it only when it sets the routes up, never with the VPN service's descriptor.
                        strict_route = true
                        when (ipv6Mode) {
                            IPv6Mode.DISABLE -> {
                                address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                            }

                            IPv6Mode.ONLY -> {
                                address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                            }

                            else -> {
                                address = listOf(
                                    VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                                    VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                                )
                            }
                        }
                    },
                )
            }
            // Local mixed (SOCKS/HTTP) inbound. In VPN/TUN mode this port is usually
            // unnecessary and only widens the local attack surface (issue #1197), so it
            // is omitted unless the user opts in (requireProxyInVPN) or the system HTTP
            // proxy needs it (appendHttpProxy routes the device proxy through this port).
            if (keepMixedInbound) {
                inbounds.add(
                    Inbound_MixedOptions().apply {
                        type = "mixed"
                        tag = TAG_MIXED
                        listen = bind
                        listen_port = DataStore.mixedPort
                        if (DataStore.mixedInboundNeedsAuth && !forExport) {
                            users = buildList {
                                add(
                                    User().also { u ->
                                        u.username = Key.MIXED_USERNAME
                                        u.password = DataStore.mixedSecret
                                    },
                                )
                                // Other devices get their own credential, so regenerating it
                                // revokes their access without touching the app's own loopback user.
                                if (DataStore.allowAccess) {
                                    add(
                                        User().also { u ->
                                            u.username = Key.SHARE_USERNAME
                                            u.password = DataStore.shareSecret
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        val outbounds = mutableListOf<SingBoxOption>().also { outbounds = it }
        // sing-box endpoints (Tailscale) are outbounds with their own top-level array; they
        // keep the chain tag/detour wiring and are referenced by tag like any outbound.
        val endpointList = mutableListOf<SingBoxOption>()

        // init routing object
        val route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            // Connection diagnostics shows the app behind each connection; the owner lookup is a
            // system call per connection, so it is only paid when the user opted in.
            if (!forTest && DataStore.connectionDiagnostics) find_process = true
            rules = routeRules
            rule_set = routeRuleSets
            default_domain_resolver = dnsResolver("dns-direct", directStrategy)

            // Sniffing and destination resolution are rule actions, first so that every rule after
            // them sees their result. Sniffing reads the site name from a connection's first bytes,
            // so domain rules match connections an app opened by address. Connecting by name also
            // hands that name to the outbound, which dials it, so a proxy server resolves it itself.
            if (needSniff && appInbounds.isNotEmpty()) {
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        inbound = appInbounds
                        action = "sniff"
                        if (sniffing == TrafficSniffing.CONNECT_BY_NAME) override_destination = true
                    },
                )
            }
            // WebRTC leak protection: reject STUN, which ICE uses to discover server-reflexive
            // and relay candidates, so no address is learned through WebRTC. The sniffer catches
            // STUN on any UDP port; the port rule covers TURN over TCP/TLS. Only meaningful in
            // VPN mode, where every UDP packet of a tunneled app passes through here. A stun-only
            // sniff is added when general sniffing is off; sing-box skips TCP streams for it.
            // Scoped to the app-facing inbounds: the plugin mapping inbounds forward to the
            // user's own server, which may itself listen on one of these ports.
            if (!forTest && isVPN && DataStore.webrtcLeakProtection) {
                if (!needSniff) {
                    routeRules.add(
                        Rule_DefaultOptions().apply {
                            inbound = appInbounds
                            action = "sniff"
                            sniffer = listOf("stun")
                        },
                    )
                }
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        inbound = appInbounds
                        protocol = listOf("stun")
                        action = "reject"
                    },
                )
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        inbound = appInbounds
                        port = WEBRTC_STUN_PORTS
                        port_range = listOf(WEBRTC_STUN_PORT_RANGE)
                        action = "reject"
                    },
                )
            }
            // After sniffing, so a name sniffing connects by is resolved on the device too, and IP rules
            // match it: resolve destination means names resolve here, not at the exit server.
            val resolveStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (resolveStrategy.isNotEmpty()) {
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        action = "resolve"
                        strategy = resolveStrategy
                    },
                )
            }
        }.also { route = it }

        // returns outbound tag; selected: the main profile or a selector member, not a rule outbound
        @Suppress("UNCHECKED_CAST")
        fun buildChain(chainId: Long, entity: ProxyEntity, selected: Boolean, changes: ChainBuildChanges): String {
            val profileList = entity.resolveChain()
            // Validate before touching the shared lists: a chain skipped halfway would leave
            // outbounds with a detour to a tag that is never created. The first dialed hop is
            // shared through globalOutbounds, so a Tailscale profile already built there is reused.
            val chainTailscaleProfiles = mutableListOf<Long>()
            val chainTailscaleEndpoints = HashMap<Long, ConfigBuildResult.TailscaleEndpoint>()
            profileList.forEachIndexed { index, hop ->
                if (hop.requireBean() !is TailscaleBean) return@forEachIndexed
                if (index == profileList.lastIndex && globalOutbounds.containsKey(hop.id)) return@forEachIndexed
                require(hop.id !in tailscaleProfiles && hop.id !in chainTailscaleProfiles) {
                    NekoBox.application.getString(R.string.tailscale_single_use, hop.displayName())
                }
                chainTailscaleProfiles += hop.id
            }
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()
            val chainWireGuard = mutableListOf<Pair<Long, ConfigBuildResult.WireGuardInstance>>()
            val chainProfileDns = mutableListOf<WireGuardProfileDns>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                require(proxyEntity.canBuild()) { NekoBox.application.getString(R.string.profile_unsupported) }

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    changes.add(bypassDNSBeans, proxyEntity.requireBean())

                    // Per-subscription custom resolver (#71). The resolver belongs to the
                    // subscription that owns this chain (entity.groupId), not necessarily the
                    // last hop's group (a front/landing proxy may come from another group).
                    // We record the server hostnames of the chain hops that belong to that
                    // same subscription, so the resolver is scoped to its own servers only.
                    // Tests and lookups use it too, so they reach the address the connection would.
                    val ownerGid = entity.groupId
                    val ownerGroup = lookupCache.group(ownerGid)
                    val resolver = ownerGroup
                        ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                        ?.subscription?.customDnsResolver
                        ?.let { sanitizeDnsEntry(it) }
                        ?.takeIf { it.isNotBlank() }

                    if (resolver != null) {
                        // Record hosts of every chain hop owned by this subscription.
                        profileList.forEach { hop ->
                            if (hop.groupId == ownerGid) {
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    changes.put(perGroupResolver, ownerGid, resolver)
                                    changes.add(perGroupServerHosts, ownerGid, host)
                                    changes.add(hostResolvers, host, resolver)
                                }
                            } else {
                                // hop from another (non-custom) group sharing this chain
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    changes.add(nonCustomFinalHosts, host)
                                }
                            }
                        }
                    } else {
                        // No custom resolver for this chain: none of its hosts (any hop)
                        // must be hijacked by another subscription's resolver.
                        profileList.forEach { hop ->
                            val host = serverHostOf(hop.requireBean())
                            if (host != null && !host.isIpAddress()) {
                                changes.add(nonCustomFinalHosts, host)
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName(), changes)
                }

                // Resolve reuse before linking the previous hop: a standalone profile may
                // already own a readable tag rather than the provisional global tag.
                val reusedGlobalTag = if (needGlobal) globalOutbounds[proxyEntity.id] else null
                if (reusedGlobalTag != null) tagOut = reusedGlobalTag

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        routeRules.add(
                            Rule_DefaultOptions().apply {
                                inbound = listOf(pastInboundTag)
                                outbound = tagOut
                            },
                        )
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    if (reusedGlobalTag != null) return@forEachIndexed
                    changes.put(globalOutbounds, proxyEntity.id, tagOut)
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                        // Authenticate the local SOCKS loopback for plugins that support
                        // it (naive), so other apps on the device can't use this open
                        // 127.0.0.1 port to leak the egress IP (#1166). The plugin is
                        // configured to listen with the same generated credentials.
                        // Skip for export: the exported naive config (ProxyEntity.
                        // buildNaiveConfig without creds) would otherwise mismatch and
                        // produce a broken standalone config.
                        if ((bean is NaiveBean || bean is MieruBean) && !forExport) {
                            val user = "neko"
                            val pass = UUID.randomUUID().toString().replace("-", "")
                            changes.put(localProxyCredentials, localPort, user to pass)
                            username = user
                            password = pass
                        }
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean ->
                            buildSingBoxOutboundWireguardBean(bean)

                        is AmneziaWGBean ->
                            buildSingBoxOutboundAmneziaWGBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        is TailscaleBean -> {
                            chainTailscaleEndpoints[proxyEntity.id] =
                                ConfigBuildResult.TailscaleEndpoint(tagOut, bean.exitNode!!.isNotBlank())
                            buildSingBoxEndpointTailscaleBean(bean, proxyEntity.id, proxyEntity.uuid)
                        }

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj?.enabled == true) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    // The first hop's TLS handshake is the one a firewall sees. The core splits it into TCP
                    // segments, REALITY's too.
                    if (needGlobal && DataStore.enableTLSFragment) {
                        val tree = SingBoxOptions.toJsonTree(currentOutbound).asJsonObject
                        val enabled = tree.get("tls")?.takeIf { it.isJsonObject }?.asJsonObject?.get("enabled")
                        val tlsEnabled = enabled?.isJsonPrimitive == true &&
                            enabled.asJsonPrimitive.isBoolean &&
                            enabled.asBoolean
                        if (tlsEnabled && tree.get("type")?.asString !in UNFRAGMENTED_TLS_TYPES) {
                            currentOutbound._hack_config_map["tls"] = mapOf("fragment" to true)
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // Keep chain server lookups off the proxy's DNS path.
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress!!.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    changes.put(outboundHosts, this, serverHostOf(bean))

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }
                if (bean is WireGuardBean || bean is AmneziaWGBean) {
                    chainWireGuard += proxyEntity.id to ConfigBuildResult.WireGuardInstance(tagOut, entity.id)
                    if (!forTest) bean.wireGuardProfileDns(proxyEntity.id, tagOut)?.let { chainProfileDns += it }
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort!!
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    val mappingPort = mkPort()
                    bean.finalAddress = LOCALHOST
                    bean.finalPort = mappingPort

                    inbounds.add(
                        Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = checkNotNull(tag)

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                routeRules.add(
                                    Rule_DefaultOptions().apply {
                                        inbound = listOf(pastInboundTag)
                                        action = "resolve"
                                        strategy = if (forTest) null else defaultServerDomainStrategy
                                        changes.put(mappingResolvers, this, serverHostOf(bean))
                                    },
                                )
                                routeRules.add(
                                    Rule_DefaultOptions().apply {
                                        inbound = listOf(pastInboundTag)
                                        outbound = TAG_DIRECT
                                    },
                                )
                            }
                        },
                    )
                }

                if (currentOutbound is Endpoint) endpointList.add(currentOutbound) else outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            // Parse custom JSON before committing the chain, so malformed rule targets can be blocked.
            chainOutbounds.forEach { outbound ->
                val json = SingBoxOptions.toJsonTree(outbound)
                fun stringField(key: String) = json[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                val port = json["server_port"]
                val types = if (outbound is Endpoint) setOf("wireguard", "tailscale") else coreOutboundTypes
                require(
                    stringField("type") in types &&
                        stringField("tag") == outbound._hack_config_map["tag"] &&
                        (!json.has("server") || !stringField("server").isNullOrBlank()) &&
                        (
                            port == null || (
                                port.isJsonPrimitive && port.asJsonPrimitive.isNumber &&
                                    port.asString.toIntOrNull()?.let { it in 1..65535 } == true
                                )
                            ),
                ) { NekoBox.application.getString(R.string.route_proxy_invalid, entity.displayName()) }
            }
            trafficMap[chainTagOut] = chainTrafficSet.toList()
            // Reserve only for a chain that built completely; a hop that fails later releases them.
            tailscaleProfiles += chainTailscaleProfiles
            tailscaleEndpoints += chainTailscaleEndpoints
            chainWireGuard.forEach { (profileId, instance) -> wireguardInstances.getOrPut(profileId) { mutableListOf() } += instance }
            chainProfileDns.forEach { profileDnsList += selected to it }
            profileList.filter { it.requireBean() is TailscaleBean }.map { it.id }.toSet()
                .takeIf { it.isNotEmpty() }?.let { profileTailscaleNodes[entity.id] = it }
            return chainTagOut
        }

        // A broken selected profile fails the build. Rules targeting skipped profiles become blocks.
        fun buildChainOrSkip(chainId: Long, entity: ProxyEntity, selected: Boolean): String? {
            // buildChain appends to these as it goes; a hop that fails halfway must not leave an
            // outbound whose detour points at a tag that is never created, or a global tag for it.
            val appended = listOf(outbounds, endpointList, inbounds, routeRules, externalIndexMap, domainListDNSDirectForce).map { it to it.size }
            val changes = ChainBuildChanges()
            return try {
                buildChain(chainId, entity, selected, changes)
            } catch (e: Exception) {
                if (entity.id == proxy.id || e is kotlinx.coroutines.CancellationException) throw e
                for ((list, size) in appended) list.subList(size, list.size).clear()
                changes.rollback()
                if (selected) {
                    runOnMainDispatcher { Toast.makeText(NekoBox.application, e.readableMessage, Toast.LENGTH_LONG).show() }
                } else {
                    // Exception messages can contain credentials; the class and source location do not.
                    Logs.w("Proxy ${entity.id} could not be built: ${e.javaClass.name} at ${e.stackTrace.firstOrNull()}")
                }
                null
            }
        }

        // build outbounds
        if (buildSelector) {
            val list = ProfileDatabase.proxyDao.getByGroup(group.id).filter { it.canBuild() }
            list.forEach { member ->
                buildChainOrSkip(member.id, member, true)?.let { tagMap[member.id] = it }
            }
            val memberTags = tagMap.values.toList()
            outbounds.add(
                0,
                if (group.autoSelect) {
                    // sing-box urltest does the health checks, failover and recovery; its defaults
                    // (3 min probe interval, 50 ms tolerance, 30 min idle timeout) already limit
                    // probing and stop the group flapping between near-equal members.
                    Outbound_URLTestOptions().apply {
                        type = "urltest"
                        tag = TAG_PROXY
                        url = DataStore.connectionTestURL
                        this.outbounds = memberTags
                    }
                } else {
                    Outbound_SelectorOptions().apply {
                        type = "selector"
                        tag = TAG_PROXY
                        default_ = tagMap[proxy.id]
                        this.outbounds = memberTags
                    }
                },
            )
        } else {
            val mainTag = buildChain(0, proxy, true, ChainBuildChanges())
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            if (key !in tagMap) buildChainOrSkip(key, p, false)?.let { tagMap[key] = it }
        }

        // Profile DNS for all queries follows the selected profile, which a selector can switch
        // without a rebuild. Listed domains apply wherever their profile carries traffic: Global
        // mode ignores the routing rules, so a profile built only for a rule stays out. Ambiguity
        // fails the build instead of depending on build order.
        val rulesApply = forTest || !DataStore.globalMode
        val activeProfileDns = profileDnsList.filter { (selected, _) -> selected || rulesApply }
        val selectedProfileDns = activeProfileDns.filter { (selected, dns) -> selected && dns.mode == WireGuardDnsMode.ALL }
            .map { it.second }.distinctBy { it.profileId }
        if (buildSelector) {
            selectedProfileDns.firstOrNull()?.let {
                throw IllegalArgumentException(NekoBox.application.getString(R.string.wireguard_dns_selector, it.name))
            }
        }
        require(selectedProfileDns.size <= 1) {
            NekoBox.application.getString(R.string.wireguard_dns_chain_conflict, selectedProfileDns[0].name, selectedProfileDns[1].name)
        }
        val domainProfileDns = activeProfileDns.map { it.second }.filter { it.mode == WireGuardDnsMode.DOMAINS }.distinctBy { it.profileId }
        domainProfileDns.forEachIndexed { index, first ->
            for (second in domainProfileDns.drop(index + 1)) {
                val shared = first.domains.firstOrNull { a -> second.domains.any { b -> a == b || a.endsWith(".$b") || b.endsWith(".$a") } }
                require(shared == null) {
                    NekoBox.application.getString(R.string.wireguard_dns_domain_conflict, first.name, second.name, shared)
                }
            }
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY
        // Endpoints are not candidates for the core's implicit first-outbound default.
        route.final_ = mainProxyTag

        // Reports go through the proxy, and their names through its DNS, whatever the rules say: a host
        // seen on the local network would tell that this app runs.
        if (!forTest && !forExport && reportHosts.isNotEmpty()) {
            routeRules.add(
                Rule_DefaultOptions().apply {
                    domain = reportHosts
                    outbound = mainProxyTag
                },
            )
            dnsRules.add(
                DNSRule_DefaultOptions().apply {
                    domain = reportHosts
                    server = "dns-remote"
                },
            )
        }

        // Nebula's overlay and unsafe-route networks are its preferred routes.
        if (joinNebula) {
            endpointList.add(
                Endpoint_NebulaOptions().apply {
                    type = "nebula"
                    _hack_config_map["tag"] = TAG_NEBULA
                    config = DataStore.nebulaConfig
                },
            )
        }

        // Tailnet and Nebula destinations (peer addresses, advertised subnets, MagicDNS names) go
        // to the endpoint that knows them, even when another outbound is the final one. The exit
        // node's default route is not a preferred route, so ordinary traffic is unaffected. Added
        // after the user's rules so an explicit block or detour of such an address still wins.
        val endpointTags = endpointList.map { it._hack_config_map["tag"] as String }
        val tailscaleTags = endpointList.filterIsInstance<Endpoint_TailscaleOptions>().map { it._hack_config_map["tag"] as String }
        fun addTailnetRouteRules() {
            endpointTags.forEach { tag ->
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        _hack_config_map["preferred_by"] = listOf(tag)
                        outbound = tag
                    },
                )
            }
        }

        // check global mode before applying user rules
        if (!forTest && DataStore.globalMode) {
            // rule handling in global mode
            addTailnetRouteRules()

            // bypass internal networks (if enabled)
            if (DataStore.bypassLan) {
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        ip_cidr = listOf(
                            "224.0.0.0/3",
                            "172.16.0.0/12",
                            "127.0.0.0/8",
                            "10.0.0.0/8",
                            "192.168.0.0/16",
                            "169.254.0.0/16",
                            "::1/128",
                            "fc00::/7",
                            "fe80::/10",
                        )
                        outbound = TAG_DIRECT
                    },
                )
            }

            routeRules.add(
                Rule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    outbound = mainProxyTag
                },
            )

            if (keepMixedInbound) {
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        inbound = listOf(TAG_MIXED)
                        outbound = mainProxyTag
                    },
                )
            }
        } else {
            // apply user rules
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            NekoBox.application,
                            NekoBox.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                // Dropping an unresolved app condition would turn an app rule into a broader rule.
                if (rule.packages.isNotEmpty() && uidList.isEmpty()) {
                    runOnMainDispatcher {
                        Toast.makeText(
                            NekoBox.application,
                            NekoBox.application.getString(R.string.route_apps_unavailable, rule.displayName()),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    continue
                }
                val targetTag = when (rule.outbound) {
                    0L -> mainProxyTag
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> tagMap[rule.outbound]
                }
                var unavailable = targetTag == null
                val ruleDnsTag = "dns-rule-${rule.id}"
                if (rule.dnsThroughOutbound && !unavailable) {
                    try {
                        rule.validateDnsRouting()
                        dnsServers += proxyRuleDnsServer(
                            rule.dnsServer.trim().ifEmpty { remoteDns.firstOrNull().orEmpty() },
                            ruleDnsTag,
                            checkNotNull(targetTag),
                        )
                        dns.reverse_mapping = true
                    } catch (_: IllegalArgumentException) {
                        unavailable = true
                    }
                }
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }

                    rule_set?.let { generateRuleSet(it, ruleSets) }

                    // store ruleset tag and type info
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()

                    // handle remote ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        val combinedRuleSets = rule_set.orEmpty().toMutableList()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)

                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)

                            rulesetTags.add(Pair(tag, isIPRuleset))

                            combinedRuleSets.add(tag)
                        }
                        rule_set = combinedRuleSets.takeIf { it.isNotEmpty() }
                    }

                    if (rule.port.isNotBlank()) {
                        val ports = mutableListOf<Int>().also { port = it }
                        val ranges = mutableListOf<String>().also { port_range = it }
                        rule.port.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ranges.add(it)
                            } else {
                                it.toIntOrNull()?.let { value -> ports.add(value) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        val ports = mutableListOf<Int>().also { source_port = it }
                        val ranges = mutableListOf<String>().also { source_port_range = it }
                        rule.sourcePort.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ranges.add(it)
                            } else {
                                it.toIntOrNull()?.let { value -> ports.add(value) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    val dnsRule = DNSRule_DefaultOptions().apply {
                        domainList?.let { makeSingBoxRule(it) }
                        rule_set = (rule_set.orEmpty() + rulesetTags.filterNot { it.second }.map { it.first })
                            .distinct().takeIf { it.isNotEmpty() }
                    }
                    val hasDomainCriteria = !dnsRule.checkEmpty()
                    val hasConnectionCriteria = rule.port.isNotBlank() || rule.sourcePort.isNotBlank() ||
                        rule.network.isNotBlank() || rule.source.isNotBlank() ||
                        rule.protocol.isNotBlank() || rule.config.isNotBlank()
                    val isAppOnlyDns = uidList.isNotEmpty() &&
                        rule.domains.isBlank() && rule.ip.isBlank() && rule.ruleset.listByLineOrComma().isEmpty()

                    // DNS cannot infer the later connection's ports, network or protocol.
                    // Custom JSON may invert or replace criteria. Skip these projections rather
                    // than dropping an AND condition. Domain/IP alternatives stay domain-scoped.
                    if ((hasDomainCriteria || isAppOnlyDns) && !hasConnectionCriteria &&
                        (rule.packages.isEmpty() || uidList.isNotEmpty())
                    ) {
                        if (uidList.isNotEmpty()) dnsRule.user_id = uidList
                        when {
                            unavailable -> {
                                dnsRule.action = "predefined"
                                dnsRule.rcode = "REFUSED"
                            }

                            rule.dnsThroughOutbound -> dnsRule.server = ruleDnsTag

                            rule.outbound == -1L -> dnsRule.server = "dns-direct"

                            rule.outbound == 0L -> if (useFakeDns) {
                                dnsRule.server = "dns-fake"
                                dnsRule.inbound = listOf("tun-in")
                                dnsRule.query_type = listOf("A", "AAAA")
                            } else {
                                dnsRule.server = "dns-remote"
                            }

                            rule.outbound == -2L -> {
                                dnsRule.action = "predefined"
                                dnsRule.rcode = "NOERROR"
                            }
                        }
                        if ((enableDnsRouting || rule.dnsThroughOutbound || unavailable) &&
                            (dnsRule.server != null || dnsRule.action != null)
                        ) {
                            userDNSRuleList += dnsRule
                        }
                    }

                    outbound = if (unavailable) TAG_BLOCK else targetTag

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (unavailable) {
                        runOnMainDispatcher {
                            Toast.makeText(
                                NekoBox.application,
                                NekoBox.application.getString(R.string.route_proxy_unavailable, rule.displayName()),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        // Preserve custom match criteria, but never let a custom action undo the guard.
                        ruleObj._hack_custom_config = SingBoxOptions.toJsonTree(ruleObj).apply {
                            remove("outbound")
                            addProperty("action", "reject")
                        }.toString()
                    }
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    routeRules.add(ruleObj)
                    routeRuleSets.addAll(ruleSets)
                }
            }
            addTailnetRouteRules()
        }

        // deduplicate rule_set tags
        route.rule_set = routeRuleSets.distinctBy { it.tag }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(
                Outbound().apply {
                    tag = freedom
                    type = "direct"
                },
            )
        }

        // Per-subscription custom resolver (#71): a host is eligible for a dedicated resolver
        // only when it maps to exactly one resolver AND is not shared with any non-custom
        // profile. Otherwise it stays on the global direct DNS path.
        fun isExclusiveCustomHost(host: String): Boolean = hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr!!.isIpAddress()) {
                // Servers handled by a dedicated per-subscription resolver are kept out of the
                // global direct-DNS force list to avoid conflicting routing (#71). Only do this
                // for hosts exclusive to a single custom-resolver subscription.
                if (!isExclusiveCustomHost(serverAddr!!)) {
                    domainListDNSDirectForce.add("full:$serverAddr")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dnsServers.add(dnsServer("local", "dns-local", "dns-local"))
        dnsServers.add(
            dnsServer(
                directDNS.firstOrNull() ?: throw Exception("No direct DNS, check your settings!"),
                "dns-direct",
                "dns-local",
            ),
        )
        // Typed DNS servers dial directly unless an explicit proxy detour is set.
        if (!forTest) {
            val profileDns = selectedProfileDns.singleOrNull()
            dnsServers.add(
                if (profileDns != null) {
                    dnsServer(profileDns.server, "dns-remote", "dns-direct", profileDns.tag)
                } else {
                    dnsServer(
                        remoteDns.firstOrNull() ?: throw Exception("No remote DNS, check your settings!"),
                        "dns-remote",
                        "dns-direct",
                        mainProxyTag,
                    )
                },
            )
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        userDNSRuleList.forEach {
            if (!it.checkEmpty()) dnsRules.add(it)
        }

        // Domains listed for a WireGuard profile's DNS, behind the user's own DNS rules.
        domainProfileDns.forEach { profileDns ->
            val serverTag = "dns-wg-${profileDns.profileId}"
            dnsServers.add(dnsServer(profileDns.server, serverTag, "dns-direct", profileDns.tag))
            dnsRules.add(
                DNSRule_DefaultOptions().apply {
                    domain_suffix = profileDns.domains
                    server = serverTag
                },
            )
        }

        // MagicDNS is also needed by standalone probes. Keep public-name fallback on
        // dns-direct for tests, and service user rules ahead of the preferred rules.
        tailscaleTags.forEachIndexed { index, tag ->
            val serverTag = "dns-tailscale-$index"
            dnsServers.add(
                DNSServerOptions().apply {
                    type = "tailscale"
                    this.tag = serverTag
                    _hack_config_map["endpoint"] = tag
                    _hack_config_map["accept_search_domain"] = true
                },
            )
            dnsRules.add(
                DNSRule_DefaultOptions().apply {
                    _hack_config_map["preferred_by"] = listOf(serverTag)
                    server = serverTag
                },
            )
        }

        if (!forTest) {
            // DNS on port 53 goes to the core's resolver before anything reads the connection. DNS on
            // other ports is left alone: sniffing would find it too, but also mDNS and LLMNR queries,
            // whose local names do not belong at the resolver.
            routeRules.add(
                0,
                Rule_DefaultOptions().apply {
                    port = listOf(53)
                    action = "hijack-dns"
                },
            )
            if (DataStore.bypassLanInCore) {
                routeRules.add(
                    Rule_DefaultOptions().apply {
                        outbound = TAG_BYPASS
                        ip_is_private = true
                    },
                )
            }
            // Multicast that no rule above sent elsewhere is refused: it only works on the local
            // network, never through a proxy. Matched by destination alone; the core requires a rule's
            // source and destination ranges together, and no packet comes from a multicast address.
            routeRules.add(
                Rule_DefaultOptions().apply {
                    ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                    action = "reject"
                },
            )
            // FakeDNS obj
            if (useFakeDns) {
                dnsServers.add(
                    DNSServerOptions().apply {
                        type = "fakeip"
                        tag = "dns-fake"
                        inet4_range = "198.18.0.0/15"
                        inet6_range = "fc00::/18"
                    },
                )
                dnsRules.add(
                    DNSRule_DefaultOptions().apply {
                        inbound = listOf("tun-in")
                        server = "dns-fake"
                        disable_cache = true
                        query_type = listOf("A", "AAAA")
                    },
                )
            }
            // User DNS hosts rewrite: a hosts-type server answering A/AAAA from the
            // predefined domain -> IP map. Scope the rule to configured domains only;
            // otherwise sing-box's hosts transport may also consult implicit hosts-file
            // data. Do not use ip_accept_any here: if the user maps only A records,
            // an AAAA query should produce no answer instead of falling through to
            // FakeDNS or remote DNS and bypassing the rewrite.
            // Scoping to A/AAAA keeps other record types (TXT, MX, HTTPS/SVCB) on
            // their normal resolution path, mirroring hosts-file semantics; SVCB
            // answers may therefore still carry upstream address hints.
            // Inserted below the loopback/force-bypass/per-subscription rules added
            // after it, so proxy bootstrap resolution is never hijacked by user
            // entries, and above the user DNS routing rules, so the rewrite wins
            // for its configured domains.
            if (dnsHosts.isNotEmpty()) {
                dnsServers.add(
                    DNSServerOptions().apply {
                        tag = TAG_DNS_HOSTS
                        _hack_config_map["type"] = "hosts"
                        _hack_config_map["predefined"] = dnsHosts
                    },
                )
                dnsRules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(dnsHosts.keys.map { "full:$it" })
                        query_type = listOf("A", "AAAA")
                        server = TAG_DNS_HOSTS
                    },
                )
            }
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dnsRules.add(
                    0,
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                        server = "dns-direct"
                    },
                )
            }
        }

        // Per-subscription custom resolver (#71): one DNS server per subscription group,
        // routed directly (TAG_DIRECT) so it never loops through the proxy outbound, and
        // a DNS rule matching ONLY that subscription's server domains. Inserted at the top
        // so it takes precedence over the global direct-DNS force rule above. Hosts shared
        // by multiple subscriptions with different resolvers are ambiguous and are skipped
        // here (they stay on the global direct path).
        perGroupResolver.forEach { (gid, resolver) ->
            val hosts = perGroupServerHosts[gid]
                ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                ?.map { "full:$it" }
            if (hosts.isNullOrEmpty()) return@forEach

            val serverTag = "dns-sub-$gid"
            dnsServers.add(dnsServer(resolver, serverTag, "dns-local"))
            dnsRules.add(
                0,
                DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                },
            )
        }

        fun resolverFor(host: String?): String {
            if (host != null && isExclusiveCustomHost(host)) {
                perGroupServerHosts.entries.firstOrNull { host in it.value }?.let { return "dns-sub-${it.key}" }
            }
            return "dns-direct"
        }
        outboundHosts.forEach { (outbound, host) ->
            val strategy = if (forTest) null else SingBoxOptionsUtil.domainStrategy("server")
            // A chain with as-is resolution must still pass hostnames to its next hop.
            val detour = outbound._hack_config_map["detour"]
            if (detour == null || !strategy.isNullOrEmpty()) {
                outbound._hack_config_map["domain_resolver"] = dnsResolver(resolverFor(host), strategy?.takeIf { it.isNotEmpty() } ?: directStrategy).asMap()
            }
        }
        mappingResolvers.forEach { (rule, host) -> rule.server = resolverFor(host) }

        val finalStrategy = if (forTest) directStrategy else remoteStrategy
        if (finalStrategy == "ipv4_only" || finalStrategy == "ipv6_only") {
            dnsRules.add(DNSRule_DefaultOptions().apply { server = dns.final_ })
        }
        dns.rules = dnsRules.flatMap { rule ->
            val strategy = when {
                rule.server == "dns-fake" -> "ipv4_only"
                rule.server == "dns-direct" || rule.server?.startsWith("dns-sub-") == true -> directStrategy
                rule.server == "dns-remote" || rule.server?.startsWith("dns-wg-") == true || rule.server?.startsWith("dns-rule-") == true -> remoteStrategy
                else -> null
            }
            dnsFamilyRules(rule, strategy)
        }

        if (endpointList.isNotEmpty()) endpoints = endpointList
        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configTree = SingBoxOptions.toJsonTree(it)
        Util.mergeJsonElement(configTree, proxy.requireBean().customConfigJson!!)
        ConfigBuildResult(
            SingBoxOptions.treeToJson(configTree),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            localProxyCredentials,
            buildSelector && group.autoSelect,
            tailscaleEndpoints,
            profileTailscaleNodes,
            wireguardInstances,
            joinNebula,
        )
    }
}

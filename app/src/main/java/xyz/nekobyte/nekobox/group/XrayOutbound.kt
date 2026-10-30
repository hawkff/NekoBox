package xyz.nekobyte.nekobox.group

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.hysteria.parseHysteria2
import xyz.nekobyte.nekobox.fmt.shadowsocks.parseShadowsocks
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.trojan.parseTrojan
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.isFragmentFinalMask
import xyz.nekobyte.nekobox.fmt.v2ray.parseV2Ray
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.linkBuilder
import xyz.nekobyte.nekobox.utils.Util

/**
 * Outbounds of Xray JSON configs, as in the JSON subscriptions of 3x-ui and other Xray panels.
 * The panels generate share links from the same server settings, so an outbound becomes the link a
 * panel would generate and imports through the link parsers: both forms of a subscription give the
 * same profiles. Settings that the core cannot apply and that decide whether a server accepts the
 * connection refuse the outbound, as they refuse a link.
 */
internal object XrayOutbound {
    /** Xray protocols whose outbounds are proxy servers. */
    val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http")

    /** [outbound] as a profile named [name]. [outbounds] are the config's other outbounds. */
    fun parse(outbound: JSONObject, name: String, outbounds: List<JSONObject?>, report: ImportReport): AbstractBean {
        requireSupported(outbound.optJSONObject("proxySettings") == null) { "unsupported proxySettings" }
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val freedom = stream.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { it.isNotEmpty() }?.let { tag ->
            // 3x-ui can send the connection through a local freedom outbound that fragments the TLS
            // ClientHello or adds UDP noise. Another proxy in front would be a chain, which this
            // import does not build.
            val target = outbounds.firstOrNull { it?.optString("tag") == tag }
            requireSupported(target?.optString("protocol") == "freedom") { "unsupported dialerProxy" }
            // A freedom outbound that redirects or adds a PROXY header would change the connection itself.
            val freedom = target!!.optJSONObject("settings") ?: JSONObject()
            requireSupported(freedom.optString("redirect").isEmpty() && freedom.optInt("proxyProtocol") == 0) { "unsupported dialerProxy" }
            freedom
        }
        if (security(stream) == "tls") {
            // The core has no counterpart to a pinned certificate, checked names or a private CA,
            // so the node would trust certificates its provider meant to refuse.
            val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
            requireSupported(tls.strings("pinnedPeerCertSha256").isEmpty()) { "unsupported pinned certificate" }
            requireSupported(tls.strings("verifyPeerCertInNames").isEmpty()) { "unsupported checked names" }
            requireSupported((tls.optJSONArray("certificates")?.length() ?: 0) == 0) { "unsupported TLS certificates" }
        }
        // Finalmask can fragment the ClientHello too, as Remnawave writes it.
        val fragmentMask = stream.optJSONObject("finalmask")?.let { isFragmentFinalMask(it.toString()) } == true
        return parseProtocol(outbound, settings, stream, name).also { bean ->
            if (freedom?.optJSONObject("fragment") != null || fragmentMask) {
                // The profile splits the ClientHello itself; a node without TLS has none to split.
                if (bean is StandardV2RayBean && bean.security == "tls") bean.tlsFragment = true else report.dropped += ImportPreview.Section.FRAGMENT
            }
            if ((freedom?.optJSONArray("noises")?.length() ?: 0) > 0) report.dropped += ImportPreview.Section.NOISE
        }
    }

    private fun parseProtocol(outbound: JSONObject, settings: JSONObject, stream: JSONObject, name: String): AbstractBean = when (val protocol = outbound.getString("protocol")) {
        "vless", "vmess" -> {
            val (server, user) = server(settings, "vnext")
            val link = address(server).username(user.text("id"))
            if (protocol == "vless") {
                link.param("encryption", user.optString("encryption").ifEmpty { "none" }).param("flow", user.optString("flow"))
            } else {
                // Only AEAD VMess; Xray-core no longer accepts a nonzero alterId.
                requireSupported(user.optInt("alterId") == 0) { "unsupported VMess alterId" }
                link.param("encryption", user.optString("security").ifEmpty { "auto" })
            }
            applyStream(link, stream)
            parseV2Ray(link.fragment(name).build().link(protocol))
        }

        "trojan" -> {
            val (server, user) = server(settings, "servers")
            val link = address(server).username(user.text("password"))
            applyStream(link, stream)
            parseTrojan(link.fragment(name).build().link("trojan"))
        }

        "shadowsocks" -> shadowsocks(settings, stream, name)

        "hysteria" -> hysteria(settings, stream, name)

        "wireguard" -> wireGuard(settings, name)

        "socks", "http" -> {
            val (server, user) = server(settings, "servers")
            requireSupported(stream.optString("network").ifEmpty { "tcp" } in setOf("tcp", "raw")) { "unsupported transport" }
            val tls = when (security(stream)) {
                "none" -> false
                "tls" -> true
                else -> refuse("unsupported security")
            }
            if (protocol == "socks") {
                requireSupported(!tls) { "unsupported SOCKS over TLS" }
                SOCKSBean().apply {
                    this.protocol = SOCKSBean.PROTOCOL_SOCKS5
                    username = user.optString("user")
                    password = user.optString("pass")
                }
            } else {
                HttpBean().apply {
                    username = user.optString("user")
                    password = user.optString("pass")
                    if (tls) {
                        val tlsSettings = stream.optJSONObject("tlsSettings") ?: JSONObject()
                        requireSupported(tlsSettings.optString("echConfigList").isEmpty()) { "unsupported ECH" }
                        security = "tls"
                        sni = tlsSettings.optString("serverName")
                        allowInsecure = tlsSettings.optBoolean("allowInsecure")
                    }
                }
            }.apply {
                serverAddress = server.text("address")
                serverPort = server.port()
                this.name = name
            }
        }

        else -> refuse("unsupported protocol")
    }

    /**
     * The server and user of an outbound: Xray accepts them flat in settings, or as the older
     * vnext or servers list. A list with more servers or users is a balancer, which a profile
     * cannot hold.
     */
    private fun server(settings: JSONObject, listKey: String): Pair<JSONObject, JSONObject> {
        val servers = settings.optJSONArray(listKey) ?: return settings to settings
        requireSupported(servers.length() == 1) { "unsupported server list" }
        val server = servers.getJSONObject(0)
        val users = server.optJSONArray("users") ?: return server to server
        requireSupported(users.length() == 1) { "unsupported user list" }
        return server to users.getJSONObject(0)
    }

    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotEmpty() } ?: refuse("missing $key")

    private fun JSONObject.port() = optInt("port").takeIf { it in 1..65535 } ?: refuse("invalid port")

    private fun address(server: JSONObject) = linkBuilder().host(server.text("address")).port(server.port())

    private fun HttpUrl.Builder.param(name: String, value: String?) = apply { if (!value.isNullOrEmpty()) addQueryParameter(name, value) }

    private fun HttpUrl.link(scheme: String) = toString().replaceFirst("https://", "$scheme://")

    private fun security(stream: JSONObject) = stream.optString("security").ifEmpty { "none" }

    /** The transport and security of a stream as the query parameters of a share link. */
    private fun applyStream(link: HttpUrl.Builder, stream: JSONObject) {
        val network = when (val value = stream.optString("network").ifEmpty { "tcp" }) {
            "raw" -> "tcp"
            "splithttp" -> "xhttp"
            else -> value
        }
        link.param("type", network)
        when (network) {
            "tcp" -> {
                val header = (stream.optJSONObject("rawSettings") ?: stream.optJSONObject("tcpSettings"))?.optJSONObject("header")
                when (header?.optString("type").orEmpty()) {
                    "", "none" -> {}

                    "http" -> {
                        val request = header!!.optJSONObject("request") ?: JSONObject()
                        link.param("headerType", "http")
                        link.param("host", request.optJSONObject("headers")?.strings("Host")?.joinToString(","))
                        link.param("path", request.strings("path").joinToString(","))
                    }

                    else -> refuse("unsupported TCP header")
                }
            }

            "ws", "httpupgrade" -> {
                val transport = stream.optJSONObject("${network}Settings") ?: JSONObject()
                link.param("path", transport.optString("path"))
                link.param("host", transport.optString("host").ifEmpty { transport.optJSONObject("headers")?.optString("Host") })
            }

            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                // The core sends no :authority of its own, and a CDN may route by it.
                requireSupported(grpc.optString("authority").isEmpty()) { "unsupported gRPC authority" }
                link.param("serviceName", grpc.optString("serviceName"))
            }

            "xhttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings") ?: JSONObject()
                link.param("path", xhttp.optString("path"))
                link.param("host", xhttp.optString("host"))
                link.param("mode", xhttp.optString("mode"))
                // As in 3x-ui's links, the remaining fields travel as extra.
                val extra = JSONObject(xhttp.toString()).apply {
                    remove("path")
                    remove("host")
                    if (optJSONObject("headers")?.length() == 0) remove("headers")
                }
                if (extra.keys().asSequence().any { it != "mode" }) link.param("extra", extra.toString())
            }

            "kcp" -> {
                val kcp = stream.optJSONObject("kcpSettings") ?: JSONObject()
                link.param("mtu", kcp.optInt("mtu").takeIf { it > 0 }?.toString())
                link.param("tti", kcp.optInt("tti").takeIf { it > 0 }?.toString())
                link.param("headerType", kcp.optJSONObject("header")?.optString("type"))
                link.param("seed", kcp.optString("seed"))
            }

            else -> refuse("unsupported transport")
        }
        // The link parsers accept only the mKCP masks and TCP fragment masks.
        stream.optJSONObject("finalmask")?.let { link.param("fm", it.toString()) }
        when (val security = security(stream)) {
            "none" -> link.param("security", security)

            "tls" -> {
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                link.param("security", security)
                link.param("sni", tls.optString("serverName"))
                link.param("alpn", tls.strings("alpn").joinToString(","))
                link.param("fp", tls.optString("fingerprint"))
                if (tls.optBoolean("allowInsecure")) link.param("allowInsecure", "1")
                link.param("ech", tls.optString("echConfigList"))
            }

            "reality" -> {
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                link.param("security", security)
                link.param("sni", reality.optString("serverName"))
                link.param("fp", reality.optString("fingerprint"))
                link.param("pbk", reality.optString("publicKey").ifEmpty { reality.optString("password") })
                link.param("sid", reality.optString("shortId"))
                link.param("spx", reality.optString("spiderX"))
                // The link parsers refuse ML-DSA-65 verification.
                link.param("pqv", reality.optString("mldsa65Verify"))
            }

            else -> refuse("unsupported security")
        }
    }

    /** Shadowsocks in the link form 3x-ui generates: v2ray-plugin for WebSocket, obfs-local for an HTTP header. */
    private fun shadowsocks(settings: JSONObject, stream: JSONObject, name: String): AbstractBean {
        val (server, user) = server(settings, "servers")
        val link = address(server).username(Util.b64EncodeUrlSafe(user.text("method") + ":" + user.text("password")))
        val network = stream.optString("network").ifEmpty { "tcp" }.let { if (it == "raw") "tcp" else it }
        val header = (stream.optJSONObject("rawSettings") ?: stream.optJSONObject("tcpSettings"))?.optJSONObject("header")
        if (network == "tcp" && header?.optString("type") == "http") {
            val request = header.optJSONObject("request") ?: JSONObject()
            // obfs-local sends its requests to /.
            requireSupported(request.strings("path").all { it == "/" }) { "unsupported HTTP header path" }
            requireSupported(security(stream) == "none") { "unsupported shadowsocks transport" }
            val host = request.optJSONObject("headers")?.strings("Host")?.firstOrNull()
            link.param("plugin", listOfNotNull("obfs-local", "obfs=http", host?.let { "obfs-host=$it" }).joinToString(";"))
        } else {
            // The plugin has no REALITY; the link parser would build such a node without it.
            requireSupported(security(stream) in setOf("none", "tls")) { "unsupported shadowsocks security" }
            applyStream(link, stream)
        }
        return parseShadowsocks(link.fragment(name).build().link("ss"))
    }

    /**
     * Hysteria 2 in the link form 3x-ui generates, with Finalmask salamander obfuscation and port hopping.
     * Finalmask's QUIC parameters shape only how this client sends: Brutal's bandwidth and port hopping
     * carry over, and the congestion control otherwise stays the core's BBR.
     */
    private fun hysteria(settings: JSONObject, stream: JSONObject, name: String): AbstractBean {
        val hysteria = stream.optJSONObject("hysteriaSettings") ?: JSONObject()
        requireSupported(settings.optInt("version", 2) == 2 && hysteria.optInt("version", 2) == 2) { "unsupported Hysteria version" }
        requireSupported(stream.optString("network").ifEmpty { "hysteria" } == "hysteria") { "unsupported transport" }
        requireSupported(security(stream) == "tls") { "unsupported security" }
        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
        val link = address(settings).username(hysteria.optString("auth"))
            .param("security", "tls")
            .param("sni", tls.optString("serverName"))
            .param("alpn", tls.strings("alpn").joinToString(","))
            .param("ech", tls.optString("echConfigList"))
        if (tls.optBoolean("allowInsecure")) link.param("insecure", "1")
        var hopInterval: Int? = null
        val masks = stream.optJSONObject("finalmask")
        requireSupported(masks == null || masks.keys().asSequence().all { it == "udp" || it == "quicParams" }) { "unsupported finalmask" }
        val quic = masks?.optJSONObject("quicParams") ?: JSONObject()
        // Brutal sends at brutalUp unless another congestion control is chosen; the receive rate is advertised either way.
        val upMbps = bandwidthMbps(quic.optString("brutalUp")).takeIf { quic.optString("congestion").lowercase() in BRUTAL }
        val downMbps = bandwidthMbps(quic.optString("brutalDown"))
        val udp = masks?.optJSONArray("udp") ?: JSONArray()
        val udpMasks = (0 until udp.length()).map { udp.getJSONObject(it) }
        // Older configs hop ports from quicParams instead of a udphop mask.
        val quicHop = quic.optJSONObject("udpHop")?.takeIf { udpMasks.none { it.optString("type") == "udphop" } }
            ?.let { JSONObject().put("type", "udphop").put("settings", it) }
        for (mask in udpMasks + listOfNotNull(quicHop)) {
            val maskSettings = mask.optJSONObject("settings") ?: JSONObject()
            when (mask.optString("type")) {
                "salamander" -> {
                    // Salamander with a packet size range is what links call gecko.
                    val packetSize = maskSettings.optString("packetSize")
                    if (packetSize.isEmpty()) {
                        link.param("obfs", "salamander")
                    } else {
                        link.param("obfs", "gecko")
                            .param("minPacketSize", packetSize.substringBefore('-'))
                            .param("maxPacketSize", packetSize.substringAfter('-'))
                    }
                    link.param("obfs-password", maskSettings.text("password"))
                }

                "udphop" -> {
                    link.param("mport", maskSettings.optString("remotePorts").ifEmpty { maskSettings.optString("ports") })
                    // A range picks a random interval in Xray; the profile keeps its lower bound.
                    hopInterval = maskSettings.optString("interval").substringBefore('-').trim().toIntOrNull()
                }

                else -> refuse("unsupported finalmask")
            }
        }
        return parseHysteria2(link.fragment(name).build().link("hysteria2")).apply {
            hopInterval?.let { this.hopInterval = it }
            upMbps?.let { uploadMbps = it }
            downMbps?.let { downloadMbps = it }
        }
    }

    /** Congestion controls under which Xray's Hysteria sends at brutalUp. */
    private val BRUTAL = setOf("", "brutal", "force-brutal")

    private val BANDWIDTH = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([a-z]*)""")

    /** An Xray bandwidth such as "100mbps", in whole Mbps for the core; Xray's units are binary. Null when unset. */
    private fun bandwidthMbps(value: String): Int? {
        if (value.isBlank()) return null
        val (number, unit) = BANDWIDTH.matchEntire(value.trim().lowercase())?.destructured ?: refuse("invalid bandwidth")
        val bits = number.toDouble() * when (unit) {
            "", "b", "bps" -> 1.0
            "k", "kb", "kbps" -> 1024.0
            "m", "mb", "mbps" -> 1048576.0
            "g", "gb", "gbps" -> 1073741824.0
            "t", "tb", "tbps" -> 1099511627776.0
            else -> refuse("invalid bandwidth")
        }
        // Zero leaves Brutal off, as an unset value does. A positive one that rounds to 0 would also turn
        // it off, so it keeps 1; Xray's own minimum is 512 kbps.
        if (bits <= 0) return null
        return (bits / 1_000_000).toInt().coerceAtLeast(1)
    }

    /** WireGuard as the wg-quick config of the same tunnel. */
    private fun wireGuard(settings: JSONObject, name: String): AbstractBean {
        val peers = settings.optJSONArray("peers") ?: refuse("missing peers")
        val reserved = settings.optJSONArray("reserved")?.let { list -> (0 until list.length()).map { list.getInt(it) } }.orEmpty()
        requireSupported(reserved.all { it == 0 } || peers.length() == 1) { "unsupported reserved bytes" }
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${settings.text("secretKey").line()}")
            appendLine("Address = ${settings.strings("address").joinToString(", ") { it.line() }}")
            settings.optInt("mtu").takeIf { it > 0 }?.let { appendLine("MTU = $it") }
            for (index in 0 until peers.length()) {
                val peer = peers.getJSONObject(index)
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = ${peer.text("publicKey").line()}")
                peer.optString("preSharedKey").takeIf { it.isNotEmpty() }?.let { appendLine("PresharedKey = ${it.line()}") }
                peer.optString("endpoint").takeIf { it.isNotEmpty() }?.let { appendLine("Endpoint = ${it.line()}") }
                // Xray routes everything to a peer without allowedIPs.
                val allowed = peer.strings("allowedIPs").ifEmpty { listOf("0.0.0.0/0", "::/0") }
                appendLine("AllowedIPs = ${allowed.joinToString(", ") { it.line() }}")
                peer.optInt("keepAlive").takeIf { it > 0 }?.let { appendLine("PersistentKeepalive = $it") }
            }
        }
        return RawUpdater.parseWireGuardConf(conf).single().apply {
            this.name = name
            if (reserved.any { it != 0 }) (this as WireGuardBean).reserved = reserved.joinToString(",")
        }
    }

    /** A value for one line of a wg-quick config; a line break would add settings of its own. */
    private fun String.line() = also { requireSupported('\n' !in it && '\r' !in it) { "invalid WireGuard value" } }
}

/** A string or a list of strings, as Xray accepts for several fields. */
internal fun JSONObject.strings(key: String): List<String> = when (val value = opt(key)) {
    is JSONArray -> (0 until value.length()).map { value.get(it).toString() }
    null, JSONObject.NULL -> emptyList()
    else -> listOf(value.toString())
}.filter { it.isNotEmpty() }

// Refusals name only the unsupported option, so the import warning can show them.
private inline fun requireSupported(value: Boolean, reason: () -> String) {
    if (!value) refuse(reason())
}

private fun refuse(reason: String): Nothing = throw UnsupportedEntryException(reason)

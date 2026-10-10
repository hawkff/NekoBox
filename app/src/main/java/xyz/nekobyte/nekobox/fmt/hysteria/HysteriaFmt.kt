package xyz.nekobyte.nekobox.fmt.hysteria

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.ktx.*
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import java.nio.ByteBuffer
import java.util.Base64

fun AbstractBean.isHysteria1(): Boolean = when (this) {
    is HysteriaBean -> protocolVersion == 1
    is ConfigBean -> type == 1 && runCatching { JSONObject(config!!).optString("type") == "hysteria" }.getOrDefault(false)
    else -> false
}

private const val ECH_CONFIGS_BEGIN = "-----BEGIN ECH CONFIGS-----"
private const val ECH_CONFIGS_END = "-----END ECH CONFIGS-----"
private const val ECH_CONFIG_VERSION = 0xFE0D
private const val MAX_ECH_CONFIG_BASE64_LENGTH = 87_384

/**
 * Normalize a Hysteria 2 ECH config list to canonical standard base64.
 *
 * Hysteria shares the public config as raw base64, while sing-box requires an
 * ECH CONFIGS PEM block. Manual profiles may paste either representation.
 */
internal fun canonicalHysteria2ECHConfig(config: String?): String {
    val trimmed = requireNotNull(config) {
        "Hysteria 2 ECH config is required when ECH is enabled"
    }.trim()
    require(trimmed.isNotEmpty()) { "Hysteria 2 ECH config is required when ECH is enabled" }

    val payload = if (trimmed.startsWith(ECH_CONFIGS_BEGIN)) {
        require(trimmed.endsWith(ECH_CONFIGS_END)) {
            "Invalid Hysteria 2 ECH config: incomplete ECH CONFIGS PEM block"
        }
        trimmed.removePrefix(ECH_CONFIGS_BEGIN).removeSuffix(ECH_CONFIGS_END)
    } else {
        require(!trimmed.contains("-----BEGIN") && !trimmed.contains("-----END")) {
            "Invalid Hysteria 2 ECH config: expected base64 or an ECH CONFIGS PEM block"
        }
        trimmed
    }

    val compact = payload.filterNot { it.isWhitespace() }
    require(compact.length <= MAX_ECH_CONFIG_BASE64_LENGTH) {
        "Invalid Hysteria 2 ECH config: config list is too large"
    }
    val decoded = try {
        Base64.getDecoder().decode(compact)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid Hysteria 2 ECH config: expected standard base64", e)
    }
    validateHysteria2ECHConfigList(decoded)
    return Base64.getEncoder().encodeToString(decoded)
}

private fun validateHysteria2ECHConfigList(raw: ByteArray) {
    val encodedList = ByteBuffer.wrap(raw)
    val configList = encodedList.readUint16LengthPrefixed("ECHConfigList")
    require(!encodedList.hasRemaining()) {
        "Invalid Hysteria 2 ECH config: trailing data after ECHConfigList"
    }
    require(configList.hasRemaining()) { "Invalid Hysteria 2 ECH config: config list is empty" }

    var hasSupportedConfig = false
    while (configList.hasRemaining()) {
        val version = configList.readUint16("ECHConfig version")
        val config = configList.readUint16LengthPrefixed("ECHConfig")
        if (version == ECH_CONFIG_VERSION) {
            validateSupportedECHConfig(config)
            hasSupportedConfig = true
        }
    }
    require(hasSupportedConfig) {
        "Invalid Hysteria 2 ECH config: no supported ECHConfig entry"
    }
}

private fun validateSupportedECHConfig(config: ByteBuffer) {
    config.readUint8("config_id")
    config.readUint16("kem_id")

    val publicKey = config.readUint16LengthPrefixed("public_key")
    require(publicKey.hasRemaining()) { "Invalid Hysteria 2 ECH config: public key is empty" }

    val cipherSuites = config.readUint16LengthPrefixed("cipher_suites")
    require(cipherSuites.hasRemaining() && cipherSuites.remaining() % 4 == 0) {
        "Invalid Hysteria 2 ECH config: malformed cipher suites"
    }
    while (cipherSuites.hasRemaining()) {
        cipherSuites.readUint16("kdf_id")
        cipherSuites.readUint16("aead_id")
    }

    config.readUint8("maximum_name_length")
    val publicNameLength = config.readUint8("public_name length")
    require(publicNameLength > 0) { "Invalid Hysteria 2 ECH config: public name is empty" }
    config.readBytes(publicNameLength, "public_name")

    val extensions = config.readUint16LengthPrefixed("extensions")
    while (extensions.hasRemaining()) {
        extensions.readUint16("extension type")
        extensions.readUint16LengthPrefixed("extension data")
    }
    require(!config.hasRemaining()) {
        "Invalid Hysteria 2 ECH config: trailing data in ECHConfig"
    }
}

private fun ByteBuffer.readUint8(field: String): Int {
    require(remaining() >= 1) { "Invalid Hysteria 2 ECH config: truncated $field" }
    return get().toInt() and 0xFF
}

private fun ByteBuffer.readUint16(field: String): Int {
    require(remaining() >= 2) { "Invalid Hysteria 2 ECH config: truncated $field" }
    return short.toInt() and 0xFFFF
}

private fun ByteBuffer.readUint16LengthPrefixed(field: String): ByteBuffer {
    val length = readUint16("$field length")
    return readBytes(length, field)
}

private fun ByteBuffer.readBytes(length: Int, field: String): ByteBuffer {
    require(length <= remaining()) { "Invalid Hysteria 2 ECH config: truncated $field" }
    val value = slice().apply { limit(length) }
    position(position() + length)
    return value
}

private fun singBoxHysteria2ECHConfig(config: String?) = listOf(
    ECH_CONFIGS_BEGIN,
    canonicalHysteria2ECHConfig(config),
    ECH_CONFIGS_END,
)

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameterPreservingPlus("ech")?.also {
            echConfig = canonicalHysteria2ECHConfig(it)
            enableECH = true
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
        // obfs type: salamander (default when password present) or gecko.
        link.queryParameter("obfs")?.also {
            hysteria2ObfsType = when (it.lowercase()) {
                "gecko" -> HysteriaBean.OBFS_GECKO
                "salamander" -> HysteriaBean.OBFS_SALAMANDER
                else -> HysteriaBean.OBFS_NONE
            }
        }
        // v2rayN and 3x-ui spell the gecko bounds minPacketSize/maxPacketSize.
        (link.queryParameter("obfs-min-packet-size") ?: link.queryParameter("minPacketSize"))?.toIntOrNull()?.also {
            geckoMinPacketSize = it
        }
        (link.queryParameter("obfs-max-packet-size") ?: link.queryParameter("maxPacketSize"))?.toIntOrNull()?.also {
            geckoMaxPacketSize = it
        }
        // A certificate pin has no counterpart here; without it the link would trust any
        // certificate that insecure=1 lets through, so such a link is refused.
        require(link.queryParameter("pinSHA256").isNullOrBlank()) { "unsupported certificate pin" }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload!!.contains(":")) {
            un = authPayload!!.substringBefore(":")
            pw = authPayload!!.substringAfter(":")
        } else {
            un = authPayload!!
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress!!)
        .port(getFirstPort(serverPorts!!))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name!!.isNotBlank()) {
        builder.encodedFragment(name!!.urlSafe())
    }
    if (allowInsecure!!) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni!!.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload!!.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn!!.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation!!.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni!!.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (enableECH == true) {
            builder.addQueryParameter("ech", canonicalHysteria2ECHConfig(echConfig))
        }
        if (obfuscation!!.isNotBlank() && hysteria2ObfsType != HysteriaBean.OBFS_NONE) {
            when (hysteria2ObfsType) {
                HysteriaBean.OBFS_GECKO -> {
                    builder.addQueryParameter("obfs", "gecko")
                    builder.addQueryParameter("obfs-password", obfuscation)
                    builder.addQueryParameter("obfs-min-packet-size", geckoMinPacketSize.toString())
                    builder.addQueryParameter("obfs-max-packet-size", geckoMaxPacketSize.toString())
                }

                else -> {
                    builder.addQueryParameter("obfs", "salamander")
                    builder.addQueryParameter("obfs-password", obfuscation)
                }
            }
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

/**
 * Parse a Hysteria 2 client config in JSON (or Clash/Mihomo-style) form into a HysteriaBean.
 *
 * Mirrors the official HY2 client config schema and the parseHysteria2(url) field mapping:
 *   server: "host:port"            -> serverAddress / serverPorts
 *   auth: "<string>"               -> authPayload (string type)
 *   tls: { sni, insecure, ech }    -> sni / allowInsecure / explicit ECH config
 *   obfs: { type, salamander|gecko: { password, minPacketSize, maxPacketSize } } -> obfs fields
 *   bandwidth: { up, down }        -> uploadMbps / downloadMbps (Mbps ints when numeric)
 */
fun JSONObject.parseHysteria2Json(): HysteriaBean = HysteriaBean().apply {
    protocolVersion = 2
    val server = optString("server")
    // Only split off a port when there's an explicit one. A bare host or an IPv6 literal
    // without a port must keep the whole string as the address (default port 443),
    // matching parseHysteria2(url)'s HttpUrl behavior.
    val lastColon = server.lastIndexOf(':')
    val portPart = if (lastColon >= 0) server.substring(lastColon + 1) else ""
    if (lastColon >= 0 && portPart.toIntOrNull() != null && !server.endsWith("]")) {
        serverAddress = server.substring(0, lastColon)
        serverPorts = portPart
    } else {
        serverAddress = server
        serverPorts = "443"
    }
    getStr("auth")?.also {
        authPayloadType = HysteriaBean.TYPE_STRING
        authPayload = it
    }
    // tls block (sni / insecure / Hysteria 2.10 ECH config list).
    optJSONObject("tls")?.also { tls ->
        require(tls.getStr("pinSHA256") == null) { "unsupported certificate pin" }
        tls.getStr("sni")?.also { sni = it }
        tls.getBool("insecure")?.also { allowInsecure = it }
        tls.getStr("ech")?.also {
            echConfig = canonicalHysteria2ECHConfig(it)
            enableECH = true
        }
    }
    // obfs block: { type: "salamander"|"gecko", salamander: { password }, gecko: {...} }.
    optJSONObject("obfs")?.also { obfs ->
        when (obfs.getStr("type")?.lowercase()) {
            "gecko" -> {
                hysteria2ObfsType = HysteriaBean.OBFS_GECKO
                obfs.optJSONObject("gecko")?.also { g ->
                    g.getStr("password")?.also { obfuscation = it }
                    g.getIntNya("min_packet_size")?.also { geckoMinPacketSize = it }
                    g.getIntNya("max_packet_size")?.also { geckoMaxPacketSize = it }
                }
            }

            "salamander" -> {
                hysteria2ObfsType = HysteriaBean.OBFS_SALAMANDER
                obfs.optJSONObject("salamander")?.getStr("password")?.also { obfuscation = it }
            }

            else -> hysteria2ObfsType = HysteriaBean.OBFS_NONE
        }
    }
    // bandwidth block: accept a bare integer (Mbps) or a unit-suffixed string ("100 mbps",
    // "1 gbps"), normalizing to Mbps. HY2 configs commonly use the string form.
    optJSONObject("bandwidth")?.also { bw ->
        parseBandwidthMbps(bw, "up")?.also { uploadMbps = it }
        parseBandwidthMbps(bw, "down")?.also { downloadMbps = it }
    }
    name = optString("name").takeIf { it.isNotBlank() }
}

/** Read a HY2 bandwidth value as Mbps: a bare int, or a unit-suffixed string (bps/kbps/mbps/gbps/tbps). */
private fun parseBandwidthMbps(obj: JSONObject, key: String): Int? {
    obj.getIntNya(key)?.let { return it }
    val raw = obj.getStr(key)?.trim()?.lowercase() ?: return null
    val match = Regex("""^(\d+(?:\.\d+)?)\s*([a-z]*)$""").matchEntire(raw) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val mbps = when (match.groupValues[2]) {
        "", "mbps", "m" -> value
        "bps", "b" -> value / 1_000_000.0
        "kbps", "k" -> value / 1_000.0
        "gbps", "g" -> value * 1_000.0
        "tbps", "t" -> value * 1_000_000.0
        else -> return null
    }
    return mbps.toInt().coerceAtLeast(0)
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int = portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption = when (bean.protocolVersion) {
    1 -> error("Hysteria 1 is no longer supported")

    2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
        type = "hysteria2"
        server = bean.serverAddress
        val port = bean.serverPorts!!.toIntOrNull()
        if (port != null) {
            server_port = port
        } else {
            server_ports = hopPortsToSingboxList(bean.serverPorts!!)
        }
        hop_interval = "${bean.hopInterval}s"
        up_mbps = bean.uploadMbps
        down_mbps = bean.downloadMbps
        if (bean.obfuscation!!.isNotBlank() && bean.hysteria2ObfsType != HysteriaBean.OBFS_NONE) {
            obfs = SingBoxOptions.Hysteria2Obfs().apply {
                when (bean.hysteria2ObfsType) {
                    HysteriaBean.OBFS_GECKO -> {
                        type = "gecko"
                        password = bean.obfuscation
                        // Clamp and order the bounds (1..2048, min <= max);
                        // an inverted or out-of-range pair would otherwise be
                        // rejected by the core at connect time.
                        val min = bean.geckoMinPacketSize?.takeIf { it > 0 }?.coerceIn(1, 2048)
                        val max = bean.geckoMaxPacketSize?.takeIf { it > 0 }?.coerceIn(min ?: 1, 2048)
                        if (min != null) min_packet_size = min
                        if (max != null) max_packet_size = max
                    }

                    else -> {
                        type = "salamander"
                        password = bean.obfuscation
                    }
                }
            }
        }
//            disable_mtu_discovery = bean.disableMtuDiscovery
        password = bean.authPayload
//            if (bean.streamReceiveWindow > 0) {
//                recv_window_conn = bean.streamReceiveWindow.toLong()
//            }
//            if (bean.connectionReceiveWindow > 0) {
//                recv_window_conn = bean.connectionReceiveWindow.toLong()
//            }
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            if (bean.sni!!.isNotBlank()) {
                server_name = bean.sni
            }
            alpn = listOf("h3")
            if (bean.caText!!.isNotBlank()) {
                certificate = bean.caText
            }
            if (bean.enableECH == true) {
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = singBoxHysteria2ECHConfig(bean.echConfig)
                }
            }
            insecure = bean.allowInsecure!! || DataStore.globalAllowInsecure
            enabled = true
        }
    }

    else -> error("Unsupported Hysteria version: ${bean.protocolVersion}")
}

fun hopPortsToSingboxList(s: String): List<String> = s.split(",").mapNotNull {
    val pRange = it.replace("-", ":")
    if (pRange.split(":").size == 2) {
        pRange
    } else {
        null
    }
}

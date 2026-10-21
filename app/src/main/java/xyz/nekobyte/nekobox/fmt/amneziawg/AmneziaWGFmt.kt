package xyz.nekobyte.nekobox.fmt.amneziawg

import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.fmt.wireguard.buildPeers
import xyz.nekobyte.nekobox.fmt.wireguard.checkWireGuardLimits
import xyz.nekobyte.nekobox.fmt.wireguard.genReserved
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardSettings
import xyz.nekobyte.nekobox.utils.listByLineOrComma

// The device sends at most one batch of 128 junk packets, and each junk and I1-I5 packet as one UDP datagram over IPv4.
internal const val AMNEZIAWG_MAX_JUNK_PACKETS = 128
internal const val AMNEZIAWG_MAX_PACKET_SIZE = 65507

private val SPACES = Regex("\\s+")

/**
 * Whether the device builds the I1-I5 tag chain [spec]: it parses the tags as the AmneziaWG devices of the core
 * and the Tailscale fork do, and adds up the packet size. The device takes lengths as given, and a negative one
 * crashes it. d and ds carry the payload, which these packets do not have.
 */
internal fun amneziaSignatureValid(spec: String): Boolean {
    var size = 0
    var rest = spec
    while (true) {
        val start = rest.indexOf('<')
        if (start < 0) return true
        val end = rest.indexOf('>', start)
        if (end < 0) return false
        val fields = rest.substring(start + 1, end).split(SPACES).filter { it.isNotEmpty() }
        rest = rest.substring(end + 1)
        val argument = fields.getOrElse(1) { "" }
        size += when (fields.firstOrNull()) {
            "b" -> argument.removePrefix("0x").takeIf { hex -> hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }?.let { it.length / 2 } ?: return false
            "t" -> 4
            "r", "rc", "rd", "dz" -> argument.toIntOrNull()?.takeIf { it in 0..AMNEZIAWG_MAX_PACKET_SIZE } ?: return false
            "d", "ds" -> 0
            else -> return false
        }
        if (size > AMNEZIAWG_MAX_PACKET_SIZE) return false
    }
}

// S1 to S4 pad the handshake initiation, response, cookie reply and empty transport message, in that order.
private val PADDED_MESSAGE_SIZES = listOf(148, 92, 64, 32)

/** Whether every message padded by S1 to S4 still fits one datagram; a larger S4 also crashes the device's packet reader. */
internal fun amneziaPaddingsFit(paddings: List<Int?>) = paddings.zip(PADDED_MESSAGE_SIZES).all { (padding, size) -> (padding ?: 0) in 0..AMNEZIAWG_MAX_PACKET_SIZE - size }

/**
 * H1 to H4 as the device reads them: a number or a min-max range of 32-bit numbers, and a blank one keeps
 * the WireGuard message type 1 to 4. Null when one does not parse.
 */
internal fun amneziaHeaderRanges(headers: List<String?>): List<LongRange>? = headers.mapIndexed { index, header ->
    val parts = header?.trim().orEmpty().ifEmpty { "${index + 1}" }.split('-')
    val bounds = parts.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toLong() } ?: return null }
    if (bounds.size > 2 || bounds.first() > bounds.last()) return null
    bounds.first()..bounds.last()
}

/** The first two headers whose ranges overlap, numbered from 1, or null; the device refuses overlapping ranges. */
internal fun amneziaHeaderOverlap(ranges: List<LongRange>): Pair<Int, Int>? {
    for (i in ranges.indices) {
        for (j in i + 1 until ranges.size) {
            if (ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last) return i + 1 to j + 1
        }
    }
    return null
}

fun buildSingBoxOutboundAmneziaWGBean(bean: AmneziaWGBean): SingBoxOptions.Outbound_AmneziaWGOptions = SingBoxOptions.Outbound_AmneziaWGOptions().apply {
    bean.checkWireGuardLimits()
    type = "amneziawg"
    // Let the system fragment packets larger than the path MTU, as WireGuard clients do, instead of failing to send them.
    udp_fragment = true
    local_address = bean.localAddress!!.listByLineOrComma()
    private_key = bean.privateKey
    mtu = bean.mtu
    peers = bean.wireGuardSettings()!!.buildPeers()
    if (peers == null) {
        server = bean.serverAddress
        server_port = bean.serverPort
        peer_public_key = bean.peerPublicKey
        pre_shared_key = bean.peerPreSharedKey
        if (bean.reserved!!.isNotBlank()) reserved = genReserved(bean.reserved!!)
    }

    // AmneziaWG obfuscation parameters; zero/blank values are omitted so the
    // tunnel behaves like plain WireGuard when unset.
    if (bean.jc != 0) jc = bean.jc
    if (bean.jmin != 0) jmin = bean.jmin
    if (bean.jmax != 0) jmax = bean.jmax
    if (bean.s1 != 0) s1 = bean.s1
    if (bean.s2 != 0) s2 = bean.s2
    if (bean.s3 != 0) s3 = bean.s3
    if (bean.s4 != 0) s4 = bean.s4
    if (bean.h1!!.isNotBlank()) h1 = bean.h1!!.trim()
    if (bean.h2!!.isNotBlank()) h2 = bean.h2!!.trim()
    if (bean.h3!!.isNotBlank()) h3 = bean.h3!!.trim()
    if (bean.h4!!.isNotBlank()) h4 = bean.h4!!.trim()
    if (bean.i1!!.isNotBlank()) i1 = bean.i1
    if (bean.i2!!.isNotBlank()) i2 = bean.i2
    if (bean.i3!!.isNotBlank()) i3 = bean.i3
    if (bean.i4!!.isNotBlank()) i4 = bean.i4
    if (bean.i5!!.isNotBlank()) i5 = bean.i5
    // AmneziaWG 3.1; blank values keep the device defaults.
    header_protection_key = bean.headerProtectionKey?.takeIf { it.isNotBlank() }
    content_padding_addition = bean.contentPaddingAddition?.takeIf { it.isNotBlank() }
    rekey_after_time = bean.rekeyAfterTime?.takeIf { it.isNotBlank() }
    rekey_timeout = bean.rekeyTimeout?.takeIf { it.isNotBlank() }
    reject_after_time = bean.rejectAfterTime?.takeIf { it.isNotBlank() }
    keepalive_timeout = bean.keepaliveTimeout?.takeIf { it.isNotBlank() }
    max_handshake_attempts = bean.maxHandshakeAttempts?.takeIf { it.isNotBlank() }
    random_trailers = bean.randomTrailers?.takeIf { it }
    disable_cookies = bean.disableCookies?.takeIf { it }
}

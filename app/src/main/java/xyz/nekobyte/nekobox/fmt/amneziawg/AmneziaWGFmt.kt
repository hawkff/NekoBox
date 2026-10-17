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

private val SIGNATURE_TAG = Regex("<([^>]*)>")
private val SPACES = Regex("\\s+")

/** Adds up an I1-I5 tag chain as the device sizes the packet; it takes the lengths as given, and a negative one crashes it. */
internal fun amneziaSignatureFits(spec: String): Boolean {
    var size = 0
    for (tag in SIGNATURE_TAG.findAll(spec)) {
        val fields = tag.groupValues[1].trim().split(SPACES)
        val argument = fields.getOrElse(1) { "" }
        size += when (fields[0]) {
            "b" -> argument.removePrefix("0x").length / 2
            "t" -> 4
            "r", "rc", "rd", "dz" -> argument.toIntOrNull()?.takeIf { it in 0..AMNEZIAWG_MAX_PACKET_SIZE } ?: return false
            else -> 0
        }
        if (size > AMNEZIAWG_MAX_PACKET_SIZE) return false
    }
    return true
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
    if (bean.h1!!.isNotBlank()) h1 = bean.h1
    if (bean.h2!!.isNotBlank()) h2 = bean.h2
    if (bean.h3!!.isNotBlank()) h3 = bean.h3
    if (bean.h4!!.isNotBlank()) h4 = bean.h4
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

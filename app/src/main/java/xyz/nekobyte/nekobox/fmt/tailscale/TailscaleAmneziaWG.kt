package xyz.nekobyte.nekobox.fmt.tailscale

import android.util.Base64
import org.json.JSONObject
import xyz.nekobyte.nekobox.SingBoxOptions

/**
 * The AmneziaWG settings of a Tailscale node, in the JSON that `tailscale awg get` prints on
 * Tailscale builds with AmneziaWG, so a profile copied from any node of the tailnet fits. Every
 * node that this one reaches directly or over DERP needs the same settings.
 */
internal object TailscaleAmneziaWG {
    private val INTEGERS = mapOf<String, SingBoxOptions.AmneziaWGObfuscationOptions.(Int) -> Unit>(
        "JC" to { jc = it },
        "JMin" to { jmin = it },
        "JMax" to { jmax = it },
        "S1" to { s1 = it },
        "S2" to { s2 = it },
        "S3" to { s3 = it },
        "S4" to { s4 = it },
    )
    private val RANGES = mapOf<String, SingBoxOptions.AmneziaWGObfuscationOptions.(String) -> Unit>(
        "H1" to { h1 = it },
        "H2" to { h2 = it },
        "H3" to { h3 = it },
        "H4" to { h4 = it },
        "ContentPaddingAddition" to { content_padding_addition = it },
        "RekeyAfterTime" to { rekey_after_time = it },
        "RekeyTimeout" to { rekey_timeout = it },
        "RejectAfterTime" to { reject_after_time = it },
        "KeepaliveTimeout" to { keepalive_timeout = it },
        "MaxHandshakeAttempts" to { max_handshake_attempts = it },
    )
    private val SIGNATURES = mapOf<String, SingBoxOptions.AmneziaWGObfuscationOptions.(String) -> Unit>(
        "I1" to { i1 = it },
        "I2" to { i2 = it },
        "I3" to { i3 = it },
        "I4" to { i4 = it },
        "I5" to { i5 = it },
    )

    /** The settings in [text], or null for an empty profile, which keeps plain WireGuard. */
    fun parse(text: String?): SingBoxOptions.AmneziaWGObfuscationOptions? {
        if (text.isNullOrBlank()) return null
        val json = JSONObject(text)
        val options = SingBoxOptions.AmneziaWGObfuscationOptions()
        var set = false
        for (key in json.keys()) {
            val value = json.get(key)
            if (value == JSONObject.NULL) continue
            when (key) {
                in INTEGERS -> integer(key, value).takeIf { it != 0 }?.let {
                    INTEGERS.getValue(key)(options, it)
                    set = true
                }

                in RANGES -> range(key, value)?.let {
                    RANGES.getValue(key)(options, it)
                    set = true
                }

                in SIGNATURES -> (value as? String ?: error("$key is not text")).takeIf { it.isNotEmpty() }?.let {
                    require('\n' !in it && '\r' !in it) { "$key has a line break" }
                    require(signatureFits(it)) { "$key needs whole-number lengths and at most $MAX_SIGNATURE_SIZE bytes" }
                    SIGNATURES.getValue(key)(options, it)
                    set = true
                }

                "HeaderProtectionKey" -> headerProtectionKey(value as? String ?: error("$key is not text"))?.let {
                    options.header_protection_key = it
                    set = true
                }

                "RandomTrailers", "DisableCookies" -> if (value as? Boolean ?: error("$key is not true or false")) {
                    if (key == "RandomTrailers") options.random_trailers = true else options.disable_cookies = true
                    set = true
                }

                else -> error("Unknown AmneziaWG setting $key")
            }
        }
        if (!set) return null
        val paddings = listOf(options.s1, options.s2, options.s3, options.s4).map { it ?: 0 }
        // The device takes the header protection nonce from the junk padding of every packet type.
        require(options.header_protection_key == null || paddings.all { it >= 12 }) {
            "S1 to S4 must be at least 12 with a header protection key"
        }
        require((options.jmin ?: 0) <= (options.jmax ?: 0)) { "JMin is greater than JMax" }
        // The device sends at most one batch of 128 junk packets, each one UDP datagram.
        require((options.jc ?: 0) <= 128) { "JC is greater than 128" }
        require((options.jmax ?: 0) <= MAX_SIGNATURE_SIZE) { "JMax is larger than $MAX_SIGNATURE_SIZE bytes" }
        // As the device checks: the paddings fit its packet buffer and the message types stay apart.
        val limits = listOf(options.s1 to 65535 - 148, options.s2 to 65535 - 92, options.s3 to 65535 - 64, options.s4 to 65535 - 40)
        require(limits.all { (value, max) -> (value ?: 0) <= max }) { "S1 to S4 exceed the packet size" }
        val headers = listOf(options.h1, options.h2, options.h3, options.h4).mapIndexed { index, value ->
            (value ?: "${index + 1}").split('-').let { it.first().toLong()..it.last().toLong() }
        }
        for (i in headers.indices) {
            for (j in i + 1 until headers.size) {
                require(headers[i].last < headers[j].first || headers[j].last < headers[i].first) { "H${i + 1} and H${j + 1} overlap" }
            }
        }
        return options
    }

    // The device sends each I1-I5 packet as one UDP datagram over IPv4.
    private const val MAX_SIGNATURE_SIZE = 65507
    private val TAG = Regex("<([^>]*)>")
    private val SPACES = Regex("\\s+")

    /** Adds up the tag lengths as the device sizes the packet; it takes them as given, and a negative one crashes it. */
    private fun signatureFits(spec: String): Boolean {
        var size = 0
        for (tag in TAG.findAll(spec)) {
            val fields = tag.groupValues[1].trim().split(SPACES)
            val argument = fields.getOrElse(1) { "" }
            size += when (fields[0]) {
                "b" -> argument.removePrefix("0x").length / 2
                "t" -> 4
                "r", "rc", "rd", "dz" -> argument.toIntOrNull()?.takeIf { it in 0..MAX_SIGNATURE_SIZE } ?: return false
                else -> 0
            }
            if (size > MAX_SIGNATURE_SIZE) return false
        }
        return true
    }

    private fun integer(key: String, value: Any): Int {
        val number = whole(key, value)
        require(number in 0..65535) { "$key is out of range" }
        return number.toInt()
    }

    /** A JSON integer or digits; a fraction would change the setting. */
    private fun whole(key: String, value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is String -> value.trim().toLongOrNull() ?: error("$key is not a whole number")
        else -> error("$key is not a whole number")
    }

    /** A number, "min-max" text, or {"min", "max"} as Tailscale writes ranges; null when zero. */
    private fun range(key: String, value: Any): String? {
        val (min, max) = when (value) {
            is JSONObject -> whole(key, value.opt("min")) to whole(key, value.opt("max"))
            is String -> value.trim().split('-', limit = 2).map { whole(key, it) }.let { it.first() to it.last() }
            else -> whole(key, value).let { it to it }
        }
        require(min in 0..UInt.MAX_VALUE.toLong() && max in min..UInt.MAX_VALUE.toLong()) { "$key is not a valid range" }
        return when {
            max == 0L -> null
            min == max -> "$min"
            else -> "$min-$max"
        }
    }

    /** Tailscale writes the key as 64 hex digits, AmneziaWG configs as base64; the core takes base64. Zeros switch it off. */
    private fun headerProtectionKey(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val bytes = if (text.length == 64 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
        }
        require(bytes != null && bytes.size == 32) { "HeaderProtectionKey is not a 32-byte key" }
        if (bytes.all { it == 0.toByte() }) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

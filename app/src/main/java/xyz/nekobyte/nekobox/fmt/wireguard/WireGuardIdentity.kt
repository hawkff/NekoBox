package xyz.nekobyte.nekobox.fmt.wireguard

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigInteger
import java.util.Base64

/**
 * The WireGuard sessions a finished config opens. A server keeps one session per client key, so for every WireGuard
 * or AmneziaWG outbound and endpoint: its client public key with each of its peers' public keys, both from the
 * decoded key bytes, as hex. Two cores with one of these pairs take the session from each other. [all] holds every
 * session read, whatever the entries' tags; [unknown] when an entry's keys cannot be read, so nothing can be told
 * about what it opens, [malformed] too when that is because the config's outbounds and endpoints are no list of
 * objects, or an entry's fields other than its keys are not clear: a name in two spellings, a tag that is no text. [byTag] names each readable entry's sessions by the tag the core gives it, for status and paths.
 */
class WireGuardIdentities(val all: Set<String>, val unknown: Boolean, val byTag: Map<String, Set<String>>, val malformed: Boolean = false) {
    val isEmpty get() = all.isEmpty() && !unknown

    companion object {
        val NONE = WireGuardIdentities(emptySet(), false, emptyMap())
        internal fun malformed() = WireGuardIdentities(emptySet(), true, emptyMap(), malformed = true)
    }
}

private val wireGuardTypes = setOf("wireguard", "amneziawg")

// The fields an entry's sessions are read from.
private val keyFields = setOf("private_key", "peer_public_key", "peers")

/**
 * [WireGuardIdentities] of a config as the core will run it, after every custom JSON was merged in, read as the core
 * decodes it: names without regard to case, a missing or null list as none, an entry without a tag named by its index.
 */
fun wireGuardIdentities(config: String): WireGuardIdentities {
    val root = (runCatching { JsonParser.parseString(config) }.getOrNull() as? JsonObject)?.fields() ?: return WireGuardIdentities.malformed()
    val all = LinkedHashSet<String>()
    val byTag = LinkedHashMap<String, Set<String>>()
    var unknown = false
    for (list in listOf("outbounds", "endpoints")) {
        val entries = root[list]?.takeUnless { it.isJsonNull } ?: continue
        if (entries !is JsonArray) return WireGuardIdentities.malformed()
        entries.forEachIndexed { index, entry ->
            if (entry !is JsonObject) return WireGuardIdentities.malformed()
            // A name outside ASCII, which the core folds by Unicode's rules, may stand for any field.
            if (entry.keySet().any { name -> name.any { it.code >= 128 } }) return WireGuardIdentities.malformed()
            // Two spellings of a name other than a key's leave open what the entry is or what it is called.
            if (entry.keySet().groupBy { it.lowercase() }.any { (name, spellings) -> spellings.size > 1 && name !in keyFields }) return WireGuardIdentities.malformed()
            val fields = entry.fields()
            val type = fields?.text("type")?.lowercase()
            if (fields == null) {
                unknown = true
            } else if (type in wireGuardTypes) {
                val sessions = sessionsOf(fields)
                val tagField = fields["tag"]?.takeUnless { it.isJsonNull }
                val tag = if (tagField == null) "$index" else (tagField as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: return WireGuardIdentities.malformed()
                if (sessions == null) {
                    unknown = true
                } else {
                    all += sessions
                    byTag[tag] = byTag[tag].orEmpty() + sessions
                }
            }
        }
    }
    return WireGuardIdentities(all, unknown, byTag)
}

// Names as the core reads them, without regard to case: two that differ only in case leave open which one counts, and
// so does a name outside ASCII, which the core folds by Unicode's rules: a long s is an s.
private fun JsonObject.fields(): Map<String, JsonElement>? = entrySet().associate { it.key.lowercase() to it.value }.takeIf { it.size == size() && keySet().all { name -> name.all { it.code < 128 } } }

private fun Map<String, JsonElement>.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

private fun sessionsOf(outbound: Map<String, JsonElement>): Set<String>? {
    val client = outbound.text("private_key")?.let(::wireGuardKey)?.let(::x25519PublicKey) ?: return null
    val peers = mutableListOf<String>()
    // A legacy outbound names one peer at its top level, the others a list; both are taken, whichever the core uses.
    // An empty or null value is none, as the core reads it.
    when (val key = outbound["peer_public_key"]) {
        null, JsonNull.INSTANCE -> Unit
        else -> (key as? JsonPrimitive)?.takeIf { it.isString }?.asString?.let { if (it.isNotEmpty()) peers += it } ?: return null
    }
    when (val list = outbound["peers"]) {
        null, JsonNull.INSTANCE -> Unit
        is JsonArray -> list.forEach { peer -> peers += (peer as? JsonObject)?.fields()?.text("public_key") ?: return null }
        else -> return null
    }
    if (peers.isEmpty()) return null
    return peers.mapTo(LinkedHashSet()) { key -> "${client.hex()}-${(wireGuardKey(key) ?: return null).hex()}" }
}

/** A WireGuard key's 32 bytes, read leniently: whatever spelling the core accepts decodes to the same bytes. */
internal fun wireGuardKey(text: String): ByteArray? {
    val compact = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()?.takeIf { it.size == 32 }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private val p25519: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
private val a24 = BigInteger.valueOf(121665)

/** The X25519 public key of [privateKey] (RFC 7748), as WireGuard derives it. */
internal fun x25519PublicKey(privateKey: ByteArray): ByteArray {
    val scalar = privateKey.copyOf().apply {
        this[0] = (this[0].toInt() and 248).toByte()
        this[31] = ((this[31].toInt() and 127) or 64).toByte()
    }
    val k = BigInteger(1, scalar.reversedArray())
    val x1 = BigInteger.valueOf(9)
    var x2 = BigInteger.ONE
    var z2 = BigInteger.ZERO
    var x3 = x1
    var z3 = BigInteger.ONE
    var swap = false
    for (t in 254 downTo 0) {
        val bit = k.testBit(t)
        if (swap != bit) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        swap = bit
        val a = (x2 + z2).mod(p25519)
        val aa = a.multiply(a).mod(p25519)
        val b = (x2 - z2).mod(p25519)
        val bb = b.multiply(b).mod(p25519)
        val e = (aa - bb).mod(p25519)
        val c = (x3 + z3).mod(p25519)
        val d = (x3 - z3).mod(p25519)
        val da = d.multiply(a).mod(p25519)
        val cb = c.multiply(b).mod(p25519)
        x3 = (da + cb).pow(2).mod(p25519)
        z3 = x1.multiply((da - cb).pow(2)).mod(p25519)
        x2 = aa.multiply(bb).mod(p25519)
        z2 = e.multiply(aa + a24.multiply(e)).mod(p25519)
    }
    if (swap) {
        x2 = x3
        z2 = z3
    }
    val u = x2.multiply(z2.modPow(p25519 - BigInteger.valueOf(2), p25519)).mod(p25519)
    return u.toByteArray().reversedArray().copyOf(32)
}

package xyz.nekobyte.nekobox.fmt.wireguard

import org.json.JSONArray
import org.json.JSONObject
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.ktx.readableMessage

/** The service's answer: every outbound the profile runs, each with its peers or an error. */
internal fun wireGuardServiceStatus(
    running: Boolean,
    instances: List<ConfigBuildResult.WireGuardInstance>,
    query: (tag: String) -> String,
): String {
    val array = JSONArray()
    for (instance in instances) {
        val item = JSONObject().put("owner", instance.ownerProfileId)
        try {
            item.put("peers", JSONObject(query(instance.tag)).getJSONArray("peers"))
        } catch (e: Exception) {
            item.put("error", e.readableMessage)
        }
        array.put(item)
    }
    return JSONObject().put("running", running).put("instances", array).toString()
}

internal class WireGuardPeerState(
    val publicKey: String,
    val endpoint: String,
    // Unix milliseconds; 0 until the first handshake.
    val lastHandshake: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

internal class WireGuardInstanceState(val ownerProfileId: Long, val peers: List<WireGuardPeerState>, val error: String?)

internal class WireGuardServiceState(val running: Boolean, val instances: List<WireGuardInstanceState>) {
    /**
     * Whether a test that failed with [failure] did so for want of a handshake: it timed out, and an outbound of its
     * path sent handshakes and has none completed, with none of its peers. A failure with a reason of its own, or an
     * outbound with an answered peer, which a peer it never needed proves nothing against, keeps its own message.
     */
    fun noCompletedHandshake(failure: String): Boolean {
        val message = failure.lowercase()
        if ("deadline exceeded" !in message && "timeout" !in message && "timed out" !in message) return false
        return instances.any { instance -> instance.peers.any { it.txBytes > 0 } && instance.peers.all { it.lastHandshake == 0L } }
    }
}

internal fun parseWireGuardServiceStatus(json: String): WireGuardServiceState {
    val root = JSONObject(json)
    val instances = root.getJSONArray("instances")
    return WireGuardServiceState(
        root.getBoolean("running"),
        (0 until instances.length()).map { index ->
            val instance = instances.getJSONObject(index)
            val peers = instance.optJSONArray("peers") ?: JSONArray()
            WireGuardInstanceState(
                instance.getLong("owner"),
                (0 until peers.length()).map {
                    val peer = peers.getJSONObject(it)
                    WireGuardPeerState(
                        peer.getString("publicKey"),
                        peer.optString("endpoint"),
                        peer.getLong("lastHandshake"),
                        peer.getLong("rxBytes"),
                        peer.getLong("txBytes"),
                    )
                },
                instance.optString("error").takeIf { instance.has("error") },
            )
        },
    )
}

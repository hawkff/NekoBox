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
     * Whether a test that [timedOut] did so for want of a handshake: an outbound of its path sent during the test,
     * that is since [before] (the same outbounds in the same order; a core started for the test has none before it),
     * and none of its peers has a session at [now]. A failure with a reason of its own, or an outbound with a live
     * session, which a peer the test never needed proves nothing against, keeps its own message.
     */
    fun noCompletedHandshake(before: WireGuardServiceState, timedOut: Boolean, now: Long): Boolean {
        if (!timedOut) return false
        return instances.withIndex().any { (index, instance) ->
            val earlier = before.instances.getOrNull(index)?.peers.orEmpty().associateBy { it.publicKey }
            val sent = instance.peers.any { it.txBytes > (earlier[it.publicKey]?.txBytes ?: 0L) }
            sent && instance.peers.none { it.lastHandshake != 0L && now - it.lastHandshake < WIREGUARD_SESSION_MS }
        }
    }

    companion object {
        val NONE = WireGuardServiceState(true, emptyList())
    }
}

// How long WireGuard keeps the keys of a handshake (REJECT_AFTER_TIME): an older one leaves the peer without a session.
private const val WIREGUARD_SESSION_MS = 180_000L

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

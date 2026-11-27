package xyz.nekobyte.nekobox.bg.proto

import org.json.JSONObject
import xyz.nekobyte.nekobox.Protocols
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.wireguard.parseWireGuardServiceStatus
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardServiceStatus
import xyz.nekobyte.nekobox.ktx.readableMessage

/** What [testRunningPath] needs of the service's running core. */
internal interface RunningCore {
    /** Whether the core is still the one the service runs connected. */
    val running: Boolean

    /** Waits for the Tailscale nodes on the profile's path, as a test through them needs; throws when they are not ready. */
    fun awaitTailscaleNodes(profileId: Long)

    fun urlTest(tag: String): Int

    fun wireGuardStatus(tag: String): String

    fun fetchText(tag: String, link: String): String?
}

/**
 * The service's side of a test through its core ([UrlTest.viaRunningCore]): the reply the binder carries
 * (INekoBoxService.testWireGuardProfile), for the path [fingerprint] names.
 */
internal fun testRunningPath(config: ConfigBuildResult, core: RunningCore, profileId: Long, fingerprint: String, answerUrl: String): JSONObject {
    val result = JSONObject()
    val tag = config.profileTagMap[profileId]
    val path = config.paths[profileId]
    if (tag == null || path == null) return result.put("result", "not_running")
    // A result is for the path the caller was asked to test, which an edit since the start does not reach.
    if (path.fingerprint == null || path.fingerprint != fingerprint) return result.put("result", "other_settings")
    val latency = try {
        // Right after the connect a Tailscale node on the path may still be logging in or picking its exit node.
        core.awaitTailscaleNodes(profileId)
        core.urlTest(tag)
    } catch (e: Exception) {
        // A stop that closed the core under the test: the caller reports the stop and starts no core of its own.
        if (!core.running) return result.put("result", "stopping")
        val owned = config.wireguardInstances.values.flatten().filter { it.ownerProfileId == profileId }
        val status = wireGuardServiceStatus(true, owned) { core.wireGuardStatus(it) }
        if (parseWireGuardServiceStatus(status).noCompletedHandshake(e.readableMessage)) return result.put("result", "no_handshake")
        return result.put("result", "failed").put("error", Protocols.genFriendlyMsg(e.readableMessage))
    }
    // A page that cannot be read leaves the answer unknown; the test itself passed.
    val answer = answerUrl.takeIf { it.isNotEmpty() }?.let { runCatching { core.fetchText(tag, it) }.getOrNull() }
    return result.put("result", "tested").put("latencyMs", latency).putOpt("answer", answer)
}

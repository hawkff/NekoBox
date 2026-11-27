package xyz.nekobyte.nekobox.bg.proto

import org.json.JSONObject
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardServiceState
import xyz.nekobyte.nekobox.fmt.wireguard.parseWireGuardServiceStatus
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardServiceStatus
import xyz.nekobyte.nekobox.ktx.readableMessage

/** What [testRunningPath] needs of the service's running core. */
internal interface RunningCore {
    /** Whether the core is still the one the service runs connected. */
    val running: Boolean

    /** Waits for the Tailscale nodes on the profile's path, as a test through them needs; throws when they are not ready. */
    fun awaitTailscaleNodes(profileId: Long)

    /** A test through the outbound [tag], or through the core's default route when null. */
    fun urlTest(tag: String?): TestOutcome

    fun wireGuardStatus(tag: String): String

    fun fetchText(tag: String?, link: String): String?

    /** Milliseconds of a clock that only goes forward, for waits. */
    fun elapsedRealtime(): Long

    /** Unix milliseconds, for the times of handshakes. */
    fun wallClock(): Long

    fun sleep(ms: Long)
}

/** A connection test: the latency, or the failure and whether the test ran out of time. */
internal class TestOutcome(val latency: Int, val error: String, val timedOut: Boolean)

// A peer without a session sends a handshake again after five seconds (REKEY_TIMEOUT) and a little jitter: a timed-out
// test looks this long at its path, so a server that does not answer shows what was sent to it.
internal const val HANDSHAKE_RETRY_WINDOW_MS = 5_500L

/**
 * The service's side of a test through its core ([UrlTest.viaRunningCore]): the reply the binder carries
 * (INekoBoxService.testWireGuardProfile), for the path [fingerprint] names.
 */
internal fun testRunningPath(config: ConfigBuildResult, core: RunningCore, profileId: Long, fingerprint: String, answerUrl: String): JSONObject {
    val result = JSONObject()
    val path = config.paths[profileId] ?: return result.put("result", "not_running")
    // A full custom config runs as written, on its default route.
    val tag = if (path.defaultRoute) null else config.profileTagMap[profileId] ?: return result.put("result", "not_running")
    // A result is for the path the caller was asked to test, which an edit since the start does not reach. A path
    // that cannot be compared, on either side, a reconnect would not make comparable.
    if (path.fingerprint == null || fingerprint.isEmpty()) return result.put("result", "unverifiable")
    // A reconnect takes saved settings that changed since the start, not a difference in how the path runs.
    if (path.fingerprint != fingerprint) return result.put("result", if (path.fingerprint.substringBefore('.') != fingerprint.substringBefore('.')) "other_settings" else "unverifiable")
    // Unknown for a full custom config: its failures keep their own message.
    val wireGuardPath = config.wireGuardPath(profileId).orEmpty()
    fun status() = parseWireGuardServiceStatus(wireGuardServiceStatus(true, wireGuardPath) { core.wireGuardStatus(it) })
    // What the path had sent before the test; null when it could not all be read, which leaves nothing to compare.
    var before: WireGuardServiceState? = null
    var started = 0L
    val outcome = try {
        // Right after the connect a Tailscale node on the path may still be logging in or picking its exit node.
        core.awaitTailscaleNodes(profileId)
        before = status().takeIf { state -> wireGuardPath.isNotEmpty() && state.instances.none { it.error != null } }
        started = core.elapsedRealtime()
        core.urlTest(tag)
    } catch (e: Exception) {
        TestOutcome(0, e.readableMessage, false)
    }
    if (outcome.error.isNotEmpty()) {
        // A stop that closed the core under the test: the caller reports the stop and starts no core of its own.
        if (!core.running) return result.put("result", "stopping")
        val baseline = before
        if (outcome.timedOut && baseline != null) {
            // A stop in the window ends it: the closed core's state says nothing of the server.
            while (true) {
                if (!core.running) return result.put("result", "stopping")
                val left = started + HANDSHAKE_RETRY_WINDOW_MS - core.elapsedRealtime()
                if (left <= 0) break
                core.sleep(minOf(left, 250))
            }
            val after = status()
            if (!core.running) return result.put("result", "stopping")
            if (after.noCompletedHandshake(baseline, true, core.wallClock())) return result.put("result", "no_handshake")
        }
        if (!core.running) return result.put("result", "stopping")
        return result.put("result", "failed").put("error", outcome.error)
    }
    // A page that cannot be read leaves the answer unknown; the test itself passed.
    val answer = answerUrl.takeIf { it.isNotEmpty() }?.let { runCatching { core.fetchText(tag, it) }.getOrNull() }
    return result.put("result", "tested").put("latencyMs", outcome.latency).putOpt("answer", answer)
}

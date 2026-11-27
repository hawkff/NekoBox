package xyz.nekobyte.nekobox.bg.proto

import android.os.RemoteException
import kotlinx.coroutines.delay
import org.json.JSONObject
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig
import xyz.nekobyte.nekobox.ktx.app
import java.io.IOException

class UrlTest(private val service: () -> INekoBoxService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    /**
     * [doTest], which also reads [answerUrl] through the profile once the test has passed: the latency and that
     * answer, null when it could not be read. A profile whose Tailscale node the service drives gives no answer.
     */
    suspend fun check(profile: ProxyEntity, answerUrl: String, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Pair<Int, String?> {
        if (prepared.tailscaleEndpoints.isNotEmpty()) return doTest(profile, prepared) to null
        return try {
            val instance = TestInstance(profile, link, timeout, prepared, answerUrl)
            instance.doTest() to instance.answer
        } catch (_: WireGuardSessionsUnavailable) {
            viaRunningCore(profile, prepared, answerUrl)
        }
    }

    suspend fun doTest(profile: ProxyEntity, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Int = try {
        isolated(profile, prepared)
    } catch (_: WireGuardSessionsUnavailable) {
        viaRunningCore(profile, prepared).first
    }

    // A core of the test's own. It takes the config's WireGuard sessions as it starts, after the Tailscale state and
    // with nothing waited for meanwhile, in the order the VPN's core does.
    private suspend fun isolated(profile: ProxyEntity, prepared: ConfigBuildResult): Int {
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TestInstance(profile, link, timeout, prepared).doTest()
        }
    }

    // A WireGuard or AmneziaWG server keeps one session per client key and server key, which a core holds for its
    // life. When one of the profile's is held, the profile is tested through the VPN's core if that runs the path
    // prepared for this test, and reads [answerUrl] through it as [check] does; otherwise the test fails with the
    // reason.
    internal suspend fun viaRunningCore(profile: ProxyEntity, prepared: ConfigBuildResult, answerUrl: String? = null): Pair<Int, String?> {
        val refusal = if (prepared.wireGuardIdentities.unknown) {
            ConnectionTestFailure("wireguard_unknown_key", app.getString(R.string.connection_test_wireguard_unknown_key))
        } else {
            ConnectionTestFailure("wireguard_in_use", app.getString(R.string.connection_test_wireguard_in_use))
        }
        val running = connectedService() ?: throw refusal
        val fingerprint = prepared.paths[profile.id]?.fingerprint.orEmpty()
        val reply = try {
            JSONObject(running.testWireGuardProfile(profile.id, fingerprint, answerUrl.orEmpty()))
        } catch (_: RemoteException) {
            throw vpnChanging()
        }
        return when (reply.optString("result")) {
            "tested" -> reply.getInt("latencyMs") to reply.optString("answer").takeIf { reply.has("answer") }
            "not_running" -> throw refusal
            "other_settings" -> throw ConnectionTestFailure("reconnect_required", app.getString(R.string.connection_test_reconnect))
            "stopping" -> throw vpnChanging()
            "no_handshake" -> throw NoHandshakeException(timeout, IOException(reply.optString("error")))
            else -> throw IOException(reply.optString("error"))
        }
    }

    // The service when it is connected, null when it is stopped, waiting a moment for one starting or stopping.
    private suspend fun connectedService(): INekoBoxService? {
        repeat(SERVICE_SETTLE_ROUNDS) {
            val binder = service()
            when (binder?.let { runCatching { BaseService.State.values()[it.state] }.getOrNull() }) {
                BaseService.State.Stopped -> return null
                BaseService.State.Connected -> return binder
                else -> delay(100)
            }
        }
        throw vpnChanging()
    }

    private fun vpnChanging() = ConnectionTestFailure("vpn_changing", app.getString(R.string.connection_test_vpn_changing))

    private companion object {
        // Rounds of 100 ms.
        const val SERVICE_SETTLE_ROUNDS = 50
    }
}

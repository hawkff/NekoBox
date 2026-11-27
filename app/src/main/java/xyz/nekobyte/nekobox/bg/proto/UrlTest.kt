package xyz.nekobyte.nekobox.bg.proto

import android.os.RemoteException
import kotlinx.coroutines.delay
import org.json.JSONObject
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
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
        if (!prepared.wireGuardIdentities.isEmpty) viaRunningCore(profile, prepared, answerUrl)?.let { return it }
        val instance = TestInstance(profile, link, timeout, prepared, answerUrl)
        return instance.doTest() to instance.answer
    }

    suspend fun doTest(profile: ProxyEntity, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Int {
        val wireGuard = !prepared.wireGuardIdentities.isEmpty
        if (wireGuard) viaRunningCore(profile, prepared)?.let { return it.first }
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        // A path with WireGuard hops is tested through the service's core only as prepared, the Tailscale node's too.
        val viaService: (INekoBoxService) -> Int = if (wireGuard) {
            { throughService(it, profile, prepared, null)?.first ?: error(PROFILE_NOT_RUNNING) }
        } else {
            { it.urlTestProfile(profile.id) }
        }
        return TailscaleAccess.run(service, nodes, viaService) { TestInstance(profile, link, timeout, prepared).doTest() }
    }

    // A WireGuard or AmneziaWG server keeps one session per client key and server key, which a core holds for its
    // life. A profile with such hops is tested through the connected service's core when that runs the path prepared
    // for this test, also when its sessions are free (another key of a connected profile is still another path), and
    // reads [answerUrl] through it as [check] does. Null only when the service is stopped or does not run the
    // profile: then the test may have a core of its own, which takes the sessions as it starts or fails with the reason.
    internal suspend fun viaRunningCore(profile: ProxyEntity, prepared: ConfigBuildResult, answerUrl: String? = null): Pair<Int, String?>? {
        val running = connectedService() ?: return null
        return throughService(running, profile, prepared, answerUrl)
    }

    private fun throughService(running: INekoBoxService, profile: ProxyEntity, prepared: ConfigBuildResult, answerUrl: String?): Pair<Int, String?>? {
        val fingerprint = prepared.paths[profile.id]?.fingerprint.orEmpty()
        val reply = try {
            JSONObject(running.testWireGuardProfile(profile.id, fingerprint, answerUrl.orEmpty()))
        } catch (_: RemoteException) {
            throw vpnChanging()
        }
        return when (reply.optString("result")) {
            "tested" -> reply.getInt("latencyMs") to reply.optString("answer").takeIf { reply.has("answer") }
            "not_running" -> null
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

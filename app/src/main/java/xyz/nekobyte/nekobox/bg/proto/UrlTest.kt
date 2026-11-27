package xyz.nekobyte.nekobox.bg.proto

import kotlinx.coroutines.delay
import org.json.JSONObject
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.CORE_NOT_STARTED
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
import xyz.nekobyte.nekobox.bg.PROFILE_RUNS_OTHER_SETTINGS
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig
import xyz.nekobyte.nekobox.ktx.app

class UrlTest(private val service: () -> INekoBoxService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    /**
     * [doTest], which also reads [answerUrl] through the profile once the test has passed: the latency and that
     * answer, null when it could not be read. A profile the service tests through its own core gives no answer.
     */
    suspend fun check(profile: ProxyEntity, answerUrl: String, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Pair<Int, String?> {
        if (prepared.tailscaleEndpoints.isNotEmpty()) return doTest(profile, prepared) to null
        viaRunningCore(profile, prepared)?.let { return it to null }
        val instance = TestInstance(profile, link, timeout, prepared, answerUrl)
        return instance.doTest() to instance.answer
    }

    suspend fun doTest(profile: ProxyEntity, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Int {
        viaRunningCore(profile, prepared)?.let { return it }
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TestInstance(profile, link, timeout, prepared).doTest()
        }
    }

    // A WireGuard or AmneziaWG server keeps one session per client key: a core of its own for a profile the
    // connected service runs would take that session from the tunnel. The service tests such a profile through its
    // core, when it runs it as saved; null when the profile has no such hop, the service is stopped or it does not
    // run the profile.
    internal suspend fun viaRunningCore(profile: ProxyEntity, prepared: ConfigBuildResult): Int? {
        if (prepared.wireguardInstances.isEmpty()) return null
        val running = connectedService() ?: return null
        val answer = try {
            JSONObject(running.testWireGuardProfile(profile.id))
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            when {
                PROFILE_NOT_RUNNING in message -> return null

                // Stopping: no isolated retry, the core may still hold the session.
                CORE_NOT_STARTED in message -> throw vpnChanging()

                // An outbound of another core would take the session: nothing tests the saved settings now.
                PROFILE_RUNS_OTHER_SETTINGS in message -> throw ConnectionTestFailure("reconnect_required", app.getString(R.string.connection_test_reconnect))

                else -> throw e
            }
        }
        return answer.getInt("latencyMs")
    }

    // The service when it is connected, null when it is stopped. A missing binder, or a service that is starting or
    // stopping, tells neither: its core may already run or still run the profile. They get a moment to settle.
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

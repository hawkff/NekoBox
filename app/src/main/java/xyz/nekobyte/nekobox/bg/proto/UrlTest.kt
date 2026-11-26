package xyz.nekobyte.nekobox.bg.proto

import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.PROFILE_NOT_RUNNING
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig

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
    // core; null when the profile has no such hop or the service does not run it.
    internal fun viaRunningCore(profile: ProxyEntity, prepared: ConfigBuildResult): Int? {
        if (prepared.wireguardInstances.isEmpty()) return null
        val running = service()?.takeIf { BaseService.State.values()[it.state] == BaseService.State.Connected } ?: return null
        return try {
            running.urlTestProfile(profile.id)
        } catch (e: IllegalStateException) {
            if (e.message?.contains(PROFILE_NOT_RUNNING) != true) throw e
            null
        }
    }
}

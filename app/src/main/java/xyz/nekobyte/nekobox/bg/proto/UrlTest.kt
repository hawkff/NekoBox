package xyz.nekobyte.nekobox.bg.proto

import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig

class UrlTest(private val service: () -> INekoBoxService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    /**
     * [doTest], which also reads [answerUrl] through the profile once the test has passed: the latency and that
     * answer, null when it could not be read. A profile whose Tailscale node the service drives gives no answer.
     */
    suspend fun check(profile: ProxyEntity, answerUrl: String, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Pair<Int, String?> {
        if (prepared.tailscaleEndpoints.isNotEmpty()) return doTest(profile, prepared) to null
        val instance = TestInstance(profile, link, timeout, prepared, answerUrl)
        return instance.doTest() to instance.answer
    }

    suspend fun doTest(profile: ProxyEntity, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Int {
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TestInstance(profile, link, timeout, prepared).doTest()
        }
    }
}

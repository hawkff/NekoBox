package xyz.nekobyte.nekobox.bg.proto

import xyz.nekobyte.nekobox.aidl.INekoBoxService
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.buildConfig

class UrlTest(private val service: () -> INekoBoxService? = { null }) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity, prepared: ConfigBuildResult = buildConfig(profile, forTest = true)): Int {
        val nodes = prepared.tailscaleEndpoints.keys
        if (nodes.isEmpty()) return TestInstance(profile, link, timeout, prepared).doTest()
        return TailscaleAccess.run(service, nodes, { it.urlTestProfile(profile.id) }) {
            TestInstance(profile, link, timeout, prepared).doTest()
        }
    }
}

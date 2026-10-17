package xyz.nekobyte.nekobox.fmt

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import java.io.File

/** The rules the app creates on first start use geosite: and geoip: rule sets that the core must find in the bundled databases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GeoRuleSetConfigTest {

    @Test
    fun defaultRulesBuildAConfigForTheCoreCheck() {
        ConfigBuilderTestEnv.reset()
        val profile = ConfigBuilderTestEnv.io {
            val node = SOCKSBean().applyDefaultValues().apply {
                name = "proxy"
                serverAddress = "192.0.2.1"
                serverPort = 1080
            }
            ProxyEntity(groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup())).putBean(node).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        }
        // Outside China the defaults bypass China, Iran and Russia; all of them are switched on here.
        ConfigBuilderTestEnv.io {
            DataStore.rulesFirstCreate = false
            val rules = runBlocking { ProfileManager.getRules() }
            ProfileDatabase.rulesDao.updateRules(rules.map { it.copy(enabled = true) })
        }
        val config = ConfigBuilderTestEnv.io { buildConfig(profile, forExport = true) }.config
        File("build/generated-core-configs/routing").apply { mkdirs() }.resolve("defaults.json").writeText(config)
        val ruleSets = JSONObject(config).getJSONObject("route").getJSONArray("rule_set")
        val paths = (0 until ruleSets.length()).associate { ruleSets.getJSONObject(it).let { set -> set.getString("tag") to set.getString("path") } }
        val expected = listOf(
            "geosite:category-ads-all",
            "geosite:cn",
            "geosite:category-ir",
            "geosite:category-ru",
            "geoip:cn",
            "geoip:ir",
            "geoip:ru",
        )
        assertEquals(expected.sorted(), paths.keys.sorted())
        assertEquals(paths.keys, paths.values.toSet())
    }
}

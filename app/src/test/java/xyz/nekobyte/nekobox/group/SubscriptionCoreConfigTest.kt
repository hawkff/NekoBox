package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.buildConfig
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStreamSettings
import java.io.File

/**
 * Builds configurations from imported subscription nodes the way the app does. The files written to
 * build/generated-core-configs are loaded by the core in libcore's TestGeneratedApplicationConfigs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionCoreConfigTest {

    @Test
    fun clashXhttpNodesBuildConfigsWithTheCoreSchemaTypes() = runTest {
        ConfigBuilderTestEnv.reset()
        val bean = RawUpdater.parseRaw(
            """
            proxies:
              - name: typed
                type: vless
                server: 192.0.2.1
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                tls: "true"
                servername: front.example
                network: xhttp
                xhttp-opts:
                  path: /x
                  mode: packet-up
                  no-grpc-header: "true"
                  x-padding-bytes: 100-1000
                  sc-max-each-post-bytes: 1000000
                  reuse-settings:
                    max-concurrency: 16-32
                    h-keep-alive-period: "30"
                smux:
                  enabled: false
            """.trimIndent(),
        )!!.single() as VMessBean

        val transport = SingBoxOptions.toJsonTree(buildSingBoxOutboundStreamSettings(bean)!!)
        assertTrue(transport["no_grpc_header"].asJsonPrimitive.isBoolean)
        assertEquals("100-1000", transport["x_padding_bytes"].asString)
        assertTrue(transport["sc_max_each_post_bytes"].asJsonPrimitive.isNumber)
        val xmux = transport["xmux"].asJsonObject
        assertEquals("16-32", xmux["max_concurrency"].asString)
        assertTrue(xmux["h_keep_alive_period"].asJsonPrimitive.isNumber)
        assertEquals(30L, xmux["h_keep_alive_period"].asLong)

        val config = ConfigBuilderTestEnv.io { buildConfig(ProxyEntity(id = 1L).putBean(bean), forTest = true).config }
        File("build/generated-core-configs/subscriptions").apply { mkdirs() }.resolve("clash-xhttp.json").writeText(config)
    }
}

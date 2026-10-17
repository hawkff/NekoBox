package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File
import java.net.URLEncoder

/**
 * Builds configurations from imported subscription nodes the way the app does. The files written to
 * build/generated-core-configs are loaded by the core in libcore's TestGeneratedApplicationConfigs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionCoreConfigTest {

    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

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

    @Test
    fun mkcpAndAmneziaWg31NodesBuildConfigsTheCoreLoads() = runTest {
        ConfigBuilderTestEnv.reset()
        val fm = """{"udp":[{"type":"mkcp-legacy","settings":{"header":"","value":""}},{"type":"mkcp-legacy","settings":{"header":"dtls","value":""}}]}"""
        val key = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        val awg = "jc: 4, jmin: 40, jmax: 70, s1: 50, s2: 90, s3: 20, s4: 16, h1: '1234567', h2: '623456789', h3: '1234567890', h4: '1934567890', " +
            "header-protection-key: '$key', content-padding-addition: 8-24, rekey-after-time: 110-130, reject-after-time: 170-200, random-trailers: true"
        val nodes = RawUpdater.parseRaw(
            "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:20026?type=kcp&security=none&encryption=none&mtu=1350&tti=20&fm=" +
                URLEncoder.encode(fm, "UTF-8") + "#kcp",
        )!! + RawUpdater.parseRaw(
            "proxies:\n  - {name: awg31, type: wireguard, server: 192.0.2.2, port: 20083, ip: 10.8.3.2, private-key: '$key', public-key: '$key', amnezia-wg-option: {$awg}}",
        )!!
        assertEquals(listOf("kcp", "awg31"), nodes.map { it.name })
        for (bean in nodes) {
            val config = ConfigBuilderTestEnv.io { buildConfig(ProxyEntity(id = 1L).putBean(bean), forTest = true).config }
            File("build/generated-core-configs/subscriptions").apply { mkdirs() }.resolve("${bean.name}.json").writeText(config)
        }
    }
}

package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.KryoConverters
import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues

/** Xray JSON subscriptions, as 3x-ui and other Xray panels serve them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class XrayJsonImportTest {

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

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/subscriptions/$name")).readText()

    private fun AbstractBean.bytes() = KryoConverters.serialize(applyDefaultValues())

    @Test
    fun panelConfigsImportAsTheirShareLinks() = runTest {
        // The same nodes as 3x-ui writes them in its JSON and its link subscription.
        val json = RawUpdater.parseImport(fixture("3x-ui-xray.json"))!!
        val links = RawUpdater.parseImport(fixture("3x-ui-links.txt"))!!.proxies
        assertEquals(links.map { it.name }, json.proxies.map { it.name })
        assertEquals(emptyList<String?>(), json.report.failed)
        assertEquals(
            listOf(ImportPreview.Section.RULES, ImportPreview.Section.DNS, ImportPreview.Section.INBOUNDS),
            ImportPreview.of(json).dropped,
        )
        for ((fromJson, fromLink) in json.proxies.zip(links)) {
            when (fromJson) {
                // Links carry no hop interval; the config sets it.
                is HysteriaBean -> if (fromJson.name == "hy2-porthop") {
                    assertEquals(30, fromJson.hopInterval)
                    (fromLink as HysteriaBean).hopInterval = 30
                }

                // The config lists the routes that the link leaves to the full-tunnel default.
                is WireGuardBean -> {
                    fromLink as WireGuardBean
                    assertEquals(
                        listOf(fromLink.serverAddress, fromLink.serverPort, fromLink.privateKey, fromLink.peerPublicKey, fromLink.localAddress, fromLink.mtu),
                        listOf(fromJson.serverAddress, fromJson.serverPort, fromJson.privateKey, fromJson.peerPublicKey, fromJson.localAddress, fromJson.mtu),
                    )
                    continue
                }
            }
            assertArrayEquals(fromJson.name, fromLink.bytes(), fromJson.bytes())
        }
    }

    private fun config(vararg outbounds: JSONObject) = JSONObject().put("remarks", "node")
        .put("outbounds", JSONArray(outbounds.toList()).put(JSONObject().put("protocol", "freedom").put("tag", "direct")))
        .toString()

    private fun vless(stream: JSONObject = JSONObject().put("network", "tcp").put("security", "none")) = JSONObject()
        .put("protocol", "vless").put("tag", "proxy")
        .put(
            "settings",
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject().put("address", "192.0.2.1").put("port", 443)
                        .put("users", JSONArray().put(JSONObject().put("id", UUID).put("encryption", "none").put("flow", "xtls-rprx-vision"))),
                ),
            ),
        )
        .put("streamSettings", stream)

    @Test
    fun olderLayoutsAndPlainProxiesImport() = runTest {
        // vnext with one user, as older Xray configs write VLESS.
        val node = RawUpdater.parseImport(config(vless()))!!.proxies.single() as VMessBean
        assertEquals("node", node.name)
        assertEquals("192.0.2.1", node.serverAddress)
        assertEquals(UUID, node.uuid)
        assertEquals("xtls-rprx-vision", node.encryption)

        val socks = JSONObject().put("protocol", "socks").put(
            "settings",
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", "192.0.2.2").put("port", 1080).put("users", JSONArray().put(JSONObject().put("user", "u").put("pass", "p"))),
                ),
            ),
        )
        val http = JSONObject().put("protocol", "http").put("settings", JSONObject().put("address", "192.0.2.3").put("port", 8443))
            .put("streamSettings", JSONObject().put("security", "tls").put("tlsSettings", JSONObject().put("serverName", "proxy.example")))
        val proxies = RawUpdater.parseImport(config(socks.put("tag", "a"), http.put("tag", "b")))!!.proxies
        // With several servers in one config, each keeps its tag.
        assertEquals(listOf("node a", "node b"), proxies.map { it.name })
        assertEquals("p", (proxies[0] as SOCKSBean).password)
        assertEquals("proxy.example", (proxies[1] as HttpBean).sni)

        val wireGuard = JSONObject().put("protocol", "wireguard").put(
            "settings",
            JSONObject().put("secretKey", KEY).put("address", JSONArray().put("10.0.0.2/32"))
                .put("reserved", JSONArray().put(1).put(2).put(3)).put("peers", JSONArray().put(JSONObject().put("publicKey", KEY).put("endpoint", "192.0.2.4:51820"))),
        )
        val wg = RawUpdater.parseImport(config(wireGuard))!!.proxies.single() as WireGuardBean
        assertEquals("192.0.2.4", wg.serverAddress)
        assertEquals("1,2,3", wg.reserved)
        // A single outbound that cannot import gives no profile.
        assertNull(RawUpdater.parseImport(JSONObject().put("protocol", "vless").put("settings", JSONObject()).toString()))
        // A single outbound, not inside a config, imports too.
        assertEquals("192.0.2.2", (RawUpdater.parseImport(socks.toString())!!.proxies.single() as SOCKSBean).serverAddress)
    }

    @Test
    fun settingsTheCoreCannotApplyRefuseTheNode() = runTest {
        val reality = JSONObject().put("network", "tcp").put("security", "reality").put(
            "realitySettings",
            JSONObject()
                .put("serverName", "www.example.com").put("publicKey", "key").put("shortId", "ab"),
        )
        val refused = listOf(
            vless(JSONObject(reality.toString()).apply { getJSONObject("realitySettings").put("mldsa65Verify", "verify") }),
            vless(JSONObject().put("network", "grpc").put("security", "none").put("grpcSettings", JSONObject().put("serviceName", "s").put("authority", "cdn.example"))),
            vless(JSONObject().put("network", "tcp").put("security", "tls").put("tlsSettings", JSONObject().put("pinnedPeerCertSha256", "abc"))),
            vless(JSONObject().put("network", "ws").put("security", "none").put("finalmask", JSONObject().put("tcp", JSONArray()))),
            vless(JSONObject().put("network", "tcp").put("security", "none").put("sockopt", JSONObject().put("dialerProxy", "other"))),
            JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("address", "192.0.2.1").put("port", 443).put("id", UUID).put("alterId", 64)),
            // A proxy whose security the profile cannot hold would send its credentials in clear text.
            JSONObject().put("protocol", "http").put("settings", JSONObject().put("address", "192.0.2.3").put("port", 8443))
                .put("streamSettings", JSONObject().put("security", "reality")),
            // Certificate constraints are refused for every protocol, not only those with share-link checks.
            JSONObject().put("protocol", "shadowsocks").put(
                "settings",
                JSONObject().put("address", "192.0.2.5").put("port", 443)
                    .put("method", "aes-256-gcm").put("password", "p"),
            )
                .put(
                    "streamSettings",
                    JSONObject().put("network", "ws").put("security", "tls")
                        .put("tlsSettings", JSONObject().put("pinnedPeerCertSha256", "abc")),
                ),
            JSONObject().put("protocol", "hysteria").put("settings", JSONObject().put("address", "192.0.2.6").put("port", 443).put("version", 2))
                .put(
                    "streamSettings",
                    JSONObject().put("network", "hysteria").put("security", "tls")
                        .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", "a"))
                        .put("tlsSettings", JSONObject().put("verifyPeerCertInNames", JSONArray().put("node.example"))),
                ),
        )
        for ((index, outbound) in refused.withIndex()) {
            val other = JSONObject().put("protocol", "vless").put("tag", "other").put("settings", JSONObject())
            val parsed = RawUpdater.parseImport(config(outbound, other))
            assertTrue("case $index", parsed == null || parsed.proxies.none { it.name == "node proxy" || it.name == "node" })
        }

        // A local fragmenting outbound in front imports the node and reports what it drops.
        val fragment = JSONObject().put("protocol", "freedom").put("tag", "fragment")
            .put("settings", JSONObject().put("fragment", JSONObject().put("packets", "tlshello").put("length", "100-200")))
        val parsed = RawUpdater.parseImport(config(vless(JSONObject(reality.toString()).put("sockopt", JSONObject().put("dialerProxy", "fragment"))), fragment))!!
        assertEquals("www.example.com", (parsed.proxies.single() as VMessBean).sni)
        assertEquals(listOf(ImportPreview.Section.FRAGMENT), ImportPreview.of(parsed).dropped)
        assertNull(parsed.report.failed.firstOrNull())
        // A freedom outbound that redirects the connection cannot be left out.
        val redirect = JSONObject().put("protocol", "freedom").put("tag", "fragment").put("settings", JSONObject().put("redirect", "203.0.113.1:443"))
        assertTrue(RawUpdater.parseImport(config(vless(JSONObject(reality.toString()).put("sockopt", JSONObject().put("dialerProxy", "fragment"))), redirect)) == null)
        // A node that does not import reports no dropped fragmentation.
        val unsupported = vless(
            JSONObject().put("network", "grpc").put("security", "none").put("sockopt", JSONObject().put("dialerProxy", "fragment"))
                .put("grpcSettings", JSONObject().put("serviceName", "s").put("authority", "cdn.example")),
        )
        val failed = RawUpdater.parseImport(config(unsupported, fragment, vless()))!!
        assertTrue(ImportPreview.of(failed).dropped.isEmpty())
    }

    @Test
    fun olderSplitHttpSettingsKeepTheirPath() = runTest {
        val stream = JSONObject().put("network", "splithttp").put("security", "none")
            .put("splithttpSettings", JSONObject().put("path", "/sh").put("host", "cdn.example").put("mode", "packet-up"))
        val node = RawUpdater.parseImport(config(vless(stream)))!!.proxies.single() as VMessBean
        assertEquals("xhttp", node.type)
        assertEquals("/sh", node.path)
        assertEquals("cdn.example", node.host)
        assertEquals("packet-up", node.xhttpMode)
    }

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    }
}

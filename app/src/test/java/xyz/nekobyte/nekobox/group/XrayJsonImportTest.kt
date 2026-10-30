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

    private fun vless(stream: JSONObject = JSONObject().put("network", "tcp").put("security", "none"), address: String = "192.0.2.1") = JSONObject()
        .put("protocol", "vless").put("tag", "proxy")
        .put(
            "settings",
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject().put("address", address).put("port", 443)
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

        // A local fragmenting outbound in front: the profile splits the ClientHello itself.
        val fragment = JSONObject().put("protocol", "freedom").put("tag", "fragment")
            .put("settings", JSONObject().put("fragment", JSONObject().put("packets", "tlshello").put("length", "100-200")))
        val behindFragment = vless(JSONObject(reality.toString()).put("sockopt", JSONObject().put("dialerProxy", "fragment")))
        val parsed = RawUpdater.parseImport(config(behindFragment, fragment))!!
        val fragmented = parsed.proxies.single() as VMessBean
        assertEquals(listOf("www.example.com", true), listOf(fragmented.sni, fragmented.tlsFragment))
        assertTrue(ImportPreview.of(parsed).dropped.isEmpty())
        assertNull(parsed.report.failed.firstOrNull())
        // UDP noise is no setting of the node, so the import reports it.
        val noise = JSONObject().put("protocol", "freedom").put("tag", "fragment")
            .put("settings", JSONObject().put("noises", JSONArray().put(JSONObject().put("type", "rand").put("packet", "10-20"))))
        assertEquals(listOf(ImportPreview.Section.NOISE), ImportPreview.of(RawUpdater.parseImport(config(behindFragment, noise))!!).dropped)
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

    private fun hysteria(finalmask: JSONObject) = JSONObject().put("protocol", "hysteria").put("tag", "proxy")
        .put("settings", JSONObject().put("address", "192.0.2.6").put("port", 443).put("version", 2))
        .put(
            "streamSettings",
            JSONObject().put("network", "hysteria").put("security", "tls")
                .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", "a"))
                .put("tlsSettings", JSONObject().put("serverName", "node.example").put("fingerprint", "firefox").put("alpn", JSONArray().put("h3")))
                .put("finalmask", finalmask),
        )

    private suspend fun quic(params: JSONObject) = (RawUpdater.parseImport(config(hysteria(JSONObject().put("quicParams", params))))!!.proxies.single() as HysteriaBean).applyDefaultValues()

    @Test
    fun quicParamsShapeOnlyTheClientsSending() = runTest {
        // As Remnawave serves Hysteria 2: a congestion control the core uses anyway.
        val bbr = quic(JSONObject().put("congestion", "bbr").put("debug", false))
        assertEquals(listOf("node.example", 0, 0), listOf(bbr.sni, bbr.uploadMbps, bbr.downloadMbps))
        // Xray's units are binary: 100 mbps is 104.9 Mbps to the core.
        val brutal = quic(JSONObject().put("brutalUp", "100mbps").put("brutalDown", "300 Mbps"))
        assertEquals(listOf(104, 314), listOf(brutal.uploadMbps, brutal.downloadMbps))
        // Zero is as good as unset: BBR.
        val zero = quic(JSONObject().put("brutalUp", "0").put("brutalDown", "0mbps"))
        assertEquals(listOf(0, 0), listOf(zero.uploadMbps, zero.downloadMbps))
        // Under BBR the client sends without Brutal, and still tells the server how fast it receives.
        val advertised = quic(JSONObject().put("congestion", "bbr").put("brutalUp", "100mbps").put("brutalDown", "50mbps"))
        assertEquals(listOf(0, 52), listOf(advertised.uploadMbps, advertised.downloadMbps))
        // Older configs hop ports from quicParams; a udphop mask beside it wins.
        val hopping = quic(JSONObject().put("udpHop", JSONObject().put("ports", "20000-50000").put("interval", "5-30")))
        assertEquals(listOf("20000-50000", 5), listOf(hopping.serverPorts, hopping.hopInterval))
        val mask = JSONObject().put("type", "udphop").put("settings", JSONObject().put("remotePorts", "30000-31000").put("interval", "10"))
        val both = JSONObject().put("quicParams", JSONObject().put("udpHop", JSONObject().put("ports", "20000-50000"))).put("udp", JSONArray().put(mask))
        val masked = RawUpdater.parseImport(config(hysteria(both)))!!.proxies.single() as HysteriaBean
        assertEquals(listOf("30000-31000", 10), listOf(masked.serverPorts, masked.hopInterval))
        // A bandwidth Xray cannot read, or a Finalmask part the core lacks, refuses the node.
        for (refused in listOf(JSONObject().put("quicParams", JSONObject().put("brutalUp", "fast")), JSONObject().put("quicParams", JSONObject()).put("xdns", JSONObject()))) {
            val parsed = RawUpdater.parseImport(config(hysteria(refused)))
            assertTrue(refused.toString(), parsed == null || parsed.proxies.isEmpty())
        }
    }

    @Test
    fun finalmaskFragmentBecomesTheProfilesTlsFragmentation() = runTest {
        // REALITY with a fragmented ClientHello, as Remnawave serves it: the profile splits it itself.
        val fragment = JSONObject().put("type", "fragment").put("settings", JSONObject().put("packets", "tlshello").put("length", "10-20").put("delay", "0"))
        fun reality(tcp: JSONArray) = JSONObject().put("network", "tcp").put("security", "reality")
            .put("realitySettings", JSONObject().put("serverName", "www.example.com").put("publicKey", "key").put("fingerprint", "firefox"))
            .put("finalmask", JSONObject().put("tcp", tcp))
        val parsed = RawUpdater.parseImport(config(vless(reality(JSONArray().put(fragment)))))!!
        val node = parsed.proxies.single() as VMessBean
        assertEquals(listOf("www.example.com", "xtls-rprx-vision", true), listOf(node.sni, node.encryption, node.tlsFragment))
        assertTrue(ImportPreview.of(parsed).dropped.isEmpty())
        assertTrue(parsed.report.failed.isEmpty())
        // Another TCP mask beside it still refuses the node.
        val mixed = RawUpdater.parseImport(config(vless(reality(JSONArray().put(fragment).put(JSONObject().put("type", "header-custom"))))))
        assertTrue(mixed == null || mixed.proxies.isEmpty())
        // Without TLS there is no ClientHello to split, and the import says what it left out.
        val plain = JSONObject().put("network", "tcp").put("security", "none").put("finalmask", JSONObject().put("tcp", JSONArray().put(fragment)))
        val unfragmented = RawUpdater.parseImport(config(vless(plain)))!!
        assertEquals(false, (unfragmented.proxies.single() as VMessBean).tlsFragment)
        assertEquals(listOf(ImportPreview.Section.FRAGMENT), ImportPreview.of(unfragmented).dropped)
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

    @Test
    fun panelSubscriptionsKeepOneCopyOfEachServerAndTheRoutingMostConfigsUse() = runTest {
        fun routing(domain: String) = JSONObject().put(
            "rules",
            JSONArray().put(JSONObject().put("type", "field").put("outboundTag", "direct").put("domain", JSONArray().put(domain))),
        )
        fun panelConfig(remarks: String, routing: JSONObject, vararg addresses: String) = JSONObject(
            config(*addresses.mapIndexed { index, address -> vless(address = address).put("tag", if (index == 0) "proxy" else "proxy-${index + 1}") }.toTypedArray()),
        ).put("remarks", remarks).put("routing", routing)
        // An auto-select config first, as Remnawave serves it: a server of its own and a copy of the next config's.
        val subscription = JSONArray()
            .put(panelConfig("auto", routing("domain:auto.example"), "192.0.2.10", "192.0.2.11"))
            .put(panelConfig("first", routing("domain:server.example"), "192.0.2.11"))
            .put(panelConfig("second", routing("domain:server.example"), "192.0.2.12"))
        val parsed = RawUpdater.parseImport(subscription.toString())!!
        assertEquals(listOf("auto proxy", "first", "second"), parsed.proxies.map { it.name })
        val rules = parsed.report.routing!!.content.getJSONArray("rules")
        assertEquals(listOf("domain:server.example"), (0 until rules.length()).map { rules.getJSONObject(it).getString("domains") })
    }

    @Test
    fun refusedNodesReportWhatIsUnsupported() = runTest {
        // A good node beside the refused one, so the import has something to return.
        val chained = JSONObject(config(vless().put("proxySettings", JSONObject().put("tag", "other"))))
        val parsed = RawUpdater.parseImport(JSONArray().put(JSONObject(config(vless()))).put(chained).toString())!!
        assertEquals(listOf("vless"), parsed.report.failed)
        assertEquals(listOf("unsupported proxySettings"), ImportPreview.of(parsed).reasons)
        // Share links report their refused options the same way.
        val links = RawUpdater.parseImport("vless://$UUID@192.0.2.1:443?type=tcp&security=tls&pcs=abcd#pinned\nvless://$UUID@192.0.2.2:443?type=tcp#plain")!!
        assertEquals(listOf("unsupported pcs"), links.report.reasons.toList())
    }

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    }
}

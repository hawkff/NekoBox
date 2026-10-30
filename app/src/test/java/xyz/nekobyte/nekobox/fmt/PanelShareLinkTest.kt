package xyz.nekobyte.nekobox.fmt

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.amneziawg.AmneziaWGBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.hysteria.parseHysteria2
import xyz.nekobyte.nekobox.fmt.shadowsocks.parseShadowsocks
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundTLS
import xyz.nekobyte.nekobox.fmt.v2ray.isFragmentFinalMask
import xyz.nekobyte.nekobox.fmt.v2ray.parseV2Ray
import xyz.nekobyte.nekobox.fmt.v2ray.parseV2RayN
import xyz.nekobyte.nekobox.fmt.v2ray.requireSupportedLinkOptions
import xyz.nekobyte.nekobox.fmt.v2ray.toUriVMessVLESSTrojan
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.parseProxies
import java.net.URLEncoder
import java.util.Base64

/** Share links and subscription metadata in the shapes 3x-ui generates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PanelShareLinkTest {

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
    fun wireguardLink_importsKeysAndAddresses() = runTest {
        val link = "wireguard://${encode(PRIVATE_KEY)}@198.51.100.7:51820?publickey=${encode(PUBLIC_KEY)}" +
            "&presharedkey=${encode(PRESHARED_KEY)}&address=${encode("10.0.0.2/32,fd00::2")}&mtu=1280&dns=1.1.1.1&keepalive=25#wg-node"
        val bean = parseProxies(link).single() as WireGuardBean

        assertEquals("wg-node", bean.name)
        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(PRIVATE_KEY, bean.privateKey)
        assertEquals(PUBLIC_KEY, bean.peerPublicKey)
        assertEquals(PRESHARED_KEY, bean.peerPreSharedKey)
        assertEquals("10.0.0.2/32\nfd00::2/128", bean.localAddress)
        assertEquals(1280, bean.mtu)
        // A raw '+' in a key is a base64 character, not a space.
        val rawPlus = parseProxies("wg://${encode(PRIVATE_KEY)}@198.51.100.7:51820?publickey=$PRESHARED_KEY&address=10.0.0.2").single() as WireGuardBean
        assertEquals(PRESHARED_KEY, rawPlus.peerPublicKey)
        assertEquals("10.0.0.2/32", rawPlus.localAddress)
    }

    @Test
    fun amneziaVpnLink_importsConfAndRemark() = runTest {
        val conf = """
            [Interface]
            PrivateKey = $PRIVATE_KEY
            Address = 10.0.0.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 18
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            # office|📊∞
            [Peer]
            PublicKey = $PUBLIC_KEY
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 198.51.100.7:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val link = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(conf.toByteArray())
        val bean = parseProxies(link).single() as AmneziaWGBean

        assertEquals("office|📊∞", bean.name)
        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(PUBLIC_KEY, bean.peerPublicKey)
        assertEquals(4, bean.jc)
        assertEquals("1", bean.h1)
    }

    @Test
    fun hysteria2Link_acceptsGeckoBoundsAliases() {
        val bean = parseHysteria2("hysteria2://pw@203.0.113.5:443?security=tls&sni=h.example&obfs=gecko&obfs-password=g&minPacketSize=512&maxPacketSize=1200#hy")

        assertEquals(HysteriaBean.OBFS_GECKO, bean.hysteria2ObfsType)
        assertEquals("g", bean.obfuscation)
        assertEquals(512, bean.geckoMinPacketSize)
        assertEquals(1200, bean.geckoMaxPacketSize)
    }

    @Test
    fun shadowsocksLink_mapsWebSocketTransportToV2rayPlugin() {
        val userinfo = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pw".toByteArray())
        val bean = parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=ws&path=%2Fss&host=cdn.example&security=tls&sni=cdn.example#ss-ws")

        assertEquals("aes-256-gcm", bean.method)
        assertEquals("v2ray-plugin;mode=websocket;host=cdn.example;path=/ss;tls;mux=0", bean.plugin)
        assertEquals("", parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=tcp#plain").plugin)
        assertEquals(
            "v2ray-plugin;mode=websocket;host=203.0.113.5;path=/ss;mux=0",
            parseShadowsocks("ss://$userinfo@203.0.113.5:80?type=ws&path=%2Fss&host=&sni=other.example#no-host").plugin,
        )
        assertThrows(IllegalStateException::class.java) {
            parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=ws&host=cdn.example&security=tls&sni=other.example#names")
        }
        assertThrows(IllegalStateException::class.java) {
            parseShadowsocks("ss://$userinfo@203.0.113.5:443?type=grpc&serviceName=ss#ss-grpc")
        }
    }

    @Test
    fun vlessLink_importsEchAndRoundTripsIt() = runTest {
        ConfigBuilderTestEnv.reset()
        val link = "vless://$UUID@node.example:443?type=tcp&security=tls&sni=node.example&fp=chrome&flow=xtls-rprx-vision&ech=${encode(ECH)}#ech"
        val bean = parseProxies(link).single() as VMessBean
        assertTrue(bean.enableECH!!)
        assertEquals(ECH, bean.echConfig)

        val reimported = parseProxies(bean.toUriVMessVLESSTrojan(false)).single() as VMessBean
        assertEquals(ECH, reimported.echConfig)

        // A DoH URL means "query the HTTPS record"; it is kept for export and sing-box runs ECH without a config.
        val dynamic = parseProxies("vless://$UUID@node.example:443?type=tcp&security=tls&ech=${encode("https://1.1.1.1/dns-query")}").single() as VMessBean
        assertTrue(dynamic.enableECH!!)
        assertEquals("https://1.1.1.1/dns-query", dynamic.echConfig)
        assertTrue(dynamic.toUriVMessVLESSTrojan(false).contains("ech="))
        val dynamicEch = buildSingBoxOutboundTLS(dynamic.applyDefaultValues())!!.ech!!
        assertEquals(true, dynamicEch.enabled)
        assertNull(dynamicEch.config)
        val inlineEch = buildSingBoxOutboundTLS(bean.applyDefaultValues())!!.ech!!
        assertEquals(listOf("-----BEGIN ECH CONFIGS-----", ECH, "-----END ECH CONFIGS-----"), inlineEch.config)
    }

    @Test
    fun vmessLink_collectsFlattenedXhttpExtra() {
        val json = JSONObject()
            .put("v", "2").put("ps", "xhttp").put("add", "node.example").put("port", 443).put("id", UUID)
            .put("net", "xhttp").put("type", "none").put("host", "cdn.example").put("path", "/x").put("mode", "packet-up")
            .put("tls", "tls").put("sni", "cdn.example").put("ech", ECH)
            .put("x_padding_bytes", "100-1000").put("xPaddingBytes", "100-1000").put("scMaxEachPostBytes", "500000")
            .put("noGRPCHeader", true).put("xmux", JSONObject().put("maxConcurrency", "16-32"))
        val bean = parseV2RayN("vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())).applyDefaultValues()

        assertEquals("xhttp", bean.type)
        assertEquals("packet-up", bean.xhttpMode)
        assertEquals(ECH, bean.echConfig)
        val extra = JSONObject(bean.xhttpExtra!!)
        assertEquals("100-1000", extra.getString("x_padding_bytes"))
        assertEquals("500000", extra.getString("sc_max_each_post_bytes"))
        assertTrue(extra.getBoolean("no_grpc_header"))
        assertEquals("16-32", extra.getJSONObject("xmux").getString("max_concurrency"))
        assertFalse(extra.has("ps"))
        assertFalse(extra.has("add"))
    }

    @Test
    fun kcpLinks_followXrayMasks() = runTest {
        suspend fun vless(query: String) = parseProxies("vless://$UUID@node.example:20015?type=kcp&security=none&encryption=none&$query#kcp").single() as VMessBean
        fun fm(vararg masks: Pair<String, String>) = encode(
            JSONObject().put(
                "udp",
                org.json.JSONArray(masks.map { (header, value) -> JSONObject().put("type", "mkcp-legacy").put("settings", JSONObject().put("header", header).put("value", value)) }),
            ).toString(),
        )

        // Without masks the core sends raw segments, as Xray-core v26.2 and later expect.
        val raw = vless("mtu=1350&tti=20")
        assertFalse(raw.kcpLegacy!!)
        assertEquals("", raw.mKcpSeed)
        val seeded = vless("seed=s&headerType=wechat-video")
        assertEquals("s", seeded.mKcpSeed)
        assertEquals("wechat-video", seeded.headerType)

        // The masks in fm win over seed and headerType, as in 3x-ui.
        val legacy = vless("headerType=srtp&fm=" + fm("" to "", "dtls" to ""))
        assertTrue(legacy.kcpLegacy!!)
        assertEquals("dtls", legacy.headerType)
        val masked = vless("fm=" + fm("" to "k", "wechat" to ""))
        assertEquals("k", masked.mKcpSeed)
        assertEquals("wechat-video", masked.headerType)
        assertFalse(masked.kcpLegacy!!)

        // Masks the core cannot apply, or fm on another transport, refuse the link.
        for (query in listOf(
            "fm=" + fm("dtls" to "", "" to "k"),
            "fm=" + fm("" to "", "" to "k"),
            "fm=" + fm("dns" to "other.example"),
            "fm=" + encode("""{"udp":[{"type":"noise"}]}"""),
        )) {
            assertTrue(query, parseProxies("vless://$UUID@node.example:20015?type=kcp&security=none&$query").isEmpty())
        }
        assertTrue(parseProxies("vless://$UUID@node.example:443?type=tcp&security=none&fm=" + fm("" to "")).isEmpty())

        // The legacy checksum survives a share and an import; the builder passes it to the core.
        val again = parseProxies(legacy.toUriVMessVLESSTrojan(false)).single() as VMessBean
        assertTrue(again.kcpLegacy!!)
        assertEquals("dtls", again.headerType)
        val transport = xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStreamSettings(legacy.applyDefaultValues()) as xyz.nekobyte.nekobox.SingBoxOptions.V2RayTransportOptions_KCPOptions
        assertEquals(true, transport.legacy)
        assertEquals("dtls", transport.header_type)
        assertNull((xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStreamSettings(raw.applyDefaultValues()) as xyz.nekobyte.nekobox.SingBoxOptions.V2RayTransportOptions_KCPOptions).legacy)
        // A seed replaces the checksum, as in Xray.
        masked.kcpLegacy = true
        val seededTransport = xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStreamSettings(masked.applyDefaultValues()) as xyz.nekobyte.nekobox.SingBoxOptions.V2RayTransportOptions_KCPOptions
        assertEquals("k", seededTransport.seed)
        assertNull(seededTransport.legacy)

        // Only a legacy mKCP profile writes the newer bytes; other profiles keep theirs.
        assertEquals(10, KryoConverters.serialize(raw.applyDefaultValues())[0].toInt())
        assertEquals(11, KryoConverters.serialize(legacy.applyDefaultValues())[0].toInt())
        assertTrue(KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(legacy)).kcpLegacy!!)
    }

    @Test
    fun vmessKcp_readsHeaderAndSeedFromTypeAndPath() {
        fun vmess(json: JSONObject) = parseV2RayN("vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())).applyDefaultValues()
        val json = JSONObject().put("v", "2").put("ps", "kcp").put("add", "node.example").put("port", 20038).put("id", UUID)
            .put("net", "kcp").put("type", "utp").put("path", "seed-1").put("tls", "none")
        val bean = vmess(json)
        assertEquals("utp", bean.headerType)
        assertEquals("seed-1", bean.mKcpSeed)
        assertEquals("", bean.path)
        val legacy = vmess(json.put("type", "none").put("path", "").put("fm", """{"udp":[{"type":"mkcp-legacy","settings":{"header":"","value":""}}]}"""))
        assertTrue(legacy.kcpLegacy!!)
        assertTrue(parseV2RayN((legacy as VMessBean).toUriVMessVLESSTrojan(false)).kcpLegacy!!)
    }

    @Test
    fun amneziaWg31Conf_keepsTheNewKeys() = runTest {
        val conf = """
            [Interface]
            PrivateKey = $PRIVATE_KEY
            Address = 10.8.3.2/32
            Jc = 4
            S1 = 15
            S2 = 18
            S3 = 20
            S4 = 16
            H1 = 1234567
            HeaderProtectionKey = $PRESHARED_KEY
            ContentPaddingAddition = 8-24
            RekeyAfterTime = 110-130
            RejectAfterTime = 170-200
            RandomTrailers = on

            [Peer]
            PublicKey = $PUBLIC_KEY
            AllowedIPs = 0.0.0.0/0
            Endpoint = 198.51.100.7:20083
        """.trimIndent()
        fun link(text: String) = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        val bean = parseProxies(link(conf)).single() as AmneziaWGBean
        assertEquals(PRESHARED_KEY, bean.headerProtectionKey)
        // The device needs S1 to S4 of at least 12 bytes for the header protection nonce.
        assertTrue(parseProxies(link(conf.replace("S1 = 15\n", ""))).isEmpty())
        assertEquals("8-24", bean.contentPaddingAddition)
        assertEquals("110-130", bean.rekeyAfterTime)
        assertEquals("170-200", bean.rejectAfterTime)
        assertTrue(bean.randomTrailers!!)
        assertFalse(bean.disableCookies!!)

        val copy = KryoConverters.deserialize(AmneziaWGBean(), KryoConverters.serialize(bean))
        assertEquals(2, KryoConverters.serialize(bean)[0].toInt())
        assertEquals(PRESHARED_KEY, copy.headerProtectionKey)
        assertTrue(copy.randomTrailers!!)
        val outbound = xyz.nekobyte.nekobox.fmt.amneziawg.buildSingBoxOutboundAmneziaWGBean(bean)
        assertEquals(PRESHARED_KEY, outbound.header_protection_key)
        assertEquals("110-130", outbound.rekey_after_time)
        assertEquals(true, outbound.random_trailers)
        assertNull(outbound.disable_cookies)
    }

    @Test
    fun subscriptionUserinfo_zeroExpiryMeansUnlimited() {
        val bean = SubscriptionBean().applyDefaultValues()
        bean.subscriptionUserinfo = "upload=1; download=2; total=0; expire=0"
        assertNull(bean.expiry())
        bean.subscriptionUserinfo = "upload=1; download=2; total=0; expire=1900000000"
        assertEquals(1900000000L, bean.expiry())
    }

    @Test
    fun subscriptionBean_sendsDeviceIdUnlessTurnedOffOrSavedBeforeTheSwitch() {
        assertTrue(SubscriptionBean().applyDefaultValues().sendDeviceId!!)
        val off = SubscriptionBean().applyDefaultValues().apply { sendDeviceId = false }
        val bytes = KryoConverters.serialize(off)
        assertFalse(KryoConverters.deserialize(SubscriptionBean(), bytes).sendDeviceId!!)
        // Version 5 bytes end with the switch: without it and marked version 4, they are a subscription
        // saved before the switch existed, which keeps sending no device ID.
        val legacy = KryoConverters.serialize(SubscriptionBean().applyDefaultValues()).let { it.copyOf(it.size - 1) }.also { it[0] = 4 }
        assertFalse(KryoConverters.deserialize(SubscriptionBean(), legacy).sendDeviceId!!)
    }

    @Test
    fun fragmentMaskLink_becomesTlsFragmentationAndBack() {
        val fm = encode("""{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"10-20","delay":"0"}}]}""")
        val reality = "security=reality&sni=www.example.com&pbk=$REALITY_KEY&sid=ab&fp=firefox&flow=xtls-rprx-vision"
        val bean = parseV2Ray("vless://$UUID@192.0.2.1:443?type=tcp&$reality&fm=$fm#fragmented").applyDefaultValues()
        assertTrue(bean.tlsFragment!!)
        assertTrue(buildSingBoxOutboundTLS(bean)!!.fragment!!)
        val exported = bean.toUriVMessVLESSTrojan(false)
        val query = exported.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertTrue(isFragmentFinalMask(query.getValue("fm")))
        assertTrue(parseV2Ray(exported).tlsFragment!!)
        // Version 12 only for a profile that fragments, so the others keep their bytes.
        val stored = KryoConverters.serialize(bean)
        assertEquals(12, stored[0].toInt())
        val copy = KryoConverters.deserialize(VMessBean(), stored)
        assertTrue(copy.tlsFragment!!)
        assertEquals(10, KryoConverters.serialize(copy.apply { tlsFragment = false })[0].toInt())

        // Without TLS there is no ClientHello to split.
        assertFalse(parseV2Ray("vless://$UUID@192.0.2.1:443?type=tcp&security=none&fm=$fm#plain").applyDefaultValues().tlsFragment!!)
        // Another mask beside the fragment still refuses the link.
        val mixed = encode("""{"tcp":[{"type":"fragment"},{"type":"header-custom"}]}""")
        assertThrows(Exception::class.java) { parseV2Ray("vless://$UUID@192.0.2.1:443?type=tcp&$reality&fm=$mixed") }
        // A scheme whose profiles do not fragment refuses the mask too.
        assertThrows(Exception::class.java) { requireSupportedLinkOptions("hy2://secret@192.0.2.1:443?sni=a.example&fm=$fm") }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val REALITY_KEY = "yAnz5TF-lXXJte14tji3zlMNq-hd2rYUIgJBgB3fBmk"
        const val PRIVATE_KEY = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        const val PUBLIC_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        const val PRESHARED_KEY = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
        const val ECH = "AEX+DQBBDQAgACCKB1Q4ZLb1MWv9S0XyWK8nZ9A8d0x2Yy5lBr0E3S9hCgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="
    }
}

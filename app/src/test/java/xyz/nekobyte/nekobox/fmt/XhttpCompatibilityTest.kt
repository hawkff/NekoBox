package xyz.nekobyte.nekobox.fmt

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.fmt.trojan.TrojanBean
import xyz.nekobyte.nekobox.fmt.v2ray.StandardV2RayBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.fmt.v2ray.XhttpExtraConverter
import xyz.nekobyte.nekobox.fmt.v2ray.buildSingBoxOutboundStreamSettings
import xyz.nekobyte.nekobox.fmt.v2ray.parseV2RayN
import xyz.nekobyte.nekobox.fmt.v2ray.toUriVMessVLESSTrojan
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.decodeBase64UrlSafe
import xyz.nekobyte.nekobox.ktx.parseProxies
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class XhttpCompatibilityTest {
    private lateinit var originalLogSink: (String) -> Unit
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = { logs.add(it) }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun legacyAliasesImportAndExportWithCanonicalNames() {
        val aliases = listOf(
            Triple("uplinkHttpMethod", "uplinkHTTPMethod", "uplink_http_method"),
            Triple("sessionIdPosition", "sessionIDPlacement", "session_placement"),
            Triple("sessionIdName", "sessionIDKey", "session_key"),
            Triple("seqPosition", "seqPlacement", "seq_placement"),
            Triple("seqName", "seqKey", "seq_key"),
            Triple("dataUpPlacement", "uplinkDataPlacement", "uplink_data_placement"),
            Triple("dataUpName", "uplinkDataKey", "uplink_data_key"),
            Triple("dataUpSplitSize", "uplinkChunkSize", "uplink_chunk_size"),
        )
        for ((old, canonical, native) in aliases) {
            val input = JSONObject().put(old, "old-value")
            assertEquals("old-value", toNative(input).getString(native))
            val exported = JSONObject(XhttpExtraConverter.singBoxToXray(input.toString()))
            assertEquals("old-value", exported.getString(canonical))
            assertFalse(exported.has(old))
            input.put(canonical, "canonical-value")
            assertEquals("canonical-value", toNative(input).getString(native))
            input.put(native, "native-value")
            assertEquals("native-value", toNative(input).getString(native))
        }
    }

    @Test
    fun sparseAndMixedFieldsDoNotTriggerWholeObjectFormatDetection() {
        val input = """{"x_padding_bytes":"100-200","sessionIDKey":"X-Session","seqName":"X-Seq","noGRPCHeader":false,"xmux":{"max_connections":0,"hKeepAlivePeriod":-1}}"""
        val expected = """{"x_padding_bytes":"100-200","session_key":"X-Session","seq_key":"X-Seq","no_grpc_header":false,"xmux":{"max_connections":0,"h_keep_alive_period":-1}}"""
        assertJson(expected, XhttpExtraConverter.xrayToSingBox(input))
        for (field in listOf("session_placement", "seq_key", "uplink_http_method")) {
            val native = JSONObject().put(field, "value").toString()
            assertJson(native, XhttpExtraConverter.xrayToSingBox(XhttpExtraConverter.singBoxToXray(native)))
        }
        assertJson(expected, XhttpExtraConverter.xrayToSingBox(XhttpExtraConverter.singBoxToXray(expected)))
    }

    @Test
    fun currentFieldsAndXmuxPreserveFalseZeroRangesAndLargeIntegers() {
        val converted = XhttpExtraConverter.xrayToSingBox(xrayExtra)
        assertJson(nativeExtra, converted)
        assertJson(xrayExtra, XhttpExtraConverter.singBoxToXray(converted))
        val json = JSONObject(converted)
        assertEquals(false, json.get("no_grpc_header"))
        assertEquals(0, json.get("sc_min_posts_interval_ms"))
        assertEquals(9007199254740993L, json.getJSONObject("xmux").getLong("h_keep_alive_period"))
        assertJson("""{"no_grpc_header":null,"xmux":{}}""", XhttpExtraConverter.xrayToSingBox("""{"noGRPCHeader":null,"xmux":{}}"""))
    }

    @Test
    fun downloadTopLevelSettingsAndHeadersSurviveRoundTrip() {
        val input = JSONObject().put(
            "downloadSettings",
            JSONObject().put("address", "download.example").put("port", 8443).put("network", "xhttp")
                .put("xhttpSettings", JSONObject(xrayExtra).put("host", "front.example").put("path", "/download").put("mode", "auto")),
        )
        val native = toNative(input)
        val down = native.getJSONObject("download")
        assertEquals("download.example", down.getString("server"))
        assertEquals(8443, down.getInt("server_port"))
        assertEquals("front.example", down.getString("host"))
        assertEquals("/download", down.getString("path"))
        assertEquals("example", down.getJSONObject("headers").getString("X-Test"))
        assertEquals(0, down.getInt("sc_max_buffered_posts"))
        val exported = JSONObject(XhttpExtraConverter.singBoxToXray(native.toString()))
        assertFalse(exported.getJSONObject("downloadSettings").getJSONObject("xhttpSettings").has("extra"))
        assertJson(input.toString(), exported.toString())
        assertJson(native.toString(), XhttpExtraConverter.xrayToSingBox(exported.toString()))
    }

    @Test
    fun nestedExtraReplacesAdvancedSettingsButNotHostPathOrMode() {
        val settings = JSONObject(
            """{
            "host":"outer.example","path":"/outer","mode":"packet-up",
            "xPaddingBytes":100,"headers":{"X-Top":"discarded"},
            "xmux":{"maxConnections":3,"hMaxRequestTimes":10},
            "extra":{"host":"ignored.example","path":"/ignored","mode":"stream-one",
                "noGRPCHeader":false,"scMinPostsIntervalMs":0,"sessionIdName":"nested",
                "xmux":{"hKeepAlivePeriod":0}}
        }""",
        )
        val expected = """{"host":"outer.example","path":"/outer","mode":"packet-up","no_grpc_header":false,"sc_min_posts_interval_ms":0,"session_key":"nested","xmux":{"h_keep_alive_period":0}}"""
        assertJson(expected, toNative(settings).toString())
        val wrapped = JSONObject().put("downloadSettings", JSONObject().put("xhttpSettings", settings))
        assertJson(expected, toNative(wrapped).getJSONObject("download").toString())
        for (empty in listOf(JSONObject(), JSONObject.NULL)) {
            settings.put("extra", empty)
            assertJson("""{"host":"outer.example","path":"/outer","mode":"packet-up"}""", toNative(settings).toString())
        }
    }

    @Test
    fun downloadTlsAndRealityPreserveSecurityAndFingerprint() {
        for (security in listOf("tls", "reality")) {
            val tls = JSONObject().put("serverName", "tls.example").put("alpn", org.json.JSONArray(listOf("h2")))
                .put("fingerprint", "chrome").put("allowInsecure", false)
            if (security == "reality") tls.put("publicKey", "example-key").put("shortId", "0123")
            val download = JSONObject().put("address", "download.example").put("port", 443)
                .put("network", "xhttp").put("security", security)
                .put(if (security == "tls") "tlsSettings" else "realitySettings", tls)
                .put("xhttpSettings", JSONObject().put("path", "/down"))
            val source = JSONObject().put("downloadSettings", download)
            val native = toNative(source)
            val nativeTls = native.getJSONObject("download").getJSONObject("tls")
            assertTrue(nativeTls.getBoolean("enabled"))
            assertTrue(nativeTls.getJSONObject("utls").getBoolean("enabled"))
            assertEquals("chrome", nativeTls.getJSONObject("utls").getString("fingerprint"))
            assertJson(source.toString(), XhttpExtraConverter.singBoxToXray(native.toString()))
        }
    }

    @Test
    fun disabledDownloadTlsNeverBecomesEnabled() {
        val source = """{"downloadSettings":{"security":"none","tlsSettings":{"serverName":"inactive.example"},"xhttpSettings":{}}}"""
        val native = JSONObject(XhttpExtraConverter.xrayToSingBox(source))
        assertFalse(native.getJSONObject("download").getJSONObject("tls").getBoolean("enabled"))
        val exported = JSONObject(XhttpExtraConverter.singBoxToXray(native.toString()))
        assertEquals("none", exported.getJSONObject("downloadSettings").getString("security"))
        assertJson(native.toString(), XhttpExtraConverter.xrayToSingBox(exported.toString()))
        val disabled = """{"download":{"tls":{"enabled":false,"server_name":"inactive.example","utls":{"enabled":false,"fingerprint":"chrome"},"reality":{"enabled":false,"public_key":"inactive-key"}}}}"""
        assertJson(disabled, XhttpExtraConverter.xrayToSingBox(XhttpExtraConverter.singBoxToXray(disabled)))
    }

    @Test
    fun unsupportedFieldsStayShareableButCannotSilentlyChangeRuntimeBehavior() {
        val unsupported = mapOf("sessionIDTable" to "hex", "sessionIDLength" to "16-24", "congestion_controller" to "cubic", "cwnd" to 32)
        for ((key, value) in unsupported) {
            for (download in listOf(false, true)) {
                val input = JSONObject().put(key, value)
                val source = if (download) JSONObject().put("download", input) else input
                val native = toNative(source)
                val exported = JSONObject(XhttpExtraConverter.singBoxToXray(native.toString()))
                val fields = if (download) exported.getJSONObject("downloadSettings").getJSONObject("xhttpSettings") else exported
                assertEquals(value, fields.get(key))
                val bean = bean().apply { xhttpExtra = native.toString() }
                val before = KryoConverters.serialize(bean)
                assertThrows(IllegalArgumentException::class.java) { buildSingBoxOutboundStreamSettings(bean) }
                org.junit.Assert.assertArrayEquals(before, KryoConverters.serialize(bean))
            }
        }
        val defaults = JSONObject("""{"session_id_table":"","session_id_length":0,"congestion_controller":"","cwnd":0}""")
        assertEquals("{}", XhttpExtraConverter.forCore(defaults).toString())
    }

    @Test
    fun coreOutputIncludesOnlySupportedExtrasAndKeepsBaseTransportSettings() {
        val extra = JSONObject(xrayExtra).put("type", "http").put("mode", "stream-one").put("path", "/ignored")
            .put("unknown", JSONObject().put("marker", 7))
            .put("download", JSONObject(nativeExtra).put("server", "download.example").put("server_port", 443).put("unknown", 7))
        val bean = bean().apply { xhttpExtra = extra.toString() }
        val config = SingBoxOptions.toJsonTree(buildSingBoxOutboundStreamSettings(bean)!!)
        assertEquals("xhttp", config["type"].asString)
        assertEquals("packet-up", config["mode"].asString)
        assertEquals("/tunnel", config["path"].asString)
        assertEquals("front.example", config["host"].asString)
        assertFalse(config.has("unknown"))
        assertFalse(config["download"].asJsonObject.has("unknown"))
        for (key in listOf("no_sse_header", "sc_max_buffered_posts", "sc_stream_up_server_secs", "server_max_header_bytes")) {
            assertEquals(JsonParser.parseString(nativeExtra).asJsonObject[key], config[key])
            assertEquals(config[key], config["download"].asJsonObject[key])
        }
        assertEquals(9007199254740993L, config["xmux"].asJsonObject["h_keep_alive_period"].asLong)
        assertTrue(JSONObject(XhttpExtraConverter.singBoxToXray(bean.xhttpExtra!!)).has("unknown"))
    }

    @Test
    fun vlessAndTrojanUriRoundTripsPreserveExtraAndTransport() = runTest {
        for (original in listOf(
            bean(),
            TrojanBean().applyDefaultValues().apply {
                serverAddress = "node.example"
                serverPort = 443
                password = "example-password"
                type = "xhttp"
                host = "front.example"
                path = "/tunnel"
                xhttpMode = "packet-up"
                xhttpExtra = nativeExtra
            },
        )) {
            val uri = original.toUriVMessVLESSTrojan(original is TrojanBean)
            val restored = parseProxies(uri).single() as StandardV2RayBean
            assertEquals(original.type, restored.type)
            assertEquals(original.xhttpMode, restored.xhttpMode)
            assertEquals(original.host, restored.host)
            assertEquals(original.path, restored.path)
            assertJson(original.xhttpExtra!!, restored.xhttpExtra!!)
        }
    }

    @Test
    fun vmessJsonRoundTripPreservesXhttpModeAndObjectOrStringExtra() {
        val original = bean().apply { alterId = 0 }
        val uri = original.toUriVMessVLESSTrojan(false)
        val json = JSONObject(uri.substringAfter("://").decodeBase64UrlSafe())
        assertEquals("packet-up", json.getString("mode"))
        assertTrue(json.get("extra") is JSONObject)
        for (extra in listOf(json.getJSONObject("extra"), json.getJSONObject("extra").toString())) {
            json.put("extra", extra).put("net", "splithttp")
            val imported = parseV2RayN("vmess://${encode(json.toString())}").applyDefaultValues()
            assertEquals("xhttp", imported.type)
            assertEquals("packet-up", imported.xhttpMode)
            assertJson(nativeExtra, imported.xhttpExtra!!)
        }
        val ordinary = bean().apply {
            alterId = 0
            type = "tcp"
        }.toUriVMessVLESSTrojan(false)
        val ordinaryJson = JSONObject(ordinary.substringAfter("://").decodeBase64UrlSafe())
        assertFalse(ordinaryJson.has("extra"))
        assertFalse(ordinaryJson.has("mode"))
    }

    @Test
    fun splitHttpUriAliasImportsAndExportsAsXhttp() = runTest {
        val input = "vless://00000000-0000-4000-8000-000000000001@node.example:443?type=splithttp&mode=packet-up&path=%2Ftunnel"
        val imported = parseProxies(input).single() as VMessBean
        assertEquals("xhttp", imported.type)
        assertEquals("packet-up", imported.xhttpMode)
        assertTrue(imported.toUriVMessVLESSTrojan(false).contains("type=xhttp"))
    }

    @Test
    fun malformedInputIsRetainedWithoutLoggingPrivateValues() {
        val input = """{"downloadSettings":{"address":"private-marker.example",invalid}}"""
        assertEquals(input, XhttpExtraConverter.xrayToSingBox(input))
        assertEquals(input, XhttpExtraConverter.singBoxToXray(input))
        val bean = bean().apply { xhttpExtra = input }
        val failure = assertThrows(IllegalStateException::class.java) { buildSingBoxOutboundStreamSettings(bean) }
        assertFalse(failure.toString().contains("private-marker"))
        assertFalse(logs.joinToString("\n").contains("private-marker"))
        assertEquals(input, bean.xhttpExtra)
        assertEquals("", XhttpExtraConverter.xrayToSingBox(" "))
        assertEquals("", XhttpExtraConverter.singBoxToXray(""))
    }

    @Test
    fun invalidStructuredExtrasCannotFallBackToDefaultTransport() {
        for (input in listOf(
            """{"downloadSettings":{"security":"unsupported"}}""",
            """{"downloadSettings":{"network":"tcp"}}""",
            """{"downloadSettings":{"network":"xhttp","sockopt":{"mark":12}}}""",
            """{"downloadSettings":{"finalmask":{"quicParams":{"congestion":"bbr"}}}}""",
            """{"downloadSettings":false}""",
            """{"downloadSettings":{"xhttpSettings":false}}""",
            """{"downloadSettings":{"security":"tls","tlsSettings":false}}""",
            """{"download":false}""",
            """{"xmux":false}""",
            """{"extra":false}""",
        )) {
            assertEquals(input, XhttpExtraConverter.xrayToSingBox(input))
            assertThrows(IllegalStateException::class.java) {
                buildSingBoxOutboundStreamSettings(bean().apply { xhttpExtra = input })
            }
        }
    }

    private fun bean() = VMessBean().applyDefaultValues().apply {
        alterId = -1
        serverAddress = "node.example"
        serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        encryption = "auto"
        type = "xhttp"
        xhttpMode = "packet-up"
        host = "front.example"
        path = "/tunnel"
        xhttpExtra = nativeExtra
    }

    private fun toNative(source: JSONObject) = JSONObject(XhttpExtraConverter.xrayToSingBox(source.toString()))
    private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private fun assertJson(expected: String, actual: String) = assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))

    private val xrayExtra = """{
        "headers":{"X-Test":"example"},"xPaddingBytes":"100-200","noGRPCHeader":false,"noSSEHeader":true,
        "scMaxEachPostBytes":1000000,"scMinPostsIntervalMs":0,"scMaxBufferedPosts":0,
        "scStreamUpServerSecs":"20-80","serverMaxHeaderBytes":8192,
        "xPaddingObfsMode":false,"xPaddingKey":"padding","xPaddingHeader":"X-Padding",
        "xPaddingPlacement":"header","xPaddingMethod":"repeat-x","uplinkHTTPMethod":"POST",
        "sessionIDPlacement":"header","sessionIDKey":"X-Session","seqPlacement":"query","seqKey":"seq",
        "uplinkDataPlacement":"body","uplinkDataKey":"data","uplinkChunkSize":"2048-3072",
        "xmux":{"maxConcurrency":"1-2","maxConnections":0,"cMaxReuseTimes":0,"hMaxRequestTimes":"10-20",
        "hMaxReusableSecs":0,"hKeepAlivePeriod":9007199254740993}
    }"""
    private val nativeExtra = """{
        "headers":{"X-Test":"example"},"x_padding_bytes":"100-200","no_grpc_header":false,"no_sse_header":true,
        "sc_max_each_post_bytes":1000000,"sc_min_posts_interval_ms":0,"sc_max_buffered_posts":0,
        "sc_stream_up_server_secs":"20-80","server_max_header_bytes":8192,
        "x_padding_obfs_mode":false,"x_padding_key":"padding","x_padding_header":"X-Padding",
        "x_padding_placement":"header","x_padding_method":"repeat-x","uplink_http_method":"POST",
        "session_placement":"header","session_key":"X-Session","seq_placement":"query","seq_key":"seq",
        "uplink_data_placement":"body","uplink_data_key":"data","uplink_chunk_size":"2048-3072",
        "xmux":{"max_concurrency":"1-2","max_connections":0,"c_max_reuse_times":0,"h_max_request_times":"10-20",
        "h_max_reusable_secs":0,"h_keep_alive_period":9007199254740993}
    }"""
}

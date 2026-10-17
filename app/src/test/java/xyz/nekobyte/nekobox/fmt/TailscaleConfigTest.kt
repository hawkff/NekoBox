package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.tailscale.TailscaleBean
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleConfigTest {
    private val modes = listOf("normal", "global", "export", "test")

    private fun node() = TailscaleBean().apply {
        name = "tailnet"
        exitNode = "100.64.0.1"
        controlUrl = "https://control.invalid"
    }

    private fun socks(name: String = "socks") = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.1"
        serverPort = 1080
    }

    private fun add(bean: AbstractBean, groupId: Long = 0, order: Long = 0): ProxyEntity = ProxyEntity(groupId = groupId, userOrder = order).putBean(bean.apply { initializeDefaultValues() })
        .also { it.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(it) } }

    private fun chain(vararg hops: ProxyEntity) = ChainBean().apply { proxies = hops.map { it.id } }

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }.orEmpty()

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    // The sidecar is independent of route.final and is checked against the core's effective
    // default after PreStart, not merely against the serialized configuration.
    private fun fixture(name: String, result: ConfigBuildResult, expectedTag: String): JSONObject {
        val config = JSONObject(result.config)
        val outbounds = config.objects("outbounds") + config.objects("endpoints")
        val tags = outbounds.map { it.getString("tag") }
        assertEquals("duplicate outbound tags", tags.size, tags.toSet().size)
        assertTrue("missing expected default $expectedTag in $tags", expectedTag in tags)
        assertEquals(expectedTag, config.getJSONObject("route").getString("final"))
        fun checkDetours(value: Any) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    if (key == "detour") {
                        assertTrue("unresolved detour ${value.getString(key)} in $tags", value.getString(key) in tags)
                    } else {
                        checkDetours(value.get(key))
                    }
                }

                is JSONArray -> (0 until value.length()).forEach { checkDetours(value.get(it)) }
            }
        }
        checkDetours(config)
        config.getJSONObject("route").objects("rules").filter { it.has("outbound") }.forEach {
            assertTrue("unresolved route outbound $it", it.getString("outbound") in tags)
        }
        outbounds.filter { it.has("outbounds") }.forEach {
            assertTrue(tags.containsAll(it.getJSONArray("outbounds").strings()))
        }
        val directory = File("build/generated-core-configs/tailscale-routing").apply { mkdirs() }
        directory.resolve("$name.json").writeText(result.config)
        directory.resolve("$name.json.expected-tag").writeText("$expectedTag\n")
        return config
    }

    private fun build(profile: ProxyEntity, mode: String): ConfigBuildResult {
        DataStore.globalMode = mode == "global"
        return ConfigBuilderTestEnv.io { buildConfig(profile, forTest = mode == "test", forExport = mode == "export") }
    }

    private fun assertMagicDns(config: JSONObject, forTest: Boolean) {
        val dns = config.getJSONObject("dns")
        val servers = dns.objects("servers")
        val preferred = dns.objects("rules").filter { it.has("preferred_by") }
        val endpoints = config.objects("endpoints")
        assertEquals(endpoints.size, servers.count { it.optString("type") == "tailscale" })
        assertEquals(endpoints.size, preferred.size)
        endpoints.forEach { endpoint ->
            val server = servers.single { it.optString("endpoint") == endpoint.getString("tag") }
            assertTrue(server.getBoolean("accept_search_domain"))
            val rule = preferred.single { it.getString("server") == server.getString("tag") }
            assertEquals(listOf(server.getString("tag")), rule.getJSONArray("preferred_by").strings())
        }
        assertEquals(if (forTest) "dns-direct" else "dns-remote", dns.getString("final"))
        if (forTest) {
            assertTrue(config.objects("inbounds").isEmpty())
            val tailscaleServers = servers.filter { it.optString("type") == "tailscale" }.map { it.getString("tag") }
            assertEquals(setOf("dns-local", "dns-direct") + tailscaleServers, servers.map { it.getString("tag") }.toSet())
            assertEquals(preferred, dns.objects("rules"))
            assertFalse(servers.single { it.getString("tag") == "dns-direct" }.has("detour"))
        }
    }

    @Test
    fun standaloneAndRegularDefaultsInEveryMode() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        for (mode in modes) {
            val config = fixture("standalone-$mode", build(node, mode), "tailnet")
            assertEquals(1, config.objects("endpoints").size)
            assertMagicDns(config, mode == "test")
            val regular = fixture("regular-$mode", build(socks, mode), "socks")
            assertTrue(regular.objects("endpoints").isEmpty())
            assertMagicDns(regular, mode == "test")
        }
    }

    @Test
    fun amneziaWgProfileFromTailscaleAwgGet() {
        ConfigBuilderTestEnv.reset()
        // As `tailscale awg get` prints it: Go field names, ranges as {min, max}, a hex key.
        val profile = JSONObject()
            .put("JC", 4).put("JMin", 40).put("JMax", 70).put("S1", 20).put("S2", 30).put("S3", 20).put("S4", 16)
            .put("H1", JSONObject().put("min", 100000).put("max", 800000)).put("H2", 1000000).put("H3", "10000000-80000000")
            .put("H4", JSONObject().put("min", 0).put("max", 0))
            .put("I1", "<b 0xc700000001><r 16>").put("HeaderProtectionKey", "07".repeat(32))
            .put("ContentPaddingAddition", JSONObject().put("min", 8).put("max", 24)).put("RandomTrailers", true).put("DisableCookies", false)
        val node = add(node().apply { amneziaWG = profile.toString() })
        val config = JSONObject(build(node, "normal").config)
        val awg = config.objects("endpoints").single().getJSONObject("amnezia_wg")
        assertEquals(4, awg.getInt("jc"))
        assertEquals(16, awg.getInt("s4"))
        assertEquals("100000-800000", awg.getString("h1"))
        assertEquals("1000000", awg.getString("h2"))
        assertEquals("10000000-80000000", awg.getString("h3"))
        assertFalse(awg.has("h4"))
        assertEquals("<b 0xc700000001><r 16>", awg.getString("i1"))
        assertEquals(android.util.Base64.encodeToString(ByteArray(32) { 7 }, android.util.Base64.NO_WRAP), awg.getString("header_protection_key"))
        assertEquals("8-24", awg.getString("content_padding_addition"))
        assertTrue(awg.getBoolean("random_trailers"))
        assertFalse(awg.has("disable_cookies"))
        val directory = File("build/generated-core-configs/tailscale-awg").apply { mkdirs() }
        directory.resolve("awg31.json").writeText(build(node, "test").config)

        // Only nodes with settings use the newer storage version.
        val bean = node.requireBean() as TailscaleBean
        assertEquals(1, KryoConverters.serialize(bean)[0].toInt())
        assertEquals(profile.toString(), KryoConverters.deserialize(TailscaleBean(), KryoConverters.serialize(bean)).amneziaWG)
        assertEquals(0, KryoConverters.serialize(node().apply { initializeDefaultValues() })[0].toInt())
        assertFalse(JSONObject(build(add(node()), "normal").config).objects("endpoints").single().has("amnezia_wg"))

        // Settings the device would refuse, or that the app cannot read, refuse the profile.
        for (bad in listOf(
            JSONObject(profile.toString()).put("S1", 8),
            JSONObject(profile.toString()).put("Transport", "quic"),
            JSONObject(profile.toString()).put("H1", "800000-100000"),
            JSONObject(profile.toString()).put("I2", "<r 4>\nprivate_key=00"),
            // The device would crash building these packets or allocate without bound.
            JSONObject(profile.toString()).put("I1", "<b 0xc7><r -5>"),
            JSONObject(profile.toString()).put("I3", "<rc 40000><rd 40000>"),
            JSONObject(profile.toString()).put("HeaderProtectionKey", "short"),
            // A header left at its WireGuard default of 2 would overlap H1.
            JSONObject().put("H1", 2),
            JSONObject(profile.toString()).put("S1", 20.9),
            JSONObject(profile.toString()).apply { remove("JMax") },
            JSONObject(profile.toString()).put("S4", 65535),
        )) {
            val broken = add(node().apply { amneziaWG = bad.toString() })
            assertTrue(bad.toString(), runCatching { build(broken, "normal") }.isFailure)
        }
    }

    @Test
    fun tailscaleLandingHopIsTheDefaultNotItsUnderlay() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        val chain = add(chain(socks, node))
        for (mode in modes) {
            val config = fixture("landing-chain-$mode", build(chain, mode), "tailnet")
            assertEquals("g-${socks.id}", config.objects("endpoints").single().getString("detour"))
            assertMagicDns(config, mode == "test")
        }
        val group = ProxyGroup(landingProxy = node.id)
        group.id = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(group) }
        socks.groupId = group.id
        ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.updateProxy(socks) }
        val config = fixture("group-landing", build(socks, "normal"), "tailnet")
        assertEquals("g-${socks.id}", config.objects("endpoints").single().getString("detour"))
    }

    @Test
    fun sharedEntryHopResolvesBeforeLinkingInEitherSelectorOrder() {
        for (tailscale in listOf(true, false)) {
            for (chainFirst in listOf(false, true)) {
                ConfigBuilderTestEnv.reset()
                val groupId = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(isSelector = true)) }
                val entry = add(if (tailscale) node() else socks("entry"), groupId, 0)
                val exit = add(socks(), groupId, 1)
                val chain = add(chain(entry, exit), groupId, if (chainFirst) -1 else 2)
                val result = build(entry, "normal")
                val config = fixture("shared-$tailscale-$chainFirst", result, TAG_PROXY)
                val entryTag = if (chainFirst) {
                    "g-${entry.id}"
                } else if (tailscale) {
                    "tailnet"
                } else {
                    "entry"
                }
                val chainTag = if (chainFirst) "socks" else "socks-1"
                val outbounds = config.objects("outbounds") + config.objects("endpoints")
                assertEquals(entryTag, outbounds.single { it.optString("tag") == chainTag }.getString("detour"))
                assertEquals(3, config.objects("outbounds").single { it.optString("type") == "selector" }.getJSONArray("outbounds").length())
                assertEquals(entryTag, result.profileTagMap[entry.id])
                assertEquals(chainTag, result.profileTagMap[chain.id])
                assertEquals(if (tailscale) 1 else 0, config.objects("endpoints").size)
                assertMagicDns(config, false)
            }
        }
    }

    @Test
    fun ruleOutboundsDoNotReplaceTheMainDefaultAndReuseTheEntry() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        val chain = add(chain(node, socks))
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:chain.example", outbound = chain.id))
        }
        for (mode in modes) {
            val config = fixture("extra-chain-$mode", build(node, mode), "tailnet")
            assertEquals(1, config.objects("endpoints").size)
            if (mode != "test") {
                assertEquals("tailnet", config.objects("outbounds").single { it.optString("tag") == "socks" }.getString("detour"))
            }
            assertMagicDns(config, mode == "test")
        }
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.reset()
            ProfileDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:tailnet.example", outbound = node.id))
        }
        val config = fixture("extra-tailnet", build(socks, "normal"), "socks")
        assertEquals(1, config.objects("endpoints").size)
        assertMagicDns(config, false)
    }

    @Test
    fun serviceDnsPrecedenceAndProbeIsolation() {
        ConfigBuilderTestEnv.reset(dnsHosts = "mapped.ts.test 192.0.2.100\nresolver.example 192.0.2.101")
        DataStore.enableDnsRouting = true
        DataStore.enableFakeDns = true
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:blocked.ts.test", outbound = -2))
        }
        val node = add(node())
        val service = fixture("dns-service", build(node, "normal"), "tailnet")
        assertMagicDns(service, false)
        val rules = service.getJSONObject("dns").objects("rules")
        val bootstrap = rules.indexOfFirst { it.optString("server") == "dns-direct" }
        val hosts = rules.indexOfFirst { it.optString("server") == TAG_DNS_HOSTS }
        val block = rules.indexOfFirst { it.optString("action") == "predefined" }
        val magic = rules.indexOfFirst { it.has("preferred_by") }
        val fake = rules.indexOfFirst { it.optString("server") == "dns-fake" }
        assertTrue("DNS precedence: $rules", bootstrap >= 0 && bootstrap < hosts && hosts < block && block < magic && magic < fake)
        val probe = fixture("dns-test", build(node, "test"), "tailnet")
        assertMagicDns(probe, true)
    }

    @Test
    fun brokenSelectorMembersRollBackBeforeAValidSharedChain() {
        ConfigBuilderTestEnv.reset()
        val groupId = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(isSelector = true)) }
        val node = add(node(), groupId, 0)
        val socks = add(socks(), groupId, 1)
        val broken = add(
            HysteriaBean().apply {
                serverAddress = "192.0.2.2"
                enableECH = true
            },
        )
        val brokenChain = add(chain(broken, node), groupId, -1)
        val duplicate = add(chain(node, socks, node), groupId, 2)
        val valid = add(chain(node, socks), groupId, 3)
        val result = build(node, "normal")
        val config = fixture("selector-rollback", result, TAG_PROXY)
        assertFalse(result.profileTagMap.containsKey(brokenChain.id))
        assertFalse(result.profileTagMap.containsKey(duplicate.id))
        assertTrue(result.profileTagMap.containsKey(valid.id))
        assertEquals(1, config.objects("endpoints").size)
        val nodeTag = config.objects("endpoints").single().getString("tag")
        assertEquals(nodeTag, config.objects("outbounds").single { it.optString("tag") == result.profileTagMap[valid.id] }.getString("detour"))
        assertMagicDns(config, false)
    }

    @Test
    fun explicitCustomFinalOverridesTheGeneratedDefault() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        DataStore.globalCustomConfig = """{"route":{"final":"direct"}}"""
        fixture("global-final-overlay", build(node, "normal"), TAG_DIRECT)
        node.requireBean().customConfigJson = """{"route":{"final":"bypass"}}"""
        for (mode in modes) {
            fixture("profile-final-overlay-$mode", build(node, mode), TAG_BYPASS)
        }
    }
}

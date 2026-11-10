package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.TrafficSniffing
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.internal.chainContains
import xyz.nekobyte.nekobox.fmt.shadowsocks.ShadowsocksBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.v2ray.VMessBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConfigBuilderGoldenTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun socksForTest_preservesEndpointAndHasNoInbound() {
        val profile = addProfile(
            addGroup(),
            SOCKSBean().apply {
                serverAddress = "192.0.2.10"
                serverPort = 1080
                username = "reader"
                password = "alpha"
                protocol = 2
                name = "golden-socks"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val root = JSONObject(result.config)
        val outbound = outbound(root, "socks")

        assertEquals("192.0.2.10", outbound.getString("server"))
        assertEquals(1080, outbound.getInt("server_port"))
        assertEquals(0, root.getJSONArray("inbounds").length())
        assertResultMaps(result, profile)
    }

    @Test
    fun vmessForTest_preservesUuidAndSecurity() {
        val profile = addProfile(
            addGroup(),
            VMessBean().apply {
                serverAddress = "192.0.2.11"
                serverPort = 443
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
                alterId = 0
                encryption = "auto"
                name = "golden-vmess"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "vmess")

        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", outbound.getString("uuid"))
        assertEquals("auto", outbound.getString("security"))
        assertResultMaps(result, profile)
    }

    @Test
    fun tlsFragment_splitsTheFirstHopHandshakeInTheCore() {
        val previousTlsFragment = DataStore.enableTLSFragment
        // The core loads each of these in libcore's generated-config check.
        val directory = File("build/generated-core-configs/fragment").apply { mkdirs() }
        fun config(profile: ProxyEntity, name: String) = JSONObject(build(profile).config).also { directory.resolve("$name.json").writeText(it.toString()) }
        try {
            DataStore.enableTLSFragment = true
            val group = addGroup()
            val vmess = addProfile(
                group,
                VMessBean().apply {
                    serverAddress = "192.0.2.13"
                    serverPort = 443
                    uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4"
                    alterId = 0
                    encryption = "auto"
                    security = "tls"
                    sni = "node.example"
                    name = "fragment-vmess"
                    initializeDefaultValues()
                },
            )
            val global = config(vmess, "global-vmess")
            val outbound = outbound(global, "vmess")
            assertTrue(outbound.getJSONObject("tls").getBoolean("fragment"))
            assertFalse(outbound.has("detour"))
            assertTrue(objects(global.getJSONArray("outbounds")).none { it.optString("tag") == "fragment" })

            // Hysteria 2's TLS runs inside QUIC, with no TCP segments to split.
            val hysteria = addProfile(
                group,
                HysteriaBean().apply {
                    protocolVersion = 2
                    serverAddress = "192.0.2.14"
                    serverPorts = "443"
                    authPayload = "charlie"
                    authPayloadType = HysteriaBean.TYPE_STRING
                    name = "fragment-hy2"
                    initializeDefaultValues()
                },
            )
            assertFalse(outbound(config(hysteria, "global-hysteria2"), "hysteria2").getJSONObject("tls").has("fragment"))

            // A profile splits its own handshake, REALITY's too, with the switch off.
            DataStore.enableTLSFragment = false
            val reality = addProfile(
                group,
                VMessBean().apply {
                    serverAddress = "192.0.2.15"
                    serverPort = 443
                    uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4"
                    alterId = -1
                    encryption = "xtls-rprx-vision"
                    security = "tls"
                    sni = "www.example.com"
                    realityPubKey = "yAnz5TF-lXXJte14tji3zlMNq-hd2rYUIgJBgB3fBmk"
                    realityShortId = "ab"
                    tlsFragment = true
                    name = "fragment-reality"
                    initializeDefaultValues()
                },
            )
            val tls = outbound(config(reality, "profile-reality"), "vless").getJSONObject("tls")
            assertTrue(tls.getJSONObject("reality").getBoolean("enabled"))
            assertTrue(tls.getBoolean("fragment"))
        } finally {
            DataStore.enableTLSFragment = previousTlsFragment
        }
    }

    @Test
    fun tlsFragment_keepsTheFirstHopResolverWhenServerStrategyIsAuto() {
        val previousTlsFragment = DataStore.enableTLSFragment
        try {
            DataStore.enableTLSFragment = true
            DataStore.configurationStore.putString("domain_strategy_for_server", "auto")
            val group = ConfigBuilderTestEnv.io {
                ProfileDatabase.groupDao.createGroup(
                    ProxyGroup(
                        type = GroupType.SUBSCRIPTION,
                        subscription = SubscriptionBean().applyDefaultValues().apply {
                            customDnsResolver = "https://subscription.example/dns-query"
                        },
                    ),
                )
            }
            val profile = addProfile(
                group,
                VMessBean().apply {
                    serverAddress = "node.example"
                    serverPort = 443
                    uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4"
                    alterId = 0
                    encryption = "auto"
                    security = "tls"
                    sni = "node.example"
                    name = "fragment-resolver"
                    initializeDefaultValues()
                },
            )

            val outbound = outbound(JSONObject(build(profile).config), "vmess")

            // Splitting the handshake keeps the subscription's resolver on the first hop.
            assertTrue(outbound.getJSONObject("tls").getBoolean("fragment"))
            assertEquals("dns-sub-$group", outbound.getJSONObject("domain_resolver").getString("server"))
        } finally {
            DataStore.enableTLSFragment = previousTlsFragment
        }
    }

    @Test
    fun shadowsocksForTest_preservesMethodAndPassword() {
        val profile = addProfile(
            addGroup(),
            ShadowsocksBean().apply {
                serverAddress = "192.0.2.12"
                serverPort = 8388
                method = "aes-256-gcm"
                password = "bravo"
                name = "golden-ss"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "shadowsocks")

        assertEquals("aes-256-gcm", outbound.getString("method"))
        assertEquals("bravo", outbound.getString("password"))
        assertResultMaps(result, profile)
    }

    @Test
    fun chainForTest_preservesBothHopsAndDetour() {
        val group = addGroup()
        val first = addSocks(group, "192.0.2.21", 1081, "chain-first")
        val second = addSocks(group, "192.0.2.22", 1082, "chain-second")
        val chain = addProfile(
            group,
            ChainBean().apply {
                name = "golden-chain"
                proxies = listOf(first.id, second.id)
                initializeDefaultValues()
            },
        )

        val result = build(chain, forTest = true)
        val socks = objects(JSONObject(result.config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }

        assertEquals(2, socks.size)
        assertEquals(
            setOf("192.0.2.21" to 1081, "192.0.2.22" to 1082),
            socks.map { it.getString("server") to it.getInt("server_port") }.toSet(),
        )
        val tags = socks.map { it.getString("tag") }.toSet()
        val detours = socks.mapNotNull { outbound ->
            outbound.optString("detour").takeIf { it.isNotEmpty() }?.let { detour ->
                outbound.getString("tag") to detour
            }
        }
        assertEquals(1, detours.size)
        assertTrue(detours.single().first != detours.single().second)
        assertTrue(detours.single().second in tags)
        assertResultMaps(result, chain)
    }

    @Test
    fun nestedChain_keepsInnerHopOrder() {
        val group = addGroup()
        val entry = addSocks(group, "192.0.2.23", 1081, "entry")
        val innerFirst = addSocks(group, "192.0.2.24", 1082, "inner-first")
        val innerLast = addSocks(group, "192.0.2.25", 1083, "inner-last")
        val inner = addChain(group, "inner", innerFirst.id, innerLast.id)
        val outer = addChain(group, "outer", entry.id, inner.id)

        val socks = objects(JSONObject(build(outer, forTest = true).config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }
            .associateBy { it.getString("server") }
        fun detourOf(server: String) = socks.getValue(server).optString("detour")
        fun tagOf(server: String) = socks.getValue(server).getString("tag")

        // Dial order entry -> inner-first -> inner-last: each hop detours through the one before it.
        assertEquals(tagOf("192.0.2.24"), detourOf("192.0.2.25"))
        assertEquals(tagOf("192.0.2.23"), detourOf("192.0.2.24"))
        assertEquals("", detourOf("192.0.2.23"))
    }

    @Test
    fun serverStrategyAuto_passesLaterChainHopHostnamesThroughEarlierHops() {
        val strategyKey = "domain_strategy_for_server"
        val group = addGroup()
        val entry = addSocks(group, "entry.example", 1081, "strategy-entry")
        val exit = addSocks(group, "exit.example", 1082, "strategy-exit")
        val chain = addChain(group, "strategy-chain", entry.id, exit.id)
        fun hops() = objects(JSONObject(build(chain).config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }
            .associateBy { it.getString("server") }

        // Nothing saved and "auto" agree: the entry resolves through Direct DNS, and the exit's
        // hostname travels through the entry.
        for (saved in listOf(null, "auto")) {
            DataStore.configurationStore.putString(strategyKey, saved)
            val hops = hops()
            assertEquals("dns-direct", hops.getValue("entry.example").getJSONObject("domain_resolver").getString("server"))
            assertFalse(hops.getValue("exit.example").has("domain_resolver"))
        }

        DataStore.configurationStore.putString(strategyKey, "prefer_ipv4")
        assertEquals("prefer_ipv4", hops().getValue("exit.example").getJSONObject("domain_resolver").getString("strategy"))
    }

    @Test
    fun chainValidation_rejectsMissingHopsCyclesAndEmptyChains() {
        val group = addGroup()
        val hop = addSocks(group, "192.0.2.26", 1081, "hop")
        val missing = addChain(group, "missing", hop.id, 424242L)
        val empty = addChain(group, "empty")
        val loopInner = addChain(group, "loop-inner", hop.id)
        val loopOuter = addChain(group, "loop-outer", loopInner.id)
        ConfigBuilderTestEnv.io {
            loopInner.chainBean!!.proxies = listOf(hop.id, loopOuter.id)
            ProfileDatabase.proxyDao.updateProxy(loopInner)
        }

        assertTrue(assertThrows(IllegalArgumentException::class.java) { build(missing, forTest = true) }.message!!.contains("missing"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { build(empty, forTest = true) }.message!!.contains("empty"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { build(loopOuter, forTest = true) }.message!!.contains("loop"))

        // A full custom config replaces the whole generated config; a custom outbound is one hop.
        val customConfig = addProfile(
            group,
            ConfigBean().apply {
                name = "custom-config"
                type = 0
                config = "{}"
                initializeDefaultValues()
            },
        )
        val customOutbound = addProfile(
            group,
            ConfigBean().apply {
                name = "custom-outbound"
                type = 1
                config = """{"type":"socks","server":"192.0.2.29","server_port":1080}"""
                initializeDefaultValues()
            },
        )
        val customConfigHop = addChain(group, "custom-config-hop", hop.id, customConfig.id)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { build(customConfigHop, forTest = true) }.message!!.contains("custom-config"))
        val customOutboundHop = addChain(group, "custom-outbound-hop", hop.id, customOutbound.id)
        assertTrue(build(customOutboundHop, forTest = true).config.contains("192.0.2.29"))

        assertTrue(ConfigBuilderTestEnv.io { chainContains(loopOuter, hop.id) })
        assertTrue(ConfigBuilderTestEnv.io { chainContains(loopInner, loopOuter.id) })
        assertFalse(ConfigBuilderTestEnv.io { chainContains(hop, loopOuter.id) })
    }

    @Test
    fun brokenChain_isSkippedAsGroupMemberButFailsWhenSelected() {
        val group = addGroup(isSelector = true)
        val healthy = addSocks(group, "192.0.2.27", 1081, "healthy")
        val broken = addChain(group, "broken", 424242L)

        val result = build(healthy)
        val selector = outbound(JSONObject(result.config), "selector")
        assertEquals(listOf(result.profileTagMap.getValue(healthy.id)), strings(selector.getJSONArray("outbounds")))
        assertFalse(result.profileTagMap.containsKey(broken.id))
        assertThrows(IllegalArgumentException::class.java) { build(broken) }
    }

    @Test
    fun sharedConnection_keepsMixedInboundInVpnModeWithSeparateCredential() {
        val profile = addSocks(addGroup(), "192.0.2.90", 1080, "shared")
        DataStore.serviceMode = Key.MODE_VPN
        assertTrue(objects(JSONObject(build(profile).config).getJSONArray("inbounds")).none { it.optString("type") == "mixed" })

        DataStore.allowAccess = true
        fun shareUsers() = objects(JSONObject(build(profile).config).getJSONArray("inbounds"))
            .single { it.optString("type") == "mixed" }
            .also { assertEquals("0.0.0.0", it.getString("listen")) }
            .let { objects(it.getJSONArray("users")) }
            .associate { it.getString("username") to it.getString("password") }

        val users = shareUsers()
        assertEquals(setOf(Key.MIXED_USERNAME, Key.SHARE_USERNAME), users.keys)
        assertEquals(DataStore.shareSecret, users.getValue(Key.SHARE_USERNAME))
        assertNotEquals(users.getValue(Key.MIXED_USERNAME), users.getValue(Key.SHARE_USERNAME))

        DataStore.regenerateShareSecret()
        val rotated = shareUsers()
        assertNotEquals(users.getValue(Key.SHARE_USERNAME), rotated.getValue(Key.SHARE_USERNAME))
        // The app's own user has a secret of each config alone, never the one handed to other devices.
        assertNotEquals(rotated.getValue(Key.MIXED_USERNAME), rotated.getValue(Key.SHARE_USERNAME))
    }

    @Test
    fun hysteria2ForTest_usesNativeOutbound() {
        val profile = addProfile(
            addGroup(),
            HysteriaBean().apply {
                protocolVersion = 2
                serverAddress = "192.0.2.30"
                serverPorts = "8443"
                authPayload = "charlie"
                authPayloadType = HysteriaBean.TYPE_STRING
                name = "golden-hy2"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "hysteria2")

        assertEquals("192.0.2.30", outbound.getString("server"))
        assertEquals(8443, outbound.getInt("server_port"))
        assertEquals("charlie", outbound.getString("password"))
        assertFalse(outbound.getJSONObject("tls").has("ech"))
        assertResultMaps(result, profile)
    }

    @Test
    fun hysteria2Ech_emitsExplicitStaticConfig() {
        val profile = addProfile(
            addGroup(),
            HysteriaBean().apply {
                protocolVersion = 2
                serverAddress = "192.0.2.31"
                serverPorts = "443"
                authPayload = "delta"
                sni = "real.example.com"
                enableECH = true
                echConfig = "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA"
                name = "golden-hy2-ech"
                initializeDefaultValues()
            },
        )

        val outbound = outbound(JSONObject(build(profile, forTest = true).config), "hysteria2")
        val ech = outbound.getJSONObject("tls").getJSONObject("ech")

        assertTrue(ech.getBoolean("enabled"))
        assertEquals(
            listOf(
                "-----BEGIN ECH CONFIGS-----",
                "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA",
                "-----END ECH CONFIGS-----",
            ),
            strings(ech.getJSONArray("config")),
        )
    }

    @Test
    fun selectorGroup_containsEveryMember() {
        val group = addGroup(isSelector = true)
        val first = addSocks(group, "192.0.2.41", 1081, "selector-first")
        val second = addSocks(group, "192.0.2.42", 1082, "selector-second")

        val result = build(first)
        val selector = outbound(JSONObject(result.config), "selector")

        assertEquals(
            setOf(result.profileTagMap.getValue(first.id), result.profileTagMap.getValue(second.id)),
            strings(selector.getJSONArray("outbounds")).toSet(),
        )
        assertEquals(group, result.selectorGroupId)
        assertResultMaps(result, first)
    }

    // A chain of one hop is that hop's outbound under another name. In a group that picks its server both would go in
    // under one tag, which the core refuses, so the chain gets a tag of its own that leads to that outbound.
    @Test
    fun selectorGroup_takesAMemberNextToAChainOfOnlyThatMember() {
        // The core loads each of these in libcore's generated-config check.
        val directory = File("build/generated-core-configs/selector").apply { mkdirs() }
        for ((name, chainFirst, auto) in listOf(Triple("member-then-chain", false, false), Triple("chain-then-member", true, false), Triple("auto-chain-then-member", true, true))) {
            ConfigBuilderTestEnv.reset()
            DataStore.connectionTestURL = "http://example.com/generate_204"
            val group = addGroup(isSelector = true, autoSelect = auto)
            val member = addSocks(group, "192.0.2.51", 1091, "shared-member")
            val other = addSocks(group, "192.0.2.52", 1092, "shared-other")
            val chain = addChain(group, "shared-chain", member.id)
            ConfigBuilderTestEnv.io {
                ProfileDatabase.proxyDao.updateOrders(mapOf(member.id to (if (chainFirst) 3L else 1L), other.id to 2L, chain.id to (if (chainFirst) 1L else 3L)))
            }

            val result = build(member)
            val root = JSONObject(result.config)
            directory.resolve("$name.json").writeText(root.toString())
            val outbounds = objects(root.getJSONArray("outbounds"))
            val tags = listOf(member, other, chain).map { result.profileTagMap.getValue(it.id) }
            assertEquals("$name: each profile has a tag of its own", 3, tags.toSet().size)
            assertEquals(name, tags.toSet(), strings(outbounds.single { it.getString("tag") == "proxy" }.getJSONArray("outbounds")).toSet())
            // Whichever of the two is built first, the server keeps the tag of its own outbound: a lookup of its
            // address reads that outbound's settings. The chain is a selector that can only choose it.
            assertEquals(name, outbounds.single { it.optString("type") == "socks" && it.optString("server") == "192.0.2.51" }.getString("tag"), tags[0])
            val alias = outbounds.single { it.optString("type") == "selector" && it.getString("tag") != "proxy" }
            assertEquals(name, tags[2], alias.getString("tag"))
            assertEquals(name, listOf(tags[0]), strings(alias.getJSONArray("outbounds")))
        }
    }

    // The profile of a rule is told from the group's members by its tag too, and its alias stays clear of the tags
    // that hops are given: "g-" and the id of the server a path dials first.
    @Test
    fun selectorGroup_tellsARulesChainOfOneMemberFromThatMember() {
        val member = addSocks(addGroup(isSelector = true), "192.0.2.53", 1093, "ruled-member")
        val rules = addGroup()
        val entry = addSocks(rules, "192.0.2.54", 1094, "ruled-entry")
        val exit = addSocks(rules, "192.0.2.55", 1095, "ruled-exit")
        val twoHops = addChain(rules, "ruled-two-hops", entry.id, exit.id)
        val chain = addChain(rules, "g-${entry.id}", member.id)
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.createRule(RuleEntity(domains = "full:ruled.example", outbound = chain.id, enabled = true))
            ProfileDatabase.rulesDao.createRule(RuleEntity(domains = "full:far.example", outbound = twoHops.id, enabled = true))
        }

        val result = build(member)
        File("build/generated-core-configs/selector").apply { mkdirs() }.resolve("rule-chain.json").writeText(result.config)
        val root = JSONObject(result.config)
        val tags = objects(root.getJSONArray("outbounds")).map { it.getString("tag") }
        assertEquals("an outbound's tag is used twice: $tags", tags.size, tags.toSet().size)
        assertTrue("the path of the other rule dials its first server under the tag the chain is named after: $tags", "g-${entry.id}" in tags)
        assertNotEquals(result.profileTagMap.getValue(member.id), result.profileTagMap.getValue(chain.id))
        val rule = objects(root.getJSONObject("route").getJSONArray("rules")).single { it.toString().contains("ruled.example") }
        assertEquals(result.profileTagMap.getValue(chain.id), rule.getString("outbound"))
    }

    @Test
    fun autoSelectGroup_emitsUrltestOverEveryMember() {
        DataStore.connectionTestURL = "http://example.com/generate_204"
        val group = addGroup(isSelector = true, autoSelect = true)
        val first = addSocks(group, "192.0.2.47", 1087, "auto-first")
        val second = addSocks(group, "192.0.2.48", 1088, "auto-second")

        val result = build(first)
        val root = JSONObject(result.config)
        val urltest = outbound(root, "urltest")

        assertEquals("proxy", urltest.getString("tag"))
        assertEquals("http://example.com/generate_204", urltest.getString("url"))
        assertEquals(
            setOf(result.profileTagMap.getValue(first.id), result.profileTagMap.getValue(second.id)),
            strings(urltest.getJSONArray("outbounds")).toSet(),
        )
        assertTrue(objects(root.getJSONArray("outbounds")).none { it.optString("type") == "selector" })
        assertEquals(group, result.selectorGroupId)
        assertResultMaps(result, first)
    }

    @Test
    fun selectorGroup_skipsArchivedEntriesWithoutDeletingThem() {
        val group = addGroup(isSelector = true)
        val active = addSocks(group, "192.0.2.42", 1080, "active")
        val archived = addProfile(group, ArchivedBean(7, byteArrayOf(1, 2, 3)))
        val result = build(active)
        val selector = outbound(JSONObject(result.config), "selector")
        assertEquals(listOf(result.profileTagMap.getValue(active.id)), strings(selector.getJSONArray("outbounds")))
        assertTrue(ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getById(archived.id) } != null)
    }

    @Test
    fun selectorGroup_appliesSharedFrontAndLandingToEveryMemberChain() {
        val supportGroup = addGroup()
        val front = addSocks(supportGroup, "192.0.2.43", 1083, "selector-front")
        val landing = addSocks(supportGroup, "192.0.2.44", 1084, "selector-landing")
        val selectorGroup = addGroup(isSelector = true)
        val first = addSocks(selectorGroup, "192.0.2.45", 1085, "selector-member-one")
        val second = addSocks(selectorGroup, "192.0.2.46", 1086, "selector-member-two")
        ConfigBuilderTestEnv.io {
            ProfileDatabase.groupDao.getById(selectorGroup)!!.also {
                it.frontProxy = front.id
                it.landingProxy = landing.id
                ProfileDatabase.groupDao.updateGroup(it)
            }
        }

        val result = build(first)
        val socks = objects(JSONObject(result.config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }
        val frontOutbound = socks.single { it.getString("server") == "192.0.2.43" }
        val memberOutbounds = socks.filter {
            it.getString("server") in setOf("192.0.2.45", "192.0.2.46")
        }
        val landingOutbounds = socks.filter { it.getString("server") == "192.0.2.44" }

        assertEquals(2, memberOutbounds.size)
        assertTrue(memberOutbounds.all { it.getString("detour") == frontOutbound.getString("tag") })
        assertEquals(2, landingOutbounds.size)
        assertEquals(
            memberOutbounds.map { it.getString("tag") }.toSet(),
            landingOutbounds.map { it.getString("detour") }.toSet(),
        )
        assertTrue(result.trafficMap.values.all { chain -> front in chain && landing in chain })
        // Each path names its hops once, in the order it is dialed from its exit, and last the server it is the path
        // of: the service reads the path's speed from that one, the only one that counts once a server is picked.
        assertEquals(
            setOf(listOf(landing.id, front.id, first.id), listOf(landing.id, front.id, second.id)),
            result.trafficMap.values.map { chain -> chain.map { it.id } }.toSet(),
        )
        assertTrue(result.profileTagMap.keys.containsAll(listOf(first.id, second.id)))
    }

    @Test
    fun aProxyListKeepsItsFirstHopWhenItsProxyIsAGroupsExit() {
        // A relay that answers only inside a tunnel, as Mullvad's SOCKS5 proxies do inside its WireGuard.
        val tunnel = addSocks(addGroup(), "192.0.2.60", 1060, "list-first-hop")
        val list = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "relays", type = GroupType.PROXIES, frontProxy = tunnel.id)) }
        val relay = addSocks(list, "10.64.0.1", 1080, "relay")
        val servers = addGroup()
        val server = addSocks(servers, "192.0.2.61", 1061, "own-server")
        ConfigBuilderTestEnv.io {
            ProfileDatabase.groupDao.getById(servers)!!.also {
                it.landingProxy = relay.id
                ProfileDatabase.groupDao.updateGroup(it)
            }
        }

        val socks = objects(JSONObject(build(server).config).getJSONArray("outbounds")).filter { it.optString("type") == "socks" }
        fun at(address: String) = socks.single { it.getString("server") == address }
        // Dialed in order: the server, the list's first hop through it, then the relay through that hop.
        assertFalse(at("192.0.2.61").has("detour"))
        assertEquals(at("192.0.2.61").getString("tag"), at("192.0.2.60").getString("detour"))
        assertEquals(at("192.0.2.60").getString("tag"), at("10.64.0.1").getString("detour"))

        // Connected directly, the relay goes through its list's first hop as well.
        val direct = objects(JSONObject(build(relay).config).getJSONArray("outbounds")).filter { it.optString("type") == "socks" }
        assertEquals(direct.single { it.getString("server") == "192.0.2.60" }.getString("tag"), direct.single { it.getString("server") == "10.64.0.1" }.getString("detour"))
    }

    @Test
    fun aPathThroughOneHopTwiceIsRefusedByName() {
        val tunnel = addSocks(addGroup(), "192.0.2.62", 1062, "shared-hop")
        val list = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "relays", type = GroupType.PROXIES, frontProxy = tunnel.id)) }
        val relay = addSocks(list, "10.64.0.2", 1080, "relay-two")
        val servers = addGroup()
        val server = addSocks(servers, "192.0.2.63", 1063, "server-two")
        // The servers go through the tunnel first and leave through the relay, whose list goes through it as well.
        ConfigBuilderTestEnv.io {
            ProfileDatabase.groupDao.getById(servers)!!.also {
                it.frontProxy = tunnel.id
                it.landingProxy = relay.id
                ProfileDatabase.groupDao.updateGroup(it)
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { build(server) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("shared-hop"))
    }

    @Test
    fun aChainKeepsTheFirstHopOfItsMemberFromAList() {
        // Add exit proxy makes a chain of a server and a relay: the relay still goes through its list's first hop.
        val tunnel = addSocks(addGroup(), "192.0.2.64", 1064, "member-first-hop")
        val list = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "relays", type = GroupType.PROXIES, frontProxy = tunnel.id)) }
        val relay = addSocks(list, "10.64.0.3", 1080, "relay-three")
        val server = addSocks(addGroup(), "192.0.2.65", 1065, "server-three")
        val chain = addChain(addGroup(), "server to relay", server.id, relay.id)

        val socks = objects(JSONObject(build(chain).config).getJSONArray("outbounds")).filter { it.optString("type") == "socks" }
        fun at(address: String) = socks.single { it.getString("server") == address }
        assertFalse(at("192.0.2.65").has("detour"))
        assertEquals(at("192.0.2.65").getString("tag"), at("192.0.2.64").getString("detour"))
        assertEquals(at("192.0.2.64").getString("tag"), at("10.64.0.3").getString("detour"))

        // A member that already sits right after its list's first hop keeps the chain as it is.
        val through = addChain(addGroup(), "tunnel to relay", tunnel.id, relay.id)
        assertEquals(2, objects(JSONObject(build(through).config).getJSONArray("outbounds")).count { it.optString("type") == "socks" })
    }

    @Test
    fun twoListsThroughEachOtherAreRefusedByName() {
        val first = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "first", type = GroupType.PROXIES)) }
        val second = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "second", type = GroupType.PROXIES)) }
        val a = addSocks(first, "192.0.2.66", 1066, "list-a-proxy")
        val b = addSocks(second, "192.0.2.67", 1067, "list-b-proxy")
        ConfigBuilderTestEnv.io {
            ProfileDatabase.groupDao.getById(first)!!.also {
                it.frontProxy = b.id
                ProfileDatabase.groupDao.updateGroup(it)
            }
            ProfileDatabase.groupDao.getById(second)!!.also {
                it.frontProxy = a.id
                ProfileDatabase.groupDao.updateGroup(it)
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { build(a) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("list-a-proxy"))
    }

    @Test
    fun forExport_omitsDeviceSecretsAndLanBinding() {
        DataStore.allowAccess = true
        val profile = addSocks(addGroup(), "192.0.2.50", 1080, "export-socks")

        val root = JSONObject(build(profile, forExport = true).config)
        val mixed = objects(root.getJSONArray("inbounds")).single { it.optString("type") == "mixed" }

        assertEquals(LOCALHOST, mixed.getString("listen"))
        assertFalse(mixed.has("users"))
        assertFalse(root.toString().contains(DataStore.shareSecret))
    }

    @Test
    fun customJsonOverlays_mergeIntoOutboundAndRoot() {
        val profile = addProfile(
            addGroup(),
            SOCKSBean().apply {
                serverAddress = "192.0.2.51"
                serverPort = 1080
                protocol = 2
                name = "custom-json"
                customOutboundJson = """{"marker":{"inner":1}}"""
                customConfigJson = """{"route":{"final":"custom-final"},"top_level_marker":true}"""
                initializeDefaultValues()
            },
        )

        val root = JSONObject(build(profile).config)
        val outbound = outbound(root, "socks")

        assertEquals(1, outbound.getJSONObject("marker").getInt("inner"))
        assertEquals("custom-final", root.getJSONObject("route").getString("final"))
        assertTrue(root.getBoolean("top_level_marker"))
    }

    @Test
    fun webrtcLeakProtection_rejectsStunAheadOfUserRulesInVpnModeOnly() {
        val profile = addSocks(addGroup(), "192.0.2.80", 1080, "webrtc")
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.createRule(RuleEntity(network = "udp", outbound = 0, enabled = true))
        }
        fun rules() = objects(JSONObject(build(profile).config).getJSONObject("route").getJSONArray("rules"))
        fun stunRules(rules: List<JSONObject>) = rules.filter {
            it.optJSONArray("protocol")?.toString()?.contains("stun") == true || it.optJSONArray("port_range") != null
        }

        DataStore.serviceMode = Key.MODE_VPN
        assertTrue(stunRules(rules()).isEmpty())

        DataStore.webrtcLeakProtection = true
        var rules = rules()
        val sniff = rules.single { it.optString("action") == "sniff" }
        val stun = rules.single { it.optJSONArray("protocol")?.toString()?.contains("stun") == true }
        val ports = rules.single { it.optJSONArray("port_range") != null }
        assertEquals(listOf("stun"), strings(sniff.getJSONArray("sniffer")))
        assertTrue(listOf(sniff, stun, ports).all { strings(it.getJSONArray("inbound")) == listOf("tun-in") })
        assertEquals("reject", stun.getString("action"))
        assertEquals("reject", ports.getString("action"))
        assertEquals(listOf(3478, 3479, 5349, 5350), (0 until ports.getJSONArray("port").length()).map(ports.getJSONArray("port")::getInt))
        assertEquals(listOf("19302:19309"), strings(ports.getJSONArray("port_range")))
        val userRule = rules.indexOfFirst { it.optJSONArray("network") != null }
        assertTrue(rules.indexOf(sniff) < rules.indexOf(stun) && rules.indexOf(stun) < rules.indexOf(ports) && rules.indexOf(ports) < userRule)

        DataStore.trafficSniffing = 1
        rules = rules()
        assertFalse(rules.single { it.optString("action") == "sniff" }.has("sniffer"))
        assertEquals(2, stunRules(rules).size)

        DataStore.serviceMode = Key.MODE_PROXY
        assertTrue(stunRules(rules()).isEmpty())
    }

    @Test
    fun trafficSniffing_readsNamesForRoutingAndConnectsByNameOnlyWhenAsked() {
        val profile = addSocks(addGroup(), "192.0.2.81", 1080, "sniffing")
        val directory = File("build/generated-core-configs/sniffing").apply { mkdirs() }
        fun rules(name: String): List<JSONObject> {
            val config = build(profile).config
            // The core loads each of these in libcore's generated-config check.
            directory.resolve("$name.json").writeText(config)
            return objects(JSONObject(config).getJSONObject("route").getJSONArray("rules"))
        }
        fun isPort53(rule: JSONObject) = rule.optString("action") == "hijack-dns" && rule.optJSONArray("port")?.getInt(0) == 53
        fun isDnsProtocol(rule: JSONObject) = rule.optJSONArray("protocol")?.let { strings(it) } == listOf("dns")
        fun isMulticast(rule: JSONObject) = rule.optJSONArray("ip_cidr")?.let { strings(it) } == listOf("224.0.0.0/3", "ff00::/8")

        // Plain DNS is hijacked by port only, in every mode: a protocol rule after sniffing would also
        // catch mDNS and LLMNR queries.
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.trafficSniffing = TrafficSniffing.OFF
        var rules = rules("off")
        assertTrue(isPort53(rules.first()))
        assertTrue(rules.none { it.optString("action") == "sniff" || isDnsProtocol(it) })
        // Multicast nothing else routed is refused, last, by destination alone.
        assertTrue(isMulticast(rules.last { isMulticast(it) || it.has("outbound") }))
        assertEquals("reject", rules.single { isMulticast(it) }.getString("action"))
        assertFalse(rules.single { isMulticast(it) }.has("source_ip_cidr"))

        // Port 53 first, then sniffing.
        DataStore.trafficSniffing = TrafficSniffing.ROUTING
        rules = rules("routing")
        assertTrue(isPort53(rules[0]))
        assertEquals("sniff", rules[1].getString("action"))
        assertFalse(rules[1].has("override_destination"))
        // Only what apps reach: a plugin's mapping inbound forwards to the user's own server.
        assertEquals(listOf("tun-in"), strings(rules[1].getJSONArray("inbound")))
        assertTrue(rules.none { isDnsProtocol(it) })
        assertEquals(1, rules.count { it.optString("action") == "sniff" })

        // With bypass LAN in core, LAN traffic, multicast included, goes direct before the refusal.
        DataStore.bypassLanInCore = true
        rules = rules("routing-lan-in-core")
        val bypass = rules.indexOfFirst { it.optBoolean("ip_is_private") && it.optString("outbound") == TAG_BYPASS }
        assertTrue(bypass >= 0 && bypass < rules.indexOfFirst { isMulticast(it) })
        DataStore.bypassLanInCore = false

        DataStore.trafficSniffing = TrafficSniffing.CONNECT_BY_NAME
        rules = rules("connect-by-name")
        assertEquals("sniff", rules[1].getString("action"))
        assertTrue(rules[1].getBoolean("override_destination"))
        assertFalse(rules[1].has("sniffer"))
        // With resolve destination, the sniffed name is resolved on the device after the override.
        DataStore.resolveDestination = true
        rules = rules("connect-by-name-resolved")
        val sniffAt = rules.indexOfFirst { it.optString("action") == "sniff" }
        assertTrue(sniffAt >= 0 && sniffAt < rules.indexOfFirst { it.optString("action") == "resolve" })
        DataStore.resolveDestination = false

        // In proxy mode apps reach the mixed inbound.
        DataStore.serviceMode = Key.MODE_PROXY
        rules = rules("connect-by-name-proxy")
        val sniff = rules.single { it.optString("action") == "sniff" }
        assertEquals(listOf(TAG_MIXED), strings(sniff.getJSONArray("inbound")))
        assertTrue(sniff.getBoolean("override_destination"))
    }

    @Test
    fun nebula_joinsBesideTheProfileWithItsNetworksAsPreferredRoutes() {
        val profile = addSocks(addGroup(), "192.0.2.70", 1080, "nebula-beside")
        DataStore.nebulaEnabled = true
        DataStore.nebulaConfig = "pki: {}"

        val result = build(profile)
        assertTrue(result.nebula)
        val config = JSONObject(result.config)
        val nebula = objects(config.getJSONArray("endpoints")).single()
        assertEquals("nebula", nebula.getString("type"))
        assertEquals(TAG_NEBULA, nebula.getString("tag"))
        assertEquals("pki: {}", nebula.getString("config"))
        val rules = objects(config.getJSONObject("route").getJSONArray("rules"))
        assertTrue(rules.any { it.optString("outbound") == TAG_NEBULA && it.optJSONArray("preferred_by")?.let(::strings) == listOf(TAG_NEBULA) })
        // MagicDNS servers belong to Tailscale endpoints alone.
        assertTrue(objects(config.getJSONObject("dns").getJSONArray("servers")).none { it.optString("type") == "tailscale" })

        // A profile's URL test and an export leave the network out, as does turning it off.
        assertFalse(JSONObject(build(profile, forTest = true).config).has("endpoints"))
        assertFalse(JSONObject(build(profile, forExport = true).config).has("endpoints"))
        DataStore.nebulaEnabled = false
        assertFalse(JSONObject(build(profile).config).has("endpoints"))
        DataStore.nebulaConfig = ""
    }

    @Test
    fun blankDnsHosts_omitsHostsServer() {
        val profile = addSocks(addGroup(), "192.0.2.60", 1080, "blank-hosts")

        val servers = objects(JSONObject(build(profile).config).getJSONObject("dns").getJSONArray("servers"))

        assertFalse(servers.any { it.optString("tag") == TAG_DNS_HOSTS })
    }

    @Test
    fun populatedDnsHosts_addsPredefinedServerAndScopedRule() {
        DataStore.dnsHosts = "node.example 192.0.2.70 2001:db8::70"
        val profile = addSocks(addGroup(), "192.0.2.71", 1080, "mapped-hosts")

        val dns = JSONObject(build(profile).config).getJSONObject("dns")
        val hostsServer = objects(dns.getJSONArray("servers"))
            .single { it.optString("tag") == TAG_DNS_HOSTS }
        val predefined = hostsServer.getJSONObject("predefined").getJSONArray("node.example")
        val rules = objects(dns.getJSONArray("rules"))
        val hostsRule = rules.single { it.optString("server") == TAG_DNS_HOSTS }

        assertEquals("hosts", hostsServer.getString("type"))
        assertEquals(listOf("192.0.2.70", "2001:db8::70"), strings(predefined))
        assertEquals(listOf("A", "AAAA"), strings(hostsRule.getJSONArray("query_type")))
        assertTrue(strings(hostsRule.getJSONArray("domain")).contains("node.example"))
        val directRuleIndex = rules.indexOfFirst { it.optString("server") == "dns-direct" }
        assertTrue(directRuleIndex >= 0)
        assertTrue(rules.indexOf(hostsRule) > directRuleIndex)
    }

    // The service tells the app's own requests which local port its config runs, and the login that port takes.
    @Test
    fun aBuiltConfigKnowsItsLocalPort_andThatVpnModeLeavesItOut() {
        val profile = addSocks(addGroup(), "192.0.2.61", 1080, "local-port")
        fun local(forTest: Boolean = false) = build(profile, forTest = forTest).localProxy?.let { listOf(it.port, it.user, it.password) }
        // Proxy mode asks for no login unless a setting says so.
        assertEquals(listOf(DataStore.mixedPort, "", ""), local())
        assertNull(local(forTest = true))
        DataStore.serviceMode = Key.MODE_VPN
        assertNull(local())
        DataStore.requireProxyInVPN = true
        // In VPN mode the port takes a login, with a secret of that one config.
        val result = build(profile)
        val login = result.localProxy!!
        assertEquals(DataStore.mixedPort to Key.MIXED_USERNAME, login.port to login.user)
        val users = objects(objects(JSONObject(result.config).getJSONArray("inbounds")).single { it.optString("tag") == TAG_MIXED }.getJSONArray("users"))
        assertEquals(listOf(Key.MIXED_USERNAME to login.password), users.map { it.getString("username") to it.getString("password") })
        assertTrue(login.password.length >= 32)
        assertNotEquals(login.password, build(profile).localProxy!!.password)
    }

    // Custom JSON is merged in last and can replace the inbounds, so the port and the login are read from what runs.
    @Test
    fun customJsonThatMovesOrRemovesTheLocalPort_decidesWhatTheConfigReports() {
        val profile = addSocks(addGroup(), "192.0.2.62", 1080, "custom-inbounds")
        fun inbound(tag: String = TAG_MIXED, listen: String = "127.0.0.1", more: String = "") = """{"inbounds":[{"type":"mixed","tag":"$tag","listen":"$listen","listen_port":2090$more}]}"""
        fun local(globalJson: String, profileJson: String = ""): List<Any>? {
            DataStore.globalCustomConfig = globalJson
            profile.requireBean().customConfigJson = profileJson
            return build(profile).localProxy?.let { listOf(it.port, it.user, it.password) }
        }
        // Moved to another port, for all profiles or for this one, which is merged in after.
        assertEquals(listOf(2090, "", ""), local(inbound()))
        assertEquals(listOf(2090, "", ""), local("", inbound()))
        assertEquals(listOf(2090, "", ""), local(inbound(listen = "0.0.0.0")))
        assertEquals(listOf(2090, "", ""), local(inbound(more = ""","users":[]""")))
        // A login that custom JSON wrote is the same at every start, so the app's own requests are not given it; nor
        // do they go to a port that takes logins of others only, or speaks TLS.
        assertNull(local(inbound(more = ""","users":[{"username":"${Key.MIXED_USERNAME}","password":"mine"}]""")))
        assertNull(local(inbound(more = ""","users":[{"username":"other","password":"theirs"}]""")))
        assertNull(local(inbound(more = ""","tls":{"enabled":true}""")))
        // The core reads names without regard to case, so these are users and TLS as well. Two spellings of one name,
        // here also the "Inbounds" next to the config's own "inbounds", leave open which of them the core takes.
        assertNull(local(inbound(more = ""","Users":[{"username":"other","password":"theirs"}]""")))
        assertNull(local(inbound(more = ""","TLS":{"Enabled":true}""")))
        assertNull(local(inbound(more = ""","users":[],"USERS":[{"username":"other","password":"theirs"}]""")))
        // To the core a long s is an s, so this is users too. A name outside ASCII is not judged.
        assertNull(local(inbound(more = ""","u\u017fers":[{"username":"other","password":"theirs"}]""")))
        assertNull(local(inbound(more = ""","users":[],"u\u017fers":[{"username":"other","password":"theirs"}]""")))
        assertNull(local("""{"Inbounds":[{"type":"mixed","tag":"$TAG_MIXED","listen":"127.0.0.1","listen_port":2091}]}"""))
        // Removed, renamed, or listening somewhere the app's requests do not connect.
        assertNull(local("""{"inbounds":[]}"""))
        assertNull(local("", """{"inbounds":[]}"""))
        assertNull(local(inbound(tag = "other-in")))
        assertNull(local(inbound(listen = "::1")))
        assertNull(local(inbound(listen = "::")))
        assertNull(local(inbound(listen = "192.0.2.1")))
        assertNull(local("""{"inbounds":[{"type":"socks","tag":"$TAG_MIXED","listen":"127.0.0.1","listen_port":2090}]}"""))
        assertNull(local("""{"inbounds":[{"type":"mixed","tag":"$TAG_MIXED","listen":"127.0.0.1"}]}"""))
    }

    // A full config runs as written. One made from an exported config still has the inbound, under its tag and
    // without a login.
    @Test
    fun aFullConfig_reportsTheLocalPortItWasWrittenWith() {
        val group = addGroup()
        fun local(config: String) = build(
            addProfile(
                group,
                ConfigBean().apply {
                    name = "full-config"
                    type = 0
                    this.config = config
                    initializeDefaultValues()
                },
            ),
        ).localProxy?.let { listOf(it.port, it.user, it.password) }
        assertEquals(listOf(2091, "", ""), local("""{"inbounds":[{"type":"mixed","tag":"$TAG_MIXED","listen":"127.0.0.1","listen_port":2091}]}"""))
        assertNull(local("""{"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":2091}]}"""))
        // The core reads names without regard to case.
        assertEquals(listOf(2091, "", ""), local("""{"Inbounds":[{"Type":"mixed","Tag":"$TAG_MIXED","Listen":"127.0.0.1","Listen_Port":2091}]}"""))
        assertNull(local("""{"inbounds":[{"type":"mixed","tag":"$TAG_MIXED","listen":"127.0.0.1","listen_port":2091,"Users":[{"Username":"${Key.MIXED_USERNAME}","Password":"mine"}]}]}"""))
        // A login written into the config is the same at every start.
        assertNull(local("""{"inbounds":[{"type":"mixed","tag":"$TAG_MIXED","listen":"127.0.0.1","listen_port":2091,"users":[{"username":"${Key.MIXED_USERNAME}","password":"mine"}]}]}"""))
        assertNull(local("{}"))
        assertNull(local("not a config"))
    }

    private fun addGroup(isSelector: Boolean = false, autoSelect: Boolean = false) = ConfigBuilderTestEnv.io {
        ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true, isSelector = isSelector, autoSelect = autoSelect))
    }

    private fun addSocks(groupId: Long, address: String, port: Int, name: String) = addProfile(
        groupId,
        SOCKSBean().apply {
            serverAddress = address
            serverPort = port
            protocol = 2
            this.name = name
            initializeDefaultValues()
        },
    )

    private fun addChain(groupId: Long, name: String, vararg hopIds: Long) = addProfile(
        groupId,
        ChainBean().apply {
            this.name = name
            proxies = hopIds.toList()
            initializeDefaultValues()
        },
    )

    private fun addProfile(groupId: Long, bean: AbstractBean) = ConfigBuilderTestEnv.io {
        ProxyEntity(groupId = groupId).apply {
            putBean(bean)
            id = ProfileDatabase.proxyDao.addProxy(this)
        }
    }

    private fun build(profile: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false) = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = forTest, forExport = forExport) }

    private fun assertResultMaps(result: ConfigBuildResult, profile: ProxyEntity) {
        assertTrue(result.profileTagMap.containsKey(profile.id))
        assertTrue(result.trafficMap.values.flatten().any { it.id == profile.id })
    }

    private fun outbound(root: JSONObject, type: String) = objects(root.getJSONArray("outbounds")).single { it.optString("type") == type }

    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
}

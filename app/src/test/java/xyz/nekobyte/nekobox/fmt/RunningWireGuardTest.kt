package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.naive.NaiveBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import java.util.Base64

/**
 * The path the VPN's core was built with for a profile, against the one a test prepares for it now: the service
 * tests through its core only when they are the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RunningWireGuardTest {
    private fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() })

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    private fun wg(name: String, block: WireGuardBean.() -> Unit = {}) = WireGuardBean().apply {
        this.name = name
        serverAddress = "wg.example"
        serverPort = 51820
        localAddress = "10.7.0.2/32"
        privateKey = key(1)
        peerPublicKey = key(2)
        initializeDefaultValues()
        block()
    }

    private fun socks(name: String, server: String = "192.0.2.30") = SOCKSBean().apply {
        this.name = name
        serverAddress = server
        serverPort = 1080
    }

    private fun add(bean: AbstractBean, groupId: Long = 0L): ProxyEntity = ProxyEntity(groupId = groupId).putBean(bean.apply { initializeDefaultValues() })
        .also { it.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(it) } }

    private fun chain(name: String, vararg hops: ProxyEntity) = ChainBean().apply {
        this.name = name
        proxies = hops.map { it.id }
    }

    private fun fresh(id: Long) = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.getById(id)!! }

    // The VPN's config for [profile], as the service started it.
    private fun running(profile: ProxyEntity) = ConfigBuilderTestEnv.io { buildConfig(fresh(profile.id)) }

    // What a test of [id] prepares now and sends to the service: always a path, or no two would compare as equal.
    private fun candidate(id: Long) = checkNotNull(ConfigBuilderTestEnv.io { buildConfig(fresh(id), forTest = true) }.paths.getValue(id).fingerprint)

    private fun ConfigBuildResult.fingerprint(id: Long) = paths.getValue(id).fingerprint

    private fun edit(id: Long, block: AbstractBean.() -> Unit) = ConfigBuilderTestEnv.io {
        val profile = ProfileDatabase.proxyDao.getById(id)!!
        profile.putBean(profile.requireBean().apply(block))
        ProfileDatabase.proxyDao.updateProxy(profile)
    }

    private fun group(block: ProxyGroup.() -> Unit) = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true).apply(block)) }

    private fun updateGroup(id: Long, block: ProxyGroup.() -> Unit) = ConfigBuilderTestEnv.io {
        ProfileDatabase.groupDao.updateGroup(ProfileDatabase.groupDao.getById(id)!!.apply(block))
    }

    @Test
    fun aTestMatchesTheRunningPathUntilAnEdit() {
        val tunnel = add(wg("tunnel"))
        val vpn = running(tunnel)
        assertNotNull(vpn.fingerprint(tunnel.id))
        assertEquals(vpn.fingerprint(tunnel.id), candidate(tunnel.id))
        // The name is no setting of the path.
        edit(tunnel.id) { name = "renamed" }
        assertEquals(vpn.fingerprint(tunnel.id), candidate(tunnel.id))
        edit(tunnel.id) { (this as WireGuardBean).peerPublicKey = key(3) }
        assertNotEquals(vpn.fingerprint(tunnel.id), candidate(tunnel.id))
    }

    @Test
    fun aChainHopEditAndAReversedPathAreOtherPaths() {
        val tunnel = add(wg("tunnel"))
        val path = add(chain("path", add(socks("hop")), tunnel))
        val vpn = running(path)
        assertEquals(vpn.fingerprint(path.id), candidate(path.id))
        edit(tunnel.id) { serverAddress = "wg2.example" }
        assertNotEquals(vpn.fingerprint(path.id), candidate(path.id))

        // A group's hop moved from its front to its landing position: the same hops, dialed the other way round.
        val relay = add(socks("relay", "relay.example"))
        val groupId = group { frontProxy = relay.id }
        val member = add(wg("member"), groupId)
        val before = running(member)
        updateGroup(groupId) {
            frontProxy = -1L
            landingProxy = relay.id
        }
        assertNotEquals(before.fingerprint(member.id), candidate(member.id))
    }

    @Test
    fun anotherResolverOfTheSubscriptionIsAnotherPath() {
        val groupId = group {
            type = GroupType.SUBSCRIPTION
            subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                customDnsResolver = "https://resolver.example/dns-query"
            }
        }
        val member = add(wg("member"), groupId)
        val vpn = running(member)
        assertEquals(vpn.fingerprint(member.id), candidate(member.id))
        updateGroup(groupId) { subscription!!.customDnsResolver = "https://other.example/dns-query" }
        assertNotEquals(vpn.fingerprint(member.id), candidate(member.id))
    }

    @Test
    fun everyMemberOfARunningGroupKeepsAPathOfItsOwn() {
        val groupId = group { isSelector = true }
        val selected = add(wg("selected"), groupId)
        val other = add(wg("other") { privateKey = key(4) }, groupId)
        // A one-hop chain of the selected member: the same outbound under an alias.
        val alias = add(chain("alias", selected), groupId)
        val vpn = running(selected)
        for (profile in listOf(selected, other, alias)) assertEquals(vpn.fingerprint(profile.id), candidate(profile.id))
        edit(other.id) { (this as WireGuardBean).privateKey = key(5) }
        assertNotEquals(vpn.fingerprint(other.id), candidate(other.id))
        assertEquals(vpn.fingerprint(selected.id), candidate(selected.id))
        assertEquals(vpn.fingerprint(alias.id), candidate(alias.id))

        // Global custom JSON that points the alias at direct: a test through it would not take the path.
        val aliased = listOf(selected, alias).single { vpn.profileTagMap.getValue(it.id) != vpn.paths.getValue(it.id).tags.first() }
        val outbounds = JSONObject(vpn.config).getJSONArray("outbounds")
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            if (outbound.optString("tag") == vpn.profileTagMap.getValue(aliased.id)) outbound.put("outbounds", JSONArray().put("direct")).remove("default")
        }
        try {
            DataStore.globalCustomConfig = JSONObject().put("outbounds", outbounds).toString()
            assertNull(running(selected).fingerprint(aliased.id))
        } finally {
            DataStore.globalCustomConfig = ""
        }
    }

    @Test
    fun aSharedWireGuardFrontHopIsOnEveryMembersPath() {
        val tunnel = add(wg("front"))
        val groupId = group {
            isSelector = true
            frontProxy = tunnel.id
        }
        val first = add(socks("first", "first.example"), groupId)
        val second = add(socks("second", "second.example"), groupId)
        val vpn = running(first)
        // One outbound for the hop, built for the member that came first; the other's path names it too.
        val front = vpn.wireGuardPath(first.id)!!.single()
        assertEquals(listOf(front.tag), vpn.wireGuardPath(second.id)!!.map { it.tag })
        assertEquals(second.id, vpn.wireGuardPath(second.id)!!.single().ownerProfileId)
        assertEquals(1, vpn.wireGuardIdentities.byTag.size)
    }

    @Test
    fun globalCustomJsonThatChangesTheRunningOutboundIsAnotherPath() {
        val tunnel = add(wg("tunnel"))
        val plain = running(tunnel)
        assertEquals(plain.fingerprint(tunnel.id), candidate(tunnel.id))
        // The same outbound, tag and keys, at another server; a test's config leaves global custom JSON out.
        val outbounds = JSONObject(plain.config).getJSONArray("outbounds")
        val tag = plain.profileTagMap.getValue(tunnel.id)
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            if (outbound.optString("tag") == tag) outbound.put("server", "203.0.113.9")
        }
        try {
            DataStore.globalCustomConfig = JSONObject().put("outbounds", outbounds).toString()
            assertNotEquals(running(tunnel).fingerprint(tunnel.id), candidate(tunnel.id))
            // An outbound added with the tag once more: which one the core takes is open.
            val again = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.single { it.optString("tag") == tag }
            DataStore.globalCustomConfig = JSONObject().put("+outbounds", JSONArray().put(again)).toString()
            assertNull(running(tunnel).fingerprint(tunnel.id))
        } finally {
            DataStore.globalCustomConfig = ""
        }
    }

    // Global custom JSON that gives the running outbound with [tag] the resolver [resolver].
    private fun overrideResolver(config: String, tag: String, resolver: JSONObject) {
        val outbounds = JSONObject(config).getJSONArray("outbounds")
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            if (outbound.optString("tag") == tag) outbound.put("domain_resolver", resolver)
        }
        DataStore.globalCustomConfig = JSONObject().put("outbounds", outbounds).toString()
    }

    @Test
    fun aResolverCustomJsonGivesAHopIsPartOfThePath() {
        val landing = add(socks("landing", "landing.example"))
        val tunnel = add(wg("tunnel"))
        val profile = add(chain("chain", tunnel, landing))
        try {
            // The address family the VPN resolves servers with is the builder's, a test's config leaves it out.
            DataStore.configurationStore.putString("domain_strategy_for_server", "ipv4_only")
            assertEquals(running(profile).fingerprint(profile.id), candidate(profile.id))
            DataStore.configurationStore.putString("domain_strategy_for_server", "auto")
            val vpn = running(profile)
            assertEquals(vpn.fingerprint(profile.id), candidate(profile.id))
            val (landingTag, tunnelTag) = vpn.paths.getValue(profile.id).tags.let { it.first() to it.last() }
            // The landing hop is dialed through the tunnel and hands its name on; resolved here, it is not.
            overrideResolver(vpn.config, landingTag, JSONObject().put("server", "dns-direct"))
            assertNotEquals(running(profile).fingerprint(profile.id), candidate(profile.id))
            // The tunnel's server resolved by the same DNS server, for another address family only.
            overrideResolver(vpn.config, tunnelTag, JSONObject().put("server", "dns-direct").put("strategy", "ipv6_only"))
            assertNotEquals(running(profile).fingerprint(profile.id), candidate(profile.id))
            // Only the path differs, not the saved settings: a reconnect would not make them the same.
            assertEquals(running(profile).fingerprint(profile.id)!!.substringBefore('.'), candidate(profile.id).substringBefore('.'))
        } finally {
            DataStore.globalCustomConfig = ""
            DataStore.configurationStore.putString("domain_strategy_for_server", "auto")
        }
    }

    @Test
    fun aPluginHopIsThePluginNotItsPortOnTheDevice() {
        val naive = add(
            NaiveBean().apply {
                serverAddress = "naive.example"
                serverPort = 443
                username = "u"
                password = "p"
            },
        )
        val tunnel = add(wg("tunnel"))
        // Naive reaches its server through the tunnel; each build gives its SOCKS server another port and login.
        val profile = add(chain("chain", tunnel, naive))
        val vpn = running(profile)
        assertNotNull(vpn.fingerprint(profile.id))
        assertEquals(vpn.fingerprint(profile.id), candidate(profile.id))
        assertEquals(vpn.fingerprint(profile.id), running(profile).fingerprint(profile.id))
        edit(naive.id) { (this as NaiveBean).password = "q" }
        assertNotEquals(vpn.fingerprint(profile.id), candidate(profile.id))
        // An edit of the saved settings, which a reconnect takes.
        assertNotEquals(vpn.fingerprint(profile.id)!!.substringBefore('.'), candidate(profile.id).substringBefore('.'))
        edit(naive.id) { (this as NaiveBean).password = "p" }

        val tag = vpn.paths.getValue(profile.id).tags.first()
        val outbounds = JSONObject(vpn.config).getJSONArray("outbounds")
        try {
            // A port custom JSON gave the SOCKS server: not the one the plugin listens on.
            for (index in 0 until outbounds.length()) outbounds.getJSONObject(index).takeIf { it.optString("tag") == tag }?.put("server_port", 1)
            DataStore.globalCustomConfig = JSONObject().put("outbounds", outbounds).toString()
            assertNull(running(profile).fingerprint(profile.id))
            // Route rules custom JSON changed: the plugin's way to its server is not the one built.
            DataStore.globalCustomConfig = JSONObject().put("route", JSONObject().put("+rules", JSONArray().put(JSONObject().put("action", "sniff")))).toString()
            assertNull(running(profile).fingerprint(profile.id))
        } finally {
            DataStore.globalCustomConfig = ""
        }
    }

    @Test
    fun aPathThatCannotBeReadNamesNoWireGuardHopToBlame() {
        val landing = add(socks("landing", "landing.example"))
        val tunnel = add(wg("tunnel"))
        val profile = add(chain("chain", tunnel, landing))
        val prepared = ConfigBuilderTestEnv.io { buildConfig(fresh(profile.id), forTest = true) }
        assertEquals(1, prepared.wireGuardPath(profile.id)!!.size)
        // The profile's custom JSON sends the landing hop direct: the tunnel is not on the way, a test's timeout is
        // no handshake missing there.
        val tag = prepared.paths.getValue(profile.id).tags.first()
        val outbounds = JSONObject(prepared.config).getJSONArray("outbounds")
        for (index in 0 until outbounds.length()) outbounds.getJSONObject(index).takeIf { it.optString("tag") == tag }?.put("detour", "direct")
        edit(profile.id) { customConfigJson = JSONObject().put("outbounds", outbounds).toString() }
        val redirected = ConfigBuilderTestEnv.io { buildConfig(fresh(profile.id), forTest = true) }
        assertNull(redirected.fingerprint(profile.id))
        assertNull(redirected.wireGuardPath(profile.id))
        // Or without a detour at all: the landing hop dials its server itself, the tunnel left aside.
        outbounds.getJSONObject((0 until outbounds.length()).single { outbounds.getJSONObject(it).optString("tag") == tag }).remove("detour")
        edit(profile.id) { customConfigJson = JSONObject().put("outbounds", outbounds).toString() }
        val skipped = ConfigBuilderTestEnv.io { buildConfig(fresh(profile.id), forTest = true) }
        assertNull(skipped.fingerprint(profile.id))
        assertNull(skipped.wireGuardPath(profile.id))
    }

    @Test
    fun aTestWhoseDefaultRouteLeavesThePathHasNone() {
        val tunnel = add(wg("tunnel"))
        assertNotNull(candidate(tunnel.id))
        // The profile's custom JSON sends the default route direct: an isolated test would not take the tunnel.
        edit(tunnel.id) { customConfigJson = JSONObject().put("route", JSONObject().put("final", "direct")).toString() }
        val redirected = ConfigBuilderTestEnv.io { buildConfig(fresh(tunnel.id), forTest = true) }
        assertNull(redirected.fingerprint(tunnel.id))
        assertNull(redirected.wireGuardPath(tunnel.id))
        // Nor a rule ahead of it that takes every connection elsewhere.
        edit(tunnel.id) { customConfigJson = JSONObject().put("route", JSONObject().put("+rules", JSONArray().put(JSONObject().put("outbound", "direct")))).toString() }
        assertNull(ConfigBuilderTestEnv.io { buildConfig(fresh(tunnel.id), forTest = true) }.fingerprint(tunnel.id))
    }

    // What a test of [id] prepares now, which may have no fingerprint.
    private fun prepared(id: Long) = ConfigBuilderTestEnv.io { buildConfig(fresh(id), forTest = true) }.fingerprint(id)

    @Test
    fun aHopsOwnJsonThatMovesItsPluginOrResolverLeavesThePathUnread() {
        val naive = add(
            NaiveBean().apply {
                serverAddress = "naive.example"
                serverPort = 443
                username = "u"
                password = "p"
            },
        )
        val landing = add(socks("landing", "landing.example"))
        val tunnel = add(wg("tunnel"))
        val viaNaive = add(chain("via naive", tunnel, naive))
        val viaSocks = add(chain("via socks", tunnel, landing))
        assertNotNull(candidate(viaNaive.id))
        assertNotNull(candidate(viaSocks.id))
        // The plugin listens on the port the builder gave it, not on the one the hop's own JSON names.
        edit(naive.id) { customOutboundJson = JSONObject().put("server_port", 1).toString() }
        assertNull(running(viaNaive).fingerprint(viaNaive.id))
        assertNull(prepared(viaNaive.id))
        // A resolver of the landing hop's own JSON, dialed through the tunnel, also when global JSON then changes the
        // DNS server it names.
        edit(landing.id) { customOutboundJson = JSONObject().put("domain_resolver", "dns-direct").toString() }
        assertNull(prepared(viaSocks.id))
        try {
            DataStore.globalCustomConfig = JSONObject().put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", "192.0.2.53")))).toString()
            assertNull(running(viaSocks).fingerprint(viaSocks.id))
        } finally {
            DataStore.globalCustomConfig = ""
        }
        // And the tunnel's own resolver options.
        edit(tunnel.id) { customOutboundJson = JSONObject().put("domain_resolver", JSONObject().put("server", "dns-direct").put("strategy", "ipv6_only")).toString() }
        assertNull(prepared(tunnel.id))
        assertNull(running(tunnel).fingerprint(tunnel.id))
    }

    @Test
    fun aServerNameARuleOutboundSharesIsResolvedLikeTheRuleOutbound() {
        val groupId = group {
            type = GroupType.SUBSCRIPTION
            subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                customDnsResolver = "https://resolver.example/dns-query"
            }
        }
        val member = add(wg("member"), groupId)
        assertEquals(running(member).fingerprint(member.id), candidate(member.id))
        // A profile of another group on the same server name, the target of a rule: the VPN resolves that name with
        // direct DNS then, which a test's config, without rules, does not.
        val shared = add(socks("shared", "wg.example"))
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.reset()
            ProfileDatabase.rulesDao.createRule(RuleEntity(domains = "full:b.example", outbound = shared.id).apply { enabled = true })
        }
        assertNotEquals(running(member).fingerprint(member.id), candidate(member.id))
    }

    @Test
    fun aFullCustomConfigIsOnePathAsWritten() {
        val config = """{"endpoints": [{"type": "wireguard", "tag": "w", "address": ["10.7.0.2/32"], "private_key": "${key(1)}", "peers": [{"address": "192.0.2.20", "port": 51820, "public_key": "${key(2)}", "allowed_ips": ["0.0.0.0/0"]}]}]}"""
        val full = add(
            ConfigBean().apply {
                type = 0
                this.config = config
            },
        )
        val vpn = running(full)
        assertTrue(vpn.paths.getValue(full.id).defaultRoute)
        // Which of its outbounds a test takes is unknown, so a timeout is never put down to one of them.
        assertNull(vpn.wireGuardPath(full.id))
        assertEquals(vpn.fingerprint(full.id), candidate(full.id))
        edit(full.id) { (this as ConfigBean).config = config.replace("51820", "51821") }
        assertNotEquals(vpn.fingerprint(full.id), candidate(full.id))
    }

    @Test
    fun aConfigIsReadOnceForAllItsPaths() {
        val groupId = group { isSelector = true }
        val members = (1..40).map { add(wg("member $it") { privateKey = key(10 + it) }, groupId) }
        val before = ConfigIndex.built.get()
        val vpn = running(members.first())
        // One reading of the finished config, not one per member.
        assertEquals(1, ConfigIndex.built.get() - before)
        assertTrue(members.all { vpn.fingerprint(it.id) != null })
    }
}

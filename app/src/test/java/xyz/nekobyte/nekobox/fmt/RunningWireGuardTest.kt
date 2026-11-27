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

    // What a test of [id] prepares now and sends to the service.
    private fun candidate(id: Long) = ConfigBuilderTestEnv.io { buildConfig(fresh(id), forTest = true) }.paths.getValue(id).fingerprint

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
}

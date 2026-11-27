package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import java.util.Base64

/** What the service checks before it tests a profile with WireGuard hops through its running core. */
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
        serverAddress = "192.0.2.20"
        serverPort = 51820
        localAddress = "10.7.0.2/32"
        privateKey = key(1)
        peerPublicKey = key(2)
        initializeDefaultValues()
        block()
    }

    private fun socks(name: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.30"
        serverPort = 1080
    }

    private fun add(bean: AbstractBean, groupId: Long = 0L): ProxyEntity = ProxyEntity(groupId = groupId).putBean(bean.apply { initializeDefaultValues() })
        .also { it.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(it) } }

    private fun chain(name: String, vararg hops: ProxyEntity) = ChainBean().apply {
        this.name = name
        proxies = hops.map { it.id }
    }

    private fun build(profile: ProxyEntity) = ConfigBuilderTestEnv.io { buildConfig(profile) }

    private fun saved(id: Long) = ConfigBuilderTestEnv.io { savedChainTraffic(ProfileDatabase.proxyDao.getById(id)!!) }

    private fun edit(id: Long, block: AbstractBean.() -> Unit) = ConfigBuilderTestEnv.io {
        val profile = ProfileDatabase.proxyDao.getById(id)!!
        profile.putBean(profile.requireBean().apply(block))
        ProfileDatabase.proxyDao.updateProxy(profile)
    }

    @Test
    fun aRunningProfileIsTestedThroughTheCoreOnlyAsSaved() {
        val tunnel = add(wg("tunnel"))
        val config = build(tunnel)
        assertTrue(config.runsAsSaved(tunnel.id, saved(tunnel.id)))
        // The name is no setting of the outbound.
        edit(tunnel.id) { name = "renamed" }
        assertTrue(config.runsAsSaved(tunnel.id, saved(tunnel.id)))
        edit(tunnel.id) { (this as WireGuardBean).peerPublicKey = key(3) }
        assertFalse(config.runsAsSaved(tunnel.id, saved(tunnel.id)))
        assertFalse(config.runsAsSaved(tunnel.id, null))
    }

    @Test
    fun aChainRunsAsSavedOnlyWithEachOfItsHopsAsSaved() {
        val tunnel = add(wg("tunnel"))
        val path = add(chain("path", add(socks("hop")), tunnel))
        val config = build(path)
        assertTrue(config.runsAsSaved(path.id, saved(path.id)))
        edit(tunnel.id) { serverAddress = "192.0.2.21" }
        assertFalse(config.runsAsSaved(path.id, saved(path.id)))
    }

    @Test
    fun anEditedMemberOfARunningGroupNoLongerRunsAsSaved() {
        val group = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true, isSelector = true)) }
        val selected = add(wg("selected"), group)
        val other = add(wg("other") { privateKey = key(4) }, group)
        val config = build(selected)
        assertTrue(config.runsAsSaved(other.id, saved(other.id)))
        edit(other.id) { (this as WireGuardBean).privateKey = key(5) }
        assertFalse(config.runsAsSaved(other.id, saved(other.id)))
        assertTrue(config.runsAsSaved(selected.id, saved(selected.id)))
    }

    @Test
    fun aProfileTheVpnDoesNotRunMayStillShareItsWireGuardSession() {
        val tunnel = add(wg("tunnel"))
        val config = build(tunnel)
        // A chain through the running hop.
        val path = add(chain("path", add(socks("hop")), tunnel))
        assertTrue(config.sharesWireGuardSession(saved(path.id)))
        // A copy under another id.
        val copy = add(wg("copy"))
        assertTrue(config.sharesWireGuardSession(saved(copy.id)))
        // The same server with another client key, and the same client key with another server.
        assertFalse(config.sharesWireGuardSession(saved(add(wg("other key") { privateKey = key(6) }).id)))
        assertFalse(config.sharesWireGuardSession(saved(add(wg("other server") { peerPublicKey = key(7) }).id)))
        // An extra peer with the running server's key.
        val extra = add(
            wg("extra") {
                peerPublicKey = key(8)
                extraPeers = "[Peer]\nPublicKey = ${key(2)}\nAllowedIPs = 10.9.0.0/24\n"
            },
        )
        assertTrue(config.sharesWireGuardSession(saved(extra.id)))
        assertFalse(config.sharesWireGuardSession(saved(add(socks("plain")).id)))
    }

    @Test
    fun relaysOfAListShareTheListsFirstWireGuardHop() {
        val tunnel = add(wg("tunnel"))
        val list = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "relays", type = GroupType.PROXIES, frontProxy = tunnel.id)) }
        val running = add(socks("relay-a"), list)
        val other = add(socks("relay-b"), list)
        val config = build(running)
        assertFalse(other.id in config.profileTagMap)
        assertTrue(config.sharesWireGuardSession(saved(other.id)))
    }
}

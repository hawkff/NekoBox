package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardIdentities
import xyz.nekobyte.nekobox.fmt.wireguard.wireGuardKey
import xyz.nekobyte.nekobox.fmt.wireguard.x25519PublicKey
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import java.io.File
import java.util.Base64

/** The WireGuard sessions a core leases, read from the config as it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardIdentityTest {
    private fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { (seed * 7 + it).toByte() })

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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

    private fun add(bean: AbstractBean, groupId: Long = 0L): ProxyEntity = ProxyEntity(groupId = groupId).putBean(bean.apply { initializeDefaultValues() })
        .also { it.id = ConfigBuilderTestEnv.io { ProfileDatabase.proxyDao.addProxy(it) } }

    private fun sessions(profile: ProxyEntity, forTest: Boolean = false) = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = forTest) }.wireGuardIdentities

    @Test
    fun theClientKeyIsTheX25519PublicKeyOfThePrivateKey() {
        // RFC 7748, section 6.1.
        val alice = x25519PublicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.joinToString("") { "%02x".format(it) })
        val bob = x25519PublicKey(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun spellingsOfOneKeyAreOneSession() {
        val plain = key(1)
        val spellings = listOf(plain, plain.trimEnd('='), plain.chunked(10).joinToString("\n"), plain.replace('+', '-').replace('/', '_'))
        spellings.forEach { assertTrue(wireGuardKey(it).contentEquals(wireGuardKey(plain))) }
        val identities = spellings.map { spelling -> sessions(add(wg("spelled") { privateKey = spelling })) }
        assertEquals(1, identities.map { it.all }.toSet().size)
        assertFalse(identities.first().unknown)
        assertEquals(1, identities.first().all.size)
    }

    @Test
    fun customJsonThatSetsTheKeyCountsAsTheKey() {
        val running = sessions(add(wg("running")))
        // A copy with a key of its own whose custom JSON puts the running key back.
        val overridden = sessions(
            add(
                wg("copy") {
                    privateKey = key(9)
                    customOutboundJson = """{"private_key": "${key(1)}"}"""
                },
            ),
        )
        assertEquals(running.all, overridden.all)
        val other = sessions(add(wg("other") { privateKey = key(9) }))
        assertNotEquals(running.all, other.all)
        // The test build reads the same keys.
        assertEquals(running.all, sessions(add(wg("again")), forTest = true).all)
    }

    @Test
    fun everyPeerAndEveryHopOpensASession() {
        val extra = add(
            wg("extra") {
                extraPeers = "[Peer]\nPublicKey = ${key(3)}\nAllowedIPs = 10.9.0.0/24\n"
            },
        )
        assertEquals(2, sessions(extra).all.size)
        val tunnel = add(wg("tunnel"))
        val path = add(
            ChainBean().apply {
                name = "path"
                proxies = listOf(
                    add(
                        SOCKSBean().apply {
                            serverAddress = "192.0.2.30"
                            serverPort = 1080
                        },
                    ).id,
                    tunnel.id,
                )
            },
        )
        assertEquals(sessions(tunnel).all, sessions(path).all)
        // A list's first hop is a hop of each of its relays.
        val list = ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.createGroup(ProxyGroup(name = "relays", type = GroupType.PROXIES, frontProxy = tunnel.id)) }
        val relay = add(
            SOCKSBean().apply {
                serverAddress = "192.0.2.31"
                serverPort = 1080
            },
            list,
        )
        assertEquals(sessions(tunnel).all, sessions(relay).all)
    }

    @Test
    fun aFullConfigIsReadAsWrittenAndUnreadableKeysAreUnknown() {
        val full = add(
            ConfigBean().apply {
                type = 0
                config = """{"endpoints": [{"type": "wireguard", "tag": "w", "private_key": "${key(1)}", "peers": [{"public_key": "${key(2)}"}]}]}"""
            },
        )
        assertEquals(sessions(add(wg("same"))).all, sessions(full).all)
        val shortKey = wireGuardIdentities("""{"outbounds": [{"type": "wireguard", "tag": "w", "private_key": "short", "peer_public_key": "${key(2)}"}]}""")
        assertTrue(shortKey.unknown)
        assertFalse(shortKey.malformed)
        // Two spellings of one key: which one the core takes is open.
        val twoKeys = wireGuardIdentities("""{"outbounds": [{"type": "wireguard", "tag": "w", "private_key": "${key(1)}", "Private_Key": "${key(4)}", "peer_public_key": "${key(2)}"}]}""")
        assertTrue(twoKeys.unknown)
        assertFalse(twoKeys.malformed)
        // Fields other than keys that are not clear are the config's fault: two spellings of a tag, also on an entry
        // without keys, and a tag that is no text beside keys that can be read.
        // A name outside ASCII too: the core folds a long s (U+017F) to an s, so it may stand for any field.
        for (config in listOf("""{"outbounds": [{"type": "direct", "tag": "d", "Tag": "e"}]}""", """{"outbounds": [{"type": "socks", "tag": "s", "domain_re${'\u017F'}olver": "dns-local"}]}""", """{"outbound${'\u017F'}": [], "outbounds": []}""", """{"outbounds": [{"type": "wireguard", "tag": 5, "private_key": "${key(1)}", "peer_public_key": "${key(2)}"}]}""")) {
            val identities = wireGuardIdentities(config)
            assertTrue(config, identities.unknown && identities.malformed)
        }
        // Not a key's fault: the outbounds are no list of objects, with or without a WireGuard entry.
        for (config in listOf("not json", "[]", """{"outbounds": {}}""", """{"outbounds": ["direct"]}""", """{"endpoints": [1, {"type": "wireguard"}]}""")) {
            val identities = wireGuardIdentities(config)
            assertTrue(config, identities.unknown && identities.malformed)
        }
        assertTrue(wireGuardIdentities("""{"outbounds": [{"type": "direct", "tag": "d"}]}""").isEmpty)
    }

    // The cases of the core decoder's test (libcore TestWireGuardIdentitiesAsTheCoreDecodesThem): null lists, tags,
    // names in other cases, spellings.
    @Test
    fun theSessionsAreTheOnesTheCoreDecodes() {
        val cases = JSONArray(File("../libcore/testdata/wireguard-identities.json").readText())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val identities = wireGuardIdentities(case.getString("config"))
            val expected = case.getJSONArray("identities").let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }
            assertEquals(case.getString("name"), expected, identities.all)
            assertEquals(case.getString("name"), case.getBoolean("unknown"), identities.unknown)
        }
    }
}

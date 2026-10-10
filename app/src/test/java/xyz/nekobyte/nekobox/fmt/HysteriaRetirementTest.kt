package xyz.nekobyte.nekobox.fmt

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.group.ImportPreview
import xyz.nekobyte.nekobox.group.RawUpdater
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.parseProxies
import xyz.nekobyte.nekobox.proxy.config.ConfigBean
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HysteriaRetirementTest {
    private val logSink = Logs.sink
    private val v1 = "hysteria://192.0.2.1:443?auth=example&upmbps=10&downmbps=50#legacy"
    private val v2 = "hy2://example@192.0.2.2:443#current"

    @Before
    fun setUp() {
        Logs.sink = {}
        ConfigBuilderTestEnv.reset()
    }

    @After
    fun tearDown() {
        Logs.sink = logSink
    }

    private fun legacy(transport: Int = HysteriaBean.PROTOCOL_UDP) = HysteriaBean().apply {
        protocolVersion = 1
        protocol = transport
        name = "legacy"
        serverAddress = "192.0.2.1"
        serverPorts = "443"
        authPayload = "example"
        authPayloadType = HysteriaBean.TYPE_STRING
        streamReceiveWindow = 65_536
        connectionReceiveWindow = 262_144
        initializeDefaultValues()
    }

    @Test
    fun allV1TransportsRemainReadableAndExportableButCannotRun() {
        for (transport in listOf(HysteriaBean.PROTOCOL_UDP, HysteriaBean.PROTOCOL_FAKETCP, HysteriaBean.PROTOCOL_WECHAT_VIDEO)) {
            val bean = legacy(transport)
            val profile = ProxyEntity(id = 17).putBean(bean)
            val encoded = KryoConverters.serialize(profile)
            val restored = KryoConverters.deserialize(ProxyEntity(), encoded)
            assertArrayEquals(encoded, KryoConverters.serialize(restored))
            assertEquals(ProxyEntity.TYPE_HYSTERIA, restored.type)
            assertEquals("legacy", restored.displayName())
            assertEquals("192.0.2.1:443", restored.displayAddress())
            assertTrue(restored.displayType().contains("unsupported"))
            assertFalse(restored.canBuild())
            assertFalse(restored.needExternal())
            assertFalse(restored.haveSettings())
            assertNull(restored.settingIntent(RuntimeEnvironment.getApplication(), false))
            assertTrue(restored.toStdLink().startsWith("hysteria://"))
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(parseUniversal(bean.toUniversalLink())))
            assertThrows(IllegalArgumentException::class.java) { buildConfig(restored, forTest = true) }
        }
    }

    @Test
    fun importPreviewsSkipV1InEverySupportedEnvelope() = runBlocking {
        val rawV1 = ConfigBean().applyDefaultValues().apply {
            type = 1
            config = """{"type":"hysteria","server":"192.0.2.1","server_port":443,"up_mbps":10}"""
        }
        val inputs = listOf(
            "$v1\n$v2",
            Base64.getEncoder().encodeToString("$v1\n$v2".toByteArray()),
            "${legacy().toUniversalLink()}\n$v2",
            "${rawV1.toUniversalLink()}\n$v2",
            "proxies:\n  - {name: old, type: hysteria, server: 192.0.2.1, port: 443, auth-str: example}\n  - {name: current, type: hysteria2, server: 192.0.2.2, port: 443, password: example}",
            """{"outbounds":[{"type":"hysteria","server":"192.0.2.1","server_port":443},{"type":"hysteria2","server":"192.0.2.2","server_port":443,"up_mbps":10,"password":"example"}]}""",
            """[{"server":"192.0.2.1:443","up_mbps":10,"auth_str":"example"},{"server":"192.0.2.2:443","auth":"example"}]""",
        )
        for (input in inputs) {
            val parsed = checkNotNull(RawUpdater.parseImport(input))
            assertEquals(1, parsed.proxies.size)
            assertEquals(listOf("hysteria"), parsed.report.failed)
            assertEquals(1, ImportPreview.of(parsed).unparsedCount)
            assertTrue(ProxyEntity().putBean(parsed.proxies.single()).canBuild())
        }
        assertTrue(parseProxies(v1).isEmpty())
        assertNull(RawUpdater.parseRaw(v1))
        assertThrows(IllegalStateException::class.java) { runBlocking { RawUpdater.parseImport(v1) } }
        val rawV2 = """{"type":"hysteria2","server":"192.0.2.2","server_port":443,"up_mbps":10,"password":"example"}"""
        assertTrue(RawUpdater.parseRaw(rawV2)!!.single() is ConfigBean)
        assertFalse(ProxyEntity().putBean(rawV1).canBuild())
        val failure = assertThrows(IllegalArgumentException::class.java) { buildConfig(ProxyEntity().putBean(rawV1), forTest = true) }
        assertTrue(failure.message!!.contains("Hysteria 1"))
    }

    @Test
    fun mixedSubscriptionUpdatesKeepRetiredRowsAndTheirOriginalPayloads() = ConfigBuilderTestEnv.io {
        runBlocking {
            val subscription = SubscriptionBean().applyDefaultValues().apply { link = "https://subscription.example/list" }
            val group = ProxyGroup(type = GroupType.SUBSCRIPTION, subscription = subscription).also {
                it.id = ProfileDatabase.groupDao.createGroup(it)
            }
            val old = ProxyEntity(groupId = group.id, userOrder = 1, tx = 19, lifetimeRx = 29).putBean(legacy())
            old.id = ProfileDatabase.proxyDao.addProxy(old)
            // The decoder tolerates a trailing extension. An order-only update must not re-encode and discard it.
            val originalPayload = KryoConverters.serialize(old.requireBean()) + byteArrayOf(1, 2, 3)
            ProfileDatabase.instance.openHelper.writableDatabase.execSQL(
                "UPDATE proxy_entities SET hysteriaBean = ? WHERE id = ?",
                arrayOf(originalPayload, old.id),
            )
            DataStore.selectedProxy = old.id
            for (content in listOf("$v1\n$v2", v2)) {
                RawUpdater.updateFromContent(group, subscription, content, reconfigureUpdater = {})
                val rows = ProfileDatabase.proxyDao.getByGroup(group.id)
                assertEquals(2, rows.size)
                val retained = rows.single { it.id == old.id }
                assertTrue(retained.isHysteria1())
                assertEquals(19L, retained.tx)
                assertEquals(29L, retained.lifetimeRx)
                assertEquals(old.id, DataStore.selectedProxy)
                assertTrue(subscription.importWarning!!.contains("Hysteria 1"))
                ProfileDatabase.instance.openHelper.readableDatabase.query("SELECT hysteriaBean FROM proxy_entities WHERE id = ${old.id}").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertArrayEquals(originalPayload, cursor.getBlob(0))
                }
                assertNotEquals(old.id, rows.single { !it.isHysteria1() }.id)
            }
            val before = ProfileDatabase.proxyDao.countByGroup(group.id)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RawUpdater.updateFromContent(group, subscription, v1, reconfigureUpdater = {}) }
            }
            assertEquals(before, ProfileDatabase.proxyDao.countByGroup(group.id))
        }
    }

    @Test
    fun selectorsSkipRetiredMembersAndChainsRefuseRetiredHops() = ConfigBuilderTestEnv.io {
        val group = ProxyGroup(isSelector = true).also { it.id = ProfileDatabase.groupDao.createGroup(it) }
        val old = ProxyEntity(groupId = group.id).putBean(legacy()).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        val current = ProxyEntity(groupId = group.id).putBean(legacy().apply { protocolVersion = 2 }).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        val built = buildConfig(current)
        assertFalse(built.profileTagMap.containsKey(old.id))
        assertTrue(built.profileTagMap.containsKey(current.id))
        assertEquals(2, ProfileDatabase.proxyDao.countByGroup(group.id))
        assertThrows(IllegalArgumentException::class.java) { buildConfig(old) }
        val chain = ProxyEntity(groupId = group.id).putBean(ChainBean().applyDefaultValues().apply { proxies = listOf(old.id) })
        assertThrows(IllegalArgumentException::class.java) { buildConfig(chain, forTest = true) }
        val json = JSONObject(built.config)
        assertFalse(json.getJSONArray("outbounds").toString().contains("\"type\":\"hysteria\""))
    }
}

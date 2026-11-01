package xyz.nekobyte.nekobox.database

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.internal.ChainBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.proxy.config.ConfigBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChainProbeResultsTest {
    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        ConfigBuilderTestEnv.io { ProfileDatabase.groupDao.insert(listOf(ProxyGroup(id = 1))) }
    }

    @Test
    fun directAndNestedHopEditsDiscardResults() = ConfigBuilderTestEnv.io {
        for (nested in listOf(false, true)) {
            for (change in listOf<(ProxyEntity) -> Unit>(
                { it.socksBean!!.password = "replacement" },
                { it.socksBean!!.serverAddress = "changed.example" },
                { it.uuid = "replacement-identity" },
            )) {
                val hop = proxy()
                val root = chain(if (nested) chain(hop.id).id else hop.id)
                val result = prepared(root)
                val current = ProfileDatabase.proxyDao.getById(hop.id)!!.also(change)
                ProfileDatabase.proxyDao.updateProxy(current)
                save(result)
                assertEquals(3, ProfileDatabase.proxyDao.getById(root.id)!!.status)
            }
        }
    }

    @Test
    fun nestedMembershipChangesAndDeletedHopsDiscardResults() = ConfigBuilderTestEnv.io {
        for (delete in listOf(false, true)) {
            val hop = proxy()
            val nested = chain(hop.id)
            val root = chain(nested.id)
            val result = prepared(root)
            if (delete) {
                ProfileDatabase.proxyDao.deleteById(hop.id)
            } else {
                nested.chainBean!!.proxies = listOf(proxy().id)
                ProfileDatabase.proxyDao.updateProxy(nested)
            }
            save(result)
            assertEquals(3, ProfileDatabase.proxyDao.getById(root.id)!!.status)
        }
    }

    @Test
    fun snapshotUsesPreparationTimeRatherThanQueueTime() = ConfigBuilderTestEnv.io {
        val hop = proxy()
        val root = chain(hop.id)
        val result = ProfileTestResult(root)
        hop.socksBean!!.serverAddress = "prepared.example"
        ProfileDatabase.proxyDao.updateProxy(hop)
        assertTrue(result.prepareUrlTest().config.contains("prepared.example"))
        hop.socksBean!!.serverAddress = "original.example"
        ProfileDatabase.proxyDao.updateProxy(hop)
        root.status = 1
        root.ping = 42
        save(result)
        assertEquals(3, ProfileDatabase.proxyDao.getById(root.id)!!.status)
    }

    @Test
    fun groupProxyEditsAndGroupReferenceChangesDiscardResults() = ConfigBuilderTestEnv.io {
        for (front in listOf(false, true)) {
            for (replaceReference in listOf(false, true)) {
                val hop = proxy()
                val root = proxy()
                val group = ProxyGroup(id = 1, frontProxy = if (front) hop.id else -1, landingProxy = if (front) -1 else hop.id)
                ProfileDatabase.groupDao.updateGroup(group)
                val result = prepared(root)
                if (replaceReference) {
                    if (front) group.frontProxy = proxy().id else group.landingProxy = proxy().id
                    ProfileDatabase.groupDao.updateGroup(group)
                } else {
                    hop.socksBean!!.password = "changed"
                    ProfileDatabase.proxyDao.updateProxy(hop)
                }
                save(result)
                assertEquals(3, ProfileDatabase.proxyDao.getById(root.id)!!.status)
            }
        }
    }

    @Test
    fun aMembersListFirstHopChangeDiscardsResults() = ConfigBuilderTestEnv.io {
        // The chain's second member comes from a list of proxies, so it goes through the list's first hop.
        val list = ProfileDatabase.groupDao.createGroup(ProxyGroup(type = GroupType.PROXIES, frontProxy = proxy().id))
        val member = ProxyEntity(groupId = list).putBean(
            SOCKSBean().apply {
                serverAddress = "relay.example"
                serverPort = 1080
                initializeDefaultValues()
            },
        ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        val root = chain(proxy().id, member.id)
        val result = prepared(root)
        ProfileDatabase.groupDao.getById(list)!!.also {
            it.frontProxy = proxy().id
            ProfileDatabase.groupDao.updateGroup(it)
        }
        save(result)
        assertEquals(3, ProfileDatabase.proxyDao.getById(root.id)!!.status)
    }

    @Test
    fun unchangedInputsKeepResultsWithoutOverwritingCountersOrOrder() = ConfigBuilderTestEnv.io {
        val hop = proxy()
        val root = chain(chain(hop.id).id)
        val result = prepared(root)
        hop.rx = 111
        ProfileDatabase.proxyDao.updateProxy(hop)
        val latest = ProfileDatabase.proxyDao.getById(root.id)!!.apply {
            rx = 222
            lifetimeTx = 333
            userOrder = 44
        }
        ProfileDatabase.proxyDao.updateProxy(latest)
        save(result)
        assertEquals(latest.copy(status = 1, ping = 42, error = null), ProfileDatabase.proxyDao.getById(root.id))
        assertEquals(111L, ProfileDatabase.proxyDao.getById(hop.id)!!.rx)
    }

    @Test
    fun repairingAMissingHopInvalidatesTheOldFailure() = ConfigBuilderTestEnv.io {
        val root = chain(9_999)
        val result = ProfileTestResult(root)
        assertThrows(IllegalArgumentException::class.java) { result.prepareUrlTest() }
        root.error = "missing hop"
        save(result)
        assertEquals("missing hop", ProfileDatabase.proxyDao.getById(root.id)!!.error)
        val replacement = ProxyEntity(id = 9_999, groupId = 1).putBean(SOCKSBean().apply { initializeDefaultValues() })
        ProfileDatabase.proxyDao.insert(listOf(replacement))
        root.error = "stale failure"
        save(result)
        assertEquals("missing hop", ProfileDatabase.proxyDao.getById(root.id)!!.error)
    }

    @Test
    fun circularChainsDoNotLoopDuringSnapshotCapture() = ConfigBuilderTestEnv.io {
        val root = chain()
        root.chainBean!!.proxies = listOf(root.id)
        ProfileDatabase.proxyDao.updateProxy(root)
        val result = ProfileTestResult(root)
        assertThrows(IllegalArgumentException::class.java) { result.prepareUrlTest() }
        root.error = "cycle"
        save(result)
        assertEquals("cycle", ProfileDatabase.proxyDao.getById(root.id)!!.error)
    }

    @Test
    fun fullCustomConfigsIgnoreUnusedGroupProxies() = ConfigBuilderTestEnv.io {
        val root = ProxyEntity(groupId = 1, status = 3).putBean(
            ConfigBean().apply {
                initializeDefaultValues()
                type = 0
                config = "{}"
            },
        ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        val result = prepared(root)
        ProfileDatabase.groupDao.updateGroup(ProxyGroup(id = 1, frontProxy = proxy().id))
        save(result)
        assertEquals(42, ProfileDatabase.proxyDao.getById(root.id)!!.ping)
    }

    private fun prepared(profile: ProxyEntity) = ProfileTestResult(profile).also {
        it.prepareUrlTest()
        profile.status = 1
        profile.ping = 42
        profile.error = null
    }

    private fun save(result: ProfileTestResult) = runBlocking { ProfileManager.updateTestResults(listOf(result)) }

    private fun proxy() = ProxyEntity(groupId = 1, status = 3, error = "old").putBean(
        SOCKSBean().apply {
            serverAddress = "original.example"
            serverPort = 1080
            password = "original"
            initializeDefaultValues()
        },
    ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }

    private fun chain(vararg ids: Long) = ProxyEntity(groupId = 1, status = 3, error = "old").putBean(
        ChainBean().apply {
            proxies = ids.toList()
            initializeDefaultValues()
        },
    ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
}

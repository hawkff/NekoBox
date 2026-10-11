package xyz.nekobyte.nekobox.fmt

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.mieru.MieruBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfigRollbackTest {
    @Before fun setup() = ConfigBuilderTestEnv.reset()

    @Test
    fun failedSelectorMemberLeavesEarlierMembersAndNoPartialCredentialsOrDns() = ConfigBuilderTestEnv.io {
        val subscription = SubscriptionBean().apply {
            initializeDefaultValues()
            customDnsResolver = "https://resolver.example/dns-query"
        }
        ProfileDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, type = GroupType.SUBSCRIPTION, subscription = subscription, isSelector = true)))
        val first = profile(1, "first", "first.example")
        val failed = profile(2, "shared", "failed.example").apply { mieruBean!!.customOutboundJson = """{"type":"unsupported"}""" }
        val last = profile(3, "shared", "last.example")
        ProfileDatabase.proxyDao.insert(listOf(first, failed, last))
        val built = buildConfig(first)
        assertEquals(setOf(1L, 3L), built.profileTagMap.keys)
        assertEquals("shared", built.profileTagMap[3L])
        assertEquals(2, built.localProxyCredentials.size)
        val ports = built.externalIndex.flatMap { it.chain.keys }.toSet()
        assertEquals(ports, built.localProxyCredentials.keys)
        assertTrue(built.config.contains("first.example"))
        assertTrue(built.config.contains("last.example"))
        assertFalse(built.config.contains("failed.example"))
        assertFalse(built.config.contains("unsupported"))
    }

    private fun profile(id: Long, name: String, server: String) = ProxyEntity(id = id, groupId = 1, userOrder = id).putBean(
        MieruBean().apply {
            this.name = name
            serverAddress = server
            serverPort = 443
            username = "user"
            password = "password"
            initializeDefaultValues()
        },
    )
}

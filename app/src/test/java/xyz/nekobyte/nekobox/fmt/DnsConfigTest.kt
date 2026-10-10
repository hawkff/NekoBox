package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.IPv6Mode
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.database.*
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DnsConfigTest {
    @Test
    fun uriSettingsBecomeTypedServersWithoutChangingPortsOrBootstrap() {
        val cases = listOf(
            "192.0.2.1" to Triple("udp", "192.0.2.1", 53),
            "2001:db8::1" to Triple("udp", "2001:db8::1", 53),
            "[2001:db8::1]:5353" to Triple("udp", "2001:db8::1", 5353),
            "tcp://dns.example:443" to Triple("tcp", "dns.example", 443),
            "tls://dns.example" to Triple("tls", "dns.example", 853),
            "quic://dns.example:8853" to Triple("quic", "dns.example", 8853),
            "https://dns.example/custom?key=value" to Triple("https", "dns.example", 443),
            "h3://dns.example:8443/dns-query" to Triple("h3", "dns.example", 8443),
        )
        cases.forEach { (address, expected) ->
            val server = dnsServer(address, "test", "bootstrap", "proxy")
            assertEquals(expected.first, server.type)
            assertEquals(expected.second, server.server)
            assertEquals(expected.third, server.server_port)
            assertEquals("proxy", server.detour)
            if (expected.second == "dns.example") {
                assertEquals("bootstrap", server.domain_resolver?.server)
            } else {
                assertNull(server.domain_resolver)
            }
        }
        assertEquals("/custom?key=value", dnsServer(cases[6].first, "test", "bootstrap").path)
        assertEquals("/dns-query", dnsServer("https://dns.example", "test", "bootstrap").path)
        assertEquals("local", dnsServer("local", "test", "bootstrap").type)
        for (address in listOf("bad://secret.example", "https://user:password@dns.example", "tls://dns.example/path", "https://dns.example/#secret", "udp://dns.example:0")) {
            val error = assertThrows(IllegalArgumentException::class.java) { dnsServer(address, "test", "bootstrap") }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("password"))
        }
    }

    @Test
    fun generatedConfigsUseTypedDnsAndPreserveFamilyAndSubscriptionPolicies() {
        val directory = File("build/generated-core-configs/dns").apply { mkdirs() }
        ConfigBuilderTestEnv.reset()
        val profile = ConfigBuilderTestEnv.io {
            val groupId = ProfileDatabase.groupDao.createGroup(
                ProxyGroup(
                    type = GroupType.SUBSCRIPTION,
                    subscription = SubscriptionBean().applyDefaultValues().apply {
                        customDnsResolver = "https://subscription.example/dns-query"
                    },
                ),
            )
            ProxyEntity(groupId = groupId).putBean(
                SOCKSBean().applyDefaultValues().apply {
                    name = "test-proxy"
                    serverAddress = "server.example"
                    serverPort = 1080
                },
            ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        }
        DataStore.dnsHosts = "mapped.example 192.0.2.100"
        DataStore.enableDnsRouting = true
        ConfigBuilderTestEnv.io {
            ProfileDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:direct.example", outbound = -1))
            ProfileDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:blocked.example", outbound = -2))
        }
        for (mode in listOf(IPv6Mode.DISABLE, IPv6Mode.ENABLE, IPv6Mode.PREFER, IPv6Mode.ONLY)) {
            DataStore.ipv6Mode = mode
            for (fake in listOf(false, true)) {
                DataStore.enableFakeDns = fake
                for (vpn in listOf(false, true)) {
                    DataStore.serviceMode = if (vpn) Key.MODE_VPN else Key.MODE_PROXY
                    val result = ConfigBuilderTestEnv.io { buildConfig(profile, forExport = true) }
                    directory.resolve("$mode-$fake-$vpn.json").writeText(result.config)
                    val root = JSONObject(result.config)
                    val dns = root.getJSONObject("dns")
                    assertFalse(dns.has("fakeip"))
                    objects(dns.getJSONArray("servers")).forEach {
                        assertTrue(it.has("type"))
                        assertFalse(it.has("address"))
                        assertFalse(it.has("strategy"))
                    }
                    val rules = objects(dns.getJSONArray("rules"))
                    assertFalse(rules.any { it.has("outbound") || it.has("strategy") })
                    val blockedFamily = when (mode) {
                        IPv6Mode.DISABLE -> "AAAA"
                        IPv6Mode.ONLY -> "A"
                        else -> null
                    }
                    if (blockedFamily != null) {
                        val first = rules.first()
                        assertEquals("predefined", first.getString("action"))
                        assertEquals("NOERROR", first.getString("rcode"))
                        assertEquals(blockedFamily, first.getJSONArray("query_type").getString(0))
                        assertEquals("server.example", first.getJSONArray("domain").getString(0))
                    }
                    val outbound = objects(root.getJSONArray("outbounds")).first()
                    assertFalse(outbound.has("domain_strategy"))
                    assertEquals("dns-sub-${profile.groupId}", outbound.getJSONObject("domain_resolver").getString("server"))
                    assertEquals("dns-direct", root.getJSONObject("route").getJSONObject("default_domain_resolver").getString("server"))
                }
            }
        }
        val probe = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = true) }
        directory.resolve("probe.json").writeText(probe.config)
        assertEquals("dns-direct", JSONObject(probe.config).getJSONObject("dns").getString("final"))
    }

    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
}

package xyz.nekobyte.nekobox.fmt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.utils.PackageCache

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConfigBuilderDnsRuleTest {
    private lateinit var profile: ProxyEntity
    private val packages = setOf("test.app")

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.enableDnsRouting = true
        PackageCache.packageMap = mapOf("test.app" to 10001)
        profile = ConfigBuilderTestEnv.io {
            val groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup())
            ProxyEntity(groupId = groupId).putBean(
                SOCKSBean().applyDefaultValues().apply {
                    name = "proxy"
                    serverAddress = "server.example"
                    serverPort = 1080
                },
            ).also { it.id = ProfileDatabase.proxyDao.addProxy(it) }
        }
    }

    @Test
    fun nonDomainAndConnectionCriteria_neverBecomeAppOnlyDns() {
        val criteria = listOf<RuleEntity.() -> Unit>(
            { ip = "192.0.2.0/24" },
            { ip = "geoip:cn" },
            { ip = "geoip:private" },
            { port = "443" },
            { port = "8000:9000" },
            { sourcePort = "1024:65535" },
            { network = "udp" },
            { source = "192.0.2.0/24" },
            { protocol = "quic" },
            { ruleset = "rsip:https://rules.example/ip.srs" },
            { config = """{"port":[443]}""" },
            { domains = "full:" },
        )
        for (fake in listOf(false, true)) {
            DataStore.enableFakeDns = fake
            val baseline = dnsRules().toString()
            for (outbound in listOf(-1L, 0L, -2L)) {
                for (criterion in criteria) {
                    for (apps in listOf(emptySet(), packages)) {
                        replaceRules(RuleEntity(outbound = outbound, packages = apps).apply(criterion))
                        assertEquals("fake=$fake outbound=$outbound criterion=${criterion.javaClass}", baseline, dnsRules().toString())
                    }
                }
            }
            replaceRules()
        }
    }

    @Test
    fun networkSourcePortAndPrivateIpOnlyRules_remainInRouting() {
        replaceRules(
            RuleEntity(network = "udp", outbound = -1),
            RuleEntity(sourcePort = "1234", outbound = -1),
            RuleEntity(sourcePort = "1024:65535", outbound = -1),
            RuleEntity(ip = "geoip:private", outbound = -1),
        )
        val routes = objects(build().getJSONObject("route").getJSONArray("rules"))
            .filter { it.optString("outbound") == TAG_BYPASS }
        assertEquals(4, routes.size)
        assertEquals(listOf("udp"), values(routes[0].getJSONArray("network")))
        assertEquals(listOf(1234), values(routes[1].getJSONArray("source_port")))
        assertEquals(listOf("1024:65535"), values(routes[2].getJSONArray("source_port_range")))
        assertTrue(routes[3].getBoolean("ip_is_private"))
    }

    @Test
    fun mixedConnectionAndDomainCriteria_doNotDropAndConditions() {
        val criteria = listOf<RuleEntity.() -> Unit>(
            { port = "443" },
            { sourcePort = "1024:65535" },
            { network = "tcp" },
            { source = "192.0.2.0/24" },
            { protocol = "tls" },
            { config = """{"invert":true}""" },
        )
        val baseline = dnsRules().toString()
        for (criterion in criteria) {
            replaceRules(
                RuleEntity(
                    domains = "full:target.example",
                    ruleset = "rssite:https://rules.example/domains.srs",
                    packages = packages,
                    outbound = -1,
                ).apply(criterion),
            )
            assertEquals(baseline, dnsRules().toString())
        }
    }

    @Test
    fun appOnly_preservesDirectProxyBlockAndFakeDns() {
        for (fake in listOf(false, true)) {
            DataStore.enableFakeDns = fake
            for ((outbound, server) in listOf(-1L to "dns-direct", 0L to if (fake) "dns-fake" else "dns-remote", -2L to "dns-block")) {
                replaceRules(RuleEntity(outbound = outbound, packages = packages))
                val rule = dnsRules().single { it.has("user_id") }
                assertEquals(listOf(10001), values(rule.getJSONArray("user_id")))
                if (outbound == -2L) {
                    assertEquals("predefined", rule.getString("action"))
                    assertEquals("NOERROR", rule.getString("rcode"))
                    assertFalse(rule.has("server"))
                } else {
                    assertEquals(server, rule.getString("server"))
                }
                assertFalse(rule.has("domain"))
                assertFalse(rule.has("rule_set"))
                if (fake && outbound == 0L) {
                    assertEquals(listOf("tun-in"), values(rule.getJSONArray("inbound")))
                    assertEquals(listOf("A", "AAAA"), values(rule.getJSONArray("query_type")))
                } else {
                    assertFalse(rule.has("inbound"))
                    assertFalse(rule.has("query_type"))
                }
            }
        }
    }

    @Test
    fun domainAndIpAlternatives_keepDomainMatchersAndAppScopeInOneRule() {
        replaceRules(
            RuleEntity(
                domains = "full:Exact.example,domain:Suffix.example,keyword:match,regexp:^test\\.,geosite:cn",
                ip = "192.0.2.0/24,geoip:cn",
                ruleset = "rssite:https://rules.example/domains.srs,rsip:https://rules.example/ip.srs",
                packages = packages,
                outbound = -1,
            ),
        )
        val root = build()
        val ruleSets = objects(root.getJSONObject("route").getJSONArray("rule_set"))
        val siteTag = ruleSets.single { it.optString("url").endsWith("/domains.srs") }.getString("tag")
        val ipTag = ruleSets.single { it.optString("url").endsWith("/ip.srs") }.getString("tag")
        val dns = dnsRules(root).single { it.has("user_id") }
        assertEquals(listOf("geosite:cn", siteTag), values(dns.getJSONArray("rule_set")))
        assertEquals(listOf(10001), values(dns.getJSONArray("user_id")))
        assertEquals(listOf("exact.example"), values(dns.getJSONArray("domain")))
        assertEquals(listOf("suffix.example"), values(dns.getJSONArray("domain_suffix")))
        assertEquals(listOf("match"), values(dns.getJSONArray("domain_keyword")))
        assertEquals(listOf("^test\\."), values(dns.getJSONArray("domain_regex")))
        val route = objects(root.getJSONObject("route").getJSONArray("rules")).single { it.has("user_id") }
        assertEquals(listOf("geosite:cn", "geoip:cn", siteTag, ipTag), values(route.getJSONArray("rule_set")))
        assertEquals(listOf("192.0.2.0/24"), values(route.getJSONArray("ip_cidr")))
    }

    @Test
    fun remoteDomainRuleSet_retainsAppScopeWithoutCatchAll() {
        for (outbound in listOf(-1L, 0L, -2L)) {
            replaceRules(RuleEntity(ruleset = "rssite:https://rules.example/domains.srs", packages = packages, outbound = outbound))
            val dns = dnsRules().filter { it.has("rule_set") || it.has("user_id") }
            assertEquals(1, dns.size)
            assertTrue(dns.single().has("rule_set"))
            assertEquals(listOf(10001), values(dns.single().getJSONArray("user_id")))
        }
        replaceRules(RuleEntity(domains = "full:target.example", packages = setOf("missing.app"), outbound = -1))
        assertFalse(dnsRules().any { it.optJSONArray("domain")?.toString()?.contains("target.example") == true })
    }

    @Test
    fun ordering_keepsBootstrapAheadOfDomainRulesAndFakeDnsLast() {
        DataStore.enableFakeDns = true
        replaceRules(
            RuleEntity(domains = "geosite:category-anticensorship", packages = packages),
            RuleEntity(ip = "geoip:cn", packages = packages, outbound = -1),
            RuleEntity(domains = "geosite:cn", packages = packages, outbound = -1),
            RuleEntity(domains = "full:blocked.example", outbound = -2),
        )
        val rules = dnsRules()
        assertEquals("dns-direct", rules.first().getString("server"))
        assertTrue(values(rules.first().getJSONArray("domain")).containsAll(listOf("server.example", "resolver.example")))
        assertFalse(rules.any { it.has("outbound") })
        assertEquals(listOf("dns-fake", "dns-direct", "predefined"), rules.drop(1).dropLast(1).map { it.optString("server", it.optString("action")) })
        assertEquals(listOf("geosite:category-anticensorship"), values(rules[1].getJSONArray("rule_set")))
        assertEquals(listOf("geosite:cn"), values(rules[2].getJSONArray("rule_set")))
        assertEquals("dns-fake", rules.last().getString("server"))
        assertEquals(listOf("A", "AAAA"), values(rules.last().getJSONArray("query_type")))
        DataStore.enableDnsRouting = false
        assertFalse(dnsRules().any { it.has("user_id") || it.has("rule_set") })
    }

    @Test
    fun subscriptionResolver_staysScopedAndAheadOfUserRules() {
        ConfigBuilderTestEnv.io {
            val group = ProfileDatabase.groupDao.getById(profile.groupId)!!
            group.type = GroupType.SUBSCRIPTION
            group.subscription = SubscriptionBean().applyDefaultValues().apply {
                customDnsResolver = "https://subscription-resolver.example/dns-query"
            }
            ProfileDatabase.groupDao.updateGroup(group)
        }
        DataStore.enableFakeDns = true
        replaceRules(RuleEntity(packages = packages, outbound = -2))
        val root = build()
        val rules = dnsRules(root)
        val tag = "dns-sub-${profile.groupId}"
        assertEquals(tag, rules.first().getString("server"))
        assertEquals(listOf("server.example"), values(rules.first().getJSONArray("domain")))
        val server = objects(root.getJSONObject("dns").getJSONArray("servers")).single { it.optString("tag") == tag }
        assertEquals("https", server.getString("type"))
        assertEquals("subscription-resolver.example", server.getString("server"))
        assertEquals("/dns-query", server.getString("path"))
        assertFalse(server.has("detour"))
        assertEquals("dns-local", server.getJSONObject("domain_resolver").getString("server"))
        val outbound = objects(root.getJSONArray("outbounds")).single { it.optString("type") == "socks" }
        assertEquals(tag, outbound.getJSONObject("domain_resolver").getString("server"))
        assertTrue(rules.indexOfFirst { it.has("user_id") } > 0)
        assertFalse(rules.filter { it.optString("server") == "dns-direct" }.any { it.optJSONArray("domain")?.toString()?.contains("server.example") == true })
    }

    @Test
    fun sharedServerHost_doesNotAcquireAnotherSubscriptionsResolver() {
        ConfigBuilderTestEnv.io {
            val groupId = ProfileDatabase.groupDao.createGroup(
                ProxyGroup(
                    type = GroupType.SUBSCRIPTION,
                    subscription = SubscriptionBean().applyDefaultValues().apply {
                        customDnsResolver = "https://subscription-resolver.example/dns-query"
                    },
                ),
            )
            val extra = profile.copy(id = 0, groupId = groupId).putBean(profile.requireBean().clone())
            extra.id = ProfileDatabase.proxyDao.addProxy(extra)
            ProfileDatabase.rulesDao.createRule(RuleEntity(domains = "full:target.example", outbound = extra.id, enabled = true))
        }
        val root = build()
        assertFalse(objects(root.getJSONObject("dns").getJSONArray("servers")).any { it.optString("tag").startsWith("dns-sub-") })
        assertTrue(values(dnsRules(root).first().getJSONArray("domain")).contains("server.example"))
    }

    private fun replaceRules(vararg rules: RuleEntity) = ConfigBuilderTestEnv.io {
        ProfileDatabase.rulesDao.reset()
        rules.forEachIndexed { index, rule ->
            rule.enabled = true
            rule.userOrder = index.toLong()
            ProfileDatabase.rulesDao.createRule(rule)
        }
    }

    private fun build() = ConfigBuilderTestEnv.io { JSONObject(buildConfig(profile).config) }
    private fun dnsRules(root: JSONObject = build()) = objects(root.getJSONObject("dns").getJSONArray("rules"))
        .filterNot { it.optString("action") == "predefined" && it.has("query_type") }
    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
    private fun values(array: JSONArray) = (0 until array.length()).map(array::get)
}

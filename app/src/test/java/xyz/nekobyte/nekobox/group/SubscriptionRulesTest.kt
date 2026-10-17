package xyz.nekobyte.nekobox.group

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.ui.BackupFormatV2

/** Routing rules inside Xray and Clash subscriptions, as rules of this app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionRulesTest {

    private fun rules(profile: RoutingProfiles.Profile) = BackupFormatV2.decodeRules(profile.content.getJSONArray("rules"))

    private val outbounds = listOf(
        JSONObject().put("tag", "proxy").put("protocol", "vless"),
        JSONObject().put("tag", "direct").put("protocol", "freedom"),
        JSONObject().put("tag", "block").put("protocol", "blackhole"),
        JSONObject().put("tag", "dns-out").put("protocol", "dns"),
    )

    private fun rule(vararg fields: Pair<String, Any>) = JSONObject().put("type", "field").apply { fields.forEach { (key, value) -> put(key, value) } }

    private fun list(vararg values: String) = JSONArray(values.toList())

    @Test
    fun xrayRulesKeepTheirOrderAndFollowTheConfigOutbounds() {
        val routing = JSONObject().put("domainStrategy", "IPIfNonMatch").put(
            "rules",
            JSONArray()
                .put(rule("outboundTag" to "block", "domain" to list("geosite:category-ads-all")))
                .put(rule("outboundTag" to "proxy", "domain" to list("full:blocked.example")))
                .put(rule("outboundTag" to "direct", "domain" to list("geosite:category-ru", "domain:example.ru", "yandex", "ext:custom.dat:ru")))
                .put(rule("outboundTag" to "direct", "ip" to list("geoip:ru", "geoip:private", "10.0.0.0/8")))
                .put(rule("ruleTag" to "quic", "outboundTag" to "block", "network" to "udp", "port" to "443", "protocol" to list("quic")))
                .put(rule("outboundTag" to "direct", "port" to "1000-2000,3000", "network" to "tcp,udp", "source" to list("192.168.1.0/24", "geoip:cn")))
                // No counterpart here: a DNS outbound, a balancer, an inbound condition, and a domain that must also match an IP.
                .put(rule("outboundTag" to "dns-out", "port" to 53))
                .put(rule("balancerTag" to "auto", "domain" to list("geosite:google")))
                .put(rule("outboundTag" to "direct", "inboundTag" to list("socks")))
                .put(rule("outboundTag" to "direct", "domain" to list("a.example"), "ip" to list("192.0.2.1")))
                // Without its only domain the rule would match everything, so it is left out.
                .put(rule("outboundTag" to "direct", "domain" to list("ext:only.dat:x"), "port" to "80"))
                .put(rule("outboundTag" to "proxy", "network" to "tcp,udp"))
                .put(rule("outboundTag" to "direct", "domain" to list("unreachable.example"))),
        )
        val profile = SubscriptionRules.fromXray(routing, outbounds)
        val rules = rules(profile)
        assertEquals(listOf("block", "proxy", "direct", "direct", "quic", "direct"), rules.map { it.name })
        assertEquals(listOf(-2L, 0L, -1L, -1L, -2L, -1L), rules.map { it.outbound })
        assertEquals((1L..6L).toList(), rules.map { it.userOrder })
        assertTrue(rules.all { it.enabled })
        // A bare Xray domain matches anywhere in the name, as a keyword does here.
        assertEquals("geosite:category-ru\ndomain:example.ru\nkeyword:yandex", rules[2].domains)
        assertEquals("geoip:ru\ngeoip:private\n10.0.0.0/8", rules[3].ip)
        assertEquals(listOf("udp", "443", "quic"), listOf(rules[4].network, rules[4].port, rules[4].protocol))
        assertEquals(listOf("1000:2000\n3000", "", "192.168.1.0/24"), listOf(rules[5].port, rules[5].network, rules[5].source))
        assertEquals(listOf("domain (2)", "source (1)", "outboundTag (1)", "balancerTag (1)", "inboundTag (1)", "domain and ip (1)"), profile.notes)
    }

    @Test
    fun aFinalXrayRuleToDirectIsReportedAndEndsTheRules() {
        val routing = JSONObject().put(
            "rules",
            JSONArray()
                .put(rule("outboundTag" to "proxy", "domain" to list("geosite:google")))
                .put(rule("outboundTag" to "direct", "network" to "tcp,udp"))
                .put(rule("outboundTag" to "block", "domain" to list("never.example"))),
        )
        val profile = SubscriptionRules.fromXray(routing, outbounds)
        assertEquals(listOf("proxy"), rules(profile).map { it.name })
        assertEquals(listOf("final rule (1)"), profile.notes)

        // 3x-ui only sends everything through the proxy: nothing to carry and nothing lost.
        val panel = SubscriptionRules.fromXray(JSONObject().put("rules", JSONArray().put(rule("outboundTag" to "proxy", "network" to "tcp,udp"))), outbounds)
        assertEquals(0, panel.ruleCount)
        assertEquals(emptyList<String>(), panel.notes)
    }

    @Test
    fun clashTargetsResolveThroughGroupsAsClashStartsThem() {
        val groups = listOf(
            mapOf("name" to "PROXY", "type" to "select", "proxies" to listOf("node-1", "DIRECT")),
            mapOf("name" to "RU", "type" to "select", "proxies" to listOf("DIRECT", "PROXY")),
            mapOf("name" to "Ads", "type" to "select", "proxies" to listOf("REJECT")),
            mapOf("name" to "Auto", "type" to "url-test", "proxies" to listOf("node-1", "node-2")),
        )
        val clash = listOf(
            "DOMAIN-SUFFIX,ads.example,Ads",
            "GEOSITE,Category-Ads-All,Ads",
            "DOMAIN,exact.example,PROXY",
            "DOMAIN-KEYWORD,stream,Auto",
            "DOMAIN-SUFFIX,ru,RU",
            "DOMAIN-REGEX,^.*\\.su$,RU",
            "GEOIP,RU,RU,no-resolve",
            "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
            "GEOIP,LAN,DIRECT",
            "DST-PORT,6881-6889/51413,REJECT",
            "RULE-SET,custom,DIRECT",
            "PROCESS-NAME,app,DIRECT",
            "AND,((DOMAIN,a.example),(NETWORK,UDP)),REJECT",
            "DOMAIN,pass.example,PASS",
            "IP-CIDR,not-an-address,DIRECT",
            "MATCH,PROXY",
            "DOMAIN,unreachable.example,DIRECT",
        )
        val profile = SubscriptionRules.fromClash(clash, groups)
        val rules = rules(profile)
        assertEquals(listOf("Ads", "PROXY", "Auto", "RU", "RU", "DIRECT", "REJECT"), rules.map { it.name })
        assertEquals(listOf(-2L, 0L, 0L, -1L, -1L, -1L, -2L), rules.map { it.outbound })
        // Neighbours with the same target and kind share one rule; the order between targets stays.
        assertEquals("domain:ads.example\ngeosite:category-ads-all", rules[0].domains)
        assertEquals("full:exact.example", rules[1].domains)
        assertEquals("keyword:stream", rules[2].domains)
        assertEquals("domain:ru\nregexp:^.*\\.su$", rules[3].domains)
        assertEquals("geoip:ru", rules[4].ip)
        assertEquals("10.0.0.0/8\ngeoip:private", rules[5].ip)
        assertEquals("6881:6889\n51413", rules[6].port)
        assertEquals(listOf("RULE-SET (1)", "PROCESS-NAME (1)", "AND (1)", "PASS (1)", "IP-CIDR (1)"), profile.notes)

        val direct = SubscriptionRules.fromClash(listOf("DOMAIN-SUFFIX,blocked.example,PROXY", "MATCH,RU"), groups)
        assertEquals(listOf("PROXY"), rules(direct).map { it.name })
        assertEquals(listOf("MATCH (1)"), direct.notes)

        // 3x-ui's default Clash rules send everything to its PROXY group.
        val panel = SubscriptionRules.fromClash(listOf("MATCH,PROXY"), groups)
        assertEquals(0, panel.ruleCount)
        assertEquals(emptyList<String>(), panel.notes)
    }
}

package xyz.nekobyte.nekobox.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.database.RuleEntity

class RouteTesterTest {
    // Stands in for the core: Russian sites end in .ru, Russian addresses start with 77., local ones with 192.168.
    private val core = { json: String, host: String ->
        require("no-such-list" !in json) { "no-such-list: missing list" }
        ("geosite:category-ru" in json && host.endsWith(".ru")) ||
            ("geoip:ru" in json && host.startsWith("77.")) ||
            ("ip_is_private" in json && host.startsWith("192.168.")) ||
            ("\"example.org\"" in json && host.endsWith("example.org"))
    }
    private val quic = RuleEntity(id = 1, enabled = true, port = "443", network = "udp", outbound = -2)
    private val ads = RuleEntity(id = 2, enabled = false, domains = "geosite:category-ads-all", outbound = -2)
    private val russianSites = RuleEntity(id = 3, enabled = true, domains = "geosite:category-ru", outbound = -1)
    private val russianAddresses = RuleEntity(id = 4, enabled = true, ip = "geoip:ru", outbound = -1)
    private val rules = listOf(quic, ads, russianSites, russianAddresses)

    private fun tester(settings: RouteSettings = RouteSettings()) = RouteTester(settings, core)
    private fun probe(text: String) = RouteProbe.parse(text)!!

    @Test
    fun readsSitesAddressesPortsAndLinks() {
        assertEquals(RouteProbe("example.com"), RouteProbe.parse(" Example.COM. "))
        assertEquals(RouteProbe("example.com", 443), RouteProbe.parse("example.com:443"))
        assertEquals(RouteProbe("example.com", 443), RouteProbe.parse("https://example.com/path?q=1"))
        assertEquals(RouteProbe("example.com", 8080), RouteProbe.parse("http://example.com:8080"))
        assertEquals(RouteProbe("2001:db8::1", 8443), RouteProbe.parse("[2001:db8::1]:8443"))
        assertTrue(RouteProbe.parse("2001:db8::1")!!.isAddress)
        assertTrue(RouteProbe.parse("192.0.2.1")!!.isAddress)
        assertFalse(RouteProbe.parse("example.com")!!.isAddress)
        // Not a site or an address: the screen looks for an app by that name.
        for (text in listOf("telegram", "", "example.com:0", "example.com:99999", "two words.com", "example.com:http")) {
            assertNull(text, RouteProbe.parse(text))
        }
    }

    @Test
    fun theFirstRuleThatCatchesItDecidesAndEarlierConditionalOnesAreNamed() {
        val answer = tester().test(rules, probe("yandex.ru"))
        assertEquals(RouteDecision.Rule(2), answer.decision)
        assertEquals(RouteVerdict.CatchesIf(listOf(RouteCondition.Ports("443"), RouteCondition.Network("udp"))), answer.verdicts[0])
        // The ads rule is off; the rule after the match is never reached.
        assertNull(answer.verdicts[1])
        assertNull(answer.verdicts[3])
        // A port the QUIC rule doesn't have rules it out.
        assertEquals(RouteVerdict.Passes, tester().test(rules, probe("yandex.ru:80")).verdicts[0])
    }

    @Test
    fun aKnownNetworkSettlesTheRulesForOne() {
        assertEquals(RouteVerdict.Passes, tester().judge(quic, RouteProbe("example.com", 443, network = "tcp")))
        assertEquals(RouteVerdict.Catches, tester().judge(quic, RouteProbe("example.com", 443, network = "udp")))
        assertEquals(RouteDecision.Rule(0), tester().test(rules, RouteProbe("yandex.ru", 443, network = "udp")).decision)
        assertEquals(RouteDecision.Rule(2), tester().test(rules, RouteProbe("yandex.ru", 443, network = "tcp")).decision)
    }

    @Test
    fun addressRulesAreOpenForSitesAndSiteRulesForAddresses() {
        val site = tester().test(listOf(russianAddresses), probe("example.com"))
        assertEquals(RouteVerdict.CatchesIf(listOf(RouteCondition.Address(listOf("geoip:ru")))), site.verdicts[0])
        assertEquals(RouteDecision.Proxy, site.decision)
        assertEquals(RouteDecision.Rule(0), tester().test(listOf(russianAddresses), probe("77.88.8.8")).decision)
        assertEquals(RouteVerdict.CatchesIf(listOf(RouteCondition.Site(listOf("geosite:category-ru")))), tester().judge(russianSites, probe("8.8.8.8")))
        // Both kinds in one rule: a site that matches needs no address.
        val both = RuleEntity(enabled = true, domains = "example.org", ip = "geoip:ru")
        assertEquals(RouteVerdict.Catches, tester().judge(both, probe("www.example.org")))
    }

    @Test
    fun appsAreJudgedByTheirListAndTheirTrafficByTheRest() {
        val telegram = RuleEntity(enabled = true, packages = setOf("org.telegram.messenger"), outbound = -1)
        assertEquals(RouteVerdict.Catches, tester().judge(telegram, RouteProbe(packageName = "org.telegram.messenger")))
        assertEquals(RouteVerdict.Passes, tester().judge(telegram, RouteProbe(packageName = "com.example")))
        // In proxy mode the core can't see apps: the rule is skipped.
        val proxyMode = tester(RouteSettings(vpn = false)).test(listOf(telegram), RouteProbe(packageName = "org.telegram.messenger"))
        assertEquals(RouteDecision.Proxy, proxyMode.decision)
        assertNull(proxyMode.verdicts[0])
        assertEquals(RouteVerdict.CatchesIf(listOf(RouteCondition.Apps(telegram.packages))), tester().judge(telegram, probe("example.com")))
        assertEquals(
            RouteVerdict.CatchesIf(listOf(RouteCondition.Destination(listOf("geosite:category-ru"), emptyList(), 0))),
            tester().judge(russianSites, RouteProbe(packageName = "com.example")),
        )
    }

    @Test
    fun whatThePhoneCannotJudgeIsSaidSo() {
        assertEquals(RouteVerdict.Unknown(RouteUnknown.CustomJson), tester().judge(russianSites.copy(config = "{}"), probe("yandex.ru")))
        val downloaded = RuleEntity(enabled = true, ruleset = "rssite:https://example.org/list.srs")
        assertEquals(RouteVerdict.Unknown(RouteUnknown.DownloadedList), tester().judge(downloaded, probe("example.com")))
        // A downloaded list with a site that matches still catches it.
        assertEquals(RouteVerdict.Catches, tester().judge(downloaded.copy(domains = "example.org"), probe("example.org")))
        assertEquals(RouteVerdict.Unknown(RouteUnknown.ListsNotLoaded), tester(RouteSettings(listsLoaded = false)).judge(russianSites, probe("yandex.ru")))
        val broken = tester().judge(RuleEntity(enabled = true, domains = "geosite:no-such-list"), probe("example.com"))
        assertEquals(RouteVerdict.Unknown(RouteUnknown.Broken("no-such-list: missing list")), broken)
    }

    @Test
    fun settingsActBeforeAndAfterTheRules() {
        assertEquals(RouteDecision.Dns, tester().test(rules, probe("1.1.1.1:53")).decision)
        assertEquals(RouteDecision.GlobalMode, tester(RouteSettings(globalMode = true)).test(rules, probe("yandex.ru")).decision)
        assertEquals(RouteDecision.LocalOutsideVpn, tester(RouteSettings(bypassLan = true)).test(rules, probe("192.168.1.1")).decision)
        // In proxy mode the VPN leaves nothing out; the core sends local addresses direct after the rules.
        assertEquals(RouteDecision.LocalNetwork, tester(RouteSettings(vpn = false, bypassLanInCore = true)).test(rules, probe("192.168.1.1")).decision)
        val perApp = RouteSettings(perAppPackages = setOf("com.example"), bypassApps = true)
        assertEquals(RouteDecision.AppOutsideVpn, tester(perApp).test(rules, RouteProbe(packageName = "com.example")).decision)
        assertEquals(RouteDecision.AppOutsideVpn, tester(perApp.copy(bypassApps = false)).test(rules, RouteProbe(packageName = "org.other")).decision)
        assertEquals(RouteDecision.Proxy, tester().test(rules, probe("example.com")).decision)
    }

    @Test
    fun destinationsAreWrittenAsTheConfigWritesThem() {
        val json = RouteTester.destinationJson(listOf("domain:Example.com", "full:a.example", "geosite:cn"), listOf("geoip:private", "10.0.0.0/8", "geoip:cn"))
        for (part in listOf("\"domain_suffix\":[\"example.com\"]", "\"domain\":[\"a.example\"]", "\"ip_is_private\":true", "\"ip_cidr\":[\"10.0.0.0/8\"]", "\"geosite:cn\"", "\"geoip:cn\"")) {
            assertTrue("$part in $json", part in json.replace(Regex("\\s"), ""))
        }
        assertTrue(RouteTester.portsContain("80, 1000:2000", 1500))
        assertTrue(RouteTester.portsContain(":1024", 22))
        assertTrue(RouteTester.portsContain("8000:", 9000))
        assertFalse(RouteTester.portsContain("443", 80))
    }
}

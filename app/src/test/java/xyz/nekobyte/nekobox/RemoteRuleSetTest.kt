package xyz.nekobyte.nekobox

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.nekobyte.nekobox.SingBoxOptions.RuleSet

class RemoteRuleSetTest {
    @Test
    fun formatUsesParsedPathAndKeepsBinaryFallback() {
        val cases = mapOf(
            "https://rules.example/domains.json" to "source",
            "https://rules.example/domains.json?token=example" to "source",
            "https://rules.example/domains.json#revision" to "source",
            "https://rules.example/domains.JSON?name=rules.srs#fragment" to "source",
            "https://rules.example/domains.JsOn" to "source",
            "https://rules.example/domains%2Ejson" to "source",
            "https://rules.example/%E8%A7%84%E5%88%99.json" to "source",
            "http://rules.example/domains.json" to "source",
            "https://rules.example/domains.srs?name=rules.json" to "binary",
            "https://rules.example/domains.srs#rules.json" to "binary",
            "https://rules.example/domains.SRS" to "binary",
            "https://rules.example/download?format=json#rules.json" to "binary",
            "https://rules.example/directory.json/" to "binary",
            "https://rules.example/" to "binary",
            "https://rules.json" to "binary",
            "not a URL.json" to "binary",
        )
        for ((url, expectedFormat) in cases) {
            val rules = mutableListOf<RuleSet>()
            val tag = generateRemoteRuleSet(url, rules, "6h")
            val rule = rules.single()
            assertEquals(url, expectedFormat, rule.format)
            assertEquals(url, rule.url)
            assertEquals("remote", rule.type)
            assertEquals("6h", rule.update_interval)
            assertEquals("ruleset-${kotlin.math.abs(url.hashCode())}", tag)
            assertEquals(tag, rule.tag)
            assertEquals(expectedFormat, SingBoxOptions.toJsonTree(rule)["format"].asString)
        }
    }

    @Test
    fun domainAndIpPrefixesRemainIndependentOfFileFormat() {
        val url = "https://rules.example/list.JSON?token=example#revision"
        for ((prefix, ip) in listOf("rssite:" to false, "rsip:" to true)) {
            val parsed = processRulesetUrl(prefix + url)
            assertEquals(url to ip, parsed)
            val rules = mutableListOf<RuleSet>()
            generateRemoteRuleSet(parsed.first, rules, "0")
            assertEquals("source", rules.single().format)
            assertEquals("0", rules.single().update_interval)
        }
    }

    @Test
    fun bundledRuleSetsKeepBinaryFormat() {
        val rules = mutableListOf<RuleSet>()
        generateRuleSet(listOf("geosite:cn", "geoip:cn"), rules)
        assertEquals(listOf("binary", "binary"), rules.map { it.format })
        assertEquals(listOf("geosite:cn", "geoip:cn"), rules.map { it.path })
    }
}

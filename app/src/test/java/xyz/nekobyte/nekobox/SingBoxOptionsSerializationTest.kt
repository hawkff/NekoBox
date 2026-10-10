package xyz.nekobyte.nekobox

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.nekobyte.nekobox.SingBoxOptions.MyOptions
import xyz.nekobyte.nekobox.SingBoxOptions.OutboundTLSOptions
import xyz.nekobyte.nekobox.SingBoxOptions.Outbound_HTTPOptions
import xyz.nekobyte.nekobox.SingBoxOptions.RouteOptions

class SingBoxOptionsSerializationTest {
    @Test
    fun nullablePublicFieldsKeepJsonNamesAndOmitUnsetValues() {
        val outbound = Outbound_HTTPOptions()
        assertNull(outbound.javaClass.getField("server_port").get(outbound))
        assertEquals(
            "java.util.List<xyz.nekobyte.nekobox.SingBoxOptions\$SingBoxOption>",
            MyOptions::class.java.getField("outbounds").genericType.typeName,
        )
        assertEquals("{}", SingBoxOptions.toJsonTree(outbound).toString())
        val route = RouteOptions().apply { final_ = "proxy" }
        assertEquals(JsonParser.parseString("""{"final":"proxy"}"""), SingBoxOptions.toJsonTree(route))
        assertFalse(SingBoxOptions.toJsonTree(route).has("_hack_config_map"))
    }

    @Test
    fun nestedOverridesAndLargeIntegersSurviveReflectiveSerialization() {
        val outbound = Outbound_HTTPOptions().apply {
            type = "http"
            tls = OutboundTLSOptions().apply {
                enabled = true
                _hack_custom_config = """{"server_name":"override.example"}"""
            }
            _hack_config_map["server_port"] = 443
        }
        val root = MyOptions().apply {
            outbounds = listOf(outbound)
            _hack_custom_config = """{"marker":9007199254740993}"""
        }
        val json = SingBoxOptions.toJsonTree(root)
        val result = json["outbounds"].asJsonArray.single().asJsonObject
        assertEquals(443, result["server_port"].asInt)
        assertEquals("override.example", result["tls"].asJsonObject["server_name"].asString)
        assertEquals(9007199254740993L, root.asMap()["marker"])
    }

    @Test
    fun domainRulesAreSharedWithDnsAndSurviveAddingIpRules() {
        val entries = listOf("full:EXAMPLE.COM", "domain:Example.org", "regexp:^Test", "keyword:EXAMPLE", "geosite:test", " ")
        val dns = SingBoxOptions.DNSRule_DefaultOptions().apply { makeSingBoxRule(entries) }
        val route = SingBoxOptions.Rule_DefaultOptions().apply { makeSingBoxRule(entries, false) }
        assertEquals(SingBoxOptions.toJsonTree(dns), SingBoxOptions.toJsonTree(route))
        route.makeSingBoxRule(listOf("geoip:private", "geoip:test", "192.0.2.0/24", " "), true)
        assertEquals(listOf("example.com"), route.domain)
        assertEquals(listOf("geosite:test", "geoip:test"), route.rule_set)
        assertEquals(listOf("192.0.2.0/24"), route.ip_cidr)
        assertEquals(true, route.ip_is_private)
        route.makeSingBoxRule(listOf("geosite:test", "geosite:more"), false)
        route.makeSingBoxRule(listOf("geoip:test"), true)
        assertEquals(listOf("geosite:test", "geoip:test", "geosite:more"), route.rule_set)
        val ruleSets = mutableListOf<SingBoxOptions.RuleSet>()
        generateRuleSet(checkNotNull(route.rule_set), ruleSets)
        assertEquals(route.rule_set, ruleSets.map { it.tag })
        assertEquals(listOf("local", "local", "local"), ruleSets.map { it.type })
    }
}

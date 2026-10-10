package xyz.nekobyte.nekobox

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nekobyte.nekobox.SingBoxOptions.RuleSet
import xyz.nekobyte.nekobox.database.DataStore
import kotlin.Exception

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String = (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", "prefer_ipv4")
            }
        }
    }
}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    val ruleSets = mutableListOf<String>()
    val domains = mutableListOf<String>()
    val suffixes = mutableListOf<String>()
    val regexes = mutableListOf<String>()
    val keywords = mutableListOf<String>()
    list.forEach {
        when {
            it.startsWith("geosite:") -> ruleSets.add(it)
            it.startsWith("full:") -> domains.add(it.removePrefix("full:").lowercase())
            it.startsWith("domain:") -> suffixes.add(it.removePrefix("domain:").lowercase())
            it.startsWith("regexp:") -> regexes.add(it.removePrefix("regexp:").lowercase())
            it.startsWith("keyword:") -> keywords.add(it.removePrefix("keyword:").lowercase())
            else -> suffixes.add(it.lowercase())
        }
    }
    rule_set = ruleSets.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    domain = domains.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    domain_suffix = suffixes.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    domain_regex = regexes.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    domain_keyword = keywords.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach {
        if (it.startsWith("geoip:") || it.startsWith("geosite:")) {
            ruleSet.add(
                RuleSet().apply {
                    type = "local"
                    tag = it
                    format = "binary"
                    path = it
                },
            )
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    val existingRuleSets = rule_set.orEmpty()
    if (isIP) {
        val ruleSets = mutableListOf<String>()
        val addresses = mutableListOf<String>()
        list.forEach {
            when {
                it == "geoip:private" -> ip_is_private = true
                it.startsWith("geoip:") -> ruleSets.add(it)
                else -> addresses.add(it)
            }
        }
        rule_set = ruleSets
        ip_cidr = addresses
    } else {
        val parsed = SingBoxOptions.DNSRule_DefaultOptions().apply { makeSingBoxRule(list) }
        rule_set = parsed.rule_set
        domain = parsed.domain
        domain_suffix = parsed.domain_suffix
        domain_regex = parsed.domain_regex
        domain_keyword = parsed.domain_keyword
    }
    ip_cidr = ip_cidr?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    rule_set = (existingRuleSets + rule_set.orEmpty()).filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }
    domain = domain?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    domain_suffix = domain_suffix?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    domain_regex = domain_regex?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    domain_keyword = domain_keyword?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_is_private == true) return false
    if (network?.isNotEmpty() == true) return false
    if (source_port?.isNotEmpty() == true) return false
    if (source_port_range?.isNotEmpty() == true) return false
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (protocol?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}

fun processRulesetUrl(origUrl: String): Pair<String, Boolean> = when {
    origUrl.startsWith("rsip:") -> {
        // IP-type ruleset
        Pair(origUrl.substring(5), true)
    }

    origUrl.startsWith("rssite:") -> {
        // domain-type ruleset
        Pair(origUrl.substring(7), false)
    }

    else -> {
        throw kotlin.Exception(NekoBox.application.getString(R.string.ruleset_prefix_error))
    }
}

fun generateRemoteRuleSet(url: String, ruleSets: MutableList<RuleSet>, updateInterval: String): String {
    val hashCode = kotlin.math.abs(url.hashCode())
    val tag = "ruleset-$hashCode"

    // add to the rule set list
    ruleSets.add(
        RuleSet().apply {
            type = "remote"
            this.tag = tag
            format = if (url.toHttpUrlOrNull()?.pathSegments?.last()?.endsWith(".json", ignoreCase = true) == true) "source" else "binary"
            this.url = url
            update_interval = updateInterval
        },
    )

    return tag
}

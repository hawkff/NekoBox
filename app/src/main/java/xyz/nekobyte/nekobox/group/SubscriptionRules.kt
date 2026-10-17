package xyz.nekobyte.nekobox.group

import org.json.JSONArray
import org.json.JSONObject
import xyz.nekobyte.nekobox.database.ProviderRouting
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.ui.BackupFormatV2

/**
 * Routing rules that a provider puts in the subscription itself: the `routing` of an Xray config
 * and the `rules` of a Clash config. They become a rules-only routing profile, as a Happ routing
 * profile does, which the subscription owns and which stays off until the user switches to it.
 *
 * Both formats use the first rule that matches, so the order is kept. A final rule that sends
 * everything else through the proxy is what this app does anyway and is left out. Rules the app
 * cannot express are reported as notes. A final rule to direct or block is one of them, because
 * it would also catch the routes this app adds after the user's rules.
 */
internal object SubscriptionRules {

    private class Collected {
        val rules = mutableListOf<RuleEntity>()
        val notes = linkedMapOf<String, Int>()

        // Entries that later rules add to the last one. Appending each to the rule itself copies the
        // whole list again, so the time grows with the square of the list, as in large Clash configs.
        private val pending = StringBuilder()

        fun note(reason: String, count: Int = 1) {
            notes[reason] = (notes[reason] ?: 0) + count
        }

        /** Adds [rule], or extends the previous rule when both list only domains or only IPs for the same target. */
        fun add(rule: RuleEntity) {
            val last = rules.lastOrNull()
            val kind = kind(rule)
            if (last != null && kind != null && kind(last) == kind && last.outbound == rule.outbound && last.name == rule.name) {
                pending.append('\n').append(if (kind == "domains") rule.domains else rule.ip)
                return
            }
            closeLast()
            rules += rule
        }

        private fun closeLast() {
            val last = rules.lastOrNull() ?: return
            if (pending.isEmpty()) return
            if (kind(last) == "domains") last.domains += pending else last.ip += pending
            pending.setLength(0)
        }

        private fun kind(rule: RuleEntity) = when {
            listOf(rule.port, rule.sourcePort, rule.network, rule.source, rule.protocol).any { it.isNotEmpty() } -> null
            rule.ip.isEmpty() -> "domains"
            rule.domains.isEmpty() -> "ip"
            else -> null
        }

        fun profile(): RoutingProfiles.Profile {
            closeLast()
            val encoded = JSONArray()
            rules.forEachIndexed { index, rule ->
                rule.userOrder = index + 1L
                rule.enabled = true
                encoded.put(BackupFormatV2.encodeRule(rule))
            }
            return RoutingProfiles.Profile(0L, "", JSONObject().put("rules", encoded), notes = notes.map { (reason, count) -> "$reason ($count)" })
        }
    }

    private val XRAY_FIELDS = setOf(
        "type", "ruleTag", "outboundTag", "domain", "ip", "port", "sourcePort", "network", "source", "sourceIP", "protocol", "domainMatcher",
    )

    /**
     * The rules of an Xray `routing` object. Targets follow the config's own [outbounds]: freedom
     * is direct, blackhole blocks and a proxy is the selected profile. Balancers, other outbounds and
     * conditions on inbounds or users have no counterpart here.
     */
    fun fromXray(routing: JSONObject, outbounds: List<JSONObject?>): RoutingProfiles.Profile {
        val protocols = outbounds.filterNotNull().associate { it.optString("tag") to it.optString("protocol") }
        val collected = Collected()
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val unsupported = rule.keys().asSequence().firstOrNull { it !in XRAY_FIELDS }
            val outbound = when (protocols[rule.optString("outboundTag")]) {
                "freedom" -> -1L
                "blackhole" -> -2L
                in XrayOutbound.PROXY_PROTOCOLS -> 0L
                else -> null
            }
            if (unsupported != null || outbound == null) {
                collected.note(unsupported ?: "outboundTag")
                continue
            }
            val entry = RuleEntity(name = rule.optString("ruleTag").ifEmpty { rule.optString("outboundTag") }, outbound = outbound)
            val translated = translateXrayConditions(rule, entry, collected)
            if (!translated) continue
            if (matchesEverything(entry)) {
                if (outbound != 0L) collected.note("final rule")
                // Xray never reaches the rules after it.
                break
            }
            collected.add(entry)
        }
        return collected.profile()
    }

    /** Copies the conditions of an Xray [rule] to [entry]; false when the rule cannot be expressed without widening it. */
    private fun translateXrayConditions(rule: JSONObject, entry: RuleEntity, collected: Collected): Boolean {
        val domains = rule.strings("domain")
        val ips = rule.strings("ip")
        // Xray needs both to match; a rule here matches either.
        if (domains.isNotEmpty() && ips.isNotEmpty()) {
            collected.note("domain and ip")
            return false
        }
        entry.domains = entries(domains, "domain", collected, ProviderRouting::domainEntry) ?: return false
        entry.ip = entries(ips, "ip", collected, ProviderRouting::ipEntry) ?: return false
        // Source conditions here take addresses and CIDRs only.
        val sources = rule.strings("source") + rule.strings("sourceIP")
        entry.source = entries(sources, "source", collected) { ProviderRouting.ipEntry(it)?.takeUnless { value -> value.startsWith("geoip:") } } ?: return false
        if (rule.has("port")) entry.port = ports(rule.opt("port")) ?: return false.also { collected.note("port") }
        if (rule.has("sourcePort")) entry.sourcePort = ports(rule.opt("sourcePort")) ?: return false.also { collected.note("sourcePort") }
        if (rule.has("network")) entry.network = network(rule.optString("network")) ?: return false.also { collected.note("network") }
        entry.protocol = rule.strings("protocol").joinToString("\n")
        return true
    }

    /**
     * [values] translated and joined, or null when none of a non-empty list translates: dropping
     * the whole condition would widen the rule. Entries that do not translate are noted.
     */
    private fun entries(values: List<String>, field: String, collected: Collected, translate: (String) -> String?): String? {
        val translated = values.mapNotNull { translate(it.trim()) }
        if (translated.size < values.size) collected.note(field, values.size - translated.size)
        if (values.isNotEmpty() && translated.isEmpty()) return null
        return translated.joinToString("\n")
    }

    private val CLASH_LOGIC = setOf("AND", "OR", "NOT", "SUB-RULE")

    // What may follow the target; anything else means the value had a comma of its own, as a regex may.
    private val CLASH_OPTIONS = setOf("no-resolve", "src")

    /**
     * The rules of a Clash config. A target is resolved through the config's proxy [groups] as Clash
     * starts them: a select group uses its first member. Any other proxy or group is the selected
     * profile here.
     */
    fun fromClash(rules: List<*>, groups: List<*>?): RoutingProfiles.Profile {
        val select = groups.orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"]?.toString() == "select" }
            .associate { it["name"].toString() to (it["proxies"] as? List<*>)?.firstOrNull()?.toString() }

        fun outbound(target: String, depth: Int = 0): Long? = when (target) {
            "DIRECT" -> -1L
            "REJECT", "REJECT-DROP", "REJECT-TINYGIF" -> -2L
            "PASS", "" -> null
            else -> select[target]?.takeIf { depth < 8 }?.let { outbound(it, depth + 1) } ?: 0L
        }

        val collected = Collected()
        for (item in rules) {
            val parts = item?.toString()?.split(',')?.map { it.trim() }.orEmpty()
            val type = parts.firstOrNull()?.uppercase().orEmpty()
            if (type.isEmpty()) continue
            if (type == "MATCH" || type == "FINAL") {
                if (outbound(parts.getOrNull(1).orEmpty()) != 0L) collected.note("MATCH")
                break
            }
            val value = parts.getOrNull(1).orEmpty()
            val target = parts.getOrNull(2).orEmpty()
            val outbound = outbound(target)
            val options = parts.drop(3)
            val malformed = type in CLASH_LOGIC || value.isEmpty() || options.any { it !in CLASH_OPTIONS }
            // A target with no counterpart, such as PASS, is reported by its name.
            val reason = if (outbound == null && !malformed) target.ifEmpty { type } else type
            val entry = if (malformed || outbound == null) {
                null
            } else {
                clashCondition(type, value, source = "src" in options)?.apply {
                    name = target
                    this.outbound = outbound
                }
            }
            if (entry == null) {
                collected.note(reason)
                continue
            }
            collected.add(entry)
        }
        return collected.profile()
    }

    private fun clashCondition(type: String, value: String, source: Boolean): RuleEntity? = when {
        // src matches where a connection comes from, which only address ranges can express here.
        source -> if (type == "IP-CIDR" || type == "IP-CIDR6") address(value)?.let { RuleEntity(source = it) } else null

        else -> destinationCondition(type, value)
    }

    private fun address(value: String) = ProviderRouting.ipEntry(value)?.takeUnless { it.startsWith("geoip:") }

    private fun destinationCondition(type: String, value: String): RuleEntity? = when (type) {
        "DOMAIN" -> RuleEntity(domains = "full:$value")

        "DOMAIN-SUFFIX" -> RuleEntity(domains = "domain:$value")

        "DOMAIN-KEYWORD" -> RuleEntity(domains = "keyword:$value")

        "DOMAIN-REGEX" -> RuleEntity(domains = "regexp:$value")

        "GEOSITE" -> RuleEntity(domains = "geosite:" + value.lowercase())

        "IP-CIDR", "IP-CIDR6" -> address(value)?.let { RuleEntity(ip = it) }

        // Clash names private networks LAN.
        "GEOIP" -> RuleEntity(ip = "geoip:" + if (value.equals("LAN", ignoreCase = true)) "private" else value.lowercase())

        "SRC-IP-CIDR" -> address(value)?.let { RuleEntity(source = it) }

        "DST-PORT" -> ports(value)?.let { RuleEntity(port = it) }

        "SRC-PORT" -> ports(value)?.let { RuleEntity(sourcePort = it) }

        "NETWORK" -> network(value)?.takeIf { it.isNotEmpty() }?.let { RuleEntity(network = it) }

        else -> null
    }

    /** "53,443,1000-2000" or "80/443" as this app writes ports, one per line with ranges as a:b; null when invalid. */
    private fun ports(value: Any?): String? {
        val entries = value?.toString()?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (entries.isEmpty()) return null
        return entries.map { entry ->
            val bounds = entry.split('-').map { bound -> bound.trim().toIntOrNull()?.takeIf { it in 0..65535 } ?: return null }
            when (bounds.size) {
                1 -> "${bounds[0]}"
                2 -> "${bounds[0]}:${bounds[1]}"
                else -> return null
            }
        }.joinToString("\n")
    }

    /** One network as this app matches it, empty for both, or null for anything else. */
    private fun network(value: String): String? {
        val networks = value.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return when {
            networks.isEmpty() || !networks.all { it == "tcp" || it == "udp" } -> null
            networks.size == 2 -> ""
            else -> networks.single()
        }
    }

    private fun matchesEverything(rule: RuleEntity) = listOf(rule.domains, rule.ip, rule.port, rule.sourcePort, rule.network, rule.source, rule.protocol).all { it.isEmpty() }
}

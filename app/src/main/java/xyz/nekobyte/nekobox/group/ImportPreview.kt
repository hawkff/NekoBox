package xyz.nekobyte.nekobox.group

import androidx.annotation.StringRes
import org.json.JSONObject
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.fmt.AbstractBean

/**
 * An entry a parser refuses on purpose. The message names only what is unsupported, never a value
 * from the source, so the import warning can show it.
 */
class UnsupportedEntryException(reason: String) : IllegalArgumentException(reason)

/** What a parse saw besides the nodes it returned. The parsers fill it entry by entry. */
class ImportReport {
    /** One item per source entry that describes a node but did not import: its protocol, or null when unknown. */
    val failed = mutableListOf<String?>()

    /** Why entries in [failed] were refused, from each [UnsupportedEntryException]. */
    val reasons = linkedSetOf<String>()

    /** Source entries that are not nodes, such as sing-box direct or selector outbounds. */
    var builtin = 0

    /** Source sections that this app never imports. */
    val dropped = linkedSetOf<ImportPreview.Section>()

    /** The source's own routing rules (Xray `routing`, Clash `rules`) as a nameless rules-only profile. */
    var routing: RoutingProfiles.Profile? = null

    // Each Xray config's routing by its rules, with how many configs use it.
    private val xrayRoutings = linkedMapOf<String, Pair<RoutingProfiles.Profile, Int>>()

    /**
     * Counts one Xray config's routing. Panels give an auto-select config other rules than the
     * server configs, so [routing] is the one most configs use, the first of them on a tie.
     */
    fun addXrayRouting(profile: RoutingProfiles.Profile) {
        // Rule names follow each config's own tags, so rules compare by what they match and where they send it.
        val rules = profile.content.optJSONArray("rules")
        val key = (0 until (rules?.length() ?: 0)).joinToString("\n") { JSONObject(rules!!.getJSONObject(it).toString()).apply { remove("name") }.toString() }
        val (first, count) = xrayRoutings[key] ?: (profile to 0)
        xrayRoutings[key] = first to count + 1
        routing = xrayRoutings.values.maxBy { it.second }.first
    }

    /** The format the parser recognized. */
    var format: ImportFormat? = null
}

enum class ImportFormat { LINKS, CLASH, SING_BOX, XRAY_JSON, WIREGUARD }

/** Profiles parsed from a source, with what the parse could not use. */
class ParsedContent(val proxies: List<AbstractBean>, val report: ImportReport)

/**
 * What an import will and will not take from its source: accepted nodes, entries that are not
 * nodes or did not import, and source sections the app never uses. Counts come from the parser's
 * own walk over the source, so every format and layout is counted the same way.
 */
data class ImportPreview(
    /** Accepted nodes by display type, e.g. "VMess" to 8. */
    val accepted: Map<String, Int>,
    /** Source entries that produced no node, by reason. */
    val skipped: Map<Reason, Int>,
    /** Source sections that this app never imports. */
    val dropped: List<Section>,
    /** Protocol of each entry that did not import, or null when unknown. */
    val failed: List<String?> = emptyList(),
    /** Why entries did not import, where a parser said so. */
    val reasons: List<String> = emptyList(),
) {
    enum class Reason { BUILTIN, UNPARSED }

    enum class Section(@param:StringRes val title: Int) {
        PROXY_GROUPS(R.string.import_section_proxy_groups),
        RULES(R.string.import_section_rules),
        RULE_PROVIDERS(R.string.import_section_rule_providers),
        DNS(R.string.import_section_dns),
        INBOUNDS(R.string.import_section_inbounds),
        FRAGMENT(R.string.import_section_fragment),
        NOISE(R.string.import_section_noise),
    }

    val acceptedCount get() = accepted.values.sum()
    val skippedCount get() = skipped.values.sum()

    /** Source entries that describe nodes but did not import. */
    val unparsedCount get() = skipped[Reason.UNPARSED] ?: 0

    /** True when applying only the nodes cannot reproduce what the source configures. */
    val behaviorDiffers get() = dropped.isNotEmpty()

    companion object {
        fun of(parsed: ParsedContent) = ImportPreview(
            parsed.proxies.groupingBy { ProxyEntity().apply { putBean(it) }.displayType() }.eachCount(),
            mapOf(Reason.BUILTIN to parsed.report.builtin, Reason.UNPARSED to parsed.report.failed.size).filterValues { it > 0 },
            parsed.report.dropped.toList(),
            parsed.report.failed.toList(),
            parsed.report.reasons.toList(),
        )
    }
}

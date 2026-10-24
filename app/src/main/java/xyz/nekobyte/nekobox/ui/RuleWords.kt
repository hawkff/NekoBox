package xyz.nekobyte.nekobox.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProfileManager
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.fmt.RouteCondition
import xyz.nekobyte.nekobox.fmt.RouteUnknown
import xyz.nekobyte.nekobox.ktx.getColorAttr
import xyz.nekobyte.nekobox.utils.PackageCache
import xyz.nekobyte.nekobox.utils.listByLineOrComma
import java.util.Locale

/**
 * Rules in plain words, for the route list, the tester and screen readers: "Sites in China",
 * "UDP · port 443". Call off the main thread once [PackageCache] may be needed for app names.
 */
object RuleWords {
    const val SEPARATOR = " · "

    fun title(context: Context, rule: RuleEntity) = rule.name.ifBlank { summary(context, rule) }

    /** What [rule] catches: its sites and addresses, then the conditions on that traffic. */
    fun summary(context: Context, rule: RuleEntity): String {
        val parts = listOfNotNull(
            destination(context, rule.domains.listByLineOrComma(), rule.ip.listByLineOrComma(), rule.ruleset.listByLineOrComma().size),
            rule.network.takeIf { it.isNotBlank() }?.uppercase(),
            rule.port.takeIf { it.isNotBlank() }?.let { ports(context, it) },
            rule.protocol.takeIf { it.isNotBlank() }?.let { protocols(context, it.listByLineOrComma()) },
            rule.packages.takeIf { it.isNotEmpty() }?.let { apps(context, it) },
            rule.source.takeIf { it.isNotBlank() }?.let { context.getString(R.string.route_words_from, it.listByLineOrComma().joinToString(", ")) },
            rule.sourcePort.takeIf { it.isNotBlank() }?.let { context.getString(R.string.route_words_from_port, it.listByLineOrComma().joinToString(", ")) },
            context.getString(R.string.route_words_custom_json).takeIf { rule.config.isNotBlank() },
        )
        return parts.joinToString(SEPARATOR).ifEmpty { context.getString(R.string.route_words_nothing) }
    }

    /** Sites, addresses and downloaded lists, the first two by name. */
    fun destination(context: Context, sites: List<String>, addresses: List<String>, downloaded: Int): String? {
        val words = sites.map { site(context, it) } + addresses.map { address(context, it) } +
            listOfNotNull(context.resources.getQuantityString(R.plurals.route_words_downloaded, downloaded, downloaded).takeIf { downloaded > 0 })
        return when {
            words.isEmpty() -> null
            words.size <= 2 -> words.joinToString(", ")
            else -> context.getString(R.string.route_words_and_more, words.take(2).joinToString(", "), words.size - 2)
        }
    }

    fun site(context: Context, entry: String): String = when {
        entry.startsWith("geosite:") -> siteList(context, entry.removePrefix("geosite:"))
        entry.startsWith("full:") -> context.getString(R.string.route_words_exact, entry.removePrefix("full:"))
        entry.startsWith("domain:") -> entry.removePrefix("domain:")
        entry.startsWith("keyword:") -> context.getString(R.string.route_words_keyword, entry.removePrefix("keyword:"))
        entry.startsWith("regexp:") -> context.getString(R.string.route_words_pattern)
        else -> entry
    }

    fun address(context: Context, entry: String): String = when {
        entry == "geoip:private" -> context.getString(R.string.route_words_local_network)

        entry.startsWith("geoip:") -> {
            val code = entry.removePrefix("geoip:")
            country(context, code)?.let { context.getString(R.string.route_words_addresses_in, it) } ?: context.getString(R.string.route_words_list, code)
        }

        else -> entry
    }

    // The bundled lists name countries by code, some as category-<code>.
    private fun siteList(context: Context, code: String): String {
        val name = code.lowercase()
        country(context, name.removePrefix("category-").removePrefix("geolocation-"))?.let { return context.getString(R.string.route_words_sites_in, it) }
        return when {
            name == "category-ads-all" || name == "category-ads" -> context.getString(R.string.route_words_ads)
            name.all { it.isLetterOrDigit() } -> context.getString(R.string.route_words_brand_sites, name.replaceFirstChar { it.titlecase() })
            else -> context.getString(R.string.route_words_list, code)
        }
    }

    private fun country(context: Context, code: String): String? {
        if (code.length != 2 || code.uppercase() !in Locale.getISOCountries()) return null
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        return Locale("", code.uppercase()).getDisplayCountry(locale)
    }

    fun ports(context: Context, ports: String): String {
        val list = ports.listByLineOrComma().map { it.replace(':', '–') }
        return context.resources.getQuantityString(R.plurals.route_words_ports, list.size, list.joinToString(", "))
    }

    private fun protocols(context: Context, protocols: List<String>) = context.getString(R.string.route_words_protocols, protocols.joinToString(", ") { it.uppercase() })

    fun apps(context: Context, packages: Set<String>): String {
        PackageCache.awaitLoadSync()
        return packages.singleOrNull()?.let(PackageCache::loadLabel) ?: context.resources.getQuantityString(R.plurals.route_words_apps, packages.size, packages.size)
    }

    /** Where traffic goes: Proxy, Bypass, Block or the profile's name. */
    fun outbound(context: Context, outbound: Long): String = when (outbound) {
        0L -> context.getString(R.string.route_proxy)
        -1L -> context.getString(R.string.route_bypass)
        -2L -> context.getString(R.string.route_block)
        else -> ProfileManager.getProfile(outbound)?.displayName() ?: context.getString(R.string.route_profile_missing)
    }

    fun outboundColor(context: Context, outbound: Long): Int = when (outbound) {
        0L -> context.getColorAttr(R.attr.routeProxyColor)
        -1L -> context.getColorAttr(R.attr.routeDirectColor)
        -2L -> context.getColorAttr(R.attr.routeBlockColor)
        else -> ContextCompat.getColor(context, R.color.color_route_config)
    }

    /** A condition the tester left open, in the words of the rule list. */
    fun condition(context: Context, condition: RouteCondition): String = when (condition) {
        is RouteCondition.Ports -> ports(context, condition.ports)
        is RouteCondition.Network -> condition.network.uppercase()
        is RouteCondition.Protocols -> protocols(context, condition.protocols)
        is RouteCondition.Apps -> apps(context, condition.packages)
        is RouteCondition.Source -> context.getString(R.string.route_words_from, condition.source.listByLineOrComma().joinToString(", "))
        is RouteCondition.SourcePorts -> context.getString(R.string.route_words_from_port, condition.ports.listByLineOrComma().joinToString(", "))
        is RouteCondition.Address -> destination(context, emptyList(), condition.addresses, 0).orEmpty()
        is RouteCondition.Site -> destination(context, condition.sites, emptyList(), 0).orEmpty()
        is RouteCondition.Destination -> destination(context, condition.sites, condition.addresses, condition.downloaded).orEmpty()
    }

    fun conditions(context: Context, conditions: List<RouteCondition>) = conditions.joinToString(SEPARATOR) { condition(context, it) }

    fun unknown(context: Context, unknown: RouteUnknown): String = when (unknown) {
        RouteUnknown.CustomJson -> context.getString(R.string.route_unknown_custom_json)
        RouteUnknown.DownloadedList -> context.getString(R.string.route_unknown_downloaded)
        RouteUnknown.ListsNotLoaded -> context.getString(R.string.route_unknown_not_loaded)
        is RouteUnknown.Broken -> unknown.message
    }
}

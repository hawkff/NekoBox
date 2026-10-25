package xyz.nekobyte.nekobox.ui

import android.content.Context
import libcore.Libcore
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.RuleEntity
import xyz.nekobyte.nekobox.fmt.RouteProbe
import xyz.nekobyte.nekobox.fmt.RouteSettings
import xyz.nekobyte.nekobox.fmt.RouteTester
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.utils.PackageCache
import xyz.nekobyte.nekobox.utils.listByLineOrComma
import java.io.File
import java.util.Locale

/** The route tester on this phone, for the rule list and the rule editor. Call off the main thread. */
object RouteChecks {
    fun tester() = RouteTester(settings()) { json, host -> Libcore.matchRouteDestination(json, host) }

    fun settings() = RouteSettings(
        globalMode = DataStore.globalMode,
        vpn = DataStore.serviceMode == Key.MODE_VPN,
        bypassLan = DataStore.bypassLan,
        bypassLanInCore = DataStore.bypassLanInCore,
        perAppPackages = DataStore.individual.split('\n').filter { it.isNotBlank() }.toSet().takeIf { DataStore.proxyApps },
        bypassApps = DataStore.bypass,
        listsLoaded = listOf("geosite.db", "geoip.db").all { File(app.externalAssets, it).exists() },
    )

    /**
     * What [text] asks about: a site or an address, or else an app by the name the launcher shows; with the
     * app's name. An installed app's package name, which reads like a site, means the app.
     */
    fun probe(text: String): Pair<RouteProbe, String?>? {
        PackageCache.awaitLoadSync()
        val typed = text.trim()
        if (typed in PackageCache.installedPackages) return RouteProbe(packageName = typed) to PackageCache.loadLabel(typed)
        RouteProbe.parse(text)?.let { return it to null }
        val name = typed.lowercase()
        val apps = PackageCache.installedPackages.keys.map { it to PackageCache.loadLabel(it) }
        val (packageName, label) = apps.firstOrNull { (packageName, label) -> label.lowercase() == name || packageName == name }
            ?: apps.filter { (_, label) -> label.lowercase().startsWith(name) }.minByOrNull { (_, label) -> label.length }
            ?: return null
        return RouteProbe(packageName = packageName) to label
    }

    /**
     * What would keep the core from starting with [rule], in words; null when nothing would. The rule
     * editor's Save and the app API both ask. [match] is the core's matcher, which also checks patterns,
     * addresses and site lists; the bundled lists are checked once the service has unpacked them.
     */
    fun problem(
        context: Context,
        rule: RuleEntity,
        listsLoaded: Boolean = settings().listsLoaded,
        match: (json: String, host: String) -> Boolean = Libcore::matchRouteDestination,
    ): String? {
        for (entry in rule.port.listByLineOrComma() + rule.sourcePort.listByLineOrComma()) {
            if (!isPorts(entry)) return context.getString(R.string.route_editor_bad_port, entry)
        }
        val addresses = rule.ip.listByLineOrComma()
        addresses.firstOrNull { it.startsWith("geoip:") && it != "geoip:private" && it.removePrefix("geoip:").uppercase() !in Locale.getISOCountries() }?.let {
            return context.getString(R.string.route_editor_no_list, it)
        }
        rule.ruleset.listByLineOrComma().firstOrNull { !isDownloadedList(it) }?.let { return context.getString(R.string.route_editor_bad_list, it) }
        // The bundled lists are unpacked when the service first starts; until then the core checks the rest.
        val sites = rule.domains.listByLineOrComma().filter { listsLoaded || !it.startsWith("geosite:") }
        // Each site list on its own, so that a misspelled one is named in plain words, not in the core's.
        for (entry in sites.filter { it.startsWith("geosite:") }) {
            try {
                match(RouteTester.destinationJson(listOf(entry), emptyList()), "example.invalid")
            } catch (_: Exception) {
                return context.getString(R.string.route_editor_no_site_list, entry)
            }
        }
        val others = sites.filterNot { it.startsWith("geosite:") }
        val checked = addresses.filter { listsLoaded || !it.startsWith("geoip:") || it == "geoip:private" }
        if (others.isEmpty() && checked.isEmpty()) return null
        return try {
            match(RouteTester.destinationJson(others, checked), "example.invalid")
            null
        } catch (e: Exception) {
            e.readableMessage
        }
    }

    /** Whether [entry] is a downloaded list as ConfigBuilder reads one: rssite: or rsip: and a web link. */
    fun isDownloadedList(entry: String): Boolean {
        val link = entry.removePrefix("rssite:").removePrefix("rsip:")
        return link != entry && (link.startsWith("https://") || link.startsWith("http://"))
    }

    /** Whether [entry] is a port or a range of ports as the core reads them: 443, 1000:2000, :1024 or 8000:. */
    fun isPorts(entry: String): Boolean {
        fun port(text: String) = text.toIntOrNull()?.takeIf { it in 1..65535 }
        if (':' !in entry) return port(entry) != null
        val (low, high) = entry.split(':', limit = 2)
        if (low.isEmpty() && high.isEmpty()) return false
        val from = if (low.isEmpty()) 1 else port(low) ?: return false
        val to = if (high.isEmpty()) 65535 else port(high) ?: return false
        return from <= to
    }
}

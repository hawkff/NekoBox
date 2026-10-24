package xyz.nekobyte.nekobox.ui

import libcore.Libcore
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.RouteProbe
import xyz.nekobyte.nekobox.fmt.RouteSettings
import xyz.nekobyte.nekobox.fmt.RouteTester
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.utils.PackageCache
import java.io.File

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
}

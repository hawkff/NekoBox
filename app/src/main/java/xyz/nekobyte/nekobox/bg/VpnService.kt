package xyz.nekobyte.nekobox.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import xyz.nekobyte.nekobox.*
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.fmt.LOCALHOST
import xyz.nekobyte.nekobox.fmt.hysteria.HysteriaBean
import xyz.nekobyte.nekobox.ktx.*
import xyz.nekobyte.nekobox.ui.VpnRequestActivity
import xyz.nekobyte.nekobox.utils.Subnet
import android.net.VpnService as BaseVpnService

class VpnService :
    BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = NekoBox.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nekobox:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        runServiceTeardown(after = { super.killProcesses() }) {
            if (data.stopGate.holdTun) {
                // Kill switch: the interface stays up with nobody reading it, so traffic drops until
                // the next core replaces it (startVpn) or the user stops. A failure ahead of the
                // core opening the tun has to block too, hence the bare interface. Without any
                // interface there is nothing that blocks, and the service must not claim otherwise.
                if (conn == null) {
                    conn = runCatching { tunBuilder(needBypassRootUid = false).establish() }
                        .onFailure { Logs.w("kill switch could not establish a bare tun", it) }
                        .getOrNull()
                }
                if (conn == null) data.stopGate.holdTun = false
            } else {
                conn?.close()
                conn = null
            }
        }
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "NekoBoxVpnService"
    override fun createNotification(profileName: String) = ServiceNotification(this, profileName, "service-vpn")

    // With the kill switch on, nothing leaves the device while the core starts: a bare interface
    // holds traffic until startVpn replaces it. The core's own sockets are protected and still reach
    // the server. A start that fails keeps it as the kill switch hold; a stop closes it.
    override fun blockWhileConnecting() {
        if (!DataStore.killSwitch || conn != null) return
        conn = runCatching { tunBuilder(needBypassRootUid = false).establish() }
            .onFailure { Logs.w("kill switch could not establish a bare tun", it) }
            .getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            // Only NekoBox.startService marks a manual start. Any other start, by network
            // automation, at boot, this service's own restart or Android starting its always-on
            // VPN, never asks for VPN permission from the background.
            val unattended = intent?.getBooleanExtra(Action.EXTRA_AUTOMATED, true) != false
            when {
                // A running service ignores a further start.
                unattended && data.state != BaseService.State.Stopped -> return super<BaseService.Interface>.onStartCommand(intent, flags, startId)

                unattended && takesOverAnotherVpn(intent) -> refuseUnattendedStart(NetworkAutomation.Blocked.OTHER_VPN)

                prepare(this) == null -> return super<BaseService.Interface>.onStartCommand(intent, flags, startId)

                unattended -> refuseUnattendedStart(NetworkAutomation.Blocked.VPN_PERMISSION)

                else -> {
                    startActivity(
                        Intent(
                            this,
                            VpnRequestActivity::class.java,
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    // The consent screen starts the service again once the user agrees.
                    answerForegroundDemand()
                }
            }
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    /**
     * Whether a start that is not manual would take over another VPN, which prepare() does.
     * - Android starting this app as its always-on VPN decided that itself.
     * - A restart of a running session, such as a reload, that kept its tunnel up has nothing to
     *   take over. One that closed it checks only the VPN carrying this app's own traffic, so the VPN
     *   of another user or a work profile never ends the session. Before Android 11 that is unknown,
     *   and a VPN of the same user that leaves this app out does not show at all; such a restart can
     *   still take over.
     * - Any other start, by a rule or at boot, holds back for every VPN Android reports.
     */
    internal fun takesOverAnotherVpn(intent: Intent?): Boolean = when {
        intent?.action == SERVICE_INTERFACE -> false
        data.restarting -> !data.stopGate.holdTun && NetworkAutomation.otherVpnCarriesThisApp()
        else -> NetworkAutomation.otherVpnActive()
    }

    private fun refuseUnattendedStart(reason: NetworkAutomation.Blocked) {
        if (DataStore.networkAutomation) NetworkAutomation.blocked = reason
        answerForegroundDemand()
    }

    /**
     * startForegroundService demands a startForeground call even from a service that stops right
     * away; without one Android ends the process. Without the VPN permission Android 14 and later
     * refuse the systemExempted type, yet the attempt answers that demand, so its failure is only
     * logged. The notice goes again at once, as the app or the tile may keep the service bound.
     */
    internal fun answerForegroundDemand() {
        val notification = NotificationCompat.Builder(this, "service-vpn")
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(getText(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ServiceNotification.notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(ServiceNotification.notificationId, notification)
            }
        } catch (e: RuntimeException) {
            Logs.w("VPN service stopped before reaching the foreground", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    inner class NullConnectionException :
        NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)
        val needBypassRootUid = data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }
        val previous = conn
        conn = tunBuilder(needBypassRootUid).establish() ?: throw NullConnectionException()
        // Android replaces the previous interface atomically, so an interface the kill switch held
        // across the restart hands over without a gap; only the stale descriptor is left to close.
        previous?.close()
        return conn!!.fd
    }

    private fun tunBuilder(needBypassRootUid: Boolean): Builder {
        val builder = Builder().setConfigureIntent(NekoBox.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Keep this app inside the VPN in either app-routing mode.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            if (DataStore.allowAccess) {
                // When LAN access is enabled the mixed inbound requires authentication
                // (see DataStore.mixedInboundNeedsAuth). Android's system HTTP proxy
                // cannot supply credentials, so registering it here would just fail.
                // Skip it instead of weakening inbound auth and exposing an open LAN proxy.
                Logs.w(
                    "Append HTTP proxy was skipped because LAN access requires proxy " +
                        "authentication. Disable Allow LAN access to use system HTTP proxy.",
                )
            } else {
                val proxyInfo = runCatching {
                    val exclusionList = parseHttpProxyBypass(DataStore.httpProxyBypass)
                    if (exclusionList.isNotEmpty()) {
                        ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort, exclusionList)
                    } else {
                        ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort)
                    }
                }.getOrElse {
                    // A malformed exclusion entry must never block service start.
                    Logs.w("Invalid HTTP proxy bypass list, ignoring it", it)
                    ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort)
                }
                builder.setHttpProxy(proxyInfo)
            }
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        return builder
    }

    // Build a validated exclusion list for the system HTTP proxy. Entries are
    // Android ProxyInfo host/suffix patterns (NOT CIDRs), one per line (commas
    // also accepted). Each entry is validated individually so a single bad
    // pattern is dropped instead of invalidating the whole list or blocking
    // service start.
    private fun parseHttpProxyBypass(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isValidProxyBypassEntry(it) }
            .distinct()
    }

    // Mirror the host/suffix patterns Android's ProxyInfo accepts: a bare "*",
    // or dot-separated labels where each label is alphanumeric (hyphens allowed
    // internally) and a single "*" wildcard label is allowed only as the first
    // or last label (e.g. "192.168.*", "*.example.com"). Rejects empty labels
    // (e.g. ".."), leading/trailing dots and stray characters.
    private fun isValidProxyBypassEntry(entry: String): Boolean {
        if (entry == "*") return true
        val labels = entry.split('.')
        val label = Regex("^[A-Za-z0-9]+(-+[A-Za-z0-9]+)*$")
        labels.forEachIndexed { index, l ->
            if (l == "*") {
                if (index != 0 && index != labels.lastIndex) return false
            } else if (!label.matches(l)) {
                return false
            }
        }
        return true
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        NekoBox.underlyingNetwork?.let { network ->
            builder?.setUnderlyingNetworks(arrayOf(network))
                ?: setUnderlyingNetworks(arrayOf(network))
        }
    }

    // Android revoked the VPN: the user disconnected it in the system settings, or another VPN app
    // took over. Network automation takes that as a manual stop. Called on a binder thread.
    override fun onRevoke() {
        runOnMainDispatcher {
            NetworkAutomation.onUserStop(this@VpnService)
            stopRunner()
        }
    }

    override fun onDestroy() {
        data.locationSpoofing?.stop()
        DataStore.vpnService = null
        conn?.close()
        conn = null
        super.onDestroy()
        data.binder.close()
    }
}

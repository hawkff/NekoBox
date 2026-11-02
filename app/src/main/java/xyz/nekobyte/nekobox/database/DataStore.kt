package xyz.nekobyte.nekobox.database

import android.os.Binder
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceDataStore
import org.json.JSONObject
import xyz.nekobyte.nekobox.CONNECTION_TEST_URL
import xyz.nekobyte.nekobox.GroupLayout
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.IPv6Mode
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.TrafficSniffing
import xyz.nekobyte.nekobox.TunImplementation
import xyz.nekobyte.nekobox.bg.BaseService
import xyz.nekobyte.nekobox.bg.VpnService
import xyz.nekobyte.nekobox.database.preference.InMemoryKeyValuePairDao
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.database.preference.PublicDatabase
import xyz.nekobyte.nekobox.database.preference.RoomPreferenceDataStore
import xyz.nekobyte.nekobox.ktx.boolean
import xyz.nekobyte.nekobox.ktx.int
import xyz.nekobyte.nekobox.ktx.long
import xyz.nekobyte.nekobox.ktx.parsePort
import xyz.nekobyte.nekobox.ktx.string
import xyz.nekobyte.nekobox.ktx.stringToInt

// Default exclusion patterns for the system HTTP proxy (appendHttpProxy).
// These are Android ProxyInfo host/suffix patterns (NOT CIDRs): private and
// loopback ranges so LAN hosts connect directly while proxied traffic flows.
val DEFAULT_HTTP_PROXY_BYPASS = listOf(
    "localhost",
    "127.*",
    "10.*",
    "172.16.*", "172.17.*", "172.18.*", "172.19.*",
    "172.20.*", "172.21.*", "172.22.*", "172.23.*",
    "172.24.*", "172.25.*", "172.26.*", "172.27.*",
    "172.28.*", "172.29.*", "172.30.*", "172.31.*",
    "192.168.*",
    "169.254.*",
).joinToString("\n")

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    @Volatile
    var mixedInboundAuthed: Boolean = false

    val configurationStore = RoomPreferenceDataStore(
        PublicDatabase.kvPairDao,
        cached = true,
        database = PublicDatabase.database,
    )
    val profileCacheStore = RoomPreferenceDataStore(InMemoryKeyValuePairDao())

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // Read by native socket callbacks as well as the service lifecycle.
    @Volatile
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    val pluginSignerApprovals: Set<String>
        get() = configurationStore.getStringSet(Key.PLUGIN_SIGNER_APPROVALS).orEmpty()

    suspend fun approvePluginSigner(identity: String) = configurationStore.addToStringSetDurable(Key.PLUGIN_SIGNER_APPROVALS, setOf(identity))

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = ProfileDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = ProfileDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = ProfileDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = ProfileDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = ProfileDatabase.groupDao.allGroups()
        groups.find { it.type == GroupType.BASIC }?.let { return it.id }
        return ProfileDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
    }

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION) { "1.2" }
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)
    var confirmProfileDelete by configurationStore.boolean(Key.CONFIRM_PROFILE_DELETE) { true }
    var askForBackgroundUse by configurationStore.boolean(Key.ASK_FOR_BACKGROUND_USE) { true }

    // One Nebula network joined alongside the selected profile; its config holds the PKI inline.
    var nebulaEnabled by configurationStore.boolean(Key.NEBULA_ENABLED)
    var nebulaConfig by configurationStore.string(Key.NEBULA_CONFIG)
    var groupLayoutMode by configurationStore.stringToInt(Key.GROUP_LAYOUT_MODE) { GroupLayout.COMPACT }

    // Profiles whose names differ only by a number fold into sections on Home.
    var profileSections by configurationStore.boolean(Key.PROFILE_SECTIONS) { true }

    // Folded sections, as "groupId<tab>section key" lines.
    var foldedSections: Set<String>
        get() = configurationStore.getString(Key.FOLDED_SECTIONS).orEmpty().split('\n').filterTo(HashSet()) { it.isNotEmpty() }
        set(value) = configurationStore.putString(Key.FOLDED_SECTIONS, value.joinToString("\n"))

    private val foldLock = Any()

    fun updateFoldedSections(change: (Set<String>) -> Set<String>) = synchronized(foldLock) { foldedSections = change(foldedSections) }

    // Profiles pinned to the Favorites tab, in the order they were pinned.
    var favoriteProfiles: List<Long>
        get() = configurationStore.getString(Key.FAVORITE_PROFILES).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        set(value) = configurationStore.putString(Key.FAVORITE_PROFILES, value.distinct().joinToString(","))

    private val favoriteLock = Any()

    /** The pins as a set, for rows that ask on every bind: parsed again only when the stored text changes. */
    val favoriteSet: Set<Long>
        get() {
            val raw = configurationStore.getString(Key.FAVORITE_PROFILES).orEmpty()
            parsedFavorites.let { (from, pins) -> if (from == raw) return pins }
            return raw.split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }.also { parsedFavorites = raw to it }
        }

    // A few dozen IDs at most, so a read compares the text by content.
    @Volatile
    private var parsedFavorites: Pair<String, Set<Long>> = "" to emptySet()

    /** Picks a list's Recent keeps. */
    const val RECENTS_PER_LIST = 3

    /** Whether lists keep and show what was picked from them lately. */
    var recentsOn by configurationStore.boolean(Key.RECENTS) { true }

    /** What was picked from each list lately, newest first, as "groupId:id,id,id" lines. */
    val recentPicks: Map<Long, List<Long>>
        get() = configurationStore.getString(Key.RECENT_PICKS).orEmpty().lineSequence().mapNotNull { line ->
            line.substringBefore(':').toLongOrNull()?.let { group -> group to line.substringAfter(':').split(',').mapNotNull(String::toLongOrNull) }
        }.toMap()

    private val recentLock = Any()

    /** Puts [profileId] first among the picks from [groupId], while Recent is on. */
    fun rememberPick(groupId: Long, profileId: Long) = synchronized(recentLock) {
        if (!recentsOn || profileId <= 0L) return@synchronized
        val picks = recentPicks.toMutableMap()
        picks[groupId] = (listOf(profileId) + picks[groupId].orEmpty().minus(profileId)).take(RECENTS_PER_LIST)
        configurationStore.putString(Key.RECENT_PICKS, picks.entries.joinToString("\n") { (group, ids) -> "$group:${ids.joinToString(",")}" })
    }

    /** Turns Recent on or off; off forgets every pick at once. */
    fun turnRecents(on: Boolean) = synchronized(recentLock) {
        recentsOn = on
        if (!on) forgetPicks()
    }

    /** Forgets what was picked from every list. */
    fun forgetPicks() = synchronized(recentLock) { configurationStore.putString(Key.RECENT_PICKS, "") }

    /**
     * What server names last resolved to, through the core's resolver for each connection: the row
     * of a server given by name shows the country of that address. Tests and connections fill it,
     * and each entry keeps when it was learned.
     */
    val serverAddresses: Map<String, String>
        get() {
            val raw = configurationStore.getString(Key.SERVER_ADDRESSES).orEmpty()
            parsedServerAddresses.let { (from, addresses) ->
                if (from === raw) return addresses
                // A refreshed store hands out a new copy of the same text: compare it once, then keep that copy.
                if (from == raw) {
                    parsedServerAddresses = raw to addresses
                    return addresses
                }
            }
            val addresses = runCatching {
                JSONObject(raw).let { json -> json.keys().asSequence().mapNotNull { name -> json.optJSONObject(name)?.optString("address")?.ifEmpty { null }?.let { name to it } }.toMap() }
            }.getOrDefault(emptyMap())
            parsedServerAddresses = raw to addresses
            return addresses
        }

    // Rows read the map on every bind; it is parsed again only when the stored text changes. The text runs to tens of
    // kilobytes, so a read compares it by identity, and by content only once per new copy.
    @Volatile
    @VisibleForTesting
    internal var parsedServerAddresses: Pair<String, Map<String, String>> = "" to emptyMap()

    private val addressLock = Any()

    /** Keeps what [found] names resolved to and when, the newest last, at most 1000 names. */
    fun rememberServerAddresses(found: Map<String, String>, learned: Long = System.currentTimeMillis()) = synchronized(addressLock) {
        val stored = runCatching { JSONObject(configurationStore.getString(Key.SERVER_ADDRESSES).orEmpty()) }.getOrDefault(JSONObject())
        val addresses = LinkedHashMap<String, JSONObject>()
        for (name in stored.keys()) stored.optJSONObject(name)?.let { addresses[name] = it }
        for ((name, address) in found) {
            addresses.remove(name)
            addresses[name] = JSONObject().put("address", address).put("learned", learned)
        }
        while (addresses.size > 1000) addresses.remove(addresses.keys.first())
        configurationStore.putString(Key.SERVER_ADDRESSES, JSONObject(addresses as Map<*, *>).toString())
    }

    /** The list open on the Proxies screen. */
    var selectedProxyList by configurationStore.long(Key.PROXY_LIST)

    /** The address each checked proxy leaves from, by profile: what sites saw at the last check. */
    val proxyExits: Map<Long, String>
        get() {
            val raw = configurationStore.getString(Key.PROXY_EXITS).orEmpty()
            parsedProxyExits.let { (from, exits) ->
                if (from === raw) return exits
                if (from == raw) {
                    parsedProxyExits = raw to exits
                    return exits
                }
            }
            val exits = runCatching {
                JSONObject(raw).let { json -> json.keys().asSequence().mapNotNull { id -> id.toLongOrNull()?.let { it to json.optString(id) }?.takeIf { it.second.isNotEmpty() } }.toMap() }
            }.getOrDefault(emptyMap())
            parsedProxyExits = raw to exits
            return exits
        }

    @Volatile
    @VisibleForTesting
    internal var parsedProxyExits: Pair<String, Map<Long, String>> = "" to emptyMap()

    /** Keeps where [found] proxies left from, the newest last, at most 1000. */
    fun rememberProxyExits(found: Map<Long, String>) = synchronized(addressLock) {
        val stored = runCatching { JSONObject(configurationStore.getString(Key.PROXY_EXITS).orEmpty()) }.getOrDefault(JSONObject())
        val exits = LinkedHashMap<String, String>()
        for (id in stored.keys()) stored.optString(id).takeIf { it.isNotEmpty() }?.let { exits[id] = it }
        for ((id, address) in found) {
            exits.remove(id.toString())
            exits[id.toString()] = address
        }
        while (exits.size > 1000) exits.remove(exits.keys.first())
        configurationStore.putString(Key.PROXY_EXITS, JSONObject(exits as Map<*, *>).toString())
    }

    /** Forgets where [ids] left from, or every proxy's exit when null: a failed check, an edit, a restore. */
    fun forgetProxyExits(ids: Collection<Long>? = null) = synchronized(addressLock) {
        if (ids == null) {
            configurationStore.putString(Key.PROXY_EXITS, "")
            return@synchronized
        }
        val stored = runCatching { JSONObject(configurationStore.getString(Key.PROXY_EXITS).orEmpty()) }.getOrNull() ?: return@synchronized
        if (ids.none { stored.has(it.toString()) }) return@synchronized
        for (id in ids) stored.remove(id.toString())
        configurationStore.putString(Key.PROXY_EXITS, stored.toString())
    }

    /** Changes the pins in one step; pinning, unpinning and pruning run in separate jobs. */
    fun updateFavoriteProfiles(change: (List<Long>) -> List<Long>) = synchronized(favoriteLock) { favoriteProfiles = change(favoriteProfiles) }

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)

    //

    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var liquidGlass by configurationStore.boolean(Key.LIQUID_GLASS)

    // -1 = not set (no dark-only-theme override active). Otherwise holds the
    // night-mode value to restore when leaving a dark-only theme (Dracula or
    // Dark High Contrast). Key name kept for backward compatibility.
    var nightThemeBeforeDracula by configurationStore.int(Key.NIGHT_THEME_BEFORE_DRACULA) { -1 }
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "" }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { TrafficSniffing.ROUTING }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 1500 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)

    // Default must match settings_notification.xml (1000ms). Without a non-zero code
    // default, a fresh install that never opens Settings reads 0, which disables the
    // TrafficLooper stats loop entirely (TrafficLooper.loop() returns on delayMs == 0),
    // so the bottom bar's up/down speeds stay blank. "0" remains a valid user choice (Off).
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://dns.quad9.net/dns-query" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "https://1.1.1.1/dns-query" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { false }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER)

    // Off until the user answers the prompt on first launch.
    var helpToImprove by configurationStore.boolean(Key.HELP_TO_IMPROVE) { false }
    val helpToImproveAnswered get() = configurationStore.getBoolean(Key.HELP_TO_IMPROVE) != null
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK) { true }
    var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)

    var rulesGeositeUrl by configurationStore.string(Key.RULES_GEOSITE_URL) {
        "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
    }
    var rulesGeoipUrl by configurationStore.string(Key.RULES_GEOIP_URL) {
        "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
    }
    var rulesUpdateInterval by configurationStore.string(Key.RULES_UPDATE_INTERVAL) {
        "0"
    } // defaults to 0, no automatic update

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }
    val mixedSecret: String get() = storedSecret(Key.MIXED_SECRET)

    // Credential handed to other devices when the connection is shared. Regenerating it revokes
    // their access on the next reload while the app's own loopback user stays valid.
    val shareSecret: String get() = storedSecret(Key.SHARE_SECRET)
    fun regenerateShareSecret() = configurationStore.putString(Key.SHARE_SECRET, newSecret())

    private fun newSecret() = java.util.UUID.randomUUID().toString().replace("-", "")

    @Synchronized
    private fun storedSecret(key: String): String {
        var s = configurationStore.getString(key)
        if (s.isNullOrEmpty()) {
            s = newSecret()
            configurationStore.putString(key, s)
        }
        return s
    }

    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    val mixedInboundNeedsAuth: Boolean
        get() =
            // When the inbound is exposed to the LAN (bind 0.0.0.0 via allowAccess),
            // authentication is mandatory so the proxy is never an open relay. This
            // holds in both VPN and Proxy service modes and overrides appendHttpProxy.
            allowAccess ||
                // Loopback-only inbound: keep the prior behavior. In VPN mode the
                // inbound is authenticated, except when appendHttpProxy is active on
                // Android Q+ (the system HTTP proxy cannot supply credentials).
                (
                    serviceMode == Key.MODE_VPN &&
                        !(appendHttpProxy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ) ||
                // Proxy service mode loopback inbound: optionally authenticate too
                // (issue #1197 residual gap). Off by default to preserve the
                // "open localhost proxy" use case; users opt in for hardening.
                (serviceMode == Key.MODE_PROXY && proxyModeInboundAuth)

    // Keep the local mixed (SOCKS/HTTP) inbound open in VPN mode. Default false: in
    // TUN mode the local proxy port is usually unnecessary, and closing it removes the
    // local port-scan attack surface entirely (PR #1154 / issue #1197).
    var requireProxyInVPN by configurationStore.boolean(Key.REQUIRE_PROXY_IN_VPN)

    // Authenticate the loopback mixed inbound in Proxy service mode (issue #1197).
    var proxyModeInboundAuth by configurationStore.boolean(Key.PROXY_MODE_INBOUND_AUTH)

    val mixedInboundUser: String get() = if (mixedInboundAuthed) Key.MIXED_USERNAME else ""
    val mixedInboundPass: String get() = if (mixedInboundAuthed) mixedSecret else ""

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
    }

    private fun getLocalPort(key: String, default: Int): Int = parsePort(configurationStore.getString(key), default + userIndex)

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }
    var networkAutomation by configurationStore.boolean(Key.NETWORK_AUTOMATION) { false }
    var automationPaused by configurationStore.boolean(Key.NETWORK_AUTOMATION_PAUSED) { false }
    var networkAutomationBlocked by configurationStore.string(Key.NETWORK_AUTOMATION_BLOCKED)
    var connectionDiagnostics by configurationStore.boolean(Key.CONNECTION_DIAGNOSTICS) { false }

    // NekoBox.onCreate writes it once: on for new installs, off for installs that predate the default.
    var killSwitch by configurationStore.boolean(Key.KILL_SWITCH) { true }
    var webrtcLeakProtection by configurationStore.boolean(Key.WEBRTC_LEAK_PROTECTION) { false }
    var gpsSpoofing by configurationStore.boolean(Key.GPS_SPOOFING) { false }
    var gpsCoordinates by configurationStore.string(Key.GPS_COORDINATES) { "" }
    var gpsLookupUrl by configurationStore.string(Key.GPS_LOOKUP_URL) { "https://ipapi.co/json/" }

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY)
    var httpProxyBypass by configurationStore.string(Key.HTTP_PROXY_BYPASS) { DEFAULT_HTTP_PROXY_BYPASS }
    var dnsHosts by configurationStore.string(Key.DNS_HOSTS) { "" }
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") { 5 }
    var connectionTestTimeout by configurationStore.int(Key.CONNECTION_TEST_TIMEOUT) { 3000 }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS) { true }

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }

    var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT) { false }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)
    var serverProtocolParam by profileCacheStore.string(Key.SERVER_PROTOCOL_PARAM)
    var serverObfsParam by profileCacheStore.string(Key.SERVER_OBFS_PARAM)

    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var serverHy2ObfsType by profileCacheStore.stringToInt(Key.SERVER_HY2_OBFS_TYPE)
    var serverHy2GeckoMinPacket by profileCacheStore.stringToInt(Key.SERVER_HY2_GECKO_MIN_PACKET) { 512 }
    var serverHy2GeckoMaxPacket by profileCacheStore.stringToInt(Key.SERVER_HY2_GECKO_MAX_PACKET) { 1200 }
    var serverHy2EchEnabled by profileCacheStore.boolean(Key.SERVER_HY2_ECH_ENABLED)
    var serverHy2EchConfig by profileCacheStore.string(Key.SERVER_HY2_ECH_CONFIG)

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var serverUserId by profileCacheStore.string(Key.SERVER_USER_ID)
    var serverPinnedCertChainSha256 by profileCacheStore.string(Key.SERVER_PINNED_CERT_CHAIN_SHA256)

    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)
    var groupAutoSelect by profileCacheStore.boolean(Key.GROUP_AUTO_SELECT)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionFormat by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FORMAT) { 0 }
    var subscriptionImportWarning by profileCacheStore.string(Key.SUBSCRIPTION_IMPORT_WARNING)
    var subscriptionBackupLinks by profileCacheStore.string(Key.SUBSCRIPTION_BACKUP_LINKS)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionSendDeviceId by profileCacheStore.boolean(Key.SUBSCRIPTION_SEND_DEVICE_ID)

    var subscriptionDeviceId by configurationStore.string(Key.SUBSCRIPTION_DEVICE_ID)

    /** Random per-install identifier sent as X-HWID by subscriptions that opt in. Not derived from hardware. */
    @Synchronized
    fun subscriptionDeviceId(): String = subscriptionDeviceId.ifBlank {
        java.util.UUID.randomUUID().toString().also { subscriptionDeviceId = it }
    }

    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }
    var subscriptionFilterMode by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FILTER_MODE) { 0 }
    var subscriptionFilterRegex by profileCacheStore.string(Key.SUBSCRIPTION_FILTER_REGEX)
    var subscriptionCustomDns by profileCacheStore.string(Key.SUBSCRIPTION_CUSTOM_DNS)

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    var webdavServer: String?
        get() = configurationStore.getString(Key.WEBDAV_SERVER)
        set(value) = configurationStore.putString(Key.WEBDAV_SERVER, value)

    var webdavUsername: String?
        get() = configurationStore.getString(Key.WEBDAV_USERNAME)
        set(value) = configurationStore.putString(Key.WEBDAV_USERNAME, value)

    var webdavPassword: String?
        get() = configurationStore.getString(Key.WEBDAV_PASSWORD)
        set(value) = configurationStore.putString(Key.WEBDAV_PASSWORD, value)

    var webdavPath: String?
        get() = configurationStore.getString(Key.WEBDAV_PATH) ?: "NekoBox" // set default value
        set(value) = configurationStore.putString(Key.WEBDAV_PATH, value)

    /** The WebDAV folder of backups, as path segments. An emptied path keeps the folder backups have used for it. */
    val webdavFolder: List<String>
        get() = (webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox").split('/').filter { it.isNotEmpty() }

    var globalMode by configurationStore.boolean(Key.GLOBAL_MODE)

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}

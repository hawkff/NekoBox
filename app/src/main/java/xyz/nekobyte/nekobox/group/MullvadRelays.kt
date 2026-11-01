package xyz.nekobyte.nekobox.group

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONException
import xyz.nekobyte.nekobox.GroupType
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.SubscriptionFilterMode
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.GroupManager
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.database.ProxyGroup
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ui.ProfileWords
import java.text.Collator
import java.util.Locale

/**
 * Mullvad's SOCKS5 proxies, one on each of its WireGuard servers, as a list on the Proxies screen. They answer only
 * inside Mullvad's WireGuard tunnel, so a new list connects through a stored Mullvad WireGuard profile, found by its
 * server's key. The list's subscription keeps where the server list comes from and the countries and cities picked,
 * as an include filter over the proxies' host names; an update adds and removes proxies to match the server list.
 */
object MullvadRelays {
    const val URL = "https://api.mullvad.net/www/relays/wireguard/"
    const val LIST_NAME = "Mullvad"
    private const val HOST_SUFFIX = ".relays.mullvad.net"

    // Marks the proxies the import added, in a column only Tailscale profiles use otherwise; backups keep it.
    private const val IMPORTED = "mullvad-server-list"

    // The list is about 300 KB.
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val COUNTRY = Regex("[a-z]{2}")
    private val CITY = Regex("[a-z]{2,4}")

    /** A server in service, its proxy at [host]:[port]. */
    class Relay(
        val country: String,
        val city: String,
        val cityName: String,
        val host: String,
        val port: Int,
        val publicKey: String,
    ) {
        /** The city as filters name it: "us-nyc". */
        val place get() = "$country-$city"
    }

    /**
     * The servers in service with a SOCKS5 proxy, and the proxy hosts of every server the list names: one out of service
     * or written oddly is still Mullvad's, so only a server gone from the list retires its proxy.
     */
    class ServerList(val relays: List<Relay>, val listed: Set<String>)

    /** Where a proxy's server stands, from its host name: "se-sto-wg-socks5-001.relays.mullvad.net" is in se, sto, 001. */
    class Place(val country: String, val city: String, val number: String)

    // The number follows a hyphen: "socks5" is part of a name, not server 5.
    private val PLACE = Regex("([a-z]{2})-([a-z]{2,4})-(?:[a-z0-9-]*-)?(\\d+)\\.relays\\.mullvad\\.net")

    /** The place of a proxy at [host], or null for a host that is not one of Mullvad's servers. */
    fun place(host: String?): Place? = host?.let(PLACE::matchEntire)?.let { Place(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    /** What a sync did: proxies added and removed, and proxies [moved] to their server's new port. */
    class Result(val list: ProxyGroup, val created: Boolean, val added: Int, val removed: Int, val moved: Int, val through: ProxyEntity?)

    fun isRelayList(group: ProxyGroup) = group.type == GroupType.PROXIES && group.subscription?.link == URL

    /** The server list, through the running service when there is one. */
    fun fetch(): String {
        val client = Libcore.newHttpClient()
        try {
            client.modernTLS()
            client.trySocks5(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
            return client.newRequest().apply { setURL(URL) }.execute().getContentStringLimited(MAX_BYTES)
        } finally {
            client.close()
        }
    }

    /** The server list in [text], its servers as rows show them: by country name, city and host. Empty when it is none. */
    fun parse(text: String, locale: Locale = Locale.getDefault()): ServerList {
        val servers = try {
            JSONArray(text)
        } catch (_: JSONException) {
            return ServerList(emptyList(), emptySet())
        }
        val relays = ArrayList<Relay>()
        val listed = HashSet<String>()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            val country = server.optString("country_code")
            val city = server.optString("city_code")
            val host = server.optString("socks_name")
            val port = server.optInt("socks_port")
            // A server named twice counts once.
            if (host.isNotEmpty() && !listed.add(host)) continue
            // Only a numbered server under Mullvad's relay domain becomes a proxy, whatever else the list holds: the
            // Proxies screen places each by its host name.
            if (!server.optBoolean("active") || !COUNTRY.matches(country) || !CITY.matches(city) || port !in 1..65535 ||
                !host.startsWith("$country-$city-") || !PLACE.matches(host)
            ) {
                continue
            }
            relays += Relay(country, city, server.optString("city_name").ifBlank { city }, host, port, server.optString("pubkey"))
        }
        val names = relays.map { it.country }.distinct().associateWith { countryName(it, locale) }
        val collator = Collator.getInstance(locale)
        val sorted = relays.sortedWith(
            compareBy<Relay, String>(collator) { names.getValue(it.country) }.thenBy(collator) { it.cityName }.thenBy { it.host },
        )
        return ServerList(sorted, listed)
    }

    fun countryName(code: String, locale: Locale = Locale.getDefault()): String = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)

    /** What a list keeps: the server list and the countries and cities picked as [codes] ("nl", "us-nyc"), null for all. */
    fun subscription(codes: Set<String>?): SubscriptionBean = SubscriptionBean().applyDefaultValues().apply {
        link = URL
        if (codes != null) {
            filterMode = SubscriptionFilterMode.INCLUDE
            filterRegex = codes.sorted().joinToString("|", "^(?:", ")-")
        }
    }

    /** The [codes] a list keeping [subscription] was given, null for all. */
    fun codes(subscription: SubscriptionBean?): Set<String>? {
        if (subscription?.filterMode != SubscriptionFilterMode.INCLUDE) return null
        return subscription.filterRegex.orEmpty().removePrefix("^(?:").removeSuffix(")-").split('|').filterTo(HashSet(), String::isNotEmpty)
    }

    /** Which proxy hosts a list keeping [subscription] holds. */
    fun holds(subscription: SubscriptionBean?): (String) -> Boolean {
        if (subscription?.filterMode != SubscriptionFilterMode.INCLUDE) return { true }
        val filter = Regex(subscription.filterRegex.orEmpty())
        return { filter.containsMatchIn(it) }
    }

    /** Fetches the server list again from [source] for [listId] and the countries and cities it keeps. */
    suspend fun update(listId: Long, source: () -> String = ::fetch): Result {
        requireNotNull(ProfileDatabase.groupDao.getById(listId)?.takeIf(::isRelayList)) { "Not a list of Mullvad's proxies" }
        return sync(listId, parse(source()))
    }

    // Held while a pick looks for the list or makes it, so two picks made before it existed fill one list.
    private val making = Mutex()

    /**
     * Fills [listId] with the proxies of the servers [pick] holds, or of the pick it keeps when that is null; a new list
     * when [listId] is 0. A proxy goes when the pick leaves its server out or the server list drops it, unless it is in
     * use or the user added it; the rest follow the server list's order.
     */
    suspend fun sync(listId: Long, servers: ServerList, pick: SubscriptionBean? = null): Result {
        val relays = servers.relays
        // A server list that failed to arrive whole must not empty the list.
        if (relays.isEmpty()) error(app.getString(R.string.no_proxies_found))
        var created = false
        var through: ProxyEntity? = null
        val id = if (listId > 0L) {
            listId
        } else {
            making.withLock {
                ProfileDatabase.groupDao.allGroups().firstOrNull(::isRelayList)?.id ?: run {
                    created = true
                    through = tunnel(relays)
                    GroupManager.createGroup(ProxyGroup(name = LIST_NAME, type = GroupType.PROXIES, subscription = requireNotNull(pick), frontProxy = through?.id ?: -1L)).id
                }
            }
        }
        // Renaming or deleting the list waits for this, as for a subscription's update, and so does another pick.
        if (!GroupUpdater.updating.add(id)) error(app.getString(R.string.group_wait_update))
        try {
            // Read once the list is held, so an update finishing after a new pick keeps the new one.
            val list = checkNotNull(ProfileDatabase.groupDao.getById(id)) { "List deleted during the update" }
            val holds = holds(pick ?: list.subscription)
            val wanted = relays.filter { holds(it.host) }
            val wantedHosts = wanted.mapTo(HashSet()) { it.host }
            val stored = ProfileDatabase.proxyDao.getByGroup(id)
            fun ProxyEntity.relayHost() = (requireBean() as? SOCKSBean)?.serverAddress?.takeIf { it.endsWith(HOST_SUFFIX) }
            val storedByHost = stored.filter { it.relayHost() != null }.groupBy { it.relayHost() }
            // Only proxies the import added go, one the user added for a server stays; a server out of service keeps its proxy.
            val unwanted = stored.filter { row ->
                row.uuid == IMPORTED && row.relayHost()?.let { host -> host !in wantedHosts && (!holds(host) || host !in servers.listed) } == true
            }
            val inUse = if (unwanted.isEmpty()) emptySet() else RawUpdater.referencedProfileIds()
            val removedIds = unwanted.filterNot { it.id in inUse }.mapTo(HashSet()) { it.id }
            // The servers in the server list's order, then what the user added and what stays, as they were.
            val rows = ArrayList<ProxyEntity>()
            val toInsert = ArrayList<ProxyEntity>()
            // Imported proxies whose server moved its proxy to another port.
            val ported = HashMap<Long, Int>()
            for (relay in wanted) {
                val row = storedByHost[relay.host]?.first()
                if (row != null && row.uuid == IMPORTED && row.requireBean().serverPort != relay.port) ported[row.id] = relay.port
                rows += row ?: ProxyEntity(groupId = id, uuid = IMPORTED).putBean(bean(relay)).also { toInsert += it }
            }
            val placed = rows.mapTo(HashSet()) { it.id }
            stored.filterTo(rows) { it.id !in placed && it.id !in removedIds }
            val moved = HashMap<Long, Long>()
            rows.forEachIndexed { index, row ->
                val order = index + 1L
                if (row.id == 0L) {
                    row.userOrder = order
                } else if (row.userOrder != order) {
                    moved[row.id] = order
                }
            }
            ProfileDatabase.instance.runInTransaction {
                // The pick goes onto the stored row with the proxies, so a first hop picked meanwhile stays and a failed
                // change keeps the old pick.
                val current = checkNotNull(ProfileDatabase.groupDao.getById(id)) { "List deleted during the update" }
                if (pick != null && !created) ProfileDatabase.groupDao.updateGroup(current.apply { subscription = pick })
                if (toInsert.isNotEmpty()) ProfileDatabase.proxyDao.insert(toInsert)
                for ((row, order) in moved) ProfileDatabase.proxyDao.updateOrder(row, order)
                removedIds.toList().chunked(500).forEach { ProfileDatabase.proxyDao.deleteByIds(it) }
                // The stored rows, so traffic counted meanwhile stays; a check of the old port says nothing now.
                for ((row, port) in ported) {
                    val entity = ProfileDatabase.proxyDao.getById(row) ?: continue
                    entity.putBean(entity.requireBean().apply { serverPort = port })
                    entity.status = 0
                    entity.ping = 0
                    entity.error = null
                    ProfileDatabase.proxyDao.updateProxy(entity)
                }
            }
            if (ported.isNotEmpty()) DataStore.forgetProxyExits(ported.keys.toList())
            return Result(list, created, toInsert.size, removedIds.size, ported.size, through)
        } catch (e: Exception) {
            // A list made for this import goes again when the import fails.
            if (created) GroupManager.deleteGroup(id)
            throw e
        } finally {
            GroupUpdater.updating.remove(id)
            // The stored list, with its pick, to the tabs and the page, which reloads its proxies on it.
            GroupManager.postUpdate(id)
        }
    }

    // "🇳🇱 Amsterdam 001": the server's number ends its host's first label.
    private fun bean(relay: Relay) = SOCKSBean().apply {
        name = "${ProfileWords.flag(relay.country)} ${relay.cityName} ${relay.host.substringBefore('.').substringAfterLast('-')}"
        serverAddress = relay.host
        serverPort = relay.port
        protocol = SOCKSBean.PROTOCOL_SOCKS5
    }.applyDefaultValues()

    // A stored WireGuard profile for one of the servers, the selected one first.
    private fun tunnel(relays: List<Relay>): ProxyEntity? {
        val keys = relays.mapNotNullTo(HashSet()) { it.publicKey.takeIf(String::isNotBlank) }
        val tunnels = ProfileDatabase.proxyDao.getEntities(ProfileDatabase.proxyDao.getIdsByType(ProxyEntity.TYPE_WG))
            .filter { (it.requireBean() as? WireGuardBean)?.peerPublicKey in keys }
        return tunnels.firstOrNull { it.id == DataStore.selectedProxy } ?: tunnels.minByOrNull { it.id }
    }
}

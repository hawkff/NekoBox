package xyz.nekobyte.nekobox.group

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

    // The list is about 300 KB.
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val COUNTRY = Regex("[a-z]{2}")
    private val CITY = Regex("[a-z]{2,4}")
    private val HOST = Regex("[a-z0-9-]+\\.relays\\.mullvad\\.net")

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

    class Result(val list: ProxyGroup, val created: Boolean, val added: Int, val removed: Int, val through: ProxyEntity?)

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

    /**
     * The servers in service with a SOCKS5 proxy, as rows show them: by country name, city and host. Empty when [text]
     * is no server list.
     */
    fun parse(text: String, locale: Locale = Locale.getDefault()): List<Relay> {
        val servers = try {
            JSONArray(text)
        } catch (_: JSONException) {
            return emptyList()
        }
        val relays = ArrayList<Relay>()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            val country = server.optString("country_code")
            val city = server.optString("city_code")
            val host = server.optString("socks_name")
            val port = server.optInt("socks_port")
            // Only a name under Mullvad's relay domain becomes a proxy, whatever else the list holds.
            if (!server.optBoolean("active") || !COUNTRY.matches(country) || !CITY.matches(city) || port !in 1..65535 ||
                !host.startsWith("$country-$city-") || !HOST.matches(host)
            ) {
                continue
            }
            relays += Relay(country, city, server.optString("city_name").ifBlank { city }, host, port, server.optString("pubkey"))
        }
        val names = relays.map { it.country }.distinct().associateWith { countryName(it, locale) }
        val collator = Collator.getInstance(locale)
        return relays.sortedWith(
            compareBy<Relay, String>(collator) { names.getValue(it.country) }.thenBy(collator) { it.cityName }.thenBy { it.host },
        )
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

    /** Which servers a list keeping [subscription] holds. */
    fun holds(subscription: SubscriptionBean?): (Relay) -> Boolean {
        if (subscription?.filterMode != SubscriptionFilterMode.INCLUDE) return { true }
        val filter = Regex(subscription.filterRegex.orEmpty())
        return { filter.containsMatchIn(it.host) }
    }

    /** Fetches the server list again for [listId] and the countries and cities it keeps. */
    suspend fun update(listId: Long): Result {
        val list = requireNotNull(ProfileDatabase.groupDao.getById(listId)?.takeIf(::isRelayList)) { "Not a list of Mullvad's proxies" }
        return sync(listId, parse(fetch()), list.subscription!!)
    }

    /**
     * Fills [listId] with the proxies of the servers [subscription] holds, or a new list when it is 0. Proxies the user
     * added stay, and so do proxies in use that the server list dropped; the rest follow the server list's order.
     */
    suspend fun sync(listId: Long, relays: List<Relay>, subscription: SubscriptionBean): Result {
        // A server list that failed to arrive whole must not empty the list.
        if (relays.isEmpty()) error(app.getString(R.string.no_proxies_found))
        var through: ProxyEntity? = null
        val list = if (listId > 0L) {
            checkNotNull(ProfileDatabase.groupDao.getById(listId)) { "List deleted during the update" }
        } else {
            through = tunnel(relays)
            GroupManager.createGroup(ProxyGroup(name = LIST_NAME, type = GroupType.PROXIES, subscription = subscription, frontProxy = through?.id ?: -1L))
        }
        // Renaming or deleting the list waits for this, as for a subscription's update.
        if (!GroupUpdater.updating.add(list.id)) error(app.getString(R.string.group_wait_update))
        try {
            val kept = list.subscription
            if (listId > 0L && (subscription.filterMode != kept?.filterMode || subscription.filterRegex != kept?.filterRegex)) {
                // Onto the stored row, so a first hop picked since the list was read stays.
                ProfileDatabase.instance.runInTransaction {
                    ProfileDatabase.groupDao.getById(list.id)?.let { ProfileDatabase.groupDao.updateGroup(it.apply { this.subscription = subscription }) }
                }
                list.subscription = subscription
            }
            val wanted = relays.filter(holds(subscription))
            val wantedHosts = wanted.mapTo(HashSet()) { it.host }
            val stored = ProfileDatabase.proxyDao.getByGroup(list.id)
            fun ProxyEntity.relayHost() = (requireBean() as? SOCKSBean)?.serverAddress?.takeIf { it.endsWith(HOST_SUFFIX) }
            val storedByHost = stored.filter { it.relayHost() != null }.groupBy { it.relayHost() }
            val unwanted = stored.filter { it.relayHost()?.let { host -> host !in wantedHosts } == true }
            val inUse = if (unwanted.isEmpty()) emptySet() else RawUpdater.referencedProfileIds()
            val removedIds = unwanted.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id !in inUse } }
            // The servers in the server list's order, then what the user added and what stays in use, as they were.
            val rows = ArrayList<ProxyEntity>()
            val toInsert = ArrayList<ProxyEntity>()
            for (relay in wanted) {
                rows += storedByHost[relay.host]?.first() ?: ProxyEntity(groupId = list.id).putBean(bean(relay)).also { toInsert += it }
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
                checkNotNull(ProfileDatabase.groupDao.getById(list.id)) { "List deleted during the update" }
                if (toInsert.isNotEmpty()) ProfileDatabase.proxyDao.insert(toInsert)
                for ((id, order) in moved) ProfileDatabase.proxyDao.updateOrder(id, order)
                removedIds.toList().chunked(500).forEach { ProfileDatabase.proxyDao.deleteByIds(it) }
            }
            return Result(list, listId <= 0L, toInsert.size, removedIds.size, through)
        } catch (e: Exception) {
            // A list made for this import goes again when the import fails.
            if (listId <= 0L) GroupManager.deleteGroup(listOf(list))
            throw e
        } finally {
            GroupUpdater.updating.remove(list.id)
            GroupManager.postReload(list.id)
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

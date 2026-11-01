package xyz.nekobyte.nekobox.ui

import xyz.nekobyte.nekobox.group.MullvadRelays

/**
 * The list of Mullvad's proxies as Mullvad's app shows its servers: countries, each opening into its cities, each
 * opening into its servers. A proxy's place comes from its host name; proxies added by hand follow the countries under
 * one more header. Countries, cities and servers keep the order of their first proxy in the list, so a sort by latency
 * puts the fastest country first.
 */
internal object RelayTree {
    /** A proxy as the tree needs it: its place, its name, and its last check (status 1 answered in [ping] ms). */
    class Server(val id: Long, val place: MullvadRelays.Place?, val name: String, val status: Int, val ping: Int)

    sealed interface Entry

    /**
     * A country ([level] 0), a city (1), or the proxies added by hand (0). Its fold is kept under [key]: "se",
     * "se-sto", or [REST]. Once a check has run, [answered] says how many of its [count] proxies answered (-1 before)
     * and [best] the lowest latency among them; [spoken] is the title without a flag, for screen readers.
     */
    class Header(
        val key: String,
        val level: Int,
        val title: String,
        val spoken: String,
        val count: Int,
        val open: Boolean,
        val answered: Int,
        val best: Int,
        val holdsSelected: Boolean,
    ) : Entry

    /** A proxy on show. In a city, [label] is its number, or its name when it was renamed; null for one added by hand. */
    class Row(val id: Long, val label: String?) : Entry

    const val REST = ""

    /**
     * The entries on show. Each header is open as [open] says for its key, given its default: a country holding the
     * [selected] proxy, and a city holding it or alone in its country, are open by default; the rest start folded.
     */
    fun build(
        servers: List<Server>,
        selected: Long,
        countryName: (String) -> String,
        restTitle: String,
        open: (key: String, byDefault: Boolean) -> Boolean,
    ): List<Entry> {
        val countries = LinkedHashMap<String, LinkedHashMap<String, MutableList<Server>>>()
        val rest = ArrayList<Server>()
        for (server in servers) {
            val place = server.place
            if (place == null) rest += server else countries.getOrPut(place.country) { LinkedHashMap() }.getOrPut(place.city) { ArrayList() } += server
        }
        val entries = ArrayList<Entry>()
        for ((country, cities) in countries) {
            val all = cities.values.flatten()
            val name = countryName(country)
            val countryOpen = open(country, all.any { it.id == selected })
            entries += header(country, 0, "${ProfileWords.flag(country)} $name", name, all, countryOpen, selected)
            if (!countryOpen) continue
            for ((city, members) in cities) {
                val title = members.firstNotNullOfOrNull(::importedCity) ?: city.uppercase()
                val key = "$country-$city"
                val cityOpen = open(key, cities.size == 1 || members.any { it.id == selected })
                entries += header(key, 1, title, title, members, cityOpen, selected)
                if (cityOpen) members.mapTo(entries) { Row(it.id, if (importedCity(it) == title) it.place!!.number else it.name) }
            }
        }
        if (rest.isNotEmpty()) {
            // Without a server of Mullvad's, the list is these proxies alone: no header over them.
            if (countries.isEmpty()) return rest.map { Row(it.id, null) }
            val restOpen = open(REST, true)
            entries += header(REST, 0, restTitle, restTitle, rest, restOpen, selected)
            if (restOpen) rest.mapTo(entries) { Row(it.id, null) }
        }
        return entries
    }

    private fun header(key: String, level: Int, title: String, spoken: String, members: List<Server>, open: Boolean, selected: Long): Header {
        val answering = members.filter { it.status == 1 }
        return Header(
            key = key,
            level = level,
            title = title,
            spoken = spoken,
            count = members.size,
            open = open,
            answered = if (members.any { it.status > 0 }) answering.size else -1,
            best = answering.minOfOrNull { it.ping } ?: 0,
            holdsSelected = members.any { it.id == selected },
        )
    }

    // The city a proxy's name gives as the import wrote it, flag first and number last: "🇸🇪 Stockholm 001" gives
    // "Stockholm"; null once renamed, also to a name that ends in the number, such as "Work 001".
    private fun importedCity(server: Server): String? {
        val place = server.place ?: return null
        val flag = "${ProfileWords.flag(place.country)} "
        val name = server.name.trim()
        if (!name.startsWith(flag) || !name.endsWith(" ${place.number}")) return null
        return name.removePrefix(flag).removeSuffix(place.number).trim().ifEmpty { null }
    }
}

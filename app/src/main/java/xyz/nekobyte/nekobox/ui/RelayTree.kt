package xyz.nekobyte.nekobox.ui

import xyz.nekobyte.nekobox.group.MullvadRelays

/**
 * The list of Mullvad's proxies as Mullvad's app shows its servers: countries, each opening into its cities, each
 * opening into its servers. A proxy's place comes from its host name; proxies added by hand follow the countries under
 * one more header. Countries, cities and servers keep the order of their first proxy in the list, so a sort by latency
 * puts the fastest country first. [group] sorts the list into its tree once per change; [show] picks what is on show
 * for the folds, the only work a fold does.
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

    /** A header with its proxies: [open] by default, and [rows] for when it is open; [children] for a country's cities. */
    class Node(val header: Header, val rows: List<Row>, val children: List<Node> = emptyList())

    /** The list sorted into countries and cities; [plain] when it holds none of Mullvad's servers. */
    class Tree(val nodes: List<Node>, val plain: List<Row>)

    /** Sorts [servers] into their countries and cities. A country and city holding the [selected] proxy open by default. */
    fun group(servers: List<Server>, selected: Long, countryName: (String) -> String, restTitle: String): Tree {
        val countries = LinkedHashMap<String, LinkedHashMap<String, MutableList<Server>>>()
        val rest = ArrayList<Server>()
        for (server in servers) {
            val place = server.place
            if (place == null) rest += server else countries.getOrPut(place.country) { LinkedHashMap() }.getOrPut(place.city) { ArrayList() } += server
        }
        // Without a server of Mullvad's, the list is these proxies alone: no header over them.
        if (countries.isEmpty()) return Tree(emptyList(), rest.map { Row(it.id, null) })
        val nodes = ArrayList<Node>(countries.size + 1)
        for ((country, cities) in countries) {
            val name = countryName(country)
            val all = cities.values.flatten()
            val children = cities.map { (city, members) ->
                // The name most of its servers carry as the import wrote it, so one renamed server cannot rename its city.
                val title = members.mapNotNull(::importedCity).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: city.uppercase()
                val holds = members.any { it.id == selected }
                Node(
                    header(members, "$country-$city", 1, title, title, open = cities.size == 1 || holds, holds),
                    members.map { Row(it.id, if (importedCity(it) == title) it.place!!.number else it.name) },
                )
            }
            val holds = all.any { it.id == selected }
            nodes += Node(header(all, country, 0, "${ProfileWords.flag(country)} $name", name, open = holds, holds), emptyList(), children)
        }
        if (rest.isNotEmpty()) nodes += Node(header(rest, REST, 0, restTitle, restTitle, open = true, rest.any { it.id == selected }), rest.map { Row(it.id, null) })
        return Tree(nodes, emptyList())
    }

    /** What shows of [tree]: each header open as [open] says for its key, given its default. */
    fun show(tree: Tree, open: (key: String, byDefault: Boolean) -> Boolean): List<Entry> {
        if (tree.nodes.isEmpty()) return tree.plain
        val entries = ArrayList<Entry>()
        fun add(node: Node) {
            val isOpen = open(node.header.key, node.header.open)
            entries += if (isOpen == node.header.open) node.header else node.header.copy(open = isOpen)
            if (!isOpen) return
            entries += node.rows
            node.children.forEach(::add)
        }
        tree.nodes.forEach(::add)
        return entries
    }

    /** [group] and [show] in one. */
    fun build(
        servers: List<Server>,
        selected: Long,
        countryName: (String) -> String,
        restTitle: String,
        open: (key: String, byDefault: Boolean) -> Boolean,
    ): List<Entry> = show(group(servers, selected, countryName, restTitle), open)

    private fun Header.copy(open: Boolean) = Header(key, level, title, spoken, count, open, answered, best, holdsSelected)

    private fun header(members: List<Server>, key: String, level: Int, title: String, spoken: String, open: Boolean, holdsSelected: Boolean): Header {
        var answered = 0
        var checked = false
        var best = Int.MAX_VALUE
        for (member in members) {
            if (member.status > 0) checked = true
            if (member.status == 1) {
                answered++
                if (member.ping < best) best = member.ping
            }
        }
        return Header(key, level, title, spoken, members.size, open, if (checked) answered else -1, if (answered > 0) best else 0, holdsSelected)
    }

    // The city a proxy's name gives as the import wrote it, flag first and number last: "🇸🇪 Stockholm 001" gives
    // "Stockholm"; null once renamed, also to a name that ends in the number, such as "Work 001". The name is the only
    // record of the city's name, so a rename in the import's own shape, "🇸🇪 Work 001", reads as one.
    private fun importedCity(server: Server): String? {
        val place = server.place ?: return null
        val flag = "${ProfileWords.flag(place.country)} "
        val name = server.name.trim()
        if (!name.startsWith(flag) || !name.endsWith(" ${place.number}")) return null
        return name.removePrefix(flag).removeSuffix(place.number).trim().ifEmpty { null }
    }
}

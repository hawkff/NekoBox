package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.nekobyte.nekobox.group.MullvadRelays

class RelayTreeTest {
    private val names = mapOf("se" to "Sweden", "al" to "Albania", "us" to "United States")

    private fun server(id: Long, host: String, name: String, status: Int = 0, ping: Int = 0) = RelayTree.Server(id, MullvadRelays.place(host), name, status, ping)

    private fun relay(id: Long, country: String, city: String, cityName: String, number: String, status: Int = 0, ping: Int = 0) = server(id, "$country-$city-wg-socks5-$number.relays.mullvad.net", "${ProfileWords.flag(country)} $cityName $number", status, ping)

    private val list = listOf(
        relay(1, "al", "tia", "Tirana", "001"),
        relay(2, "al", "tia", "Tirana", "002"),
        relay(3, "se", "sto", "Stockholm", "001"),
        relay(4, "se", "got", "Gothenburg", "001"),
        relay(5, "se", "sto", "Stockholm", "002"),
        relay(6, "us", "nyc", "New York, NY", "501"),
    )

    private fun build(servers: List<RelayTree.Server> = list, selected: Long = 0L, folds: Map<String, Boolean> = emptyMap()) = RelayTree.build(servers, selected, { names.getValue(it) }, "Other") { key, byDefault -> folds[key] ?: byDefault }

    private fun shape(entries: List<RelayTree.Entry>) = entries.map {
        when (it) {
            is RelayTree.Header -> "${"  ".repeat(it.level)}${it.title}${if (it.open) " -" else " +"} ${it.count}"
            is RelayTree.Row -> "    ${it.label ?: it.id}"
        }
    }

    @Test
    fun aPlaceComesFromTheHostName() {
        val place = MullvadRelays.place("us-nyc-wg-socks5-501.relays.mullvad.net")!!
        assertEquals(listOf("us", "nyc", "501"), listOf(place.country, place.city, place.number))
        assertNull(MullvadRelays.place("192.0.2.7"))
        assertNull(MullvadRelays.place("us-nyc-wg-socks5-501.relays.example.com"))
    }

    @Test
    fun countriesStartFolded_inTheOrderOfTheirFirstProxy() {
        assertEquals(listOf("🇦🇱 Albania + 2", "🇸🇪 Sweden + 3", "🇺🇸 United States + 1"), shape(build()))
    }

    @Test
    fun anOpenCountryShowsItsCities_aLoneCityOpenWithIt() {
        val tree = build(folds = mapOf("al" to true, "se" to true))
        assertEquals(
            listOf(
                "🇦🇱 Albania - 2",
                "  Tirana - 2",
                "    001",
                "    002",
                "🇸🇪 Sweden - 3",
                "  Stockholm + 2",
                "  Gothenburg + 1",
                "🇺🇸 United States + 1",
            ),
            shape(tree),
        )
        // A fold the user made wins over the default.
        assertEquals("  Tirana + 2", shape(build(folds = mapOf("al" to true, "al-tia" to false)))[1])
    }

    @Test
    fun theSelectedProxysCountryAndCityStartOpen() {
        assertEquals(
            listOf("🇦🇱 Albania + 2", "🇸🇪 Sweden - 3", "  Stockholm - 2", "    001", "    002", "  Gothenburg + 1", "🇺🇸 United States + 1"),
            shape(build(selected = 5)),
        )
        val sweden = build(selected = 5)[1] as RelayTree.Header
        assertTrue(sweden.holdsSelected)
        assertFalse((build(selected = 5)[0] as RelayTree.Header).holdsSelected)
    }

    @Test
    fun aRenamedProxyKeepsItsName_andACityWithoutImportedNamesItsCode() {
        val servers = listOf(
            // Renamed to a name that ends in its number, and first: it names neither its city nor itself "001".
            server(4, "se-sto-wg-socks5-001.relays.mullvad.net", "Work 001"),
            relay(1, "se", "sto", "Stockholm", "003"),
            server(2, "se-sto-wg-socks5-002.relays.mullvad.net", "Fast one"),
            server(3, "se-mma-wg-socks5-001.relays.mullvad.net", "Mine"),
        )
        assertEquals(
            listOf("🇸🇪 Sweden - 4", "  Stockholm - 3", "    Work 001", "    003", "    Fast one", "  MMA - 1", "    Mine"),
            shape(build(servers, folds = mapOf("se" to true, "se-sto" to true, "se-mma" to true))),
        )
    }

    @Test
    fun aCheckedHeaderCountsWhatAnswered_andItsBestLatency() {
        val servers = listOf(
            relay(1, "se", "sto", "Stockholm", "001", status = 1, ping = 171),
            relay(2, "se", "sto", "Stockholm", "002", status = 1, ping = 160),
            relay(3, "se", "got", "Gothenburg", "001", status = 3),
            relay(4, "al", "tia", "Tirana", "001"),
        )
        val tree = build(servers, folds = mapOf("se" to true))
        val sweden = tree[0] as RelayTree.Header
        assertEquals(listOf(2, 160, 3), listOf(sweden.answered, sweden.best, sweden.count))
        val gothenburg = tree[2] as RelayTree.Header
        assertEquals(listOf(0, 0), listOf(gothenburg.answered, gothenburg.best))
        // Never checked: no count of answers.
        assertEquals(-1, (tree[3] as RelayTree.Header).answered)
    }

    @Test
    fun proxiesAddedByHandFollowUnderOneHeader_openByDefault() {
        val servers = list.take(1) + server(9, "192.0.2.7", "Mine")
        val tree = build(servers)
        assertEquals(listOf("🇦🇱 Albania + 1", "Other - 1", "    9"), shape(tree))
        assertEquals(RelayTree.REST, (tree[1] as RelayTree.Header).key)
        assertEquals("Albania", (tree[0] as RelayTree.Header).spoken)
        // A list with none of Mullvad's servers left shows its proxies without a header.
        assertEquals(listOf("    9"), shape(build(listOf(server(9, "192.0.2.7", "Mine")))))
    }
}

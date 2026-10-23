package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

// org.json needs the Android implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddressReputationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val lists = mapOf(
        "vpn/ipv4.txt" to "198.51.100.0/24\n",
        "vpn/ipv6.txt" to "2001:db8:1::/48\n",
        "datacenter/ipv4.txt" to "# hosting\n203.0.113.0/24\n\n",
        "datacenter/ipv6.txt" to "",
        "torbulkexitlist" to "192.0.2.77\n",
    )

    // Plays GitHub and the Tor Project; with failing set every download fails, with errorPage each answers
    // with an HTML page.
    private fun client(requests: AtomicInteger, failing: Boolean = false, errorPage: Boolean = false) = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            requests.incrementAndGet()
            if (failing) throw IOException("offline")
            val url = chain.request().url.toString()
            val body = if (errorPage) "<html><body>Rate limited</body></html>\n" else lists.entries.firstOrNull { url.endsWith(it.key) }?.value ?: error("unexpected request $url")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("").body(body.toResponseBody()).build()
        },
    ).build()

    private val addresses = listOf("203.0.113.9", "198.51.100.4", "192.0.2.77", "2001:db8:1::5", "192.0.2.1")

    private fun kinds(matches: Map<String, AddressReputation.Match>) = matches.mapValues { it.value.kinds }

    @Test
    fun addressesAreMatchedOnTheDeviceAndTheListsKeptForADay() = runTest {
        val requests = AtomicInteger()
        val cache = folder.newFolder()
        val expected = mapOf(
            "203.0.113.9" to setOf(AddressReputation.Kind.DATACENTER),
            "198.51.100.4" to setOf(AddressReputation.Kind.VPN),
            "192.0.2.77" to setOf(AddressReputation.Kind.TOR),
            "2001:db8:1::5" to setOf(AddressReputation.Kind.VPN),
            "192.0.2.1" to emptySet(),
        )
        val first = AddressReputation.match(client(requests), cache, addresses)
        assertEquals(expected, kinds(first))
        assertTrue(first.values.all { it.complete })
        assertEquals(5, requests.get())
        // The Tor Project lists IPv4 exits only, so no list covers Tor for an IPv6 address.
        assertEquals(setOf(AddressReputation.Kind.TOR), first.getValue("2001:db8:1::5").uncovered)
        assertEquals(emptySet<AddressReputation.Kind>(), first.getValue("203.0.113.9").uncovered)
        // Fresh copies are reused.
        assertEquals(expected, kinds(AddressReputation.match(client(requests), cache, addresses)))
        assertEquals(5, requests.get())
        // Old copies are refreshed, and kept when the download fails.
        cache.walk().filter { it.isFile }.forEach { it.setLastModified(0) }
        assertEquals(expected, kinds(AddressReputation.match(client(requests, failing = true), cache, addresses)))
        assertEquals(10, requests.get())
    }

    @Test
    fun anErrorPageNeitherReplacesAGoodCopyNorPassesForAList() = runTest {
        val cache = folder.newFolder()
        AddressReputation.match(client(AtomicInteger()), cache, addresses)
        cache.walk().filter { it.isFile }.forEach { it.setLastModified(0) }
        val kept = AddressReputation.match(client(AtomicInteger(), errorPage = true), cache, addresses)
        assertEquals(setOf(AddressReputation.Kind.VPN), kept.getValue("198.51.100.4").kinds)
        assertTrue(kept.values.all { it.complete })
        val fresh = AddressReputation.match(client(AtomicInteger(), errorPage = true), folder.newFolder(), addresses)
        assertTrue(fresh.values.all { it.kinds.isEmpty() && !it.complete })
    }

    @Test
    fun withoutAddressesNothingIsDownloaded() = runTest {
        val requests = AtomicInteger()
        assertEquals(emptyMap<String, AddressReputation.Match>(), AddressReputation.match(client(requests), folder.newFolder(), emptyList()))
        assertEquals(0, requests.get())
    }

    @Test
    fun withoutTheListsNothingIsClaimed() = runTest {
        val result = AddressReputation.match(client(AtomicInteger(), failing = true), folder.newFolder(), addresses)
        assertTrue(result.values.all { it.kinds.isEmpty() && !it.complete })
    }

    @Test
    fun networksAndAddressesAreReadAsLiteralsOnly() {
        val network = AddressReputation.network("203.0.113.128/25")!!
        assertTrue(network.contains(AddressReputation.numeric("203.0.113.200")!!))
        assertFalse(network.contains(AddressReputation.numeric("203.0.113.127")!!))
        assertFalse(network.contains(AddressReputation.numeric("2001:db8::1")!!))
        assertTrue(AddressReputation.network("2001:db8::/32")!!.contains(AddressReputation.numeric("2001:db8:ffff::1")!!))
        assertTrue(AddressReputation.network("192.0.2.77")!!.contains(AddressReputation.numeric("192.0.2.77")!!))
        assertNull(AddressReputation.network("# comment"))
        assertNull(AddressReputation.network("203.0.113.0/33"))
        // Host names would be looked up, so they are refused.
        assertNull(AddressReputation.numeric("example.com"))
        assertNull(AddressReputation.numeric("cafe.be"))
        assertNull(AddressReputation.numeric("1.2.3"))
    }

    @Test
    fun proxycheckAnswersGiveTheFlagsTheNetworkAndTheOperator() {
        val answer = """{"status": "ok", "203.0.113.9": {
            "network": {"asn": "AS64500", "range": "203.0.113.0/24", "hostname": null, "provider": "Example Hosting", "organisation": "Example Hosting", "type": "Hosting"},
            "detections": {"proxy": false, "vpn": true, "compromised": false, "scraper": false, "tor": false, "hosting": true, "anonymous": true, "risk": 66},
            "operator": {"name": "ExampleVPN", "url": "https://vpn.example/"}}}"""
        assertEquals(
            AddressReputation.Lookup(vpn = true, proxy = false, tor = false, hosting = true, network = "Hosting · Example Hosting", operator = "ExampleVPN"),
            AddressReputation.parseProxyCheck(answer, "203.0.113.9"),
        )
        val error = runCatching { AddressReputation.parseProxyCheck("""{"status": "denied", "message": "Daily limit reached"}""", "203.0.113.9") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Daily limit reached", error?.message)
    }
}

package xyz.nekobyte.nekobox.net

import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

// HtmlCompat and org.json need the Android implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DnsLeakTestTest {
    private fun row(ip: String, place: String, detail: String) = """<div class="tr"><div class="td"><span data-type="ip" data-host="$ip" class="ip4 d0">""" +
        """<s data-whois="$ip" data-host="$ip" data-ping="$ip" data-refs="none" data-traceroute="$ip" data-ctr="nl" data-ctrname="$place">$ip</s><i></i></span></div>""" +
        """<div class="td"><img src="/module/bashws/images/flag/nl.png" /> $detail</div></div>"""

    // The shape of the HTML the bash.ws page shows for a finished test.
    private val sample = """<div class="test-ip"><b>Your IP address:</b><div class="table">""" +
        row("203.0.113.7", "Amsterdam, North Holland, Netherlands", "Amsterdam, North Holland, Netherlands, AS64500 Example") +
        """</div></div><b>You use 2 DNS servers:</b><div class="table">""" +
        row("198.51.100.53", "S&atilde;o Paulo, Brazil", "S&atilde;o Paulo, Brazil, AS64501 Resolver &amp; Co") +
        row("198.51.100.54", "", "AS64502 Other") +
        """</div><div class="conclusion">[OK] DNS is not leaking.</div><div class="info"> You use the same ISP for DNS and internet access.</div>"""

    // The markup bash.ws has used since October 2026: the city and the rest of the place in spans of a location cell.
    private fun rowWithLocationCell(ip: String, place: String, city: String, rest: String) = """<div class="tr ip-row"><div class="td"><span data-type="ip" data-host="$ip" class="ip4 d0">""" +
        """<s data-whois="..." data-host="..." data-ping="..." data-refs="none" data-traceroute="..." data-ctr="fi" data-ctrname="$place">$ip</s><i></i></span></div>""" +
        """<div class="td ip-location"><span class="ip-location-main"><img src="/module/bashws/images/flag/fi.png" /> $city</span><span class="ip-location-rest">$rest</span></div></div>"""

    private val sampleWithLocationCell = """<div class="test-ip"><b>Your IP address:</b><div class="table">""" +
        rowWithLocationCell("203.0.113.7", "Helsinki, Uusimaa, Finland", "Helsinki", "Uusimaa, Finland, AS64500 Example AB") +
        """</div></div><b>You use 1 DNS server:</b><div class="table">""" +
        rowWithLocationCell("198.51.100.53", "Helsinki, Uusimaa, Finland", "Helsinki", "Uusimaa, Finland, AS64500 Example AB") +
        """</div><div class="conclusion"><span class="green">[OK]</span> DNS is not leaking.</div>""" +
        """<div class="info"> You use the same ISP for DNS and internet access. If "AS64500 Example AB" is your VPN, then your connection is 100% secure.</div>"""

    private fun answer(vararg fields: Pair<String, Any>) = JSONObject(fields.toMap()).toString()

    // Plays bash.ws: the page's start, exec and checkrun answers, and refused probes. Records each step.
    private fun client(
        events: MutableCollection<String> = ConcurrentLinkedQueue(),
        start: String = answer("kind" to "test", "run_id" to "3128jeklvpzdjrb4", "url" to "/dnsleak/test/3128jeklvpzdjrb4"),
        status: Int = 200,
        pending: Int = 0,
        probeMillis: Long = 0,
        lostTests: Int = 0,
    ): OkHttpClient {
        var checks = 0
        var execs = 0
        return OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val url = request.url
                val form = (request.body as? FormBody)?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }.orEmpty()
                val body = when {
                    url.host.endsWith(".3128jeklvpzdjrb4.bash.ws") -> {
                        Thread.sleep(probeMillis)
                        events += "probe ${url.host}"
                        throw IOException("probe refused")
                    }

                    url.toString() == "https://bash.ws/id" && request.method == "GET" -> {
                        events += "keep-alive"
                        return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("").body("x".toResponseBody()).build()
                    }

                    url.toString() != "https://bash.ws/dnsleak" || request.method != "POST" || form["ajax"] != "1" -> error("unexpected request $url")

                    form["start"] == "1" && form["url"] == "/dnsleak" -> start

                    // bash.ws forgets a test that a connection from another address asks about.
                    form["exec"] == "1" && form["url"] == "/dnsleak/test/3128jeklvpzdjrb4" ->
                        if (execs++ < lostTests) answer("error" to "Invalid test ID") else answer("started" to 1)

                    form["checkrun"] == "1" && form["url"] == "/dnsleak/test/3128jeklvpzdjrb4" ->
                        if (checks++ < pending) answer("done" to 0) else answer("done" to 1, "format" to "html", "result" to sample)

                    else -> error("unexpected form $form")
                }
                events += form.keys.first { it != "ajax" && it != "url" && it != "summary" }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("").body(body.toResponseBody()).build()
            },
        ).build()
    }

    @Test
    fun readsTheAddressTheServersAndTheVerdict() {
        val result = DnsLeakTest.parse(sample)
        assertEquals(listOf(DnsLeakTest.Address("203.0.113.7", "Amsterdam, North Holland, Netherlands", "AS64500 Example")), result.addresses)
        assertEquals(
            listOf(DnsLeakTest.Address("198.51.100.53", "São Paulo, Brazil", "AS64501 Resolver & Co"), DnsLeakTest.Address("198.51.100.54", "", "AS64502 Other")),
            result.servers,
        )
        assertEquals("DNS is not leaking.", result.conclusion)
        assertEquals(DnsLeakTest.Verdict.CLEAN, result.verdict)
        val server = """<b>You use 1 DNS server:</b><div class="table">${row("198.51.100.53", "", "AS64501 Resolver")}</div>"""
        assertEquals(DnsLeakTest.Verdict.LEAK, DnsLeakTest.parse("""$server<div class="conclusion">[WARNING] DNS may be leaking.</div>""").verdict)
        assertEquals(DnsLeakTest.Verdict.CLEAN, DnsLeakTest.parse("""$server<div class="conclusion">No leak detected</div>""").verdict)
        assertEquals(DnsLeakTest.Verdict.UNKNOWN, DnsLeakTest.parse("""$server<div class="conclusion">Something else</div>""").verdict)
        assertEquals(DnsLeakTest.Verdict.UNKNOWN, DnsLeakTest.parse("").verdict)
        // A verdict is only as good as the rows behind it.
        val unbacked = DnsLeakTest.parse("""<div class="conclusion">[OK] DNS is not leaking.</div>""")
        assertEquals(null, unbacked.conclusion)
        assertEquals(DnsLeakTest.Verdict.UNKNOWN, unbacked.verdict)
        assertEquals(DnsLeakTest.Verdict.LEAK, DnsLeakTest.parse("""$server<div class="conclusion">DNS is leaking.</div>""").verdict)
    }

    @Test
    fun readsTheMarkupWithALocationCell() {
        val result = DnsLeakTest.parse(sampleWithLocationCell)
        val address = { ip: String -> DnsLeakTest.Address(ip, "Helsinki, Uusimaa, Finland", "AS64500 Example AB") }
        assertEquals(listOf(address("203.0.113.7")), result.addresses)
        assertEquals(listOf(address("198.51.100.53")), result.servers)
        assertEquals("DNS is not leaking.", result.conclusion)
        assertEquals(DnsLeakTest.Verdict.CLEAN, result.verdict)
    }

    @Test
    fun bashWsCountOfTheServersBacksAVerdictWhoseRowsCannotBeRead() {
        val counted = DnsLeakTest.parse("""<b>You use 2 DNS servers:</b><div class="table"><p>198.51.100.53</p></div><div class="conclusion">[WARNING] DNS may be leaking.</div>""")
        assertEquals(emptyList<DnsLeakTest.Address>(), counted.servers)
        assertEquals(DnsLeakTest.Verdict.LEAK, counted.verdict)
        val noLookups = DnsLeakTest.parse("""<b>You use 0 DNS servers:</b><div class="conclusion">[OK] DNS is not leaking.</div>""")
        assertEquals(DnsLeakTest.Verdict.UNKNOWN, noLookups.verdict)
    }

    @Test
    fun theTestStartsBeforeItsNamesAreLookedUpAndIsReadWhenDone() = runTest {
        val events = ConcurrentLinkedQueue<String>()
        val result = DnsLeakTest.run(client(events, pending = 1))
        val steps = events.filter { it != "keep-alive" }
        val probes = steps.filter { it.startsWith("probe ") }
        assertEquals(30, probes.toSet().size)
        assertTrue(probes.all { it.matches(Regex("probe \\d+\\.3128jeklvpzdjrb4\\.bash\\.ws")) })
        assertEquals(listOf("start", "exec"), steps.take(2))
        assertEquals(listOf("checkrun", "checkrun"), steps.drop(32))
        assertEquals(DnsLeakTest.Verdict.CLEAN, result.verdict)
    }

    @Test
    fun theConnectionThatStartedTheTestStaysBusyWhileNamesAreLookedUp() = runTest {
        val events = ConcurrentLinkedQueue<String>()
        DnsLeakTest.run(client(events, probeMillis = 1_600))
        val steps = events.toList()
        assertTrue("$steps", steps.indexOf("keep-alive") in 2 until steps.indexOf("checkrun"))
        assertTrue("$steps", steps.lastIndexOf("keep-alive") < steps.indexOf("checkrun"))
    }

    @Test
    fun aTestBashWsForgotIsStartedOnceMore() = runTest {
        val events = ConcurrentLinkedQueue<String>()
        assertEquals(DnsLeakTest.Verdict.CLEAN, DnsLeakTest.run(client(events, lostTests = 1)).verdict)
        assertEquals(2, events.count { it == "start" })
        // Twice is not retried again.
        val error = runCatching { DnsLeakTest.run(client(lostTests = 2)) }.exceptionOrNull()
        assertEquals("Invalid test ID", (error as DnsLeakTest.ServiceException).message)
    }

    @Test
    fun serviceErrorsCarryTheirText() = runTest {
        val error = runCatching { DnsLeakTest.run(client(start = answer("error" to "Too many tests"))) }.exceptionOrNull()
        assertTrue(error is DnsLeakTest.ServiceException)
        assertEquals("Too many tests", error?.message)
        // An unknown test comes with an error status.
        val unknown = runCatching { DnsLeakTest.run(client(status = 400, start = answer("error" to "Invalid test ID"))) }.exceptionOrNull()
        assertEquals("Invalid test ID", (unknown as DnsLeakTest.ServiceException).message)
    }

    @Test
    fun answersThatAreNotTheServiceStopTheCheck() = runTest {
        for (client in listOf(client(status = 502, start = "Bad gateway"), client(start = "<html>"), client(start = answer("kind" to "test")))) {
            val error = runCatching { DnsLeakTest.run(client) }.exceptionOrNull()
            assertTrue("$error", error is IOException && error !is DnsLeakTest.ServiceException)
        }
    }
}

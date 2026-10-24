package xyz.nekobyte.nekobox.net

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The bash.ws DNS leak test, run the way its web page runs it: start a test, request unique names
 * under its id so the resolver has to look each one up, then read what bash.ws saw: the public
 * address and the DNS servers that asked for the names. The JSON results endpoint of its
 * command-line clients answers "Test ID not found" for every test, so the result is read from the
 * HTML the page shows.
 */
object DnsLeakTest {
    private const val PAGE = "https://bash.ws/dnsleak"
    private const val PROBES = 30
    private const val POLLS = 40
    private val ID = Regex("[A-Za-z0-9]{1,64}")
    private val HEADER = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
    private val ADDRESS = Regex("""<s\b([^>]*)>([^<]+)</s>""")
    private val PLACE = Regex("""data-ctrname="([^"]*)"""")
    private val NETWORK = Regex("""\bAS\d+\b.*""")
    private val COUNT = Regex("""\d+""")
    private val CONCLUSION = Regex("""<div class="[^"]*\bconclusion\b[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val LEVEL = Regex("""^\[\w+]\s*""")
    private val TAG = Regex("<[^>]*>")
    private val SPACE = Regex("""\s+""")

    /** An address bash.ws saw, with the place and network it reports for it. */
    data class Address(val ip: String, val place: String, val asn: String)

    enum class Verdict { CLEAN, LEAK, UNKNOWN }

    data class Result(val addresses: List<Address>, val servers: List<Address>, val conclusion: String?) {
        // The phrases bash.ws's own clients read, clean ones first: "No leak detected" is clean.
        val verdict = when {
            conclusion == null -> Verdict.UNKNOWN
            listOf("not leaking", "no leak").any { conclusion.contains(it, ignoreCase = true) } -> Verdict.CLEAN
            listOf("may be leaking", "is leaking", "leak detected").any { conclusion.contains(it, ignoreCase = true) } -> Verdict.LEAK
            else -> Verdict.UNKNOWN
        }
    }

    /** bash.ws answered with an error of its own. */
    class ServiceException(message: String) : IOException(message)

    suspend fun run(client: OkHttpClient): Result = withContext(Dispatchers.IO) {
        try {
            attempt(client)
        } catch (e: ServiceException) {
            // A test belongs to the address that started it. Where connections leave from changing
            // addresses, a connection opened mid-test makes it unknown; a fresh test usually gets through.
            if (!e.message.orEmpty().contains("test id", ignoreCase = true)) throw e
            attempt(client)
        }
    }

    private suspend fun attempt(client: OkHttpClient): Result {
        val test = post(client, "start" to "1", "url" to "/dnsleak")
        val id = test.optString("run_id")
        val url = test.optString("url")
        if (!ID.matches(id) || url.isEmpty()) throw IOException("bash.ws did not start the test")
        post(client, "exec" to "1", "url" to url)
        // Only the lookups matter; the requests themselves may fail.
        val probes = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        coroutineScope {
            val lookups = (1..PROBES).map { n ->
                async {
                    try {
                        probes.newCall(Request.Builder().url("https://$n.$id.bash.ws/").build()).await().close()
                    } catch (_: IOException) {
                    }
                }
            }
            // bash.ws ties a test to the address that started it and drops connections idle for a
            // second. Where each connection leaves from its own address, a new one would make the
            // test unknown, so the first connection is kept busy until the lookups end. The loop
            // ends by itself: canceling a request in flight would close that connection.
            launch {
                while (true) {
                    delay(300)
                    if (lookups.none { it.isActive }) break
                    try {
                        client.newCall(Request.Builder().url("https://bash.ws/id").build()).await().close()
                    } catch (_: IOException) {
                    }
                }
            }
            lookups.awaitAll()
        }
        repeat(POLLS) {
            val check = post(client, "checkrun" to "1", "url" to url, "summary" to "0")
            if (check.optString("done") == "1") return parse(check.optString("result"))
            // Under the second after which bash.ws drops the connection.
            delay(500)
        }
        throw IOException("bash.ws did not finish the test")
    }

    // bash.ws reports its own errors as an "error" text in the JSON answer, with or without an error status.
    private suspend fun post(client: OkHttpClient, vararg fields: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().add("ajax", "1")
        for ((name, value) in fields) form.add(name, value)
        return client.newCall(Request.Builder().url(PAGE).post(form.build()).build()).await().use { response ->
            val answer = response.body.string().takeIf { it.trimStart().startsWith("{") }?.let(::JSONObject)
            (answer?.opt("error") as? String)?.takeIf { it.isNotEmpty() }?.let { throw ServiceException(it) }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            answer ?: throw IOException("unexpected answer from bash.ws")
        }
    }

    // Each address sits in an <s> element under its table's header, and its place and network follow
    // it as text. Only that order is read, not the markup around it, which bash.ws changes; only the
    // DNS server table names DNS in its header.
    internal fun parse(html: String): Result {
        val conclusion = CONCLUSION.find(html)
        val end = conclusion?.range?.first ?: html.length
        val headers = HEADER.findAll(html).filter { it.range.first < end }.map { it.range.first to text(it.groupValues[1]) }.toList()
        val cells = ADDRESS.findAll(html).filter { it.range.first < end }.toList()
        val addresses = mutableListOf<Address>()
        val servers = mutableListOf<Address>()
        cells.forEachIndexed { i, cell ->
            val start = cell.range.first
            val next = minOf(cells.getOrNull(i + 1)?.range?.first ?: end, headers.firstOrNull { it.first > start }?.first ?: end)
            val detail = text(html.substring(cell.range.last + 1, next))
            val place = text(PLACE.find(cell.groupValues[1])?.groupValues?.get(1).orEmpty())
            val address = Address(text(cell.groupValues[2]), place, NETWORK.find(detail)?.value.orEmpty())
            if (headers.lastOrNull { it.first < start }?.second.orEmpty().contains("DNS")) servers += address else addresses += address
        }
        // A verdict says nothing unless lookups reached bash.ws: servers it listed, or its count of them.
        val reported = headers.filter { it.second.contains("DNS") }.sumOf { COUNT.find(it.second)?.value?.toIntOrNull() ?: 0 }
        val verdict = conclusion?.let { text(it.groupValues[1]).replace(LEVEL, "") }?.ifEmpty { null }?.takeIf { servers.isNotEmpty() || reported > 0 }
        return Result(addresses, servers, verdict)
    }

    // Tags become spaces, so text in neighboring elements stays apart.
    private fun text(html: String) = HtmlCompat.fromHtml(html.replace(TAG, " "), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(SPACE, " ").trim()
}

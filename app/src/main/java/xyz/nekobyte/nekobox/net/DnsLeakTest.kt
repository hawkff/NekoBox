package xyz.nekobyte.nekobox.net

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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
    private val ROW = Regex("""<s\b([^>]*)>([^<]+)</s>.*?<div class="td">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val PLACE = Regex("""data-ctrname="([^"]*)"""")
    private val CONCLUSION = Regex("""<div class="conclusion">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val LEVEL = Regex("""^\[\w+]\s*""")
    private val TAG = Regex("<[^>]*>")

    /** An address bash.ws saw, with the place and network it reports for it. */
    data class Address(val ip: String, val place: String, val asn: String)

    enum class Verdict { CLEAN, LEAK, UNKNOWN }

    data class Result(val addresses: List<Address>, val servers: List<Address>, val conclusion: String?) {
        // The phrases bash.ws's own clients read, clean ones first: "No leak detected" is clean.
        val verdict = when {
            conclusion == null -> Verdict.UNKNOWN
            listOf("not leaking", "no leak").any { conclusion.contains(it, ignoreCase = true) } -> Verdict.CLEAN
            listOf("may be leaking", "leak detected").any { conclusion.contains(it, ignoreCase = true) } -> Verdict.LEAK
            else -> Verdict.UNKNOWN
        }
    }

    /** bash.ws answered with an error of its own. */
    class ServiceException(message: String) : IOException(message)

    suspend fun run(client: OkHttpClient): Result = withContext(Dispatchers.IO) {
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
            // ends by itself: cancelling a request in flight would close that connection.
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
            if (check.optString("done") == "1") return@withContext parse(check.optString("result"))
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

    // Leaving the check cancels the request in flight; a blocking execute() would run on.
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
                }
            },
        )
    }

    // Each row sits under its table's header; only the DNS server table names DNS there.
    internal fun parse(html: String): Result {
        val headers = HEADER.findAll(html).toList()
        val addresses = mutableListOf<Address>()
        val servers = mutableListOf<Address>()
        for (row in ROW.findAll(html)) {
            val header = headers.lastOrNull { it.range.first < row.range.first }?.groupValues?.get(1).orEmpty()
            val place = text(PLACE.find(row.groupValues[1])?.groupValues?.get(1).orEmpty())
            val detail = text(row.groupValues[3])
            val address = Address(text(row.groupValues[2]), place, detail.removePrefix(place).trimStart(',', ' '))
            if (header.contains("DNS")) servers += address else addresses += address
        }
        // Without the servers behind it a verdict says nothing, e.g. once the page markup changes.
        val conclusion = CONCLUSION.find(html)?.let { text(it.groupValues[1]).replace(LEVEL, "") }?.ifEmpty { null }?.takeIf { servers.isNotEmpty() }
        return Result(addresses, servers, conclusion)
    }

    private fun text(html: String) = HtmlCompat.fromHtml(html.replace(TAG, ""), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
}

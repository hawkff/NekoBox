package xyz.nekobyte.nekobox.ktx

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xyz.nekobyte.nekobox.database.RoutingProfiles
import xyz.nekobyte.nekobox.fmt.AbstractBean
import xyz.nekobyte.nekobox.fmt.Serializable
import xyz.nekobyte.nekobox.fmt.http.parseHttp
import xyz.nekobyte.nekobox.fmt.hysteria.parseHysteria1
import xyz.nekobyte.nekobox.fmt.hysteria.parseHysteria2
import xyz.nekobyte.nekobox.fmt.juicity.parseJuicity
import xyz.nekobyte.nekobox.fmt.naive.parseNaive
import xyz.nekobyte.nekobox.fmt.parseUniversal
import xyz.nekobyte.nekobox.fmt.shadowsocks.parseShadowsocks
import xyz.nekobyte.nekobox.fmt.shadowsocksr.parseShadowsocksR
import xyz.nekobyte.nekobox.fmt.snell.parseSnell
import xyz.nekobyte.nekobox.fmt.socks.parseSOCKS
import xyz.nekobyte.nekobox.fmt.trojan.parseTrojan
import xyz.nekobyte.nekobox.fmt.tuic.parseTuic
import xyz.nekobyte.nekobox.fmt.v2ray.parseV2Ray
import xyz.nekobyte.nekobox.fmt.v2ray.requireSupportedLinkOptions
import xyz.nekobyte.nekobox.fmt.wireguard.parseWireGuardLink
import xyz.nekobyte.nekobox.group.ImportReport
import xyz.nekobyte.nekobox.group.RawUpdater
import xyz.nekobyte.nekobox.proxy.anytls.parseAnytls
import xyz.nekobyte.nekobox.utils.JavaUtil.gson
import xyz.nekobyte.nekobox.utils.Util

// JSON & Base64

fun JSONObject.toStringPretty(): String = gson.toJson(JsonParser.parseString(this.toString()))

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? = try {
    getBoolean(name)
} catch (ignored: Exception) {
    null
}

// name collision, nya
fun JSONObject.getIntNya(name: String): Int? = try {
    getInt(name)
} catch (ignored: Exception) {
    null
}

fun String.decodeBase64UrlSafe(): String = String(Util.b64Decode(this))

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

internal fun String.linesNoComments(): List<String> = removePrefix("\uFEFF").lineSequence()
    .map { it.trim() }.filterNot { it.startsWith('#') || it.isEmpty() }.toList()

private val LINK_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

// Start at the first whitespace character to avoid rescanning long runs.
private val LINK_SEPARATOR = Regex("(?<!\\s)\\s+(?=[A-Za-z][A-Za-z0-9+.-]*://)")
private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me")
private val TELEGRAM_NODES = setOf("proxy", "socks")

/**
 * The scheme of a link that describes a node, used to report one that did not import; null for
 * other links: web pages, Telegram chat and channel links, routing links and words of a remark.
 * Telegram's proxy and SOCKS links are nodes this app cannot import, and so is a web address with
 * credentials, which is how HTTP proxy links look.
 */
internal fun linkNodeScheme(link: String): String? {
    val scheme = LINK_SCHEME.find(link)?.groupValues?.get(1)?.lowercase() ?: return null
    if (RoutingProfiles.isRoutingLink(link)) return null
    return when (scheme) {
        "http", "https" -> {
            val url = link.toHttpUrlOrNull()
            when {
                url == null -> scheme.takeIf { '@' in link.substringAfter("://").substringBefore('/') }
                url.host.lowercase() in TELEGRAM_HOSTS && url.pathSegments.firstOrNull() in TELEGRAM_NODES -> "telegram"
                url.username.isNotEmpty() -> scheme
                else -> null
            }
        }

        "tg" -> scheme.takeIf { link.substringAfter("://").substringBefore('?').substringBefore('/').lowercase() in TELEGRAM_NODES }

        else -> scheme
    }
}

/**
 * Parses share links, one per line or separated by spaces. Links that describe nodes but do not
 * import are added to [report] by scheme.
 */
suspend fun parseProxies(text: String, report: ImportReport? = null): List<AbstractBean> {
    val linksByLine = text.linesNoComments()
    fun String.hasUnsupportedOptions(): Boolean {
        if (runCatching { requireSupportedLinkOptions(this) }.isSuccess) return false
        val scheme = substringBefore("://").lowercase()
        return (scheme != "http" && scheme != "https") || runCatching { parseHttp(this) }.isSuccess
    }
    // Keep a rejected link's query intact without discarding neighboring links.
    val links = linksByLine.flatMap { line ->
        line.split(LINK_SEPARATOR).flatMap { if (it.hasUnsupportedOptions()) listOf(it) else it.split(' ') }
    }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()
    val failures = ArrayList<String?>()
    val failuresByLine = ArrayList<String?>()
    // An http(s) link that fails to parse as an HTTP proxy is a subscription candidate.
    // Don't abort import immediately (issue #1128): a file may contain valid profile
    // links alongside a plain promo/Telegram URL. Remember the first candidate and only
    // treat the input as a subscription if NO profiles parsed at all.
    var subscriptionCandidate: String? = null

    fun String.parseLink(entities: ArrayList<AbstractBean>, failures: MutableList<String?>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }
        // Options that change which servers a connection accepts are refused here, for every
        // scheme, before a parser that does not know them could drop them. A web address that is
        // not an HTTP proxy link may be a subscription, and its query belongs to the provider.
        if (hasUnsupportedOptions()) {
            Logs.w("Link with unsupported options rejected")
            failures += substringBefore("://").lowercase()
            return
        }
        val parsed = entities.size

        if (startsWith("sn://")) {
            Logs.d("Trying universal parser")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w("Universal parser rejected input")
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://",
            )
        ) {
            Logs.d("Trying SOCKS parser")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w("SOCKS parser rejected input")
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            Logs.d("Trying HTTP parser")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w("HTTP parser rejected input")
                if (subscriptionCandidate == null) {
                    val clashUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("install-config")
                        .addQueryParameter("url", this)
                        .build()
                        .toString()
                        .replaceFirst("https://", "clash://")
                    // Defer: only thrown later if no profile links were parsed.
                    subscriptionCandidate = clashUrl
                }
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Trying V2Ray parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("V2Ray parser rejected input")
            }
        } else if (startsWith("vless://")) {
            Logs.d("Trying VLESS parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("VLESS parser rejected input")
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Trying Trojan parser")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w("Trojan parser rejected input")
            }
        } else if (startsWith("ss://")) {
            Logs.d("Trying Shadowsocks parser")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w("Shadowsocks parser rejected input")
            }
        } else if (startsWith("ssr://")) {
            Logs.d("Trying ShadowsocksR parser")
            runCatching {
                entities.add(parseShadowsocksR(this))
            }.onFailure {
                Logs.w("ShadowsocksR parser rejected input")
            }
        } else if (startsWith("naive+")) {
            Logs.d("Trying Naive parser")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w("Naive parser rejected input")
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Trying Hysteria 1 parser")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w("Hysteria 1 parser rejected input")
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Trying Hysteria 2 parser")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w("Hysteria 2 parser rejected input")
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Trying TUIC parser")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w("TUIC parser rejected input")
            }
        } else if (startsWith("juicity://")) {
            Logs.d("Trying Juicity parser")
            runCatching {
                entities.add(parseJuicity(this))
            }.onFailure {
                Logs.w("Juicity parser rejected input")
            }
        } else if (startsWith("snell://")) {
            Logs.d("Trying Snell parser")
            runCatching {
                entities.add(parseSnell(this))
            }.onFailure {
                Logs.w("Snell parser rejected input")
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Trying AnyTLS parser")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w("AnyTLS parser rejected input")
            }
        } else if (startsWith("wireguard://") || startsWith("wg://")) {
            Logs.d("Trying WireGuard parser")
            runCatching {
                entities.add(parseWireGuardLink(this))
            }.onFailure {
                Logs.w("WireGuard parser rejected input")
            }
        } else if (startsWith("vpn://")) {
            // AmneziaVPN share link: base64url of a WireGuard or AmneziaWG .conf text.
            Logs.d("Trying AmneziaVPN parser")
            runCatching {
                entities.addAll(RawUpdater.parseWireGuardConf(substringAfter("vpn://").decodeBase64UrlSafe()))
            }.onFailure {
                Logs.w("AmneziaVPN parser rejected input")
            }
        }
        if (entities.size == parsed) linkNodeScheme(this)?.let { failures += it }
    }

    for (link in links) {
        link.parseLink(entities, failures)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine, failuresByLine)
    }
    // No profile links parsed but we saw an unparsable http(s) URL: treat the whole
    // input as a subscription link (single-URL paste / file). When profiles WERE found,
    // the stray URL is ignored so the profiles still import (issue #1128).
    if (entities.isEmpty() && entitiesByLine.isEmpty()) {
        subscriptionCandidate?.let { throw SubscriptionFoundException(it) }
    }
//    var isBadLink = false
    if (entities.onEach {
            it.initializeDefaultValues()
        }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size
    ) {
        run test@{
            entities.forEachIndexed { index, bean ->
                val lineBean = entitiesByLine[index]
                if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                    return@test
                }
            }
        }
    }
    val byTokens = entities.size > entitiesByLine.size ||
        (entities.size == entitiesByLine.size && failures.size > failuresByLine.size)
    report?.failed?.addAll(if (byTokens) failures else failuresByLine)
    return if (byTokens) entities else entitiesByLine
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}

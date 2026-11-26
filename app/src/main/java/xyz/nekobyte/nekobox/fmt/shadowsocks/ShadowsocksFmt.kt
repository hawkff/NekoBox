package xyz.nekobyte.nekobox.fmt.shadowsocks

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.ktx.*
import xyz.nekobyte.nekobox.utils.Util

fun ShadowsocksBean.fixPluginName() {
    if (plugin!!.startsWith("simple-obfs")) {
        plugin = plugin!!.replaceFirst("simple-obfs", "obfs-local")
    }
}

// Xray-native links (3x-ui) carry the stream transport as type/path/host/security. sing-box has no
// Shadowsocks transport: WebSocket maps onto the built-in v2ray-plugin, the others cannot be
// expressed and are rejected instead of being imported as a plain TCP profile.
private fun ShadowsocksBean.applyTransportParams(link: HttpUrl) {
    val tls = link.queryParameter("security") == "tls"
    when (val network = link.queryParameter("type") ?: "tcp") {
        "tcp" -> if (tls) error("unsupported shadowsocks transport: tcp with tls")

        "ws" -> if (plugin!!.isBlank()) {
            val wsHost = link.queryParameter("host")?.takeIf { it.isNotBlank() }
            val sni = link.queryParameter("sni")?.takeIf { tls && it.isNotBlank() }
            // The plugin also sends its host as the TLS server name, so a different SNI cannot be kept.
            if (wsHost != null && sni != null && wsHost != sni) error("unsupported shadowsocks transport: host and sni differ")
            // Without a host the plugin sends a fixed default; an Xray client sends the server's name.
            val host = wsHost ?: sni ?: serverAddress
            plugin = listOfNotNull(
                "v2ray-plugin",
                "mode=websocket",
                "host=$host",
                link.queryParameter("path")?.takeIf { it.isNotBlank() }?.let { "path=$it" },
                "tls".takeIf { tls },
                "mux=0",
            ).joinToString(";")
        }

        else -> error("unsupported shadowsocks transport: $network")
    }
}

private val CIPHER_NAME = Regex("[A-Za-z0-9_-]+")

/** The user info of user@host:port..., or null when no '@' separates one from the host. */
private fun userInfoOf(authority: String): String? = authority.substringBefore('?').let { head ->
    head.lastIndexOf('@').takeIf { it >= 0 }?.let { head.substring(0, it) }
}

/** method:password with a cipher name as the method; the password may be empty, as in "none:". */
private fun isMethodAndPassword(userInfo: String?) = userInfo != null && ':' in userInfo && CIPHER_NAME.matches(userInfo.substringBefore(':'))

fun parseShadowsocks(url: String): ShadowsocksBean {
    if (url.substringBefore("#").contains("@")) {
        var link = url.replace("ss://", "https://").toHttpUrlOrNull() ?: error(
            "invalid ss-android link $url",
        )

        var authority = url.substringAfter("ss://").substringBefore("#")
        if (link.username.isBlank()) { // fix justmysocks's shit link
            authority = authority.decodeBase64UrlSafe()
            link = ("https://$authority").toHttpUrlOrNull()?.newBuilder()?.fragment(url.substringAfter("#"))?.build()
                ?: error("invalid jms link $url")
        }

        // ss-android style: method:password in plain text. Base64 has no ':', so one in the user info marks this
        // form, and "none:" keeps its explicitly empty password. A port's ':' is not one: without an '@' there is
        // no user info.
        val userInfo = userInfoOf(authority) ?: error("invalid shadowsocks link: no user info")
        if (':' in userInfo) {
            require(isMethodAndPassword(userInfo)) { "invalid shadowsocks method" }
            return ShadowsocksBean().apply {
                serverAddress = link.host
                serverPort = link.port
                method = link.username
                password = link.password
                plugin = link.queryParameter("plugin") ?: ""
                name = link.fragment
                applyTransportParams(link)
                fixPluginName()
            }
        }

        val methodAndPswd = link.username.decodeBase64UrlSafe()
        // A userinfo that is not base64 of method:password, such as a VLESS UUID or a cipher name alone, has no
        // cipher name before a ':'.
        require(':' in methodAndPswd && CIPHER_NAME.matches(methodAndPswd.substringBefore(":"))) { "invalid shadowsocks method" }

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = methodAndPswd.substringBefore(":")
            password = methodAndPswd.substringAfter(":")
            plugin = link.queryParameter("plugin") ?: ""
            name = link.fragment
            applyTransportParams(link)
            fixPluginName()
        }
    } else {
        // v2rayN style
        var v2Url = url

        if (v2Url.contains("#")) v2Url = v2Url.substringBefore("#")

        val decoded = v2Url.substringAfter("ss://").decodeBase64UrlSafe()
        // method:password@host:port; without the '@' and the ':' before it, the host's port or the method would stand
        // in for the credentials.
        require(isMethodAndPassword(userInfoOf(decoded))) { "invalid shadowsocks method" }
        val link = ("https://$decoded").toHttpUrlOrNull() ?: error("invalid v2rayN link $url")

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = link.username
            password = link.password
            plugin = ""
            val remarks = url.substringAfter("#").unUrlSafe()
            if (remarks.isNotBlank()) name = remarks
        }
    }
}

fun ShadowsocksBean.toUri(): String {
    val builder = linkBuilder().username(Util.b64EncodeUrlSafe("$method:$password"))
        .host(serverAddress!!)
        .port(serverPort!!)

    if (plugin!!.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    if (name!!.isNotBlank()) {
        builder.encodedFragment(name!!.urlSafe())
    }

    return builder.toLink("ss").replace("$serverPort/", "$serverPort")
}

fun JSONObject.parseShadowsocks(): ShadowsocksBean = ShadowsocksBean().apply {
    serverAddress = getStr("server")
    serverPort = getIntNya("server_port")
    password = getStr("password")
    method = getStr("method")
    name = optString("remarks", "")

    val pId = getStr("plugin")
    if (!pId.isNullOrBlank()) {
        plugin = pId + ";" + optString("plugin_opts", "")
    }
}

fun buildSingBoxOutboundShadowsocksBean(bean: ShadowsocksBean): SingBoxOptions.Outbound_ShadowsocksOptions = SingBoxOptions.Outbound_ShadowsocksOptions().apply {
    type = "shadowsocks"
    server = bean.serverAddress
    server_port = bean.serverPort
    password = bean.password
    method = bean.method
    if (bean.plugin!!.isNotBlank()) {
        plugin = bean.plugin!!.substringBefore(";")
        plugin_opts = bean.plugin!!.substringAfter(";")
        if (plugin == "none") {
            plugin = null
            plugin_opts = null
        }
    }
}

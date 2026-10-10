package xyz.nekobyte.nekobox.fmt.shadowsocksr

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import xyz.nekobyte.nekobox.SingBoxOptions
import xyz.nekobyte.nekobox.ktx.applyDefaultValues
import xyz.nekobyte.nekobox.ktx.decodeBase64UrlSafe
import xyz.nekobyte.nekobox.utils.Util
import java.util.Locale

fun parseShadowsocksR(url: String): ShadowsocksRBean {
    val params = url.substringAfter("ssr://").decodeBase64UrlSafe().split(":")

    val bean = ShadowsocksRBean().apply {
        serverAddress = params[0]
        serverPort = params[1].toInt()
        protocol = params[2]
        method = params[3]
        obfs = params[4]
        password = params[5].substringBefore("/").decodeBase64UrlSafe()
    }

    val httpUrl = ("https://localhost" + params[5].substringAfter("/")).toHttpUrl()

    runCatching {
        bean.obfsParam = httpUrl.queryParameter("obfsparam")!!.decodeBase64UrlSafe()
    }
    runCatching {
        bean.protocolParam = httpUrl.queryParameter("protoparam")!!.decodeBase64UrlSafe()
    }

    val remarks = httpUrl.queryParameter("remarks")
    if (!remarks.isNullOrBlank()) {
        bean.name = remarks.decodeBase64UrlSafe()
    }

    return bean
}

fun ShadowsocksRBean.toUri(): String = "ssr://" + Util.b64EncodeUrlSafe(
    "%s:%d:%s:%s:%s:%s/?obfsparam=%s&protoparam=%s&remarks=%s".format(
        Locale.ENGLISH,
        serverAddress,
        serverPort,
        protocol,
        method,
        obfs,
        Util.b64EncodeUrlSafe("%s".format(Locale.ENGLISH, password)),
        Util.b64EncodeUrlSafe("%s".format(Locale.ENGLISH, obfsParam)),
        Util.b64EncodeUrlSafe("%s".format(Locale.ENGLISH, protocolParam)),
        Util.b64EncodeUrlSafe(
            "%s".format(
                Locale.ENGLISH,
                name ?: "",
            ),
        ),
    ),
)

fun JSONObject.parseShadowsocksR(): ShadowsocksRBean = ShadowsocksRBean().applyDefaultValues().apply {
    serverAddress = optString("server", serverAddress)
    serverPort = optInt("server_port", serverPort!!)
    method = optString("method", method)
    password = optString("password", password)
    protocol = optString("protocol", protocol)
    protocolParam = optString("protocol_param", protocolParam)
    obfs = optString("obfs", obfs)
    obfsParam = optString("obfs_param", obfsParam)
    name = optString("remarks", name)
}

fun buildSingBoxOutboundShadowsocksRBean(bean: ShadowsocksRBean): SingBoxOptions.Outbound_ShadowsocksROptions = SingBoxOptions.Outbound_ShadowsocksROptions().apply {
    type = "shadowsocksr"
    server = bean.serverAddress
    server_port = bean.serverPort
    method = bean.method
    password = bean.password
    protocol = bean.protocol
    protocol_param = bean.protocolParam
    obfs = bean.obfs
    obfs_param = bean.obfsParam
    // do NOT set network field here
}

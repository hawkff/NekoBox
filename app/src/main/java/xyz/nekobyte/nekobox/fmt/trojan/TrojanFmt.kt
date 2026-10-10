package xyz.nekobyte.nekobox.fmt.trojan

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.nekobyte.nekobox.fmt.v2ray.parseDuckSoft
import xyz.nekobyte.nekobox.fmt.v2ray.requireSupportedLinkOptions

fun parseTrojan(server: String): TrojanBean {
    requireSupportedLinkOptions(server)
    val link = server.replace("trojan://", "https://").toHttpUrlOrNull()
        ?: error("invalid trojan link $server")

    return TrojanBean().apply {
        parseDuckSoft(link)
        link.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        link.queryParameter("peer")?.apply { if (this.isNotBlank()) sni = this }
    }
}

package xyz.nekobyte.nekobox.ui

import android.util.Xml
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R

/**
 * Security policy for WebDAV endpoints.
 *
 * A WebDAV backup contains every saved profile, including all proxy secrets, and
 * the credentials are sent via HTTP Basic auth. Allowing a plain `http://` endpoint
 * would transmit both the credentials and the secret-bearing backup in cleartext,
 * so only TLS (`https://`) endpoints are accepted.
 */
internal fun redactedWebDavUrlForLog(url: HttpUrl): String {
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val port = if (url.port != defaultPort) ":${url.port}" else ""
    return "${url.scheme}://$host$port/<redacted-path>"
}

object WebDAVSecurity {

    internal fun isBackupName(name: String) = name.startsWith("nekobox_backup_") &&
        (name.endsWith(".json") || name.endsWith(".zip")) &&
        name.length <= 200 && name.none { it == '/' || it == '\\' || it.isISOControl() }

    internal fun backupFiles(xml: String, directory: HttpUrl): List<String> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "DTD declarations are not allowed" }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        val files = linkedSetOf<String>()
        val base = directory.newBuilder().addPathSegment("").build()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "href" || parser.namespace != "DAV:") continue
            val url = base.resolve(parser.nextText()) ?: continue
            if (url.scheme != directory.scheme || url.host != directory.host || url.port != directory.port || url.query != null || url.fragment != null) continue
            if (url.pathSegments.dropLast(1).filter { it.isNotEmpty() } != directory.pathSegments.filter { it.isNotEmpty() }) continue
            val name = url.pathSegments.last()
            if (isBackupName(name)) files += name
            require(files.size <= 1000) { "WebDAV listing is too large" }
        }
        return files.sortedDescending()
    }

    /**
     * Validates and parses a user-provided WebDAV server URL.
     *
     * @return the parsed [HttpUrl] when the URL is a well-formed `https://` endpoint.
     * @throws Exception with a localized message when the scheme is not `https`
     *   or the URL is malformed. The message intentionally contains no credentials.
     */
    fun requireSecureUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw Exception(NekoBox.application.getString(R.string.webdav_insecure_url))
        if (!url.isHttps) {
            throw Exception(NekoBox.application.getString(R.string.webdav_insecure_url))
        }
        return url
    }
}

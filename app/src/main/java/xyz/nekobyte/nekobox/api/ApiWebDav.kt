package xyz.nekobyte.nekobox.api

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ktx.readBytesBounded
import xyz.nekobyte.nekobox.ktx.readTextBounded
import xyz.nekobyte.nekobox.ui.BackupArchiveFormat
import xyz.nekobyte.nekobox.ui.WebDAVSecurity
import xyz.nekobyte.nekobox.ui.backupFileName
import xyz.nekobyte.nekobox.ui.encodeBackupArchive
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal class ApiWebDav {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS).build()

    private fun directory(): HttpUrl {
        val base = DataStore.webdavServer?.takeIf { it.isNotBlank() } ?: reject("not_configured", "Set the WebDAV server first")
        val url = WebDAVSecurity.requireSecureUrl(base)
        requireApi(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null, "Use a WebDAV URL without credentials, a query or a fragment")
        return url.newBuilder().apply {
            DataStore.webdavPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        }.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", Credentials.basic(DataStore.webdavUsername.orEmpty(), DataStore.webdavPassword.orEmpty()))

    fun list(): JSONArray {
        val directory = directory()
        val request = request(directory).method("PROPFIND", "".toRequestBody()).header("Depth", "1").build()
        return client.newCall(request).execute().use { response ->
            if (response.code != 207) reject("remote_error", "WebDAV did not return a directory listing")
            val body = response.body.byteStream().use { it.readTextBounded(2L * 1024 * 1024) }
            try {
                JSONArray(WebDAVSecurity.backupFiles(body, directory))
            } catch (e: IllegalArgumentException) {
                reject("invalid_parameters", e.message ?: "Invalid WebDAV listing")
            }
        }
    }

    fun upload(content: JSONObject): JSONObject {
        val directory = directory()
        client.newCall(request(directory).method("MKCOL", "".toRequestBody()).build()).execute().use {
            if (it.code !in setOf(200, 201, 204, 405)) reject("remote_error", "Could not create the WebDAV backup directory")
        }
        val name = backupFileName().removeSuffix(".json") + ".zip"
        val bytes = encodeBackupArchive(content.toString(), BackupArchiveFormat.ZIP)
        val url = directory.newBuilder().addPathSegment(name).build()
        client.newCall(request(url).header("If-None-Match", "*").put(bytes.toRequestBody("application/zip".toMediaType())).build()).execute().use {
            if (!it.isSuccessful) reject("remote_error", "Could not upload the WebDAV backup")
        }
        return JSONObject().put("name", name).put("bytes", bytes.size)
    }

    fun download(name: String): JSONObject {
        requireApi(WebDAVSecurity.isBackupName(name), "Invalid backup filename")
        val url = directory().newBuilder().addPathSegment(name).build()
        return client.newCall(request(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) reject("remote_error", "Could not download the WebDAV backup")
            val bytes = response.body.byteStream().use { it.readBytesBounded() }
            val content = if (name.endsWith(".zip")) {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    requireApi(zip.nextEntry?.name?.endsWith(".json") == true, "Invalid backup archive")
                    zip.readTextBounded()
                }
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            JSONObject(content)
        }
    }
}

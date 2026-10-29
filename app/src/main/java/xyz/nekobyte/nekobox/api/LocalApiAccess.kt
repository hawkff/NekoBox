package xyz.nekobyte.nekobox.api

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.readBytesBounded
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

internal object LocalApiAccess {
    private const val FILE_NAME = "local-api.json"

    /** Where a new port comes from: clear of well-known services, every one a valid listener. */
    private val PORTS = 20000..60000
    private const val CLIPBOARD_MILLIS = 60_000L
    private val random = SecureRandom()

    data class Config(val enabled: Boolean, val port: Int, val token: String)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    fun read(context: Context): Config? {
        val input = try {
            file(context).openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        return input.use { input ->
            val bytes = input.readBytesBounded(4096)
            parse(String(bytes, Charsets.UTF_8))
        }
    }

    fun parse(text: String): Config {
        val json = JSONObject(text)
        require(json.keysSet() == setOf("enabled", "port", "token")) { "Invalid API configuration" }
        val port = json.integer("port")
        val token = json.text("token")
        require(port in 1024..65535 && token.matches(Regex("[0-9a-f]{64}"))) { "Invalid API configuration" }
        return Config(json.flag("enabled"), port.toInt(), token)
    }

    /** A new token on a free random port: a probe of one fixed port cannot spot the API. Binds sockets, so not on the main thread. */
    fun fresh() = Config(true, freePort(), ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) })

    private fun freePort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        repeat(20) {
            val port = PORTS.first + random.nextInt(PORTS.last - PORTS.first + 1)
            if (runCatching { ServerSocket(port, 1, loopback).close() }.isSuccess) return port
        }
        return ServerSocket(0, 1, loopback).use { it.localPort }
    }

    @Synchronized
    fun write(context: Context, config: Config) {
        val file = file(context)
        val text = JSONObject().put("enabled", config.enabled).put("port", config.port).put("token", config.token).toString()
        parse(text)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    fun sync(context: Context) = runOnDefaultDispatcher {
        val app = context.applicationContext
        val enabled = runCatching { read(app)?.enabled == true }.getOrDefault(false)
        onMainDispatcher {
            if (enabled) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, LocalApiService::class.java))
                } catch (_: IllegalStateException) {
                    Toast.makeText(app, R.string.local_api_start_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                app.stopService(Intent(app, LocalApiService::class.java))
            }
        }
    }

    internal fun saveFromUi(activity: Activity, config: Config) = runOnDefaultDispatcher {
        val app = activity.applicationContext
        val saved = runCatching { write(app, config) }.isSuccess
        if (saved) sync(app).join()
        onMainDispatcher {
            if (saved || activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
            Toast.makeText(activity, R.string.local_api_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private class Copy(val label: String, val timestamp: Long, val at: Long)

    // shortcut: kept in memory only, so a killed app leaves the token on the clipboard; persist it if that matters.
    private var copied: Copy? = null

    /** Copies the token, marked sensitive, and clears it a minute later if it is still there. */
    fun copyToken(context: Context, token: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val label = context.getString(R.string.local_api_token_label)
        val clip = ClipData.newPlainText(label, token)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        val timestamp = if (Build.VERSION.SDK_INT >= 26) clipboard.primaryClipDescription?.timestamp ?: 0 else 0
        copied = Copy(label, timestamp, SystemClock.elapsedRealtime())
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ clearCopiedToken(app) }, CLIPBOARD_MILLIS)
    }

    /**
     * Clears the copied token once its minute is up, if it is still the clipboard's clip. Android shows the
     * clipboard only to the app in focus, so a minute that ends elsewhere waits for the app's next focus. The
     * check reads the clip's label and time, never its text, so Android shows no paste notice.
     */
    fun clearCopiedToken(context: Context) {
        val copy = copied ?: return
        if (SystemClock.elapsedRealtime() - copy.at < CLIPBOARD_MILLIS) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val description = clipboard.primaryClipDescription ?: return
        copied = null
        val ours = description.label?.toString() == copy.label && (Build.VERSION.SDK_INT < 26 || description.timestamp == copy.timestamp)
        if (!ours) return
        if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

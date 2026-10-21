package xyz.nekobyte.nekobox.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.onMainDispatcher
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher
import java.io.File
import java.io.FileInputStream

object SendLog {
    // Bounds what the report carries, so it stays readable before it is shared.
    private const val MAX_LOGCAT_LINES = "1000"
    private const val MAX_NEKO_LOG_BYTES = 64L * 1024

    /**
     * Shows the problem report with servers, names, keys and addresses replaced, and shares that text
     * only if the user agrees. Like Mullvad's problem reports, nothing leaves the device unread.
     */
    fun review(activity: Activity, title: String, onDone: () -> Unit = {}) {
        runOnDefaultDispatcher {
            val report = try {
                redact(rawReport(), profileSecrets())
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher { onDone() }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher onDone()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.problem_report_title)
                    .setMessage(activity.getString(R.string.problem_report_note) + "\n\n" + report)
                    .setPositiveButton(R.string.problem_report_share) { _, _ -> share(activity, title, report) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { onDone() }
                    .show()
            }
        }
    }

    private fun rawReport() = buildString {
        append(CrashHandler.buildReportHeader())
        append("Logcat:\n\n")
        try {
            val process = ProcessBuilder("logcat", "-d", "-t", MAX_LOGCAT_LINES).start()
            append(process.inputStream.bufferedReader().use { it.readText() })
            process.waitFor()
        } catch (e: Exception) {
            append("Export logcat error: ").append(e.javaClass.simpleName)
        }
        append("\n\n")
        // The in-app Logs screen renders the colors; a shared report is plain text.
        append(AnsiLog.strip(String(getNekoLog(MAX_NEKO_LOG_BYTES))))
    }

    private fun share(context: Context, title: String, report: String) {
        val file = File.createTempFile("$title ", ".log", File(app.cacheDir, "log").also { it.mkdirs() })
        file.writeText(report)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", file)),
                context.getString(R.string.abc_shareactionprovider_share_with),
            ),
        )
    }

    // Bean fields whose values can name a server, an account or a person.
    private val SECRET_FIELD = Regex("(?i)server|address|host|sni|uuid|password|passwd|key|secret|token|name|path|user|psk|seed|url|link|peer|endpoint|auth|obfs|id$")

    /** Values stored in profiles and groups that a report must not carry. */
    internal fun profileSecrets(): Set<String> {
        val values = HashSet<String>()
        for (profile in ProfileDatabase.proxyDao.getAll()) {
            val bean = runCatching { profile.requireBean() }.getOrNull() ?: continue
            for (field in bean.javaClass.fields) {
                if (field.type == String::class.java && SECRET_FIELD.containsMatchIn(field.name)) (field.get(bean) as? String)?.let(values::add)
            }
        }
        for (group in ProfileDatabase.groupDao.allGroups()) {
            group.name?.let(values::add)
            group.subscription?.let { listOfNotNull(it.link, it.backupLinks, it.customDnsResolver).forEach(values::add) }
        }
        return values.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private val URL = Regex("""\b[a-z][a-z0-9+.-]*://\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)*\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val UUID = Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
    private val IPV6 = Regex("""(?<![\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\w:])""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val TOKEN = Regex("""[A-Za-z0-9+/_-]{24,}={0,2}""")
    private val DOMAIN = Regex("""\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,23}\b""", RegexOption.IGNORE_CASE)

    // Dotted names that are code, not hosts: packages, Go module paths and file names.
    private val CODE_PREFIXES = listOf(
        "xyz.nekobyte.", "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.", "com.android.",
        "com.google.", "io.sentry.", "com.posthog.", "okhttp3.", "okio.", "libcore.", "sun.", "org.json.", "go.",
    )
    private val FILE_SUFFIXES = setOf("go", "kt", "java", "log", "so", "txt", "json", "xml", "db", "conf", "yaml", "yml", "apk", "aar", "dex")

    /**
     * Replaces [secrets] and anything that looks like a URL, an email, a UUID, an IP address, a key or
     * a host name. Over-redacting is the safe side: the report helps less, but it leaks nothing.
     */
    internal fun redact(text: String, secrets: Collection<String>): String {
        var result = text
        // Stored values are replaced as whole tokens, so a username such as "user" leaves "userdebug" alone.
        for (secret in secrets.sortedByDescending { it.length }) {
            if (secret in result) result = Regex("(?<![A-Za-z0-9])" + Regex.escape(secret) + "(?![A-Za-z0-9])").replace(result, "<redacted>")
        }
        result = URL.replace(result, "<url>")
        result = EMAIL.replace(result, "<email>")
        result = UUID.replace(result, "<uuid>")
        // A time such as 12:34:56 has two colons and no "::"; an IPv6 address has more or a "::".
        result = IPV6.replace(result) { if ("::" in it.value || it.value.count { c -> c == ':' } >= 3) "<ip>" else it.value }
        result = IPV4.replace(result, "<ip>")
        // Base64 keys mix upper and lower case with digits, hex keys are long runs of hex digits; versions,
        // paths and code names are neither.
        result = TOKEN.replace(result) { token ->
            val value = token.value.trimEnd('=')
            val base64 = value.any(Char::isUpperCase) && value.any(Char::isLowerCase) && value.any(Char::isDigit) && value.count { it == '/' } <= 2
            val hex = value.length >= 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (base64 || hex) "<key>" else token.value
        }
        return DOMAIN.replace(result) { match ->
            val value = match.value
            val next = result.getOrNull(match.range.last + 1)
            val code = next == '/' || next == '(' || CODE_PREFIXES.any { value.startsWith(it, ignoreCase = true) } ||
                value.substringAfterLast('.').lowercase() in FILE_SUFFIXES
            if (code) value else "<host>"
        }
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray = try {
        val file = File(
            NekoBox.application.cacheDir,
            "neko.log",
        )
        val len = file.length()
        val stream = FileInputStream(file)
        if (max in 1 until len) {
            stream.skip(len - max) // TODO string?
        }
        stream.use { it.readBytes() }
    } catch (e: Exception) {
        e.stackTraceToString().toByteArray()
    }
}

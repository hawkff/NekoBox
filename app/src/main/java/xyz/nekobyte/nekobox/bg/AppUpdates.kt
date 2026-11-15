package xyz.nekobyte.nekobox.bg

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import libcore.Libcore
import org.json.JSONObject
import xyz.nekobyte.nekobox.APP_UPDATE_NOTIFICATION_CHANNEL
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.database.preference.OnPreferenceDataStoreChangeListener
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.isPreview
import xyz.nekobyte.nekobox.ui.MainActivity
import java.util.concurrent.TimeUnit

/** A release on GitHub, with the APK for this device when the release has one with a SHA-256 digest. */
data class AppRelease(val version: String, val notes: String, val page: String, val apk: ReleaseApk?)

data class ReleaseApk(val url: String, val sha256: String)

private val VERSION = Regex("""\d{1,9}(\.\d{1,9})*""")
private val SHA256_DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

/** Whether [release] is a later version than [current], number by number; a name that is no dotted number is not. */
internal fun isNewerVersion(release: String, current: String): Boolean {
    val (newer, older) = listOf(release, current).map { version -> version.removePrefix("v").takeIf { it.matches(VERSION) } ?: return false }
        .map { version -> version.split('.').map(String::toInt) }
    for (i in 0 until maxOf(newer.size, older.size)) {
        val order = newer.getOrElse(i) { 0 }.compareTo(older.getOrElse(i) { 0 })
        if (order != 0) return order > 0
    }
    return false
}

/** A preview build is behind any release of another name; a release build, behind a later version. */
internal fun releaseIsNewer(version: String, current: String = BuildConfig.VERSION_NAME, preview: Boolean = isPreview) = if (preview) version.isNotBlank() && version != current else isNewerVersion(version, current)

/** The hex SHA-256 of a GitHub asset's "digest", or null when it names none. */
internal fun sha256OfDigest(digest: String?): String? = digest?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

/** The release in GitHub's [json], with the APK of the first of [abis] it has one for, and that one only with a digest. */
internal fun parseRelease(json: JSONObject, abis: List<String>): AppRelease {
    val assets = json.optJSONArray("assets")
    val apks = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") }
    val asset = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.getString("name").endsWith("-$abi.apk") } }
    return AppRelease(
        version = json.getString("name"),
        notes = plainNotes(json.optString("body")),
        page = json.getString("html_url"),
        apk = asset?.let { apk -> sha256OfDigest(apk.optString("digest"))?.let { ReleaseApk(apk.getString("browser_download_url"), it) } },
    )
}

/** Release notes as plain text for the update dialog: Markdown marks and link targets go, long notes are cut at a line. */
internal fun plainNotes(markdown: String, max: Int = 1500): String {
    val text = markdown.lineSequence().filterNot { it.startsWith("**Full Changelog**") }.joinToString("\n")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return if (text.length <= max) text else text.take(max).substringBeforeLast('\n') + "\n…"
}

/** Home's banner shows the release found newer than this build until it is dismissed for that release. */
internal fun updateBannerShown(available: String, dismissed: String, current: String = BuildConfig.VERSION_NAME, preview: Boolean = isPreview) = available.isNotEmpty() && available != dismissed && releaseIsNewer(available, current, preview)

object AppUpdates : OnPreferenceDataStoreChangeListener {
    const val ACTION_OFFER = "xyz.nekobyte.nekobox.OFFER_UPDATE"
    const val NOTIFICATION_TAG = "appUpdate"
    internal const val WORK_NAME = "AppUpdateCheck"
    private const val LATEST = "https://api.github.com/repos/hawkff/NekoBox/releases/latest"

    /**
     * The latest release when it is newer than this build, which is then remembered for Home's banner; null when it
     * is not. Like the app's other own requests it goes through the running proxy when there is one. Not for the main
     * thread.
     */
    fun fetchNewerRelease(): AppRelease? {
        val client = Libcore.newHttpClient().apply { throughRunningProxy() }
        val response = client.newRequest().apply { setURL(LATEST) }.execute()
        val release = parseRelease(JSONObject(response.getContentStringLimited(10L * 1024 * 1024)), Build.SUPPORTED_ABIS.toList())
        if (!releaseIsNewer(release.version)) return null
        DataStore.updateAvailable = release.version
        return release
    }

    // Store builds update through their store: a GitHub release cannot replace them.
    val checksReleases = BuildConfig.FLAVOR == "oss" || isPreview

    /** Checks daily while the setting is on, from the main process, where WorkManager runs. */
    fun schedule(context: Context = app): Operation {
        val workManager = WorkManager.getInstance(context)
        if (!DataStore.checkUpdates || !checksReleases) return workManager.cancelUniqueWork(WORK_NAME)
        // Kept as scheduled: every start of the app would otherwise move the next check a day away.
        return workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(Check::class.java, 1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.CHECK_UPDATES) schedule()
    }

    /** Opens the app on the update dialog. */
    fun offerIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).setAction(ACTION_OFFER).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class Check(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!DataStore.checkUpdates) return Result.success()
            val release = try {
                fetchNewerRelease()
            } catch (e: Exception) {
                Logs.w(e)
                return Result.retry()
            }
            // Once per release: a refused notification leaves the banner and About to show it.
            if (release != null && release.version != DataStore.updateNotified) {
                DataStore.updateNotified = release.version
                notify(release)
            }
            return Result.success()
        }

        private fun notify(release: AppRelease) {
            val context = applicationContext
            val offer = offerIntent(context)
            val notification = NotificationCompat.Builder(context, APP_UPDATE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(context.getString(R.string.update_dialog_title))
                .setContentText(context.getString(R.string.app_update_available, release.version))
                .setContentIntent(offer)
                .addAction(R.drawable.ic_update, context.getString(R.string.app_update_action), offer)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).post(0, notification, NOTIFICATION_TAG)
        }
    }
}

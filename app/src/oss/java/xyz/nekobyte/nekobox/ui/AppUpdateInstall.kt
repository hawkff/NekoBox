package xyz.nekobyte.nekobox.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.AppRelease
import xyz.nekobyte.nekobox.bg.ReleaseApk
import xyz.nekobyte.nekobox.bg.throughRunningProxy
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.dp2px
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnIoDispatcher
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher
import java.io.File
import java.security.MessageDigest

/** What decides whether an APK may replace the installed app. */
internal data class PackageIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** An update is the same package, a later version code, and signed by exactly the installed app's signers. */
internal fun isUpdateOf(apk: PackageIdentity, installed: PackageIdentity) = apk.packageName == installed.packageName &&
    apk.versionCode > installed.versionCode &&
    apk.signers.isNotEmpty() &&
    apk.signers == installed.signers

/**
 * The GitHub release build downloads the release's APK for this device, checks it and installs it through
 * PackageInstaller. Debug builds and builds under another application id open the release page instead.
 */
object AppUpdateInstall {
    val inApp = !BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "xyz.nekobyte.nekobox"

    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000

    fun start(activity: ComponentActivity, release: AppRelease) {
        val apk = release.apk
        if (!inApp || apk == null) return activity.openReleasePage(release)
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether this app may install others; the download starts when the user comes back.
            lateinit var allowed: ActivityResultLauncher<Intent>
            allowed = activity.activityResultRegistry.register("appUpdateInstalls", ActivityResultContracts.StartActivityForResult()) {
                allowed.unregister()
                if (activity.packageManager.canRequestPackageInstalls()) download(activity, apk)
            }
            allowed.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
            return
        }
        download(activity, apk)
    }

    private fun download(activity: ComponentActivity, apk: ReleaseApk) {
        lateinit var job: Job
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.app_update_downloading)
            .setView(LinearProgressIndicator(activity).apply { isIndeterminate = true; setPadding(dp2px(24), dp2px(16), dp2px(24), 0) })
            .setNegativeButton(android.R.string.cancel) { _, _ -> job.cancel() }
            .setCancelable(false)
            .show()
        job = runOnIoDispatcher {
            val file = File(app.cacheDir, "app-update.apk")
            val failure = try {
                Libcore.newHttpClient().apply {
                    setTimeoutMs(DOWNLOAD_TIMEOUT_MS)
                    throughRunningProxy()
                }.newRequest().apply { setURL(apk.url) }.execute().writeToLimited(file.path, MAX_APK_BYTES)
                ensureActive()
                if (verified(file, apk.sha256)) {
                    install(file)
                    null
                } else {
                    app.getString(R.string.app_update_rejected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            } finally {
                // The session holds its own copy once written.
                file.delete()
            }
            runOnMainDispatcher {
                if (!activity.isDestroyed) progress.dismiss()
                failure?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun verified(file: File, sha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } != sha256) return false
        val packageManager = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = packageManager.getPackageArchiveInfo(file.path, flags) ?: return false
        return isUpdateOf(archive.identity(), packageManager.getPackageInfo(app.packageName, flags).identity())
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity() = PackageIdentity(
        packageName,
        PackageInfoCompat.getLongVersionCode(this),
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().map { it.toCharsString() }.toSet(),
    )

    private fun install(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Once this app installed itself, later updates may go without Android's prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // Mutable: the installer adds the status and the confirmation intent.
                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val status = Intent(app, AppUpdateStatusReceiver::class.java)
                session.commit(PendingIntent.getBroadcast(app, id, status, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}

/** Asks the user to confirm the install when Android wants that, and reports a failed install. */
class AppUpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
}

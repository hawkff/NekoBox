package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.AppRelease
import xyz.nekobyte.nekobox.bg.AppUpdates
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.readableMessage
import xyz.nekobyte.nekobox.ktx.runOnIoDispatcher
import xyz.nekobyte.nekobox.ktx.runOnMainDispatcher

/**
 * Asks GitHub for the latest release and offers it when it is newer than this build, or says there is none. [done] runs
 * on the main thread once the check ends, whatever it found.
 */
fun ComponentActivity.checkForUpdate(done: () -> Unit = {}) = runOnIoDispatcher {
    val found = runCatching { AppUpdates.fetchNewerRelease() }.onFailure { Logs.w(it) }
    runOnMainDispatcher {
        done()
        found.onFailure { Toast.makeText(app, it.readableMessage, Toast.LENGTH_SHORT).show() }
        found.onSuccess { release ->
            when {
                release == null -> Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                // The check may outlive the screen.
                !isFinishing && !isDestroyed -> offerUpdate(release)
            }
        }
    }
}

private fun ComponentActivity.offerUpdate(release: AppRelease) {
    val message = listOfNotNull(
        getString(R.string.update_dialog_message, NekoBox.appVersionNameForDisplay, release.version),
        release.notes.takeIf { it.isNotEmpty() },
        if (AppUpdateInstall.inApp && release.apk != null) getString(R.string.app_update_connection_note) else null,
    ).joinToString("\n\n")
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.update_dialog_title)
        .setMessage(message)
        .setPositiveButton(R.string.app_update_action) { _, _ -> AppUpdateInstall.start(this, release) }
        .setNegativeButton(R.string.no, null)
        .show()
}

fun Context.openReleasePage(release: AppRelease) = startActivity(Intent(Intent.ACTION_VIEW, release.page.toUri()))

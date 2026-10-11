package xyz.nekobyte.nekobox.ui

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.utils.Theme

internal fun Activity.confirmVpnShortcut(@StringRes action: Int, @StringRes message: Int, confirmed: () -> Unit): androidx.appcompat.app.AlertDialog {
    Theme.applyNightTheme()
    val context = ContextThemeWrapper(this, R.style.Theme_NekoBox).also { Theme.apply(it) }
    var accepted = false
    return MaterialAlertDialogBuilder(context)
        .setTitle(R.string.app_name)
        .setMessage(message)
        .setPositiveButton(action) { _, _ ->
            if (!accepted && !isFinishing && !isDestroyed) {
                accepted = true
                confirmed()
            }
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()
}

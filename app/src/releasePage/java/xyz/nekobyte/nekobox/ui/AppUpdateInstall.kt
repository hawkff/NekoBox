package xyz.nekobyte.nekobox.ui

import android.content.Context
import xyz.nekobyte.nekobox.bg.AppRelease

/**
 * The F-Droid, Play and preview builds open the release page: their store or the preview channel updates them, and
 * they hold no permission to install packages.
 */
object AppUpdateInstall {
    const val inApp = false

    fun start(context: Context, release: AppRelease) = context.openReleasePage(release)
}

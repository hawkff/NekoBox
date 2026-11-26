package xyz.nekobyte.nekobox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import xyz.nekobyte.nekobox.R

/**
 * The launcher icon picked in Settings > Appearance: one activity-alias of [MainActivity] per icon in the manifest,
 * exactly one enabled. The package manager keeps the choice; nothing else stores it.
 */
enum class LauncherIcon(
    private val alias: String,
    @StringRes val label: Int,
    @StringRes val summary: Int?,
    @AnyRes val icon: Int,
) {
    CLASSIC("LauncherIconClassic", R.string.launcher_icon_classic, null, R.mipmap.ic_launcher),
    BLACK("LauncherIconBlack", R.string.launcher_icon_black, null, R.mipmap.ic_launcher_black),
    FULL_BLEED("LauncherIconFullBleed", R.string.launcher_icon_full_bleed, R.string.launcher_icon_full_bleed_sum, R.mipmap.ic_launcher_full_bleed),
    NO_PLATE("LauncherIconNoPlate", R.string.launcher_icon_no_plate, R.string.launcher_icon_no_plate_sum, R.mipmap.ic_launcher_no_plate),
    ;

    // Aliases keep the source package's names while the application id differs per build type.
    fun component(context: Context) = ComponentName(context.packageName, "xyz.nekobyte.nekobox.ui.$alias")

    /** Enables this icon's alias before disabling the others, so the app keeps a launcher entry throughout. */
    fun select(context: Context) {
        val packageManager = context.packageManager
        packageManager.setComponentEnabledSetting(component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (other in entries) {
            if (other != this) {
                packageManager.setComponentEnabledSetting(other.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }

    companion object {
        /** The enabled alias; Classic, enabled by the manifest, until another is picked. */
        fun current(context: Context): LauncherIcon = entries.firstOrNull {
            context.packageManager.getComponentEnabledSetting(it.component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: CLASSIC
    }
}

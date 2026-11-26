package xyz.nekobyte.nekobox.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File
import java.io.IOException

/**
 * The launcher icon picked in Settings > Appearance: one activity-alias of [MainActivity] per icon in the manifest,
 * exactly one enabled. The package manager keeps the choice; only a switch under way is noted besides.
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

    /** Whether the launcher shows this icon's alias: Classic's is enabled by the manifest until it is turned off. */
    fun shown(context: Context) = when (context.packageManager.getComponentEnabledSetting(component(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> this == CLASSIC
        else -> false
    }

    /**
     * Enables this icon's alias and disables the others: in one step from Android 13, before that the target first,
     * so the app keeps a launcher entry throughout. The pick is noted first, so a switch cut short (the process
     * killed, a call refused) is finished by [reconcile]; a note that cannot be written leaves the aliases as they
     * are. Switches and reconciling take turns: two of them interleaved before Android 13 could disable every alias.
     * Not on the main thread.
     */
    fun select(context: Context) = synchronized(switching) {
        val pending = pendingFile(context)
        // Written aside, then renamed over the last note: a pick cut short while writing leaves that note whole.
        val written = File(pending.path + ".new")
        written.writeText(name)
        if (!written.renameTo(pending)) {
            written.delete()
            throw IOException("could not note the switch to $name")
        }
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.setComponentEnabledSettings(
                entries.map {
                    PackageManager.ComponentEnabledSetting(
                        it.component(context),
                        if (it == this) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                },
            )
        } else {
            packageManager.setComponentEnabledSetting(component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            // The others go only once this one shows.
            check(shown(context)) { "$name was not enabled" }
            for (other in entries) {
                if (other != this) {
                    packageManager.setComponentEnabledSetting(other.component(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        }
        pending.delete()
    }

    /** [select], false when the switch could not be noted or Android refused it; the next start finishes a refused one. */
    fun trySelect(context: Context) = try {
        select(context)
        true
    } catch (e: IOException) {
        Logs.w("launcher icon", e)
        false
    } catch (e: RuntimeException) {
        Logs.w("launcher icon", e)
        false
    }

    companion object {
        // One lock for every switch and reconciliation, in the app's process (the only one that switches), and for the
        // migration of pinned shortcuts, which reads the alias shown and writes it into them.
        @PublishedApi
        internal val switching = Any()

        /** Runs [block] with no switch under way and none starting until it ends. Not on the main thread. */
        internal inline fun <T> notSwitching(block: () -> T): T = synchronized(switching, block)

        // The icon a switch under way goes to; device-local, so a backup restored elsewhere does not carry it.
        internal fun pendingFile(context: Context) = File(context.noBackupFilesDir, "launcher_icon_pending")

        private fun pending(context: Context) = runCatching { valueOf(pendingFile(context).readText()) }.getOrNull()

        /**
         * The icon the launcher should show: a pick still under way, else the one alias shown, else Classic. Changes
         * nothing, so the picker reads it on the main thread without waiting for a switch; [reconcile] reads it under
         * the lock.
         */
        fun current(context: Context): LauncherIcon = pending(context) ?: entries.filter { it.shown(context) }.singleOrNull() ?: CLASSIC

        /** The one alias the launcher shows; null while a switch is unfinished (none or several shown). */
        fun shownAlias(context: Context): LauncherIcon? = entries.filter { it.shown(context) }.singleOrNull()

        /** Leaves exactly the alias of [current] shown, as after a switch cut short; run off the main thread at start. */
        fun reconcile(context: Context) = synchronized(switching) {
            val kept = current(context)
            if (entries.filter { it.shown(context) } == listOf(kept)) {
                pendingFile(context).delete()
            } else {
                kept.trySelect(context)
            }
        }
    }
}

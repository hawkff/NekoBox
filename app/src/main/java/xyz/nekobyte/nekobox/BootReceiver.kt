package xyz.nekobyte.nekobox

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import xyz.nekobyte.nekobox.bg.NetworkAutomation
import xyz.nekobyte.nekobox.bg.NetworkAutomationService
import xyz.nekobyte.nekobox.bg.SubscriptionUpdater
import xyz.nekobyte.nekobox.database.DataStore
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.app
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }

        // The manifest default is enabled, so an unset component state counts as enabled too.
        var enabled: Boolean
            get() = when (app.packageManager.getComponentEnabledSetting(componentName)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                -> true

                else -> false
            }
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName,
                if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )

        // Boot and app updates restart the service or the network automation watcher.
        val wanted get() = DataStore.persistAcrossReboot || DataStore.networkAutomation

        /**
         * After boot or an app update; not a manual start. A pause a manual stop set stays, and the VPN
         * stays off without its permission or while Android reports another VPN, which can also belong
         * to another user or a work profile.
         */
        fun restore(context: Context) {
            if (DataStore.persistAcrossReboot && DataStore.selectedProxy > 0) {
                val blocked = NetworkAutomation.unattendedStartBlocked()
                if (blocked == null) NekoBox.startService(byUser = false) else Logs.w("start at boot skipped: $blocked")
            }
            NetworkAutomationService.sync(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
        }

        if (!wanted) { // sanity check
            enabled = false
            return
        }

        val unlocked = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false

            // DataStore.directBootAware
            else -> Build.VERSION.SDK_INT < 24 || NekoBox.user.isUserUnlocked
        }
        if (unlocked) restore(context)
    }
}

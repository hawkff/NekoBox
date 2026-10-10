package xyz.nekobyte.nekobox.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.UserManager
import androidx.core.content.getSystemService
import xyz.nekobyte.nekobox.QuickToggleShortcut
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.ktx.runOnDefaultDispatcher

// Pinned profile shortcuts retain explicit component names across app updates.
internal fun migratePinnedProfileShortcuts(context: Context) {
    if (Build.VERSION.SDK_INT < 26 || context.getSystemService<UserManager>()?.isUserUnlocked != true) return
    try {
        val manager = context.getSystemService<ShortcutManager>() ?: return
        val target = ComponentName(context, QuickToggleShortcut::class.java)
        val activity = ComponentName(context, MainActivity::class.java)
        val updates = manager.pinnedShortcuts.mapNotNull { shortcut ->
            if (shortcut.isImmutable || !shortcut.id.startsWith("shortcut-profile-")) return@mapNotNull null
            val profileId = shortcut.id.removePrefix("shortcut-profile-").toLongOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val intent = shortcut.intents?.singleOrNull() ?: return@mapNotNull null
            if (intent.component?.packageName != context.packageName || intent.getLongExtra("profile", -1) != profileId) return@mapNotNull null
            if (intent.component == target && shortcut.activity == activity) return@mapNotNull null
            if (ProfileDatabase.proxyDao.getById(profileId)?.canBuild() != true) return@mapNotNull null
            val label = shortcut.shortLabel ?: return@mapNotNull null
            ShortcutInfo.Builder(context, shortcut.id)
                .setShortLabel(label)
                .apply { shortcut.longLabel?.let { setLongLabel(it) } }
                .setActivity(activity)
                .setIntent(Intent(intent).setComponent(target))
                .build()
        }
        if (updates.isNotEmpty() && manager.updateShortcuts(updates)) {
            manager.enableShortcuts(updates.map { it.id })
        }
    } catch (e: Exception) {
        // Retry on the next UI launch if Android has locked or rate-limited shortcut updates.
        Logs.w("Could not update pinned profile shortcuts", e)
    }
}

class ProfileShortcutMigrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        runOnDefaultDispatcher {
            try {
                migratePinnedProfileShortcuts(context)
            } finally {
                pending.finish()
            }
        }
    }
}

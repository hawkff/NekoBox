package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.getSystemService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.QuickToggleShortcut
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileShortcutsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService<ShortcutManager>()!!
    private val oldTarget get() = ComponentName(context.packageName, "io.example.previous.QuickToggleShortcut")

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        ConfigBuilderTestEnv.io {
            ProfileDatabase.proxyDao.addProxy(
                ProxyEntity(id = 17).putBean(SOCKSBean().apply { initializeDefaultValues() }),
            )
        }
    }

    private fun pin(id: String, profileId: Long = 17L) {
        val shortcut = ShortcutInfo.Builder(context, id)
            .setShortLabel("Saved server")
            .setLongLabel("Saved server connection")
            .setActivity(ComponentName(context.packageName, "io.example.previous.MainActivity"))
            .setIntent(
                Intent(Intent.ACTION_MAIN).setComponent(oldTarget)
                    .putExtra("profile", profileId).putExtra("preserved", "value"),
            )
            .build()
        assertTrue(manager.requestPinShortcut(shortcut, null))
    }

    @Test
    fun migratesPinnedProfileWithoutReplacingItsIdentityOrLabels() {
        pin("shortcut-profile-17")
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        val migrated = manager.pinnedShortcuts.single()
        assertEquals("shortcut-profile-17", migrated.id)
        assertEquals("Saved server", migrated.shortLabel)
        assertEquals("Saved server connection", migrated.longLabel)
        assertEquals(ComponentName(context, QuickToggleShortcut::class.java), migrated.intent!!.component)
        assertEquals(ComponentName(context, MainActivity::class.java), migrated.activity)
        assertEquals(17L, migrated.intent!!.getLongExtra("profile", -1))
        assertEquals("value", migrated.intent!!.getStringExtra("preserved"))
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(1, manager.pinnedShortcuts.size)
    }

    @Test
    fun leavesUnrelatedMissingAndMismatchedProfilesAlone() {
        pin("unrelated")
        pin("shortcut-profile-99", 99)
        pin("shortcut-profile-17", 18)
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(3, manager.pinnedShortcuts.size)
        manager.pinnedShortcuts.forEach { assertEquals(oldTarget, it.intent!!.component) }
    }
}

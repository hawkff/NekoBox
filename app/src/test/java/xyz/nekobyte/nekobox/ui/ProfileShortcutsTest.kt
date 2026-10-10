package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.getSystemService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager
import org.robolectric.util.ReflectionHelpers
import xyz.nekobyte.nekobox.QuickToggleShortcut
import xyz.nekobyte.nekobox.database.ProfileDatabase
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShortcutMigrationShadow::class])
class ProfileShortcutsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService<ShortcutManager>()!!
    private val oldTarget get() = ComponentName(context.packageName, "io.example.previous.QuickToggleShortcut")

    @Before
    fun setUp() {
        ShortcutMigrationShadow.updatesAllowed = true
        ShortcutMigrationShadow.updateAttempts = 0
        ShortcutMigrationShadow.enabledIds = emptyList()
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
    fun retriesEnablingAShortcutWhoseTargetsWereAlreadyUpdated() {
        pin("shortcut-profile-17")
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        // An interrupted migration can update the targets without completing enableShortcuts.
        val updated = manager.pinnedShortcuts.single()
        // The ShortcutManager shadow does not update ShortcutInfo's disabled flag.
        val flags = ReflectionHelpers.getField<Int>(updated, "mFlags")
        val disabled = ReflectionHelpers.getStaticField<Int>(ShortcutInfo::class.java, "FLAG_DISABLED")
        ReflectionHelpers.setField(updated, "mFlags", flags or disabled)
        assertFalse(updated.isEnabled)
        ShortcutMigrationShadow.updatesAllowed = false
        ShortcutMigrationShadow.updateAttempts = 0
        ShortcutMigrationShadow.enabledIds = emptyList()
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(0, ShortcutMigrationShadow.updateAttempts)
        assertEquals(listOf("shortcut-profile-17"), ShortcutMigrationShadow.enabledIds)
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

@Implements(ShortcutManager::class)
class ShortcutMigrationShadow : ShadowShortcutManager() {
    @Implementation
    override fun updateShortcuts(shortcuts: List<ShortcutInfo>): Boolean {
        updateAttempts++
        return updatesAllowed && super.updateShortcuts(shortcuts)
    }

    @Implementation
    override fun enableShortcuts(ids: List<String>) {
        enabledIds = ids.toList()
        super.enableShortcuts(ids)
    }

    companion object {
        var updatesAllowed = true
        var updateAttempts = 0
        var enabledIds = emptyList<String>()
    }
}

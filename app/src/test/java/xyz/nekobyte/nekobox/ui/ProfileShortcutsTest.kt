package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        ShortcutMigrationShadow.beforeUpdate = null
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
        assertEquals(LauncherIcon.CLASSIC.component(context), migrated.activity)
        assertEquals(17L, migrated.intent!!.getLongExtra("profile", -1))
        assertEquals("value", migrated.intent!!.getStringExtra("preserved"))
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(1, manager.pinnedShortcuts.size)
    }

    @Test
    fun aPinnedProfileFollowsTheLauncherIconToEachAlias() {
        pin("shortcut-profile-17")
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        for (icon in listOf(LauncherIcon.BLACK, LauncherIcon.FULL_BLEED, LauncherIcon.CLASSIC)) {
            icon.select(context)
            ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
            val migrated = manager.pinnedShortcuts.single()
            assertEquals(icon.component(context), migrated.activity)
            assertEquals(ComponentName(context, QuickToggleShortcut::class.java), migrated.intent!!.component)
            assertEquals(17L, migrated.intent!!.getLongExtra("profile", -1))
            assertEquals("Saved server", migrated.shortLabel)
        }
    }

    @Test
    fun shortcutsGoOnlyToTheAliasTheLauncherShows() {
        pin("shortcut-profile-17")
        // A switch to Black noted but refused: Classic still shows, and the shortcut goes there.
        LauncherIcon.pendingFile(context).writeText(LauncherIcon.BLACK.name)
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(LauncherIcon.CLASSIC.component(context), manager.pinnedShortcuts.single().activity)
        // Cut short with two aliases shown: the shortcut waits where it is.
        context.packageManager.setComponentEnabledSetting(LauncherIcon.BLACK.component(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        ShortcutMigrationShadow.updateAttempts = 0
        ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        assertEquals(0, ShortcutMigrationShadow.updateAttempts)
        assertEquals(LauncherIcon.CLASSIC.component(context), manager.pinnedShortcuts.single().activity)
        LauncherIcon.pendingFile(context).delete()
    }

    @Test
    fun aSwitchWaitsForAMigrationUnderWay_soAnOlderBatchIsNotTheLast() {
        pin("shortcut-profile-17")
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The older migration read Classic and is about to write it.
        ShortcutMigrationShadow.beforeUpdate = {
            if (paused.count > 0) {
                paused.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val older = thread { ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) } }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        val switch = thread {
            LauncherIcon.BLACK.select(context)
            ConfigBuilderTestEnv.io { migratePinnedProfileShortcuts(context) }
        }
        Thread.sleep(300)
        // The switch waits for it instead of enabling Black under it.
        assertTrue(LauncherIcon.CLASSIC.shown(context) && !LauncherIcon.BLACK.shown(context))
        release.countDown()
        older.join(5_000)
        switch.join(5_000)
        assertEquals(LauncherIcon.BLACK.component(context), manager.pinnedShortcuts.single().activity)
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
        beforeUpdate?.invoke()
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

        // Runs before each update, on the thread that makes it.
        @Volatile var beforeUpdate: (() -> Unit)? = null
    }
}

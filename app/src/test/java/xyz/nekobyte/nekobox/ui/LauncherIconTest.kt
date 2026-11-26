package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherIconTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun launcherEntries() = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        0,
    ).map { it.activityInfo.name }

    @Test
    fun classicIsTheOnlyLauncherEntryUntilAnotherIsPicked() {
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.current(context))
        assertEquals(listOf(LauncherIcon.CLASSIC.component(context).className), launcherEntries())
    }

    @Test
    fun pickingAnIconLeavesOnlyItsAliasEnabled() {
        for (picked in listOf(LauncherIcon.NO_PLATE, LauncherIcon.BLACK, LauncherIcon.CLASSIC)) {
            picked.select(context)
            assertEquals(picked, LauncherIcon.current(context))
            assertEquals(listOf(picked.component(context).className), launcherEntries())
        }
    }

    @Test
    fun everyAliasOpensMainActivityWithItsIconAndTheStaticShortcuts() {
        for (icon in LauncherIcon.entries) {
            val info = context.packageManager.getActivityInfo(
                icon.component(context),
                PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals(MainActivity::class.java.name, info.targetActivity)
            assertEquals(icon.icon, info.icon)
            assertEquals(R.xml.shortcuts, info.metaData.getInt("android.app.shortcuts"))
        }
    }
}

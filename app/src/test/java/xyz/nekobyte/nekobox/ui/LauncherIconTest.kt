package xyz.nekobyte.nekobox.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import androidx.appcompat.content.res.AppCompatResources
import org.junit.After
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
import org.robolectric.shadows.ShadowApplicationPackageManager
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RefusingPackageManagerShadow::class])
class LauncherIconTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val logSink = Logs.sink

    @Before
    fun setUp() {
        RefusingPackageManagerShadow.refuse = false
        RefusingPackageManagerShadow.beforeSet = null
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        RefusingPackageManagerShadow.refuse = false
        RefusingPackageManagerShadow.beforeSet = null
        LauncherIcon.pendingFile(context).deleteRecursively()
        Logs.sink = logSink
    }

    private fun set(icon: LauncherIcon, state: Int) = context.packageManager.setComponentEnabledSetting(icon.component(context), state, PackageManager.DONT_KILL_APP)

    private fun only(icon: LauncherIcon) {
        assertEquals(icon, LauncherIcon.current(context))
        assertEquals(listOf(icon.component(context).className), launcherEntries())
        assertFalse(LauncherIcon.pendingFile(context).exists())
    }

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
    fun aSwitchCutShortFromClassicIsFinishedAtTheNextStart() {
        // The pick is noted and Black enabled, then the process dies: Classic is still enabled by the manifest.
        LauncherIcon.pendingFile(context).writeText(LauncherIcon.BLACK.name)
        set(LauncherIcon.BLACK, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        assertEquals(2, launcherEntries().size)
        assertEquals(LauncherIcon.BLACK, LauncherIcon.current(context))
        LauncherIcon.reconcile(context)
        only(LauncherIcon.BLACK)
    }

    @Test
    fun withoutANoteSeveralAliasesOrNoneLeaveClassic() {
        set(LauncherIcon.BLACK, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        set(LauncherIcon.FULL_BLEED, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.current(context))
        LauncherIcon.reconcile(context)
        only(LauncherIcon.CLASSIC)
        for (icon in LauncherIcon.entries) set(icon, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertTrue(launcherEntries().isEmpty())
        LauncherIcon.reconcile(context)
        only(LauncherIcon.CLASSIC)
    }

    @Test
    fun pickingTheIconShownAgainRepairsTheAliases() {
        LauncherIcon.NO_PLATE.select(context)
        set(LauncherIcon.CLASSIC, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        // Two entries and no note: the picker shows Classic, and picking it again leaves Classic alone.
        assertEquals(LauncherIcon.CLASSIC, LauncherIcon.current(context))
        assertTrue(LauncherIcon.CLASSIC.trySelect(context))
        only(LauncherIcon.CLASSIC)
    }

    @Test
    fun aRefusedSwitchIsReportedAndFinishedAtTheNextStart() {
        RefusingPackageManagerShadow.refuse = true
        assertFalse(LauncherIcon.FULL_BLEED.trySelect(context))
        assertEquals(listOf(LauncherIcon.CLASSIC.component(context).className), launcherEntries())
        // The pick stays noted, so the app shows it and the next start carries it out.
        assertEquals(LauncherIcon.FULL_BLEED, LauncherIcon.current(context))
        RefusingPackageManagerShadow.refuse = false
        LauncherIcon.reconcile(context)
        only(LauncherIcon.FULL_BLEED)
    }

    @Test
    @Config(sdk = [30])
    fun aSwitchAndTheStartReconciliationTakeTurns_andLeaveOneEntry() {
        // Before Android 13 a switch takes a call per alias. The picker stops right before enabling Black, after noting
        // it; the reconciliation at start must wait instead of switching the aliases in between.
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        RefusingPackageManagerShadow.beforeSet = {
            calls += Thread.currentThread().name
            if (Thread.currentThread().name == "picker" && paused.count > 0) {
                paused.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val picker = thread(name = "picker") { LauncherIcon.BLACK.trySelect(context) }
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        val start = thread(name = "start") { LauncherIcon.reconcile(context) }
        Thread.sleep(300)
        assertTrue(calls.none { it == "start" })
        release.countDown()
        picker.join(5_000)
        start.join(5_000)
        only(LauncherIcon.BLACK)
    }

    @Test
    fun aSwitchThatCannotBeNotedLeavesTheAliasesAlone() {
        // A directory where the note goes: writing the note fails with an IOException.
        assertTrue(LauncherIcon.pendingFile(context).mkdirs())
        assertFalse(LauncherIcon.FULL_BLEED.trySelect(context))
        assertEquals(listOf(LauncherIcon.CLASSIC.component(context).className), launcherEntries())
    }

    @Test
    fun aNoteThatCannotBeWrittenLeavesTheLastOneWhole() {
        // A switch to No plate cut short: its note stands. The next pick cannot write its own (a directory is in the way
        // of the file it writes first), so the last note and the aliases stay as they were.
        LauncherIcon.pendingFile(context).writeText(LauncherIcon.NO_PLATE.name)
        val written = File(LauncherIcon.pendingFile(context).path + ".new")
        assertTrue(written.mkdirs())
        try {
            assertFalse(LauncherIcon.BLACK.trySelect(context))
            assertEquals(LauncherIcon.NO_PLATE.name, LauncherIcon.pendingFile(context).readText())
            assertEquals(listOf(LauncherIcon.CLASSIC.component(context).className), launcherEntries())
        } finally {
            written.deleteRecursively()
        }
    }

    @Test
    @Config(sdk = [24])
    fun everyPreviewResolvesBeforeAdaptiveIcons() {
        // The picker's previews on Android 7: each icon has its own PNGs beside the adaptive one.
        for (icon in LauncherIcon.entries) assertTrue(icon.name, AppCompatResources.getDrawable(context, icon.icon) is BitmapDrawable)
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

// Android refusing a component switch, as a call that fails part way does; and a hook into each single switch.
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class RefusingPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation
    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        beforeSet?.invoke()
        if (refuse) throw SecurityException("refused")
        super.setComponentEnabledSetting(componentName, newState, flags)
    }

    @Implementation(minSdk = 33)
    override fun setComponentEnabledSettings(settings: List<PackageManager.ComponentEnabledSetting>) {
        if (refuse) throw SecurityException("refused")
        super.setComponentEnabledSettings(settings)
    }

    companion object {
        @Volatile var refuse = false

        // Runs before each single switch, on the thread that makes it.
        @Volatile var beforeSet: (() -> Unit)? = null
    }
}

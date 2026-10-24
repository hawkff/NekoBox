package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import kotlin.math.roundToInt

/**
 * Every control a screen reader can act on, on each main screen and in the drawer, has a name to
 * read out and a touch target of at least 48 dp. A failure lists each control with where it is.
 */
@RunWith(AndroidJUnit4::class)
class AccessibleControlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyControlHasANameAndRoomToTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val problems = sortedSetOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (screen in SCREENS) {
                scenario.onActivity { it.displayFragmentWithId(screen) }
                settle()
                scenario.onActivity { walk(it.window.decorView, it.resources.getResourceEntryName(screen), problems) }
            }
            scenario.onActivity { it.binding.drawerLayout.open() }
            settle()
            scenario.onActivity { walk(it.navigation, "drawer", problems) }
        }
        assertTrue(problems.joinToString("\n", prefix = "${problems.size} problems:\n"), problems.isEmpty())
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1200)
    }

    private fun walk(view: View, screen: String, problems: MutableSet<String>) {
        if (!view.isShown || view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
        if ((view.isClickable || view.isLongClickable) && view.isEnabled && view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO) {
            if (spoken(view).isBlank()) problems += "$screen: ${describe(view)} has no name"
            val density = view.resources.displayMetrics.density
            val width = (view.width / density).roundToInt()
            val height = (view.height / density).roundToInt()
            if (width < 48 || height < 48) problems += "$screen: ${describe(view)} is $width x $height dp"
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), screen, problems)
    }

    /** What a screen reader says for [view]: its own name, or for a container the names of what it holds. */
    private fun spoken(view: View): String {
        val info = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(view, info)
        val own = listOfNotNull(info.contentDescription, info.text, (view as? TextView)?.hint).joinToString(" ").trim()
        if (own.isNotEmpty() || view !is ViewGroup) return own
        return (0 until view.childCount).map { view.getChildAt(it) }
            .filter { it.isShown && !it.isClickable && it.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            .joinToString(" ") { spoken(it) }.trim()
    }

    private fun describe(view: View): String {
        val id = if (view.id != View.NO_ID) runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() else null
        return listOfNotNull(view.javaClass.simpleName, id?.let { "#$it" }).joinToString(" ")
    }

    private companion object {
        // The traffic screen is a web view and has no controls of the app's own.
        val SCREENS = listOf(
            R.id.nav_configuration,
            R.id.nav_group,
            R.id.nav_route,
            R.id.nav_tailscale,
            R.id.nav_settings,
            R.id.nav_logcat,
            R.id.nav_connections,
            R.id.nav_tools,
            R.id.nav_about,
        )
    }
}

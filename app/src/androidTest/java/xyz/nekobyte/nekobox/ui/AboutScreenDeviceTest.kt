package xyz.nekobyte.nekobox.ui

import android.Manifest
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.widget.CheckCat

/** About reads as a colophon: the version and core, the project's pages, and the cat closing the page. */
@RunWith(AndroidJUnit4::class)
class AboutScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test
    fun thePageNamesWhatTheAppRunsOnAndWhereTheProjectLives() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_about) }
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)
            scenario.onActivity {
                assertEquals(context.getString(R.string.about_edition, NekoBox.appVersionNameForDisplay), it.findViewById<TextView>(R.id.edition).text.toString())
                val core = it.findViewById<TextView>(R.id.core).text.toString()
                assertTrue(core, core.startsWith("sing-box "))
                // Background use is reached from a keyboard too, on every Android version.
                assertTrue(it.findViewById<View>(R.id.background).isFocusable)
                // Each page says it leaves the app.
                for (id in listOf(R.id.source, R.id.releases, R.id.license)) {
                    val page = it.findViewById<Button>(id)
                    assertEquals(context.getString(R.string.about_opens_browser, page.text), page.contentDescription)
                }
                // The cat closes the page, content by its ramen, and TalkBack passes it by.
                val cat = it.findViewById<CheckCat>(R.id.cat)
                assertEquals(CheckCat.Pose.CONTENT, cat.pose)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, cat.importantForAccessibility)
                val scroll = it.findViewById<NestedScrollView>(R.id.about_scroll)
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            // At the end of the page, the cat is in view.
            scenario.onActivity { assertTrue(it.findViewById<CheckCat>(R.id.cat).getGlobalVisibleRect(Rect())) }
        }
    }
}

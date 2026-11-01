package xyz.nekobyte.nekobox.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.PlainInflater

/**
 * Home's rows made ahead come from a thread without a looper and an inflater without AppCompat's factory
 * ([RowWarmup]): they must be the same views, looking the same, as rows the list inflates itself.
 */
@RunWith(AndroidJUnit4::class)
class RowWarmupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rowsMadeOffTheMainThreadMatchTheListsOwn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            for (layout in listOf(R.layout.layout_profile, R.layout.layout_profile_compact, R.layout.layout_profile_section)) {
                var made: View? = null
                var failure: Throwable? = null
                val worker = Thread {
                    try {
                        made = PlainInflater(activity).inflate(layout, FrameLayout(activity), false)
                    } catch (e: Throwable) {
                        failure = e
                    }
                }
                worker.start()
                worker.join()
                assertNull(failure)
                instrumentation.runOnMainSync {
                    val own = LayoutInflater.from(activity).inflate(layout, FrameLayout(activity), false)
                    assertEquals(looks(own), looks(requireNotNull(made)))
                }
            }
        }
    }

    /** Each view's class, padding and, for text, size, color, style and line limit, in tree order. */
    private fun looks(root: View): List<String> = buildList {
        fun walk(view: View) {
            add("${view.javaClass.name} ${view.paddingStart},${view.paddingTop},${view.paddingEnd},${view.paddingBottom} ${view.visibility}")
            if (view is TextView) add("text ${view.textSize} ${view.currentTextColor} ${view.typeface?.style} ${view.maxLines} ${view.lineHeight}")
            if (view is ViewGroup) view.children.forEach(::walk)
        }
        walk(root)
    }
}

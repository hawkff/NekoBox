package xyz.nekobyte.nekobox.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.utils.SendLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Logs screen reads the log off the main thread, where a read can wait for whoever writes or trims the file. So
 * a read can come back after a newer one, or after a clear. What it read is not shown then. That a read coming back
 * after the screen was left shows nothing is the view's lifecycle scope's doing, and not checked here.
 */
@RunWith(AndroidJUnit4::class)
class LogReadsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** One read of the log, which comes back with [text] when the test lets it. */
    private class Read(val text: String) {
        val out = CountDownLatch(1)
        val let = CountDownLatch(1)
        val back = CountDownLatch(1)
    }

    // The reads the screen will make, in their order, and all the test made.
    private val reads = LinkedBlockingQueue<Read>()
    private val made = CopyOnWriteArrayList<Read>()

    @Before
    fun setUp() {
        LogcatFragment.read = {
            val read = reads.take()
            read.out.countDown()
            read.let.await()
            read.back.countDown()
            read.text.toByteArray()
        }
    }

    @After
    fun tearDown() {
        // A test that failed halfway left reads out: they are let back, so none keeps a thread waiting.
        made.forEach { it.let.countDown() }
        LogcatFragment.read = SendLog::getNekoLog
    }

    private fun next(text: String) = Read(text).also {
        made += it
        reads += it
    }

    private fun shown(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { text = (it.currentScreen() as LogcatFragment).binding.textview.text.toString() }
        return text
    }

    private fun choose(scenario: ActivityScenario<MainActivity>, item: Int) = scenario.onActivity {
        val screen = it.currentScreen() as LogcatFragment
        screen.onMenuItemClick(screen.toolbar.menu.findItem(item))
    }

    private fun awaitShown(scenario: ActivityScenario<MainActivity>, text: String) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (shown(scenario) != text) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for the screen to show \"$text\": it shows \"${shown(scenario)}\"" }
            SystemClock.sleep(100)
        }
    }

    // The read is let back, and for a while after it the screen still shows what it showed.
    private fun letBackUnshown(scenario: ActivityScenario<MainActivity>, late: Read, text: String) {
        late.let.countDown()
        assertTrue(late.back.await(40, TimeUnit.SECONDS))
        repeat(10) {
            instrumentation.waitForIdleSync()
            assertEquals("what the late read \"${late.text}\" came back with was shown", text, shown(scenario))
            SystemClock.sleep(50)
        }
    }

    @Test
    fun aReadThatComesBackLate_isNotShownOverANewerReadOrAClear() {
        val first = next("first")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.displayFragmentWithId(R.id.nav_logcat) }
            assertTrue(first.out.await(40, TimeUnit.SECONDS))

            // A refresh while the first read is out: the newer read is the one shown, whichever comes back last.
            val second = next("second")
            choose(scenario, R.id.action_refresh)
            assertTrue(second.out.await(40, TimeUnit.SECONDS))
            second.let.countDown()
            awaitShown(scenario, "second")
            letBackUnshown(scenario, first, "second")

            // A clear while a read is out: the screen stays empty.
            val third = next("third")
            choose(scenario, R.id.action_refresh)
            assertTrue(third.out.await(40, TimeUnit.SECONDS))
            choose(scenario, R.id.action_clear_logcat)
            awaitShown(scenario, "")
            letBackUnshown(scenario, third, "")
        }
    }
}

package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuildResult
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv

/** The service's side of a test through its core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RunningPathTest {
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    private fun config(fingerprint: String? = "path-7") = ConfigBuildResult("{}", emptyList(), 7, emptyMap(), mapOf(7L to "wg-7"), -1, paths = mapOf(7L to ConfigBuildResult.PathSnapshot(fingerprint, listOf("wg-7"))))

    private open inner class Core : RunningCore {
        override var running = true

        override fun awaitTailscaleNodes(profileId: Long) {
            calls += "await $profileId"
        }

        override fun urlTest(tag: String): Int {
            calls += "test $tag"
            return 42
        }

        override fun wireGuardStatus(tag: String): String = """{"peers":[]}"""

        override fun fetchText(tag: String, link: String): String {
            calls += "fetch $tag $link"
            return "ip=203.0.113.7"
        }
    }

    @Test
    fun aMixedPathWaitsForItsTailscaleNodesJustAfterTheConnect() {
        var ready = false
        val core = object : Core() {
            override fun awaitTailscaleNodes(profileId: Long) {
                super.awaitTailscaleNodes(profileId)
                // The node finishes its login and exit selection while the service waits.
                ready = true
            }

            override fun urlTest(tag: String): Int {
                check(ready) { "Timeout" }
                return super.urlTest(tag)
            }
        }
        val reply = testRunningPath(config(), core, 7, "path-7", "https://trace.example/")
        assertEquals("tested", reply.getString("result"))
        assertEquals(42, reply.getInt("latencyMs"))
        // The Proxies check reads its answer through the same outbound.
        assertEquals("ip=203.0.113.7", reply.getString("answer"))
        assertEquals(listOf("await 7", "test wg-7", "fetch wg-7 https://trace.example/"), calls)
    }

    @Test
    fun onlyThePathTheCoreRunsIsTestedThroughIt() {
        assertEquals("other_settings", testRunningPath(config(), Core(), 7, "path-8", "").getString("result"))
        assertEquals("other_settings", testRunningPath(config(fingerprint = null), Core(), 7, "", "").getString("result"))
        assertEquals("not_running", testRunningPath(config(), Core(), 8, "path-7", "").getString("result"))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aFailedTestSaysWhetherTheCoreStoppedUnderIt() {
        val failing = object : Core() {
            override fun urlTest(tag: String): Int = error("Get \"http://10.0.0.1/\": EOF")
        }
        val failed = testRunningPath(config(), failing, 7, "path-7", "")
        assertEquals("failed", failed.getString("result"))
        assertEquals("Get \"http://10.0.0.1/\": EOF", failed.getString("error"))
        failing.running = false
        assertEquals("stopping", testRunningPath(config(), failing, 7, "path-7", "").getString("result"))
    }
}

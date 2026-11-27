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
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardIdentities

/** The service's side of a test through its core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RunningPathTest {
    private val calls = mutableListOf<String>()
    private val now = 1795700000000L

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    // Profile 7 runs on its own WireGuard outbound; profile 8 is a selector member behind the shared front hop "front".
    private fun config(fingerprint: String? = "path-7") = ConfigBuildResult(
        "{}",
        emptyList(),
        7,
        emptyMap(),
        mapOf(7L to "wg-7", 8L to "socks-8"),
        -1,
        wireGuardIdentities = WireGuardIdentities(mapOf("wg-7" to setOf("a-b"), "front" to setOf("c-d"))),
        paths = mapOf(
            7L to ConfigBuildResult.PathSnapshot(fingerprint, listOf("wg-7")),
            8L to ConfigBuildResult.PathSnapshot("path-8", listOf("socks-8", "front")),
        ),
    )

    private open inner class Core : RunningCore {
        override var running = true
        var clock = now
        val sent = mutableMapOf<String, Long>()

        override fun awaitTailscaleNodes(profileId: Long) {
            calls += "await $profileId"
        }

        override fun urlTest(tag: String): TestOutcome {
            calls += "test $tag"
            return TestOutcome(42, "", false)
        }

        override fun wireGuardStatus(tag: String): String {
            calls += "status $tag"
            return """{"peers":[{"publicKey":"k-$tag","endpoint":"192.0.2.1:51820","lastHandshake":0,"rxBytes":0,"txBytes":${sent[tag] ?: 0}}]}"""
        }

        override fun fetchText(tag: String, link: String): String {
            calls += "fetch $tag $link"
            return "ip=203.0.113.7"
        }

        override fun now() = clock

        override fun sleep(ms: Long) {
            calls += "sleep $ms"
            clock += ms
        }
    }

    // A test that runs out of time after [elapsed] ms; the dead server's outbound [tag] retries its handshake once
    // the core has looked at the path for HANDSHAKE_RETRY_WINDOW_MS.
    private inner class DeadServer(private val tag: String, private val elapsed: Long) : Core() {
        override fun urlTest(tag: String): TestOutcome {
            calls += "test $tag"
            clock += elapsed
            return TestOutcome(0, "Get \"http://10.0.0.1/timeout\": context deadline exceeded", true)
        }

        override fun sleep(ms: Long) {
            super.sleep(ms)
            sent[tag] = 148
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

            override fun urlTest(tag: String): TestOutcome {
                check(ready) { "Timeout" }
                return super.urlTest(tag)
            }
        }
        val reply = testRunningPath(config(), core, 7, "path-7", "https://trace.example/")
        assertEquals("tested", reply.getString("result"))
        assertEquals(42, reply.getInt("latencyMs"))
        // The Proxies check reads its answer through the same outbound.
        assertEquals("ip=203.0.113.7", reply.getString("answer"))
        assertEquals(listOf("await 7", "status wg-7", "test wg-7", "fetch wg-7 https://trace.example/"), calls)
    }

    @Test
    fun onlyThePathTheCoreRunsIsTestedThroughIt() {
        assertEquals("other_settings", testRunningPath(config(), Core(), 7, "path-8", "").getString("result"))
        assertEquals("other_settings", testRunningPath(config(fingerprint = null), Core(), 7, "", "").getString("result"))
        assertEquals("not_running", testRunningPath(config(), Core(), 9, "path-7", "").getString("result"))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aTimeoutOnAServerThatSendsAndHearsNothingIsANoHandshakeFailure() {
        // The test ran out of time after 3 s; the retry window takes the rest of 5.5 s.
        assertEquals("no_handshake", testRunningPath(config(), DeadServer("wg-7", 3_000), 7, "path-7", "").getString("result"))
        assertEquals("sleep 2500", calls.single { it.startsWith("sleep") })
    }

    @Test
    fun aSelectorMemberBehindASharedDeadHopGetsTheReasonToo() {
        // The front hop's outbound was built for another member first; it is on this member's path all the same.
        assertEquals("no_handshake", testRunningPath(config(), DeadServer("front", 3_000), 8, "path-8", "").getString("result"))
        assertEquals(listOf("status front"), calls.filter { it.startsWith("status") }.distinct())
    }

    @Test
    fun aFailureWithAReasonOfItsOwnKeepsIt() {
        val refused = object : Core() {
            override fun urlTest(tag: String) = TestOutcome(0, "Get \"http://10.99.0.1/timeout\": destination 10.99.0.1 is outside the allowed IPs", false)
        }
        refused.sent["wg-7"] = 148
        val reply = testRunningPath(config(), refused, 7, "path-7", "")
        assertEquals("failed", reply.getString("result"))
        assertEquals("Get \"http://10.99.0.1/timeout\": destination 10.99.0.1 is outside the allowed IPs", reply.getString("error"))
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("sleep") })
        refused.running = false
        assertEquals("stopping", testRunningPath(config(), refused, 7, "path-7", "").getString("result"))
    }
}

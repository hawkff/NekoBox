package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import java.io.File

/** A WireGuard session is held by one core at a time, whichever of a test and the VPN takes it first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WireGuardLeaseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val originalDirectory = WireGuardLease.directory
    private lateinit var directory: File
    private val session = setOf("aa-bb")

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        directory = temporary.newFolder()
        WireGuardLease.directory = { directory }
    }

    @After
    fun tearDown() {
        WireGuardLease.directory = originalDirectory
    }

    @Test
    fun aVpnStartedWhileATestHoldsTheSessionWaitsForTheTestsCoreToClose() = runTest {
        // The test saw the VPN stopped and took the session; then the VPN starts.
        val test = WireGuardLease.tryAcquire(session)!!
        val vpn = async { WireGuardLease.acquire(session, 60_000) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(vpn.isCompleted)
        test.close()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(vpn.isCompleted)
        // Now the VPN's: a test finds the session held.
        vpn.await().use { assertNull(WireGuardLease.tryAcquire(session)) }
    }

    @Test
    fun aRestartThatStoppedFirstStillWaitsForATestThatTookTheGap() = runTest {
        val old = WireGuardLease.tryAcquire(session)!!
        assertNull(WireGuardLease.tryAcquire(session))
        // The old core closed and the service published Stopped; a test takes the session before the new start.
        old.close()
        val test = WireGuardLease.tryAcquire(session)!!
        val restart = async { WireGuardLease.acquire(session, 60_000) }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(restart.isCompleted)
        test.close()
        advanceTimeBy(200)
        runCurrent()
        restart.await().close()
    }

    @Test
    fun aVpnStartGivesUpOnATestThatOutlivesItsBound() = runTest {
        WireGuardLease.tryAcquire(session)!!.use {
            val failure = runCatching { WireGuardLease.acquire(session, 0) }.exceptionOrNull()
            assertTrue(failure is LeaseBusyException)
        }
    }
}

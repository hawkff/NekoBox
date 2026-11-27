package xyz.nekobyte.nekobox.bg.proto

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.ProxyEntity
import xyz.nekobyte.nekobox.fmt.ConfigBuilderTestEnv
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardBean
import xyz.nekobyte.nekobox.fmt.wireguard.WireGuardIdentities
import xyz.nekobyte.nekobox.ktx.app
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

    @Test
    fun aVpnStartDoesNotTakeASessionFreedAfterItsBound() = runBlocking {
        // On the clock: the bound is 20 ms, the test's core closes at 60 ms, before the next 100 ms round would end.
        val test = WireGuardLease.tryAcquire(session)!!
        val closing = launch(Dispatchers.Default) {
            delay(60)
            test.close()
        }
        val outcome = runCatching { WireGuardLease.acquire(session, 20) }
        closing.join()
        outcome.getOrNull()?.close()
        assertTrue(outcome.exceptionOrNull() is LeaseBusyException)
    }

    @Test
    fun aVpnWhoseSessionsCannotAllBeReadDoesNotStart() = runTest {
        val vpn = ProxyInstance(ProxyEntity(id = 1).putBean(WireGuardBean().apply { initializeDefaultValues() }))
        // The readable session is free, but the config has an entry whose keys cannot be read.
        val partly = WireGuardIdentities(session, true, mapOf("w" to session))
        val refusal = runCatching { vpn.leaseWireGuard(partly) }.exceptionOrNull()
        assertTrue(refusal is IllegalStateException)
        assertEquals(app.getString(R.string.vpn_wireguard_unknown_key), refusal?.message)
        // Outbounds that are no list of objects: a configuration error, not a key's.
        val malformed = runCatching { vpn.leaseWireGuard(WireGuardIdentities(emptySet(), true, emptyMap(), malformed = true)) }.exceptionOrNull()
        assertEquals(app.getString(R.string.wireguard_config_unreadable), malformed?.message)
        // Nothing was taken on the way.
        WireGuardLease.tryAcquire(session)!!.close()
        vpn.leaseWireGuard(WireGuardIdentities(session, false, mapOf("w" to session)))!!.close()
    }
}

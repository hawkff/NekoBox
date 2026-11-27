package xyz.nekobyte.nekobox.bg.proto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A WireGuard session another process holds, as the VPN's core in :bg holds it against a test in the app's process. */
class WireGuardLeaseProcessTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val originalDirectory = WireGuardLease.directory

    @After
    fun tearDown() {
        WireGuardLease.directory = originalDirectory
    }

    @Test
    fun aCoreInAnotherProcessHoldsTheSession() {
        val directory = temporary.newFolder()
        WireGuardLease.directory = { directory }
        val fixture = ProcessLockFixture::class.java
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            File(requireNotNull(requireNotNull(fixture.protectionDomain).codeSource).location.toURI()).path,
            fixture.name,
            File(directory, "aa-bb.lock").path,
            "hold",
        ).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            assertEquals("acquired", reader.submit<String> { process.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS))
            assertNull(WireGuardLease.tryAcquire(setOf("aa-bb")))
            // Other sessions stay free.
            assertNotNull(WireGuardLease.tryAcquire(setOf("aa-cc"))?.also { it.close() })
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            WireGuardLease.tryAcquire(setOf("aa-bb"))!!.close()
        } finally {
            process.destroyForcibly()
            reader.shutdownNow()
        }
    }
}

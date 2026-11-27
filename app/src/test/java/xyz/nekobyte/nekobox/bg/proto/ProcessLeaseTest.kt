package xyz.nekobyte.nekobox.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProcessLeaseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun duplicateReservationDoesNotOpenAndCloseAnotherDescriptor() {
        val directory = temporary.newFolder()
        lease(directory, 1).use {
            assertNotNull(runCatching { lease(directory, 1) }.exceptionOrNull())
            // A second descriptor closed by a failed acquisition would drop the first POSIX lock.
            assertEquals("busy", probe(directory, 1))
        }
        assertEquals("acquired", probe(directory, 1))
        assertTrue(File(directory, "1.lock").isFile)
    }

    @Test
    fun partialAcquisitionIsReleasedAndOtherNodesRemainIndependent() {
        val directory = temporary.newFolder()
        lease(directory, 2).use {
            assertNotNull(runCatching { lease(directory, 1, 2, 3) }.exceptionOrNull())
            lease(directory, 1, 3).use {
                assertEquals("busy", probe(directory, 1))
                assertEquals("busy", probe(directory, 2))
            }
        }
        lease(directory, 3, 2, 1, 1).use { }
    }

    @Test
    fun separateProcessOwnerPreventsAcquisitionUntilItsClose() {
        val directory = temporary.newFolder()
        val process = process(directory, 7, hold = true)
        val reader = Executors.newSingleThreadExecutor()
        try {
            val ready = reader.submit<String> { process.inputStream.bufferedReader().readLine() }
            assertEquals("acquired", ready.get(10, TimeUnit.SECONDS))
            assertNotNull(runCatching { lease(directory, 7) }.exceptionOrNull())
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            lease(directory, 7).use { }
        } finally {
            process.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private fun lease(directory: File, vararg ids: Long) = ProcessLease.acquire(directory, ids.map { "$it" }) { "$it busy" }

    private fun probe(directory: File, id: Long): String {
        val process = process(directory, id)
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return process.inputStream.bufferedReader().readText().trim()
        } finally {
            process.destroyForcibly()
        }
    }

    private fun process(directory: File, id: Long, hold: Boolean = false): Process {
        val fixture = ProcessLockFixture::class.java
        return ProcessBuilder(
            listOf(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                File(requireNotNull(requireNotNull(fixture.protectionDomain).codeSource).location.toURI()).path,
                fixture.name,
                File(directory, "$id.lock").path,
            ) + if (hold) listOf("hold") else emptyList(),
        ).redirectErrorStream(true).start()
    }
}

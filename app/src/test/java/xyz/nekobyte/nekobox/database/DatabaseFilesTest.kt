package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.nekobyte.nekobox.bg.proto.TailscaleLockProcess
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DatabaseFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val suffixes = listOf("-journal", "-wal", "-shm")

    private fun source() = folder.newFile("legacy.db").apply {
        writeText("rows")
        suffixes.forEach { File(path + it).writeText("recovery$it") }
    }

    private fun assertSourceIntact(from: File) {
        assertEquals("rows", from.readText())
        suffixes.forEach { assertEquals("recovery$it", File(from.path + it).readText()) }
    }

    private fun assertMigrated(from: File, to: File) {
        assertEquals("rows", to.readText())
        assertFalse(from.exists())
        suffixes.forEach {
            assertEquals("recovery$it", File(to.path + it).readText())
            assertFalse(File(from.path + it).exists())
        }
    }

    @Test
    fun movesTheDatabaseWithAllRecoveryFiles() {
        val from = source()
        val to = File(folder.root, "profiles.db")
        moveDatabaseFiles(from, to)
        assertMigrated(from, to)
    }

    @Test
    fun keepsAnExistingTargetAndItsJournal() {
        val from = source()
        val to = folder.newFile("profiles.db").apply { writeText("new") }
        File(to.path + "-wal").writeText("new writes")
        moveDatabaseFiles(from, to)
        assertEquals("new", to.readText())
        assertEquals("new writes", File(to.path + "-wal").readText())
        assertSourceIntact(from)
    }

    @Test
    fun failedMainFileRenameLeavesTheOldDatabaseUsableAndCanRetry() {
        val from = source()
        val to = File(folder.root, "profiles.db")
        val failingSource = object : File(from.path) {
            override fun renameTo(dest: File) = false
        }
        assertThrows(IOException::class.java) { moveDatabaseFiles(failingSource, to) }
        assertFalse(to.exists())
        assertSourceIntact(from)
        moveDatabaseFiles(from, to)
        assertMigrated(from, to)
    }

    @Test
    fun failedJournalCopyNeverPublishesTheDatabaseAndCanRetry() {
        val from = source()
        val to = File(folder.root, "profiles.db")
        val blocked = File(to.path + "-wal").apply { mkdir() }
        assertThrows(IOException::class.java) { moveDatabaseFiles(from, to) }
        assertFalse(to.exists())
        assertSourceIntact(from)
        assertTrue(blocked.delete())
        File(to.path + "-journal").writeText("interrupted copy")
        moveDatabaseFiles(from, to)
        assertMigrated(from, to)
    }

    @Test
    fun refusesAnUnmatchedTargetJournal() {
        val from = folder.newFile("legacy.db").apply { writeText("rows") }
        val to = File(folder.root, "profiles.db")
        File(to.path + "-wal").writeText("unrelated recovery data")
        assertThrows(IOException::class.java) { moveDatabaseFiles(from, to) }
        assertFalse(to.exists())
        assertEquals("rows", from.readText())
    }

    @Test
    fun concurrentOpensPublishOneCompleteDatabase() {
        val from = source()
        val to = File(folder.root, "profiles.db")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val opens = (1..4).map {
                executor.submit {
                    start.await()
                    moveDatabaseFiles(from, to)
                    assertEquals("rows", to.readText())
                    suffixes.forEach { assertEquals("recovery$it", File(to.path + it).readText()) }
                }
            }
            start.countDown()
            opens.forEach { it.get(10, TimeUnit.SECONDS) }
            assertMigrated(from, to)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun anotherProcessKeepsTheMigrationBlockedUntilItsLockIsReleased() {
        val from = source()
        val to = File(folder.root, "profiles.db")
        val fixture = TailscaleLockProcess::class.java
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            File(checkNotNull(checkNotNull(fixture.protectionDomain).codeSource).location.toURI()).path,
            fixture.name,
            File(folder.root, "${to.name}.migration.lock").path,
            "hold",
        ).redirectErrorStream(true).start()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val ready = executor.submit<String> { process.inputStream.bufferedReader().readLine() }
            assertEquals("acquired", ready.get(10, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val migration = executor.submit {
                started.countDown()
                moveDatabaseFiles(from, to)
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { migration.get(200, TimeUnit.MILLISECONDS) }
            assertSourceIntact(from)
            assertFalse(to.exists())
            process.outputStream.write(1)
            process.outputStream.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            migration.get(10, TimeUnit.SECONDS)
            assertMigrated(from, to)
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsRecoveryFilesWithoutADatabase() {
        val from = File(folder.root, "legacy.db")
        val to = File(folder.root, "profiles.db")
        File(from.path + "-wal").writeText("recovery")
        assertThrows(IOException::class.java) { moveDatabaseFiles(from, to) }
        assertFalse(to.exists())
    }

    @Test
    fun rejectsATargetDirectory() {
        val from = source()
        val to = folder.newFolder("profiles.db")
        assertThrows(IOException::class.java) { moveDatabaseFiles(from, to) }
        assertSourceIntact(from)
    }

    @Test
    fun ignoresAMissingSourceOnFreshInstall() {
        val to = File(folder.root, "profiles.db")
        moveDatabaseFiles(File(folder.root, "legacy.db"), to)
        assertFalse(to.exists())
    }
}

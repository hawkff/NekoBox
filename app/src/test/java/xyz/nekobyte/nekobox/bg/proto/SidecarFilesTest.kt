package xyz.nekobyte.nekobox.bg.proto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.nekobyte.nekobox.database.ProxyEntity
import java.util.concurrent.Executors

class SidecarFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun parallelWritersNeverShareFilesOrDeleteAnotherInstancesFiles() {
        val instances = List(50) { object : BoxInstance(ProxyEntity()) {} }
        val executor = Executors.newFixedThreadPool(5)
        try {
            val files = instances.mapIndexed { index, instance ->
                executor.submit<java.io.File> {
                    instance.writeSidecarFile(temporary.root, "naive-", ".json", "profile-$index")
                }
            }.map { it.get() }
            assertEquals(files.size, files.toSet().size)
            files.forEachIndexed { index, file -> assertEquals("profile-$index", file.readText()) }
            instances.first().close()
            assertFalse(files.first().exists())
            assertTrue(files.drop(1).all { it.isFile })
        } finally {
            executor.shutdownNow()
            instances.forEach { it.close() }
        }
    }
}

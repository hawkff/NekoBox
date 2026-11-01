package xyz.nekobyte.nekobox.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.nekobyte.nekobox.ktx.MAX_BACKUP_BYTES
import java.util.zip.ZipInputStream

class BackupArchiveEncodingTest {

    @Test
    fun json_returnsUnwrappedUtf8Bytes() {
        val json = "{\"version\":2}"

        val encoded = encodeBackupArchive(json, BackupArchiveFormat.JSON)

        assertArrayEquals(json.toByteArray(Charsets.UTF_8), encoded)
    }

    @Test
    fun aBackupOverTheRestoreLimitIsRefusedInEitherFormat() {
        // Two bytes a character in UTF-8: the limit counts bytes. The zipped copy is tiny, and still refused.
        val atLimit = "é".repeat((MAX_BACKUP_BYTES / 2).toInt())
        for (format in BackupArchiveFormat.entries) {
            encodeBackupArchive(atLimit, format)
            assertThrows(BackupTooLargeException::class.java) { encodeBackupArchive(atLimit + "a", format) }
        }
    }

    @Test
    fun zip_containsExpectedEntryAndContent() {
        val json = "{\"profiles\":[]}"

        val encoded = encodeBackupArchive(json, BackupArchiveFormat.ZIP)

        val (entryName, content) = unzip(encoded)
        assertEquals("nekobox_backup.json", entryName)
        assertEquals(json, content)
    }

    @Test
    fun nonAsciiJson_preservesUtf8() {
        val json = "{\"name\":\"€ café\"}"

        val encoded = encodeBackupArchive(json, BackupArchiveFormat.JSON)

        assertArrayEquals(json.toByteArray(Charsets.UTF_8), encoded)
        assertEquals(json, encoded.toString(Charsets.UTF_8))
    }

    @Test
    fun emptyJsonObject_preservesBothFormats() {
        val json = encodeBackupArchive("{}", BackupArchiveFormat.JSON)
        val zip = encodeBackupArchive("{}", BackupArchiveFormat.ZIP)

        assertArrayEquals("{}".toByteArray(Charsets.UTF_8), json)
        val (entryName, content) = unzip(zip)
        assertEquals("nekobox_backup.json", entryName)
        assertEquals("{}", content)
    }

    @Test
    fun concurrentJsonAndZipCalls_keepTheirRequestedFormats() = runTest {
        val json = "{\"value\":\"concurrent\"}"
        val results = (0 until 64).map { index ->
            async(Dispatchers.Default) {
                val format = if (index % 2 == 0) BackupArchiveFormat.JSON else BackupArchiveFormat.ZIP
                format to encodeBackupArchive(json, format)
            }
        }.awaitAll()

        results.forEach { (format, encoded) ->
            when (format) {
                BackupArchiveFormat.JSON -> assertArrayEquals(json.toByteArray(Charsets.UTF_8), encoded)
                BackupArchiveFormat.ZIP -> assertEquals(json, unzip(encoded).second)
            }
        }
    }

    private fun unzip(encoded: ByteArray) = ZipInputStream(encoded.inputStream()).use { zip ->
        val entry = requireNotNull(zip.nextEntry)
        val content = zip.readBytes().toString(Charsets.UTF_8)
        zip.closeEntry()
        assertNull(zip.nextEntry)
        entry.name to content
    }
}

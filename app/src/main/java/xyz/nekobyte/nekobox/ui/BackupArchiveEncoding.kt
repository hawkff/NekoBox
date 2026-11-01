package xyz.nekobyte.nekobox.ui

import android.text.format.Formatter
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.MAX_BACKUP_BYTES
import xyz.nekobyte.nekobox.ktx.app
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class BackupArchiveFormat {
    JSON,
    ZIP,
}

/** A backup larger than a restore reads: refused when it is made, not found unrestorable later. */
internal class BackupTooLargeException(private val size: Long) : IOException("Backup is $size bytes, over the $MAX_BACKUP_BYTES-byte restore limit") {
    override fun getLocalizedMessage(): String = app.getString(R.string.backup_too_large, Formatter.formatFileSize(app, size), Formatter.formatFileSize(app, MAX_BACKUP_BYTES))
}

internal fun encodeBackupArchive(jsonContent: String, format: BackupArchiveFormat): ByteArray {
    val json = jsonContent.toByteArray(Charsets.UTF_8)
    // A restore reads the JSON, zipped or not, up to MAX_BACKUP_BYTES.
    if (json.size > MAX_BACKUP_BYTES) throw BackupTooLargeException(json.size.toLong())
    return when (format) {
        BackupArchiveFormat.JSON -> json

        BackupArchiveFormat.ZIP -> ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                zip.putNextEntry(
                    ZipEntry("nekobox_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    },
                )
                zip.write(json)
                zip.closeEntry()
                zip.finish()
            }
            output.toByteArray()
        }
    }
}

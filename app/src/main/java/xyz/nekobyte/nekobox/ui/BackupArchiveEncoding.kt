package xyz.nekobyte.nekobox.ui

import android.text.format.Formatter
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.ktx.MAX_BACKUP_BYTES
import xyz.nekobyte.nekobox.ktx.app
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class BackupArchiveFormat {
    JSON,
    ZIP,
}

internal fun encodeBackupArchive(jsonContent: String, format: BackupArchiveFormat): ByteArray {
    val json = jsonContent.toByteArray(Charsets.UTF_8)
    // A restore reads the JSON, zipped or not, up to MAX_BACKUP_BYTES: a larger backup would save and never load.
    check(json.size <= MAX_BACKUP_BYTES) {
        app.getString(R.string.backup_too_large, Formatter.formatFileSize(app, json.size.toLong()), Formatter.formatFileSize(app, MAX_BACKUP_BYTES))
    }
    return encode(json, format)
}

private fun encode(json: ByteArray, format: BackupArchiveFormat) = when (format) {
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

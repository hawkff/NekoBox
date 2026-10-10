package xyz.nekobyte.nekobox.database

import android.content.Context
import xyz.nekobyte.nekobox.Key
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private val legacyDatabaseNames = mapOf(
    Key.DB_PROFILE to "sager_net.db",
    Key.DB_PUBLIC to "configuration.db",
)
private val journalSuffixes = listOf("-journal", "-wal", "-shm")

internal fun Context.databaseFile(name: String): File {
    val target = getDatabasePath(name)
    val directory = checkNotNull(target.parentFile)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create database directory")
    legacyDatabaseNames[name]?.let { moveDatabaseFiles(getDatabasePath(it), target) }
    return target
}

// Called before Room opens either name. The monitor covers threads; the file lock covers processes.
@Synchronized
internal fun moveDatabaseFiles(from: File, to: File) {
    RandomAccessFile(File(to.parentFile, "${to.name}.migration.lock"), "rw").use { lockFile ->
        lockFile.channel.lock().use {
            if (to.exists()) {
                if (!to.isFile) throw IOException("Database target is not a file: ${to.name}")
                return
            }
            if (!from.exists()) {
                if (journalSuffixes.any { File(from.path + it).exists() || File(to.path + it).exists() }) {
                    throw IOException("Database recovery files exist without a database")
                }
                return
            }
            if (!from.isFile) throw IOException("Database source is not a file: ${from.name}")

            // Copy recovery data first, leaving the old database usable if copying or renaming fails.
            // A retry replaces incomplete copies while the target database is still absent.
            for (suffix in journalSuffixes) {
                val source = File(from.path + suffix)
                val target = File(to.path + suffix)
                if (source.exists()) {
                    source.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                } else if (target.exists()) {
                    throw IOException("Unmatched database recovery file: ${target.name}")
                }
            }
            // Both names share a directory. Publishing the main file is the atomic commit point.
            if (!from.renameTo(to)) throw IOException("Cannot rename database: ${from.name}")

            // Leftover source journals are harmless and excluded from Android backups if removal fails.
            journalSuffixes.forEach { File(from.path + it).delete() }
        }
    }
}

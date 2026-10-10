package xyz.nekobyte.nekobox.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseFileUpgradeTest {
    @get:Rule
    val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    private fun row(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.rawQuery("SELECT value FROM records", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    @Test
    fun renamedSqliteDatabaseKeepsItsRows() {
        val source = File(folder.root, "legacy.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.execSQL("CREATE TABLE records(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO records VALUES ('saved profile')")
        }
        val target = File(folder.root, "profiles.db")
        moveDatabaseFiles(source, target)
        assertEquals("saved profile", row(target))
        assertFalse(source.exists())
        moveDatabaseFiles(source, target)
        assertEquals("saved profile", row(target))
    }

    private fun walSnapshot(): File {
        val writer = File(folder.root, "writer.db")
        val snapshot = File(folder.root, "legacy.db")
        SQLiteDatabase.openOrCreateDatabase(writer, null).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE records(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO records VALUES ('committed WAL row')")
            // No writes run during the copy. The snapshot represents a process exiting without a checkpoint.
            writer.copyTo(snapshot)
            for (suffix in listOf("-wal", "-shm")) File(writer.path + suffix).copyTo(File(snapshot.path + suffix))
            assertTrue(File(snapshot.path + "-wal").length() > 0)
        }
        return snapshot
    }

    @Test
    fun committedWalRowsSurviveTheFilenameChange() {
        val source = walSnapshot()
        val target = File(folder.root, "profiles.db")
        moveDatabaseFiles(source, target)
        assertEquals("committed WAL row", row(target))
        assertFalse(source.exists())
        assertFalse(File(source.path + "-wal").exists())
    }

    @Test
    fun renameFailureLeavesTheOldDatabaseAndWalReadable() {
        val source = walSnapshot()
        val failingSource = object : File(source.path) {
            override fun renameTo(dest: File) = false
        }
        val target = File(folder.root, "profiles.db")
        assertThrows(IOException::class.java) { moveDatabaseFiles(failingSource, target) }
        assertFalse(target.exists())
        assertEquals("committed WAL row", row(source))
    }
}

package xyz.nekobyte.nekobox.database

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileArchiveMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ProfileDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesPayloadsOrderingStatisticsAndSubscriptionState() {
        val name = "archive-migration-preservation"
        val before = helper.createDatabase(name, 12).use { db ->
            seed(db)
            snapshot(db)
        }
        helper.runMigrationsAndValidate(name, 13, true, ProfileArchiveMigration).use { db ->
            val after = snapshot(db)
            assertEquals(before[1], after[1])
            assertEquals(before[2], after[2])
            val columns = mapOf(7L to "trojanGoBean", 25L to "masterDnsVpnBean", 27L to "olcrtcBean")
            val expected = before[0].map { row ->
                row.toMutableMap().apply {
                    val payload = columns[row["type"]]?.let(row::get)
                    columns.values.forEach(::remove)
                    put("archivedData", payload)
                }
            }
            assertEquals(expected, after[0])
            db.query("PRAGMA index_info('groupId')").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("groupId", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertFalse(cursor.moveToNext())
            }
            assertEquals(1001L, db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, profileValues(0).apply { remove("id") }))
        }
    }

    @Test
    fun emptyDatabaseMigratesWithoutChangingOtherTables() {
        val name = "archive-migration-empty"
        helper.createDatabase(name, 12).close()
        helper.runMigrationsAndValidate(name, 13, true, ProfileArchiveMigration).use { db ->
            assertTrue(snapshot(db).all { it.isEmpty() })
            assertEquals(1L, db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, profileValues(0).apply { remove("id") }))
        }
    }

    @Test
    fun mismatchedPayloadRejectsMigrationWithoutDroppingData() {
        val name = "archive-migration-mismatch"
        val before = helper.createDatabase(name, 12).use { db ->
            seed(db)
            db.execSQL("UPDATE proxy_entities SET olcrtcBean = X'010203' WHERE id = 1")
            snapshot(db)
        }
        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(name, 13, true, ProfileArchiveMigration)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(12, db.version)
            val after = listOf("proxy_entities", "proxy_groups", "rules").map { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY id", null).use(::rows)
            }
            assertEquals(before, after)
        }
    }

    @Test
    fun failureAfterTableReplacementRollsBackEveryRow() {
        helper.createDatabase("archive-migration-rollback", 12).use { db ->
            seed(db)
            val before = snapshot(db)
            val failing = object : SupportSQLiteDatabase by db {
                override fun execSQL(sql: String) {
                    if (sql.startsWith("ALTER TABLE")) error("injected migration failure")
                    db.execSQL(sql)
                }
            }
            db.beginTransaction()
            try {
                assertThrows(IllegalStateException::class.java) { ProfileArchiveMigration.migrate(failing) }
            } finally {
                db.endTransaction()
            }
            assertEquals(before, snapshot(db))
            db.query("SELECT name FROM sqlite_master WHERE name = 'proxy_entities_archive_migration'").use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        val columns = mapOf(0 to "socksBean", 7 to "trojanGoBean", 25 to "masterDnsVpnBean", 27 to "olcrtcBean")
        columns.entries.forEachIndexed { index, (type, column) ->
            val values = profileValues(type).apply {
                put("id", index + 1L)
                put(column, ByteArray(5000 + index) { (it * 31 + index).toByte() })
            }
            db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, values)
        }
        db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, profileValues(0).apply { put("id", 1000L) })
        db.execSQL("DELETE FROM proxy_entities WHERE id = 1000")
        db.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,subscription,`order`,isSelector,frontProxy,landingProxy) VALUES (1,9,0,'Example group',1,X'0102030405',0,1,-1,-1)")
        db.execSQL("INSERT INTO rules (id,name,config,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,ruleset,outbound,packages) VALUES (1,'Example rule','{}',3,1,'example.invalid','','','','','','','',-1,'')")
    }

    private fun profileValues(type: Int) = ContentValues().apply {
        put("id", 1L)
        put("groupId", 1L)
        put("type", type)
        put("userOrder", 100L - type)
        put("tx", 123L + type)
        put("rx", 456L + type)
        put("lifetimeTx", 9007199254740993L + type)
        put("lifetimeRx", 9007199254741993L + type)
        put("status", 1)
        put("ping", 42)
        put("uuid", "example-profile-$type")
        put("error", "stored result")
    }

    private fun snapshot(db: SupportSQLiteDatabase) = listOf("proxy_entities", "proxy_groups", "rules").map { table ->
        db.query("SELECT * FROM $table ORDER BY id").use(::rows)
    }

    private fun rows(cursor: Cursor) = buildList {
        while (cursor.moveToNext()) {
            add(
                cursor.columnNames.withIndex().associate { (index, name) ->
                    name to when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        else -> cursor.getString(index)
                    }
                },
            )
        }
    }
}

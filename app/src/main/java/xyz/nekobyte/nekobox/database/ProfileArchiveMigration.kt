package xyz.nekobyte.nekobox.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object ProfileArchiveMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val previousColumns = mapOf(7 to "trojanGoBean", 25 to "masterDnsVpnBean", 27 to "olcrtcBean")
        for ((type, column) in previousColumns) {
            db.query("SELECT 1 FROM proxy_entities WHERE `$column` IS NOT NULL AND type != $type LIMIT 1").use {
                check(!it.moveToFirst()) { "Cannot migrate mismatched archived profile data" }
            }
        }
        val sequence = db.query("SELECT seq FROM sqlite_sequence WHERE name = 'proxy_entities'").use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        db.execSQL(
            """CREATE TABLE proxy_entities_archive_migration (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                groupId INTEGER NOT NULL, type INTEGER NOT NULL, userOrder INTEGER NOT NULL,
                tx INTEGER NOT NULL, rx INTEGER NOT NULL,
                lifetimeRx INTEGER NOT NULL DEFAULT 0, lifetimeTx INTEGER NOT NULL DEFAULT 0,
                status INTEGER NOT NULL, ping INTEGER NOT NULL, uuid TEXT NOT NULL, error TEXT,
                socksBean BLOB, httpBean BLOB, ssBean BLOB, ssrBean BLOB, vmessBean BLOB,
                trojanBean BLOB, mieruBean BLOB, naiveBean BLOB, hysteriaBean BLOB,
                tuicBean BLOB, juicityBean BLOB, sshBean BLOB, wgBean BLOB,
                shadowTLSBean BLOB, anyTLSBean BLOB, chainBean BLOB, configBean BLOB,
                snellBean BLOB, awgBean BLOB, archivedData BLOB
            )
            """.trimIndent(),
        )
        val columns = "id,groupId,type,userOrder,tx,rx,lifetimeRx,lifetimeTx,status,ping,uuid,error," +
            "socksBean,httpBean,ssBean,ssrBean,vmessBean,trojanBean,mieruBean,naiveBean,hysteriaBean," +
            "tuicBean,juicityBean,sshBean,wgBean,shadowTLSBean,anyTLSBean,chainBean,configBean,snellBean,awgBean"
        db.execSQL(
            "INSERT INTO proxy_entities_archive_migration ($columns,archivedData) " +
                "SELECT $columns,CASE type " +
                previousColumns.entries.joinToString(" ") { (type, column) -> "WHEN $type THEN `$column`" } +
                " ELSE NULL END FROM proxy_entities",
        )
        db.execSQL("DROP TABLE proxy_entities")
        db.execSQL("ALTER TABLE proxy_entities_archive_migration RENAME TO proxy_entities")
        db.execSQL("CREATE INDEX IF NOT EXISTS groupId ON proxy_entities (groupId)")
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = 'proxy_entities'", arrayOf(sequence))
        db.execSQL(
            "INSERT INTO sqlite_sequence (name,seq) SELECT 'proxy_entities',? " +
                "WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'proxy_entities')",
            arrayOf(sequence),
        )
    }
}

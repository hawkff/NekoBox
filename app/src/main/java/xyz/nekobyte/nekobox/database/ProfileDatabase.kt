package xyz.nekobyte.nekobox.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.nekobyte.nekobox.BuildConfig
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.fmt.KryoConverters

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 16,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11, spec = ProfileDatabase.RemoveNekoColumn::class),
        // v12: additive lifetimeRx/lifetimeTx columns on proxy_entities (default 0). Pure column
        // adds are auto-migratable without a spec; never destructive.
        AutoMigration(from = 11, to = 12),
        // v14: additive proxy_groups.autoSelect column (default 0).
        AutoMigration(from = 13, to = 14),
        // v15: additive proxy_entities.tailscaleBean column (nullable BLOB).
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
    ],
)
@TypeConverters(KryoConverters::class)
abstract class ProfileDatabase : RoomDatabase() {

    @DeleteColumn(tableName = "proxy_entities", columnName = "nekoBean")
    class RemoveNekoColumn : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Legacy neko-plugin rows are non-functional placeholders; without the
            // bean column they could no longer even render. Purge them.
            db.execSQL("DELETE FROM proxy_entities WHERE type = 999")
        }
    }

    companion object {
        val instance by lazy {
            val file = NekoBox.application.databaseFile(Key.DB_PROFILE)
            Room.databaseBuilder(NekoBox.application, ProfileDatabase::class.java, file.name)
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(ProfileArchiveMigration)
                // Plan 027 Stage 3: the main-thread-DB allowance is behind a build flag so it can
                // be removed once the app runs StrictMode-clean (debug already ships with it off).
                .apply { if (BuildConfig.ALLOW_MAIN_THREAD_DB) allowMainThreadQueries() }
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()
    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao
}

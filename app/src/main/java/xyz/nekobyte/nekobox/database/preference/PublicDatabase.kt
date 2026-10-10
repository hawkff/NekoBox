package xyz.nekobyte.nekobox.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import xyz.nekobyte.nekobox.Key
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.database.DbExecutors
import xyz.nekobyte.nekobox.database.databaseFile

@Database(entities = [KeyValuePair::class], version = 1)
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            val file = NekoBox.application.databaseFile(Key.DB_PUBLIC)
            Room.databaseBuilder(NekoBox.application, PublicDatabase::class.java, file.name)
                .setJournalMode(JournalMode.TRUNCATE)
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val kvPairDao get() = instance.keyValuePairDao()

        /** Exposed for the cached [RoomPreferenceDataStore] to observe `KeyValuePair`
         *  invalidations (cross-process refresh). */
        val database get() = instance
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao
}

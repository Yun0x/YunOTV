package com.yuno.tv.core.database

import android.content.Context
import androidx.room.*

@Database(entities = [SourceEntity::class, ConfigCacheEntity::class, HistoryEntity::class,
    FavoriteEntity::class, SearchHistoryEntity::class, LiveFavoriteEntity::class, LiveRecentEntity::class],
    version = 2, exportSchema = true)
abstract class YunoDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun caches(): CacheDao
    abstract fun library(): LibraryDao
    companion object {
        fun create(context: Context, name: String = "yuno-tv.db"): YunoDatabase = Room.databaseBuilder(
            context.applicationContext, YunoDatabase::class.java, name
        ).addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE config_cache ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE config_cache ADD COLUMN url TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE config_cache ADD COLUMN contentType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE config_cache ADD COLUMN lastAttemptTime INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE config_cache ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE config_cache ADD COLUMN latencyMs INTEGER NOT NULL DEFAULT 0")
            }
        }).build()
    }
}

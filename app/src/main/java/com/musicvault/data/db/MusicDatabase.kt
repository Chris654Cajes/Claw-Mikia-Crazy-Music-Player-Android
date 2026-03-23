package com.musicvault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.musicvault.data.model.Song

@Database(entities = [Song::class], version = 3, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        // Migration 1 → 2: no-op (was intermediate, never shipped cleanly)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add online metadata columns (safe: IF NOT EXISTS avoids duplicate-column crash)
                db.execSQL("ALTER TABLE songs ADD COLUMN albumName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN albumArtUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN metadataFetched INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 2 → 3: same columns for devices that got v2 without them
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumName TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumArtUrl TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN metadataFetched INTEGER NOT NULL DEFAULT 0") }
            }
        }

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_vault.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
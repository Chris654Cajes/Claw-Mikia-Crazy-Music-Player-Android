package com.mochimochi.clawmikia.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mochimochi.clawmikia.data.model.*

@Database(
    entities = [
        Song::class,
        PlaybackProfile::class,
        SkipRegion::class,
        LyricLine::class,
        LyricsMeta::class,
        EqPreset::class,
        WaveformCache::class,
        PlaybackHistory::class,
        SongAnalysis::class,
        Playlist::class,
        PlaylistSong::class
    ],
    version = 7,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playbackProfileDao(): PlaybackProfileDao
    abstract fun skipRegionDao(): SkipRegionDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun eqPresetDao(): EqPresetDao
    abstract fun waveformCacheDao(): WaveformCacheDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun songAnalysisDao(): SongAnalysisDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumName TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumArtUrl TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN metadataFetched INTEGER NOT NULL DEFAULT 0") }
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumName TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN albumArtUrl TEXT NOT NULL DEFAULT ''") }
                runCatching { db.execSQL("ALTER TABLE songs ADD COLUMN metadataFetched INTEGER NOT NULL DEFAULT 0") }
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS playback_profiles (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL DEFAULT 0, pitchSemitones REAL NOT NULL DEFAULT 0.0, playbackSpeed REAL NOT NULL DEFAULT 1.0, trimStart INTEGER NOT NULL DEFAULT 0, trimEnd INTEGER NOT NULL DEFAULT -1, loopStart INTEGER NOT NULL DEFAULT -1, loopEnd INTEGER NOT NULL DEFAULT -1, loopEnabled INTEGER NOT NULL DEFAULT 0, abRepeatA INTEGER NOT NULL DEFAULT -1, abRepeatB INTEGER NOT NULL DEFAULT -1, abRepeatEnabled INTEGER NOT NULL DEFAULT 0, eqBands TEXT NOT NULL DEFAULT '0,0,0,0,0,0,0,0,0,0', eqEnabled INTEGER NOT NULL DEFAULT 0, eqPresetName TEXT NOT NULL DEFAULT 'Flat', bassBoostStrength INTEGER NOT NULL DEFAULT 0, bassBoostEnabled INTEGER NOT NULL DEFAULT 0, reverbPreset INTEGER NOT NULL DEFAULT -1, reverbEnabled INTEGER NOT NULL DEFAULT 0, loudnessGain INTEGER NOT NULL DEFAULT 0, loudnessEnabled INTEGER NOT NULL DEFAULT 0, compressorEnabled INTEGER NOT NULL DEFAULT 0, compressorThreshold REAL NOT NULL DEFAULT -18.0, compressorRatio REAL NOT NULL DEFAULT 4.0, compressorAttack REAL NOT NULL DEFAULT 10.0, compressorRelease REAL NOT NULL DEFAULT 100.0, replayGainDb REAL NOT NULL DEFAULT 0.0, replayGainEnabled INTEGER NOT NULL DEFAULT 0, crossfadeDuration INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_songId ON playback_profiles(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS skip_regions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '', startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, isEnabled INTEGER NOT NULL DEFAULT 1, createdAt INTEGER NOT NULL, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_skip_songId ON skip_regions(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS lyric_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL, timeMs INTEGER NOT NULL, text TEXT NOT NULL, isSynced INTEGER NOT NULL DEFAULT 1, lineIndex INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_lyric_songId ON lyric_lines(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS lyrics_meta (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL UNIQUE, source TEXT NOT NULL DEFAULT 'local', isSynced INTEGER NOT NULL DEFAULT 0, fetchedAt INTEGER NOT NULL, rawLrc TEXT NOT NULL DEFAULT '', FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_lyrics_meta_songId ON lyrics_meta(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS eq_presets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, bands TEXT NOT NULL, isBuiltIn INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS waveform_cache (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL UNIQUE, amplitudes TEXT NOT NULL, sampleCount INTEGER NOT NULL, generatedAt INTEGER NOT NULL, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_waveform_songId ON waveform_cache(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS playback_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL, playedAt INTEGER NOT NULL, durationListened INTEGER NOT NULL DEFAULT 0, completedFully INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_songId ON playback_history(songId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_playedAt ON playback_history(playedAt)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS song_analysis (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL UNIQUE, bpm REAL NOT NULL DEFAULT 0.0, bpmConfidence REAL NOT NULL DEFAULT 0.0, key TEXT NOT NULL DEFAULT '', keyConfidence REAL NOT NULL DEFAULT 0.0, chorusTimestamps TEXT NOT NULL DEFAULT '', silenceRegions TEXT NOT NULL DEFAULT '', suggestedLoopStart INTEGER NOT NULL DEFAULT -1, suggestedLoopEnd INTEGER NOT NULL DEFAULT -1, analyzedAt INTEGER NOT NULL, analysisVersion INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_analysis_songId ON song_analysis(songId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', artworkSongId INTEGER NOT NULL DEFAULT -1, isSmartPlaylist INTEGER NOT NULL DEFAULT 0, smartQuery TEXT NOT NULL DEFAULT '', sortOrder INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS playlist_songs (playlistId INTEGER NOT NULL, songId INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0, addedAt INTEGER NOT NULL, PRIMARY KEY(playlistId, songId), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE, FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_playlistId ON playlist_songs(playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_songId ON playlist_songs(songId)")
                val now = System.currentTimeMillis()
                listOf(
                    "Flat" to "0,0,0,0,0,0,0,0,0,0",
                    "Rock" to "4,3,1,0,-1,0,1,3,4,4",
                    "Pop" to "-1,0,2,3,3,0,0,-1,-1,-1",
                    "Jazz" to "3,2,1,2,0,0,-1,-1,0,1",
                    "Classical" to "3,3,0,0,0,0,0,3,3,4",
                    "Electronic" to "4,3,0,-1,-1,0,1,2,3,4",
                    "Hip-Hop" to "4,4,2,2,0,-1,0,1,2,3",
                    "Bass Boost" to "6,5,4,2,0,0,0,0,0,0",
                    "Treble Boost" to "0,0,0,0,0,2,4,5,6,6",
                    "Vocal" to "-2,-2,0,2,4,4,2,0,-2,-2",
                    "Late Night" to "-6,-5,-3,-2,0,0,-2,-3,-4,-5"
                ).forEach { (name, bands) ->
                    db.execSQL("INSERT OR IGNORE INTO eq_presets (name, bands, isBuiltIn, createdAt) VALUES ('$name', '$bands', 1, $now)")
                }
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add volume column to songs table if it doesn't exist
                try {
                    db.execSQL("ALTER TABLE songs ADD COLUMN volume REAL NOT NULL DEFAULT 1.0")
                } catch (e: Exception) {
                    android.util.Log.w(
                        "MusicDatabase",
                        "Volume column already exists in songs table: ${e.message}"
                    )
                }

                // Add volume column to playback_profiles table if it doesn't exist
                try {
                    db.execSQL("ALTER TABLE playback_profiles ADD COLUMN volume REAL NOT NULL DEFAULT 1.0")
                } catch (e: Exception) {
                    android.util.Log.w(
                        "MusicDatabase",
                        "Volume column already exists in playback_profiles table: ${e.message}"
                    )
                }
            }
        }

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_vault.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

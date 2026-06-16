package com.mochimochi.clawmikia.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.mochimochi.clawmikia.data.model.*

// ─── PlaybackProfile DAO ──────────────────────────────────────────────────────
@Dao
interface PlaybackProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: PlaybackProfile): Long

    @Update
    suspend fun update(profile: PlaybackProfile)

    @Delete
    suspend fun delete(profile: PlaybackProfile)

    @Query("SELECT * FROM playback_profiles WHERE songId = :songId ORDER BY createdAt")
    fun getProfilesForSong(songId: Long): LiveData<List<PlaybackProfile>>

    @Query("SELECT * FROM playback_profiles WHERE songId = :songId ORDER BY createdAt")
    suspend fun getProfilesForSongSync(songId: Long): List<PlaybackProfile>

    @Query("SELECT * FROM playback_profiles WHERE songId = :songId AND isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(songId: Long): PlaybackProfile?

    @Query("SELECT * FROM playback_profiles WHERE songId = :songId AND isActive = 1 LIMIT 1")
    fun getActiveProfileLiveData(songId: Long): LiveData<PlaybackProfile?>

    @Query("UPDATE playback_profiles SET isActive = 0 WHERE songId = :songId")
    suspend fun deactivateAll(songId: Long)

    @Query("UPDATE playback_profiles SET isActive = 1, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun activateProfile(profileId: Long, songId: Long) {
        deactivateAll(songId)
        setActive(profileId)
    }

    @Query("UPDATE playback_profiles SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun updateName(id: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM playback_profiles WHERE songId = :songId")
    suspend fun countForSong(songId: Long): Int

    @Query("DELETE FROM playback_profiles WHERE songId = :songId")
    suspend fun deleteAllForSong(songId: Long)

    @Query("UPDATE playback_profiles SET pitchSemitones = :pitch, playbackSpeed = :speed, updatedAt = :now WHERE id = :id")
    suspend fun updatePitchSpeed(
        id: Long,
        pitch: Float,
        speed: Float,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE playback_profiles SET loopStart = :start, loopEnd = :end, loopEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun updateLoop(
        id: Long,
        start: Long,
        end: Long,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE playback_profiles SET abRepeatA = :a, abRepeatB = :b, abRepeatEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun updateAbRepeat(
        id: Long,
        a: Long,
        b: Long,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE playback_profiles SET trimStart = :start, trimEnd = :end, updatedAt = :now WHERE id = :id")
    suspend fun updateTrim(id: Long, start: Long, end: Long, now: Long = System.currentTimeMillis())
}

// ─── SkipRegion DAO ───────────────────────────────────────────────────────────
@Dao
interface SkipRegionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(region: SkipRegion): Long

    @Update
    suspend fun update(region: SkipRegion)

    @Delete
    suspend fun delete(region: SkipRegion)

    @Query("SELECT * FROM skip_regions WHERE songId = :songId ORDER BY startMs")
    fun getRegionsForSong(songId: Long): LiveData<List<SkipRegion>>

    @Query("SELECT * FROM skip_regions WHERE songId = :songId AND isEnabled = 1 ORDER BY startMs")
    suspend fun getEnabledRegionsSync(songId: Long): List<SkipRegion>

    @Query("UPDATE skip_regions SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM skip_regions WHERE songId = :songId")
    suspend fun deleteAllForSong(songId: Long)
}

// ─── Lyrics DAO ──────────────────────────────────────────────────────────────
@Dao
interface LyricsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: LyricsMeta): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<LyricLine>)

    @Query("SELECT * FROM lyrics_meta WHERE songId = :songId LIMIT 1")
    suspend fun getMeta(songId: Long): LyricsMeta?

    @Query("SELECT * FROM lyric_lines WHERE songId = :songId ORDER BY timeMs, lineIndex")
    fun getLinesForSong(songId: Long): LiveData<List<LyricLine>>

    @Query("SELECT * FROM lyric_lines WHERE songId = :songId ORDER BY timeMs, lineIndex")
    suspend fun getLinesSync(songId: Long): List<LyricLine>

    @Query("SELECT * FROM lyric_lines WHERE songId = :songId AND timeMs <= :currentMs ORDER BY timeMs DESC LIMIT 1")
    suspend fun getCurrentLine(songId: Long, currentMs: Long): LyricLine?

    @Query("DELETE FROM lyric_lines WHERE songId = :songId")
    suspend fun deleteLinesForSong(songId: Long)

    @Query("DELETE FROM lyrics_meta WHERE songId = :songId")
    suspend fun deleteMetaForSong(songId: Long)

    @Transaction
    suspend fun replaceLyrics(meta: LyricsMeta, lines: List<LyricLine>) {
        deleteMetaForSong(meta.songId)
        deleteLinesForSong(meta.songId)
        insertMeta(meta)
        if (lines.isNotEmpty()) insertLines(lines)
    }

    @Query("SELECT songId FROM lyrics_meta WHERE songId IN (:ids)")
    suspend fun getSongIdsWithLyrics(ids: List<Long>): List<Long>
}

// ─── Waveform Cache DAO ───────────────────────────────────────────────────────
@Dao
interface WaveformCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: WaveformCache): Long

    @Query("SELECT * FROM waveform_cache WHERE songId = :songId LIMIT 1")
    suspend fun getForSong(songId: Long): WaveformCache?

    @Query("DELETE FROM waveform_cache WHERE songId = :songId")
    suspend fun deleteForSong(songId: Long)

    @Query("SELECT COUNT(*) FROM waveform_cache")
    suspend fun count(): Int

    @Query("DELETE FROM waveform_cache WHERE generatedAt < :before")
    suspend fun pruneOlderThan(before: Long)
}

// ─── Playback History DAO ─────────────────────────────────────────────────────
@Dao
interface PlaybackHistoryDao {

    @Insert
    suspend fun insert(history: PlaybackHistory): Long

    @Query("SELECT * FROM playback_history ORDER BY playedAt DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): LiveData<List<PlaybackHistory>>

    @Query(
        """
        SELECT ph.songId, COUNT(*) as playCount, MAX(ph.playedAt) as lastPlayed
        FROM playback_history ph
        WHERE ph.playedAt > :since
        GROUP BY ph.songId
        ORDER BY playCount DESC
        LIMIT :limit
    """
    )
    suspend fun getTopPlayed(since: Long, limit: Int = 20): List<TopPlayedResult>

    @Query("DELETE FROM playback_history WHERE playedAt < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("DELETE FROM playback_history WHERE songId = :songId")
    suspend fun deleteForSong(songId: Long)
}

data class TopPlayedResult(val songId: Long, val playCount: Int, val lastPlayed: Long)

// ─── Song Analysis DAO ────────────────────────────────────────────────────────
@Dao
interface SongAnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: SongAnalysis): Long

    @Query("SELECT * FROM song_analysis WHERE songId = :songId LIMIT 1")
    suspend fun getForSong(songId: Long): SongAnalysis?

    @Query("SELECT * FROM song_analysis WHERE songId = :songId LIMIT 1")
    fun observeForSong(songId: Long): LiveData<SongAnalysis?>

    @Query("DELETE FROM song_analysis WHERE songId = :songId")
    suspend fun deleteForSong(songId: Long)

    @Query("UPDATE song_analysis SET bpm = :bpm, bpmConfidence = :conf, analyzedAt = :now WHERE songId = :songId")
    suspend fun updateBpm(
        songId: Long,
        bpm: Float,
        conf: Float,
        now: Long = System.currentTimeMillis()
    )
}

// ─── Playlist DAO ─────────────────────────────────────────────────────────────
@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("SELECT * FROM playlists ORDER BY sortOrder, name")
    fun getAllPlaylists(): LiveData<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(entry: PlaylistSong)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongs(entries: List<PlaylistSong>)

    @Delete
    suspend fun removeSongFromPlaylist(entry: PlaylistSong)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>)

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    fun getSongsInPlaylist(playlistId: Long): LiveData<List<Song>>

    @Query("SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    suspend fun getSongsInPlaylistSync(playlistId: Long): List<Song>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getSongCount(playlistId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun containsSong(playlistId: Long, songId: Long): Boolean

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updatePosition(playlistId: Long, songId: Long, position: Int)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)
}

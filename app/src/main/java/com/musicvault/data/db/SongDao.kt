package com.musicvault.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.musicvault.data.model.Song

@Dao
interface SongDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<Song>)

    @Update
    suspend fun updateSong(song: Song)

    @Delete
    suspend fun deleteSong(song: Song)

    @Query("SELECT * FROM songs ORDER BY folderName, title")
    fun getAllSongs(): LiveData<List<Song>>

    @Query("SELECT * FROM songs ORDER BY folderName, title")
    suspend fun getAllSongsSync(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE filePath = :path")
    suspend fun getSongByPath(path: String): Song?

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR folderName LIKE '%' || :query || '%' ORDER BY folderName, title")
    fun searchSongs(query: String): LiveData<List<Song>>

    @Query("SELECT * FROM songs WHERE folderPath = :folder ORDER BY title")
    fun getSongsByFolder(folder: String): LiveData<List<Song>>

    @Query("SELECT DISTINCT folderPath, folderName FROM songs ORDER BY folderName")
    fun getDistinctFolders(): LiveData<List<FolderInfo>>

    @Query("UPDATE songs SET pitchSemitones = :pitch WHERE id = :id")
    suspend fun updatePitch(id: Long, pitch: Int)

    @Query("UPDATE songs SET trimStart = :start, trimEnd = :end WHERE id = :id")
    suspend fun updateTrim(id: Long, start: Long, end: Long)

    @Query("UPDATE songs SET isFavorite = :fav WHERE id = :id")
    suspend fun updateFavorite(id: Long, fav: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :time WHERE id = :id")
    suspend fun incrementPlayCount(id: Long, time: Long)

    @Query("UPDATE songs SET albumName = :album, albumArtUrl = :artUrl, metadataFetched = 1 WHERE id = :id")
    suspend fun updateOnlineMetadata(id: Long, album: String, artUrl: String)

    @Query("SELECT * FROM songs WHERE metadataFetched = 0 ORDER BY dateAdded DESC")
    suspend fun getSongsWithoutMetadata(): List<Song>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title")
    fun getFavorites(): LiveData<List<Song>>

    @Query("DELETE FROM songs WHERE filePath NOT IN (:validPaths)")
    suspend fun removeDeletedFiles(validPaths: List<String>)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    /** Wipes every row from the songs table. Does NOT touch the actual MP3 files on disk. */
    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()
}

data class FolderInfo(
    val folderPath: String,
    val folderName: String
)

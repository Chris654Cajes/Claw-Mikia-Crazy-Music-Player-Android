package com.mochimochi.clawmikiacrazy.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.mochimochi.clawmikiacrazy.data.model.Song

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

    @Query("SELECT * FROM songs WHERE id = :id")
    fun getSongByIdLiveData(id: Long): LiveData<Song?>

    @Query("SELECT * FROM songs WHERE folderPath = :folder ORDER BY title")
    fun getSongsByFolder(folder: String): LiveData<List<Song>>

    @Query("SELECT DISTINCT folderPath, folderName FROM songs ORDER BY folderName")
    fun getDistinctFolders(): LiveData<List<FolderInfo>>

    @Query("UPDATE songs SET pitchSemitones = :pitch WHERE id = :id")
    suspend fun updatePitch(id: Long, pitch: Float)

    @Query("UPDATE songs SET playbackSpeed = :speed WHERE id = :id")
    suspend fun updateSpeed(id: Long, speed: Float)

    @Query("UPDATE songs SET trimStart = :start, trimEnd = :end WHERE id = :id")
    suspend fun updateTrim(id: Long, start: Long, end: Long)

    @Query("UPDATE songs SET isFavorite = :fav WHERE id = :id")
    suspend fun updateFavorite(id: Long, fav: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :time WHERE id = :id")
    suspend fun incrementPlayCount(id: Long, time: Long)

    @Query("UPDATE songs SET repeatMode = :repeatMode WHERE id = :id")
    suspend fun updateRepeatMode(id: Long, repeatMode: Int)

    @Query("UPDATE songs SET title = :title, artist = :artist, albumName = :album, albumArtUrl = :artUrl, metadataFetched = 1 WHERE id = :id")
    suspend fun updateOnlineMetadata(
        id: Long,
        title: String,
        artist: String,
        album: String,
        artUrl: String
    )

    @Query("UPDATE songs SET folderName = :newName WHERE folderPath = :path")
    suspend fun renameFolder(path: String, newName: String)

    @Query("UPDATE songs SET folderPath = :newPath, folderName = :newFolderName, filePath = :newFilePath WHERE id = :songId")
    suspend fun moveSong(songId: Long, newPath: String, newFolderName: String, newFilePath: String)

    @Query("UPDATE songs SET title = :title, artist = :artist, albumName = :album, albumArtUrl = :artUrl, isManuallyEdited = 1, metadataFetched = 1 WHERE id = :id")
    suspend fun updateSongDetailsManual(
        id: Long,
        title: String,
        artist: String,
        album: String,
        artUrl: String,
    )

    @Query("SELECT * FROM songs WHERE metadataFetched = 0 AND isManuallyEdited = 0 ORDER BY dateAdded DESC")
    suspend fun getSongsWithoutMetadata(): List<Song>

    @Query("SELECT * FROM songs WHERE isManuallyEdited = 0 ORDER BY dateAdded DESC")
    suspend fun getSongsEligibleForOnlineUpdate(): List<Song>

    @Query("SELECT * FROM songs WHERE isManuallyEdited = 1")
    fun getManuallyEditedSongs(): LiveData<List<Song>>

    @Query("SELECT COUNT(*) FROM songs WHERE isManuallyEdited = 1")
    fun getManuallyEditedCount(): LiveData<Int>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title")
    fun getFavorites(): LiveData<List<Song>>

    @Query("DELETE FROM songs WHERE filePath NOT IN (:validPaths)")
    suspend fun removeDeletedFiles(validPaths: List<String>)

    /** Wipes every row from the songs table. Does NOT touch the actual MP3 files on disk. */
    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()
}

data class FolderInfo(
    val folderPath: String,
    val folderName: String
)
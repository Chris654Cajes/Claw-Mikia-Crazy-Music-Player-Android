package com.musicvault.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import com.musicvault.data.db.FolderInfo
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.db.SongDao
import com.musicvault.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SongRepository(private val context: Context) {

    private val songDao: SongDao = MusicDatabase.getDatabase(context).songDao()

    val allSongs: LiveData<List<Song>> = songDao.getAllSongs()
    val favorites: LiveData<List<Song>> = songDao.getFavorites()
    val folders: LiveData<List<FolderInfo>> = songDao.getDistinctFolders()

    fun searchSongs(query: String): LiveData<List<Song>> = songDao.searchSongs(query)
    fun getSongsByFolder(folder: String): LiveData<List<Song>> = songDao.getSongsByFolder(folder)

    suspend fun scanFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        val songs = mutableListOf<Song>()
        scanDocumentFile(docFile, songs)
        songDao.insertAll(songs)
        songs.size
    }

    private fun scanDocumentFile(
        dir: DocumentFile,
        songs: MutableList<Song>,
        parentPath: String = ""
    ) {
        val folderName = dir.name ?: "Unknown"
        val folderPath = if (parentPath.isEmpty()) folderName else "$parentPath/$folderName"

        dir.listFiles().forEach { file ->
            when {
                file.isDirectory -> scanDocumentFile(file, songs, folderPath)
                file.isFile && file.name?.lowercase()?.endsWith(".mp3") == true -> {
                    val song = extractSongMeta(file, folderPath, folderName)
                    if (song != null) songs.add(song)
                }
            }
        }
    }

    private fun extractSongMeta(file: DocumentFile, folderPath: String, folderName: String): Song? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, file.uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.name?.removeSuffix(".mp3") ?: "Unknown"
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            Song(
                title = title,
                artist = artist,
                filePath = file.uri.toString(),
                folderPath = folderPath,
                folderName = folderName,
                duration = duration,
                fileSize = file.length()
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updatePitch(id: Long, pitch: Int) = withContext(Dispatchers.IO) {
        songDao.updatePitch(id, pitch)
    }

    suspend fun updateTrim(id: Long, start: Long, end: Long) = withContext(Dispatchers.IO) {
        songDao.updateTrim(id, start, end)
    }

    suspend fun toggleFavorite(song: Song) = withContext(Dispatchers.IO) {
        songDao.updateFavorite(song.id, !song.isFavorite)
    }

    suspend fun incrementPlayCount(id: Long) = withContext(Dispatchers.IO) {
        songDao.incrementPlayCount(id, System.currentTimeMillis())
    }

    suspend fun getSongById(id: Long): Song? = withContext(Dispatchers.IO) {
        songDao.getSongById(id)
    }

    suspend fun getSongCount(): Int = withContext(Dispatchers.IO) {
        songDao.getSongCount()
    }

    /**
     * Deletes every row from the songs table.
     * The actual MP3 files on the device are completely untouched.
     * After this call, allSongs LiveData will emit an empty list automatically.
     */
    suspend fun resetLibrary() = withContext(Dispatchers.IO) {
        songDao.deleteAllSongs()
    }
}

package com.mochimochi.clawmikiacrazy.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import com.mochimochi.clawmikiacrazy.data.db.FolderInfo
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.db.SongDao
import com.mochimochi.clawmikiacrazy.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SongRepository(private val context: Context) {

    private val songDao: SongDao = MusicDatabase.getDatabase(context).songDao()

    val allSongs: LiveData<List<Song>> = songDao.getAllSongs()
    val favorites: LiveData<List<Song>> = songDao.getFavorites()
    val folders: LiveData<List<FolderInfo>> = songDao.getDistinctFolders()

    fun getSongsByFolder(folder: String): LiveData<List<Song>> = songDao.getSongsByFolder(folder)

    suspend fun scanFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        val songs = mutableListOf<Song>()
        scanDocumentFile(docFile, songs)
        songDao.insertAll(songs)
        songs.size
    }

    suspend fun scanFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        uris.forEach { uri ->
            val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
            if (docFile.isFile && docFile.name?.lowercase()?.endsWith(".mp3") == true) {
                val song = extractSongMeta(docFile, "Imported", "Imported")
                if (song != null) songs.add(song)
            }
        }
        songDao.insertAll(songs)
        songs.size
    }

    private fun scanDocumentFile(
        dir: DocumentFile,
        songs: MutableList<Song>,
        parentPath: String = "",
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
            val artist =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val duration =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 0L
            mmr.release()

            // Copy file to internal storage so it remains playable even if deleted from device
            val internalUri = copyToInternalStorage(file.uri, file.name ?: "$title.mp3")
                ?: return null

            Song(
                title = title,
                artist = artist,
                filePath = internalUri,
                folderPath = folderPath,
                folderName = folderName,
                duration = duration,
                fileSize = file.length(),
                dateModified = file.lastModified()
            )
        } catch (e: Exception) {
            Log.e("SongRepository", "Error extracting meta or copying file: ${e.message}")
            null
        }
    }

    private fun copyToInternalStorage(uri: Uri, fileName: String): String? {
        return try {
            // If the URI is already a file in our internal music directory, no need to copy again
            if (uri.scheme == "file" && uri.path?.contains(context.filesDir.absolutePath) == true) {
                return uri.toString()
            }

            val musicDir = File(context.filesDir, "music")
            if (!musicDir.exists()) musicDir.mkdirs()

            // Sanitize filename and make it unique to avoid overwriting or collisions
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val uniqueName = "${System.currentTimeMillis()}_$safeName"
            val destFile = File(musicDir, uniqueName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile).toString()
        } catch (e: Exception) {
            Log.e("SongRepository", "Failed to copy file to internal storage: ${e.message}")
            null
        }
    }

    suspend fun updatePitchAndSyncProfile(id: Long, pitch: Float) = withContext(Dispatchers.IO) {
        songDao.updatePitch(id, pitch)
        // Also update the active profile if it exists
        val profileDao = MusicDatabase.getDatabase(context).playbackProfileDao()
        val activeProfile = profileDao.getActiveProfile(id)
        activeProfile?.let { profile ->
            profileDao.updatePitchSpeed(profile.id, pitch, profile.playbackSpeed)
        }
    }

    suspend fun updateSpeedAndSyncProfile(id: Long, speed: Float) = withContext(Dispatchers.IO) {
        songDao.updateSpeed(id, speed)
        // Also update the active profile if it exists
        val profileDao = MusicDatabase.getDatabase(context).playbackProfileDao()
        val activeProfile = profileDao.getActiveProfile(id)
        activeProfile?.let { profile ->
            profileDao.updatePitchSpeed(profile.id, profile.pitchSemitones, speed)
        }
    }

    suspend fun updateTrimAndSyncProfile(id: Long, start: Long, end: Long) =
        withContext(Dispatchers.IO) {
            songDao.updateTrim(id, start, end)
            // Also update the active profile if it exists
            val profileDao = MusicDatabase.getDatabase(context).playbackProfileDao()
            val activeProfile = profileDao.getActiveProfile(id)
            activeProfile?.let { profile ->
                profileDao.updateTrim(profile.id, start, end)
            }
        }

    suspend fun updateRepeatModeAndSyncProfile(id: Long, repeatMode: Int) =
        withContext(Dispatchers.IO) {
            songDao.updateRepeatMode(id, repeatMode)
            // Note: repeat mode is stored in song, not profile, so no profile sync needed
        }

    suspend fun renameFolder(path: String, newName: String) = withContext(Dispatchers.IO) {
        songDao.renameFolder(path, newName)
    }

    suspend fun moveSong(
        songId: Long,
        newPath: String,
        newFolderName: String,
        newFilePath: String
    ) = withContext(Dispatchers.IO) {
        songDao.moveSong(songId, newPath, newFolderName, newFilePath)
    }

    suspend fun updateSongDetailsManual(
        id: Long,
        title: String,
        artist: String,
        album: String,
        artUrl: String
    ) = withContext(Dispatchers.IO) {
        songDao.updateSongDetailsManual(id, title, artist, album, artUrl)
    }

    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        // Delete the internal file associated with this song
        try {
            val uri = Uri.parse(song.filePath)
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists() && path.contains(context.filesDir.absolutePath)) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SongRepository", "Failed to delete internal file: ${e.message}")
        }
        songDao.deleteSong(song)
    }

    val manuallyEditedCount: LiveData<Int> = songDao.getManuallyEditedCount()

    suspend fun toggleFavorite(song: Song) = withContext(Dispatchers.IO) {
        songDao.updateFavorite(song.id, !song.isFavorite)
    }

    suspend fun incrementPlayCount(id: Long) = withContext(Dispatchers.IO) {
        songDao.incrementPlayCount(id, System.currentTimeMillis())
    }

    suspend fun getSongById(id: Long): Song? = withContext(Dispatchers.IO) {
        songDao.getSongById(id)
    }

    fun getSongByIdLiveData(id: Long): LiveData<Song?> = songDao.getSongByIdLiveData(id)

    /**
     * Deletes every row from the songs table and all internal music files.
     * The original MP3 files on the device (outside app storage) are completely untouched.
     * After this call, allSongs LiveData will emit an empty list automatically.
     */
    suspend fun resetLibrary() = withContext(Dispatchers.IO) {
        try {
            val musicDir = File(context.filesDir, "music")
            if (musicDir.exists()) {
                musicDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e("SongRepository", "Failed to clear internal music directory: ${e.message}")
        }
        songDao.deleteAllSongs()
    }
}
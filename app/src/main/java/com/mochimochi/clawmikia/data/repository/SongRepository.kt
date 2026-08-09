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

    data class ImportCandidate(
        val uri: Uri,
        val sourceName: String,
        val folderPath: String,
        val folderName: String,
        val title: String,
        val artist: String,
        val duration: Long,
        val fileSize: Long,
        val dateModified: Long
    )

    data class ImportPlan(
        val newSongs: List<ImportCandidate>,
        val duplicates: List<ImportCandidate>
    )

    /** Scans a folder tree and builds an import plan, separating brand-new songs from duplicates. */
    suspend fun buildFolderImportPlan(treeUri: Uri): ImportPlan = withContext(Dispatchers.IO) {
        val docFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ImportPlan(emptyList(), emptyList())
        val candidates = mutableListOf<ImportCandidate>()
        collectDocumentFile(docFile, candidates)
        partitionCandidates(candidates)
    }

    /** Scans a list of files and builds an import plan, separating brand-new songs from duplicates. */
    suspend fun buildFilesImportPlan(uris: List<Uri>): ImportPlan = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ImportCandidate>()
        uris.forEach { uri ->
            val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
            if (docFile.isFile && docFile.name?.lowercase()?.endsWith(".mp3") == true) {
                readMeta(docFile, "Imported", "Imported")?.let { candidates.add(it) }
            }
        }
        partitionCandidates(candidates)
    }

    /** Copies the given candidates into internal storage and inserts them into the library. */
    suspend fun commitImport(candidates: List<ImportCandidate>): Int = withContext(Dispatchers.IO) {
        val songs = candidates.mapNotNull { candidate ->
            // Copy file to internal storage so it remains playable even if deleted from device.
            // The unique timestamp prefix guarantees duplicates never overwrite existing files.
            val internalUri = copyToInternalStorage(candidate.uri, candidate.sourceName)
                ?: return@mapNotNull null

            Song(
                title = candidate.title,
                artist = candidate.artist,
                filePath = internalUri,
                folderPath = candidate.folderPath,
                folderName = candidate.folderName,
                duration = candidate.duration,
                fileSize = candidate.fileSize,
                dateModified = candidate.dateModified
            )
        }
        songDao.insertAll(songs)
        songs.size
    }

    private suspend fun partitionCandidates(candidates: List<ImportCandidate>): ImportPlan {
        if (candidates.isEmpty()) return ImportPlan(emptyList(), emptyList())
        val existingKeys = songDao.getAllSongsSync()
            .map { songKey(it.title, it.artist) }
            .toHashSet()
        val newSongs = mutableListOf<ImportCandidate>()
        val duplicates = mutableListOf<ImportCandidate>()
        candidates.forEach { candidate ->
            if (existingKeys.contains(songKey(candidate.title, candidate.artist))) {
                duplicates.add(candidate)
            } else {
                newSongs.add(candidate)
            }
        }
        return ImportPlan(newSongs, duplicates)
    }

    private fun songKey(title: String, artist: String) =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun collectDocumentFile(
        dir: DocumentFile,
        candidates: MutableList<ImportCandidate>,
        parentPath: String = "",
    ) {
        val folderName = dir.name ?: "Unknown"
        val folderPath = if (parentPath.isEmpty()) folderName else "$parentPath/$folderName"

        dir.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectDocumentFile(file, candidates, folderPath)
                file.isFile && file.name?.lowercase()?.endsWith(".mp3") == true -> {
                    readMeta(file, folderPath, folderName)?.let { candidates.add(it) }
                }
            }
        }
    }

    private fun readMeta(
        file: DocumentFile,
        folderPath: String,
        folderName: String
    ): ImportCandidate? {
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

            ImportCandidate(
                uri = file.uri,
                sourceName = file.name ?: "$title.mp3",
                folderPath = folderPath,
                folderName = folderName,
                title = title,
                artist = artist,
                duration = duration,
                fileSize = file.length(),
                dateModified = file.lastModified()
            )
        } catch (e: Exception) {
            Log.e("SongRepository", "Error reading metadata: ${e.message}")
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
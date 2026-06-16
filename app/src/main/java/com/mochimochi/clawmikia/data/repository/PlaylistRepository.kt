package com.mochimochi.clawmikia.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.mochimochi.clawmikia.data.db.MusicDatabase
import com.mochimochi.clawmikia.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistRepository(context: Context) {

    private val db = MusicDatabase.getDatabase(context)
    private val playlistDao = db.playlistDao()
    private val historyDao = db.playbackHistoryDao()

    // ─── Playlists ────────────────────────────────────────────────────────────

    val allPlaylists: LiveData<List<Playlist>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String, description: String = ""): Long =
        withContext(Dispatchers.IO) {
            playlistDao.insertPlaylist(
                Playlist(name = name, description = description),
            )
        }

    suspend fun updatePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlist)
    }

    fun getSongsInPlaylist(playlistId: Long): LiveData<List<Song>> =
        playlistDao.getSongsInPlaylist(playlistId)

    suspend fun getSongsInPlaylistSync(playlistId: Long): List<Song> = withContext(Dispatchers.IO) {
        playlistDao.getSongsInPlaylistSync(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        val position = playlistDao.getSongCount(playlistId)
        playlistDao.addSongToPlaylist(PlaylistSong(playlistId, songId, position))
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) =
        withContext(Dispatchers.IO) {
            var position = playlistDao.getSongCount(playlistId)
            val entries = songIds.map { PlaylistSong(playlistId, it, position++) }
            playlistDao.insertPlaylistSongs(entries)
        }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        withContext(Dispatchers.IO) {
            playlistDao.removeSongFromPlaylist(PlaylistSong(playlistId, songId))
        }

    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) =
        withContext(Dispatchers.IO) {
            playlistDao.removeSongsFromPlaylist(playlistId, songIds)
        }

    // ─── Playback History ─────────────────────────────────────────────────────

    suspend fun recordPlay(songId: Long, durationListened: Long = 0, completed: Boolean = false) =
        withContext(Dispatchers.IO) {
            historyDao.insert(
                PlaybackHistory(
                    songId = songId,
                    durationListened = durationListened,
                    completedFully = completed
                )
            )
        }
}

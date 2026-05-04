package com.musicvault.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistRepository(private val context: Context) {

    private val db = MusicDatabase.getDatabase(context)
    private val playlistDao = db.playlistDao()
    private val eqPresetDao = db.eqPresetDao()
    private val historyDao = db.playbackHistoryDao()

    // ─── Playlists ────────────────────────────────────────────────────────────

    val allPlaylists: LiveData<List<Playlist>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String, description: String = ""): Long =
        withContext(Dispatchers.IO) {
            playlistDao.insertPlaylist(
                Playlist(name = name, description = description)
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

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        val position = playlistDao.getSongCount(playlistId)
        playlistDao.addSongToPlaylist(PlaylistSong(playlistId, songId, position))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        withContext(Dispatchers.IO) {
            playlistDao.removeSongFromPlaylist(PlaylistSong(playlistId, songId))
        }

    suspend fun isSongInPlaylist(playlistId: Long, songId: Long): Boolean =
        withContext(Dispatchers.IO) {
            playlistDao.containsSong(playlistId, songId)
        }

    suspend fun getPlaylistSongsForPlayback(playlistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            playlistDao.getSongsInPlaylistSync(playlistId)
        }

    // ─── EQ Presets ──────────────────────────────────────────────────────────

    val allEqPresets: LiveData<List<EqPreset>> = eqPresetDao.getAllPresets()

    suspend fun getAllEqPresetsSync(): List<EqPreset> = withContext(Dispatchers.IO) {
        eqPresetDao.getAllPresetsSync()
    }

    suspend fun saveCustomEqPreset(name: String, bands: List<Int>): Long =
        withContext(Dispatchers.IO) {
            eqPresetDao.insert(
                EqPreset(
                    name = name,
                    bands = bands.joinToString(","),
                    isBuiltIn = false
                )
            )
        }

    suspend fun deleteCustomEqPreset(preset: EqPreset) = withContext(Dispatchers.IO) {
        if (!preset.isBuiltIn) eqPresetDao.delete(preset)
    }

    // ─── Playback History ─────────────────────────────────────────────────────

    fun getRecentHistory(limit: Int = 50): LiveData<List<PlaybackHistory>> =
        historyDao.getRecent(limit)

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

    suspend fun getTopPlayedSongIds(since: Long, limit: Int = 20): List<Long> =
        withContext(Dispatchers.IO) {
            historyDao.getTopPlayed(since, limit).map { it.songId }
        }

    suspend fun pruneHistory(keepDays: Int = 90) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (keepDays * 24 * 60 * 60 * 1000L)
        historyDao.pruneOlderThan(cutoff)
    }
}

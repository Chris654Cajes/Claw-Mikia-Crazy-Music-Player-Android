package com.mochimochi.clawmikia.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.repository.PlaylistRepository
import com.mochimochi.clawmikia.data.repository.SongRepository
import com.mochimochi.clawmikia.utils.MetadataFetcher
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SongRepository(application)
    private val playlistRepo = PlaylistRepository(application)

    val allSongs: LiveData<List<Song>> = repository.allSongs
    val favorites: LiveData<List<Song>> = repository.favorites
    val folders = repository.folders
    val allPlaylists = playlistRepo.allPlaylists

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    val filteredSongs: LiveData<List<Song>> = _searchQuery.switchMap { query ->
        if (query.isBlank()) repository.allSongs
        else repository.searchSongs(query)
    }

    private val _scanStatus = MutableLiveData<ScanStatus>()
    val scanStatus: LiveData<ScanStatus> = _scanStatus

    private val _currentSongId = MutableLiveData<Long?>(null)
    val currentSong: LiveData<Song?> = _currentSongId.switchMap { id ->
        if (id == null) MutableLiveData(null)
        else repository.getSongByIdLiveData(id)
    }

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _playlistMode = MutableLiveData(PlaylistMode.ALL)
    val playlistMode: LiveData<PlaylistMode> = _playlistMode

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setCurrentSong(song: Song?) {
        _currentSongId.value = song?.id
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun scanFolder(uri: Uri) {
        viewModelScope.launch {
            _scanStatus.value = ScanStatus.Scanning
            val count = repository.scanFolder(uri)
            _scanStatus.value = if (count > 0) ScanStatus.Success(count) else ScanStatus.Empty
            if (count > 0) {
                MetadataFetcher.fetchMissingMetadata(getApplication())
            }
        }
    }

    /** Call this on app resume to pick up any songs that missed metadata last time. */
    fun fetchMetadataIfOnline() {
        viewModelScope.launch {
            MetadataFetcher.fetchMissingMetadata(getApplication())
        }
    }

    fun updatePitch(id: Long, pitch: Float) {
        viewModelScope.launch { repository.updatePitchAndSyncProfile(id, pitch) }
    }

    fun updateTrim(id: Long, start: Long, end: Long) {
        viewModelScope.launch { repository.updateTrimAndSyncProfile(id, start, end) }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { repository.toggleFavorite(song) }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { playlistRepo.addSongToPlaylist(playlistId, songId) }
    }

    fun incrementPlayCount(id: Long) {
        viewModelScope.launch { repository.incrementPlayCount(id) }
    }

    fun getSongsByFolder(folder: String): LiveData<List<Song>> =
        repository.getSongsByFolder(folder)

    /**
     * Wipes all songs from SQLite. The actual MP3 files on the device are NOT deleted.
     * After this completes the user can re-scan the same folder to rebuild the library.
     */
    fun resetLibrary() {
        viewModelScope.launch {
            repository.resetLibrary()
            _currentSongId.value = null
            _isPlaying.value = false
            _scanStatus.value = ScanStatus.Reset
        }
    }

    sealed class ScanStatus {
        object Scanning : ScanStatus()
        data class Success(val count: Int) : ScanStatus()
        object Empty : ScanStatus()
        data class Error(val msg: String) : ScanStatus()

        /** Emitted after a successful library reset. */
        object Reset : ScanStatus()
    }

    enum class PlaylistMode { ALL, FOLDER, FAVORITES, SEARCH }
}

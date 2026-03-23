package com.musicvault.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.musicvault.data.model.Song
import com.musicvault.data.repository.SongRepository
import com.musicvault.utils.MetadataFetcher
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SongRepository(application)

    val allSongs: LiveData<List<Song>> = repository.allSongs
    val favorites: LiveData<List<Song>> = repository.favorites
    val folders = repository.folders

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    val filteredSongs: LiveData<List<Song>> = _searchQuery.switchMap { query ->
        if (query.isBlank()) repository.allSongs
        else repository.searchSongs(query)
    }

    private val _scanStatus = MutableLiveData<ScanStatus>()
    val scanStatus: LiveData<ScanStatus> = _scanStatus

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _playlistMode = MutableLiveData(PlaylistMode.ALL)
    val playlistMode: LiveData<PlaylistMode> = _playlistMode

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun setCurrentSong(song: Song?) { _currentSong.value = song }
    fun setPlaying(playing: Boolean) { _isPlaying.value = playing }

    fun scanFolder(uri: Uri) {
        viewModelScope.launch {
            _scanStatus.value = ScanStatus.Scanning
            val count = repository.scanFolder(uri)
            _scanStatus.value = if (count > 0) ScanStatus.Success(count) else ScanStatus.Empty
            // After scan, fetch metadata for new songs if online
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

    fun updatePitch(id: Long, pitch: Int) {
        viewModelScope.launch { repository.updatePitch(id, pitch) }
    }

    fun updateTrim(id: Long, start: Long, end: Long) {
        viewModelScope.launch { repository.updateTrim(id, start, end) }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { repository.toggleFavorite(song) }
    }

    fun incrementPlayCount(id: Long) {
        viewModelScope.launch { repository.incrementPlayCount(id) }
    }

    fun getSongsByFolder(folder: String): LiveData<List<Song>> =
        repository.getSongsByFolder(folder)

    sealed class ScanStatus {
        object Scanning : ScanStatus()
        data class Success(val count: Int) : ScanStatus()
        object Empty : ScanStatus()
        data class Error(val msg: String) : ScanStatus()
    }

    enum class PlaylistMode { ALL, FOLDER, FAVORITES, SEARCH }
}
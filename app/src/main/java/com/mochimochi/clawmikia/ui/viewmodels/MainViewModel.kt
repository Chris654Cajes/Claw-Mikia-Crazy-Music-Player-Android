package com.mochimochi.clawmikia.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.repository.PlaylistRepository
import com.mochimochi.clawmikia.data.repository.SongRepository
import com.mochimochi.clawmikia.utils.MetadataFetcher
import kotlinx.coroutines.launch

enum class TriState { ALL, YES, NO }

data class AdvancedFilter(
    val minDuration: Long? = null,
    val maxDuration: Long? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val minPlayCount: Int? = null,
    val minPitch: Float? = null,
    val maxPitch: Float? = null,
    val minSpeed: Float? = null,
    val maxSpeed: Float? = null,
    val favoriteState: TriState = TriState.ALL,
    val metadataState: TriState = TriState.ALL,
    val manualEditState: TriState = TriState.ALL,
    val customPitchState: TriState = TriState.ALL,
    val customSpeedState: TriState = TriState.ALL,
    val trimState: TriState = TriState.ALL,
    val addedAfter: Long? = null,
    val modifiedAfter: Long? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SongRepository(application)
    private val playlistRepo = PlaylistRepository(application)

    val allSongs: LiveData<List<Song>> = repository.allSongs
    val favorites: LiveData<List<Song>> = repository.favorites
    val folders = repository.folders
    val allPlaylists = playlistRepo.allPlaylists
    val manuallyEditedCount: LiveData<Int> = repository.manuallyEditedCount

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _advancedFilter = MutableLiveData<AdvancedFilter>(AdvancedFilter())
    val advancedFilter: LiveData<AdvancedFilter> = _advancedFilter

    val filteredSongs: LiveData<List<Song>> =
        MediatorLiveData<List<Song>>().apply {
            addSource(_searchQuery) { query ->
                value = applyFilters(
                    query,
                    _advancedFilter.value ?: AdvancedFilter(),
                    allSongs.value ?: emptyList()
                )
            }
            addSource(_advancedFilter) { filter ->
                value =
                    applyFilters(_searchQuery.value ?: "", filter, allSongs.value ?: emptyList())
            }
            addSource(allSongs) { songs ->
                value = applyFilters(
                    _searchQuery.value ?: "",
                    _advancedFilter.value ?: AdvancedFilter(),
                    songs ?: emptyList()
                )
            }
        }

    private fun applyFilters(query: String, filter: AdvancedFilter, songs: List<Song>): List<Song> {
        return songs.filter { song ->
            // Search query filter
            val matchesQuery = if (query.isBlank()) true else {
                song.title.contains(query, ignoreCase = true) ||
                        song.artist.contains(query, ignoreCase = true) ||
                        song.albumName.contains(query, ignoreCase = true) ||
                        song.folderName.contains(query, ignoreCase = true)
            }
            if (!matchesQuery) return@filter false

            // Advanced filters
            if (filter.minDuration != null && song.duration < filter.minDuration * 1000) return@filter false
            if (filter.maxDuration != null && song.duration > filter.maxDuration * 1000) return@filter false

            if (filter.minSize != null && song.fileSize < filter.minSize * 1024 * 1024) return@filter false
            if (filter.maxSize != null && song.fileSize > filter.maxSize * 1024 * 1024) return@filter false

            if (filter.minPlayCount != null && song.playCount < filter.minPlayCount) return@filter false

            if (filter.minPitch != null && song.pitchSemitones < filter.minPitch) return@filter false
            if (filter.maxPitch != null && song.pitchSemitones > filter.maxPitch) return@filter false

            if (filter.minSpeed != null && song.playbackSpeed < filter.minSpeed) return@filter false
            if (filter.maxSpeed != null && song.playbackSpeed > filter.maxSpeed) return@filter false

            // Tri-state filters
            if (!matchesTriState(filter.favoriteState, song.isFavorite)) return@filter false
            if (!matchesTriState(filter.metadataState, song.metadataFetched)) return@filter false
            if (!matchesTriState(filter.manualEditState, song.isManuallyEdited)) return@filter false
            if (!matchesTriState(
                    filter.customPitchState,
                    song.pitchSemitones != 0f
                )
            ) return@filter false
            if (!matchesTriState(
                    filter.customSpeedState,
                    song.playbackSpeed != 1.0f
                )
            ) return@filter false
            if (!matchesTriState(
                    filter.trimState,
                    song.trimStart > 0 || song.trimEnd != -1L
                )
            ) return@filter false

            // Date filters
            if (filter.addedAfter != null && song.dateAdded < filter.addedAfter) return@filter false
            if (filter.modifiedAfter != null && song.dateModified < filter.modifiedAfter) return@filter false

            true
        }
    }

    private fun matchesTriState(state: TriState, value: Boolean): Boolean {
        return when (state) {
            TriState.ALL -> true
            TriState.YES -> value
            TriState.NO -> !value
        }
    }

    fun setAdvancedFilter(filter: AdvancedFilter) {
        _advancedFilter.value = filter
    }

    fun resetAdvancedFilters() {
        _advancedFilter.value = AdvancedFilter()
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
            if (count > 0) {
                _scanStatus.value = ScanStatus.Success(count)
            } else {
                _scanStatus.value = ScanStatus.Empty
            }
        }
    }

    fun scanFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _scanStatus.value = ScanStatus.Scanning
            val count = repository.scanFiles(uris)
            if (count > 0) {
                _scanStatus.value = ScanStatus.Success(count)
            } else {
                _scanStatus.value = ScanStatus.Empty
            }
        }
    }

    /** Call this on app resume to pick up any songs that missed metadata last time. */
    fun fetchMetadataIfOnline() {
        // No longer auto-fetching on resume as per user request
    }

    fun fetchMetadataManual(overwriteManual: Boolean) {
        viewModelScope.launch {
            _scanStatus.value = ScanStatus.FetchingMetadata
            MetadataFetcher.fetchMissingMetadata(getApplication<Application>(), overwriteManual)
            _scanStatus.value = ScanStatus.Idle
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

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            songIds.forEach { playlistRepo.addSongToPlaylist(playlistId, it) }
        }
    }

    fun renameFolder(path: String, newName: String) {
        viewModelScope.launch { repository.renameFolder(path, newName) }
    }

    fun moveSong(songId: Long, newPath: String, newFolderName: String, newFilePath: String) {
        viewModelScope.launch { repository.moveSong(songId, newPath, newFolderName, newFilePath) }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch { repository.deleteSong(song) }
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
        object Idle : ScanStatus()
        object Scanning : ScanStatus()
        object FetchingMetadata : ScanStatus()
        data class Success(val count: Int) : ScanStatus()
        object Empty : ScanStatus()
        data class Error(val msg: String) : ScanStatus()

        /** Emitted after a successful library reset. */
        object Reset : ScanStatus()
    }

    enum class PlaylistMode { ALL, FOLDER, FAVORITES, SEARCH }
}

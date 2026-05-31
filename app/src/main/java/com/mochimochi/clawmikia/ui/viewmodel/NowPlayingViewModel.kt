package com.mochimochi.clawmikia.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.mochimochi.clawmikia.data.db.MusicDatabase
import com.mochimochi.clawmikia.data.model.EqPreset
import com.mochimochi.clawmikia.data.model.LyricLine
import com.mochimochi.clawmikia.data.model.LyricsMeta
import com.mochimochi.clawmikia.data.model.PlaybackProfile
import com.mochimochi.clawmikia.data.model.SkipRegion
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.model.SongAnalysis
import com.mochimochi.clawmikia.data.repository.PlaylistRepository
import com.mochimochi.clawmikia.data.repository.ProfileRepository
import com.mochimochi.clawmikia.data.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NowPlayingViewModel(application: Application) : AndroidViewModel(application) {

    private val songRepository = SongRepository(application)
    private val profileRepository = ProfileRepository(application)
    private val playlistRepository = PlaylistRepository(application)
    private val db = MusicDatabase.getDatabase(application)

    // ─── Song State ──────────────────────────────────────────────────────────
    private val _currentSongId = MutableLiveData<Long?>()
    val currentSong: LiveData<Song?> = _currentSongId.switchMap { id ->
        if (id == null) MutableLiveData(null)
        else songRepository.getSongByIdLiveData(id)
    }

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    // ─── Profile State ────────────────────────────────────────────────────────
    private val _activeProfile = MutableLiveData<PlaybackProfile?>()
    val activeProfile: LiveData<PlaybackProfile?> = _activeProfile

    private val _profiles = MutableLiveData<List<PlaybackProfile>>(emptyList())
    val profiles: LiveData<List<PlaybackProfile>> = _profiles

    // ─── Skip Regions ─────────────────────────────────────────────────────────
    private val _skipRegions = MutableLiveData<List<SkipRegion>>(emptyList())
    val skipRegions: LiveData<List<SkipRegion>> = _skipRegions

    // ─── Analysis ─────────────────────────────────────────────────────────────
    private val _songAnalysis = MutableLiveData<SongAnalysis?>()
    val songAnalysis: LiveData<SongAnalysis?> = _songAnalysis

    private val _waveformAmplitudes = MutableLiveData<FloatArray?>(null)
    val waveformAmplitudes: LiveData<FloatArray?> = _waveformAmplitudes

    // ─── EQ ───────────────────────────────────────────────────────────────────
    private val _eqPresets = MutableLiveData<List<EqPreset>>(emptyList())
    val eqPresets: LiveData<List<EqPreset>> = _eqPresets

    // ─── Lyrics ───────────────────────────────────────────────────────────────
    private val _lyrics = MutableLiveData<List<LyricLine>>(emptyList())
    val lyrics: LiveData<List<LyricLine>> = _lyrics

    private val _lyricsMeta = MutableLiveData<LyricsMeta?>(null)
    val lyricsMeta: LiveData<LyricsMeta?> = _lyricsMeta

    private val _currentLyricLine = MutableLiveData<LyricLine?>()
    val currentLyricLine: LiveData<LyricLine?> = _currentLyricLine

    private val _hasLyrics = MutableLiveData(false)
    val hasLyrics: LiveData<Boolean> = _hasLyrics

    // ─── UI Flags ─────────────────────────────────────────────────────────────
    private val _showLyrics = MutableLiveData(false)
    val showLyrics: LiveData<Boolean> = _showLyrics

    private val _showEq = MutableLiveData(false)
    val showEq: LiveData<Boolean> = _showEq

    private val _isOriginalState = MutableLiveData(false)
    val isOriginalState: LiveData<Boolean> = _isOriginalState

    private val _sleepTimerRemaining = MutableStateFlow(-1L)
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining

    // ─── Operations ──────────────────────────────────────────────────────────

    fun setSong(song: Song, playing: Boolean) {
        _currentSongId.value = song.id
        _isPlaying.value = playing
        loadSongData(song.id, song.filePath)
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    private fun loadSongData(songId: Long, filePath: String) {
        viewModelScope.launch {
            // Load profiles
            val profs = withContext(Dispatchers.IO) {
                profileRepository.getProfilesSync(songId)
            }
            _profiles.value = profs
            _activeProfile.value = profs.firstOrNull { it.isActive } ?: profs.firstOrNull()

            // Load skip regions
            val regions = withContext(Dispatchers.IO) {
                profileRepository.getEnabledSkipRegions(songId)
            }
            _skipRegions.value = regions

            // Load analysis
            val analysis = withContext(Dispatchers.IO) {
                db.songAnalysisDao().getForSong(songId)
            }
            _songAnalysis.value = analysis

            // Load waveform
            val waveCache = withContext(Dispatchers.IO) {
                db.waveformCacheDao().getForSong(songId)
            }
            _waveformAmplitudes.value = waveCache?.amplitudeList()

            // Load EQ presets
            val presets = withContext(Dispatchers.IO) {
                playlistRepository.getAllEqPresetsSync()
            }
            _eqPresets.value = presets

            // Load lyrics
            val lines = withContext(Dispatchers.IO) { db.lyricsDao().getLinesSync(songId) }
            val meta = withContext(Dispatchers.IO) { db.lyricsDao().getMeta(songId) }
            _lyrics.value = lines
            _lyricsMeta.value = meta
            _hasLyrics.value = lines.isNotEmpty()
        }
    }

    fun onLyricsPositionChanged(positionMs: Long) {
        val lines = _lyrics.value ?: return
        val synced = lines.filter { it.isSynced }
        if (synced.isEmpty()) return
        val active = synced.lastOrNull { it.timeMs <= positionMs }
        if (active != _currentLyricLine.value) _currentLyricLine.value = active
    }

    // ─── Profile CRUD ─────────────────────────────────────────────────────────

    fun createProfile(songId: Long, name: String) {
        viewModelScope.launch {
            val base = _activeProfile.value
            val newProfile = PlaybackProfile(
                songId = songId, name = name,
                pitchSemitones = base?.pitchSemitones ?: 0f,
                playbackSpeed = base?.playbackSpeed ?: 1f,
                trimStart = base?.trimStart ?: 0L,
                trimEnd = base?.trimEnd ?: -1L
            )
            profileRepository.createProfile(newProfile)
            reloadProfiles(songId)
        }
    }

    fun activateProfile(profile: PlaybackProfile) {
        viewModelScope.launch {
            profileRepository.activateProfile(profile.id, profile.songId)
            _activeProfile.value = profile
            reloadProfiles(profile.songId)
        }
    }

    fun deleteProfile(profile: PlaybackProfile) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profile)
            reloadProfiles(profile.songId)
        }
    }

    fun updatePitchSpeed(profileId: Long, pitch: Float, speed: Float) {
        viewModelScope.launch {
            profileRepository.updatePitchSpeed(profileId, pitch, speed)
            val updated = _activeProfile.value?.copy(pitchSemitones = pitch, playbackSpeed = speed)
            _activeProfile.value = updated
        }
    }

    fun updateEq(profileId: Long, bands: List<Int>, presetName: String, enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.updateEq(profileId, bands, presetName, enabled)
            val updated = _activeProfile.value?.copy(
                eqBands = bands.joinToString(","), eqPresetName = presetName, eqEnabled = enabled
            )
            _activeProfile.value = updated
        }
    }

    fun updateLoop(profileId: Long, start: Long, end: Long, enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.updateLoop(profileId, start, end, enabled)
        }
    }

    fun updateAbRepeat(profileId: Long, a: Long, b: Long, enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.updateAbRepeat(profileId, a, b, enabled)
        }
    }

    fun updateTrim(profileId: Long, start: Long, end: Long) {
        viewModelScope.launch {
            profileRepository.updateTrim(profileId, start, end)
        }
    }

    private suspend fun reloadProfiles(songId: Long) {
        val profs = withContext(Dispatchers.IO) { profileRepository.getProfilesSync(songId) }
        _profiles.value = profs
        if (_activeProfile.value == null || profs.none { it.id == _activeProfile.value?.id }) {
            _activeProfile.value = profs.firstOrNull { it.isActive } ?: profs.firstOrNull()
        }
    }

    // ─── Skip Regions ─────────────────────────────────────────────────────────

    fun addSkipRegion(songId: Long, label: String, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            profileRepository.addSkipRegion(
                SkipRegion(
                    songId = songId,
                    label = label,
                    startMs = startMs,
                    endMs = endMs
                )
            )
            val updated =
                withContext(Dispatchers.IO) { profileRepository.getEnabledSkipRegions(songId) }
            _skipRegions.value = updated
        }
    }

    fun deleteSkipRegion(region: SkipRegion) {
        viewModelScope.launch {
            profileRepository.deleteSkipRegion(region)
            _skipRegions.value = _skipRegions.value?.filter { it.id != region.id }
        }
    }

    // ─── EQ Presets ───────────────────────────────────────────────────────────

    fun saveCustomEqPreset(name: String, bands: List<Int>) {
        viewModelScope.launch {
            playlistRepository.saveCustomEqPreset(name, bands)
            val presets = withContext(Dispatchers.IO) { playlistRepository.getAllEqPresetsSync() }
            _eqPresets.value = presets
        }
    }

    // ─── UI Toggles ──────────────────────────────────────────────────────────

    fun toggleLyricsPanel() {
        _showLyrics.value = !(_showLyrics.value ?: false)
    }

    fun toggleEqPanel() {
        _showEq.value = !(_showEq.value ?: false)
    }

    fun setOriginalState(isOriginal: Boolean) {
        _isOriginalState.value = isOriginal
    }

    fun updateSleepTimerRemaining(ms: Long) {
        viewModelScope.launch { _sleepTimerRemaining.value = ms }
    }
}

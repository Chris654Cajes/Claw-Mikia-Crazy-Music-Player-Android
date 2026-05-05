package com.musicvault.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.model.PlaybackProfile
import com.musicvault.data.model.SkipRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileRepository(private val context: Context) {

    private val db = MusicDatabase.getDatabase(context)
    private val profileDao = db.playbackProfileDao()
    private val skipRegionDao = db.skipRegionDao()

    // ─── Profiles ─────────────────────────────────────────────────────────────

    fun getProfilesForSong(songId: Long): LiveData<List<PlaybackProfile>> =
        profileDao.getProfilesForSong(songId)

    suspend fun getProfilesSync(songId: Long): List<PlaybackProfile> = withContext(Dispatchers.IO) {
        profileDao.getProfilesForSongSync(songId)
    }

    /**
     * Returns the active profile for a song, or auto-creates a default one
     * seeded with the song's current per-song settings.
     */
    suspend fun getOrCreateActiveProfile(songId: Long): PlaybackProfile =
        withContext(Dispatchers.IO) {
            profileDao.getActiveProfile(songId)
                ?: createDefaultProfile(songId)
        }

    private suspend fun createDefaultProfile(songId: Long): PlaybackProfile {
        // Fetch song to seed pitch/speed/trim/volume
        val song = db.songDao().getSongById(songId)
        val profile = PlaybackProfile(
            songId = songId,
            name = "Default",
            isActive = true,
            pitchSemitones = song?.pitchSemitones?.toFloat() ?: 0f,
            playbackSpeed = song?.playbackSpeed ?: 1.0f,
            volume = song?.volume ?: 1.0f,
            trimStart = song?.trimStart ?: 0L,
            trimEnd = song?.trimEnd ?: -1L
        )
        val id = profileDao.insert(profile)
        return profile.copy(id = id)
    }

    suspend fun createProfile(profile: PlaybackProfile): Long = withContext(Dispatchers.IO) {
        profileDao.insert(profile)
    }

    suspend fun updateProfile(profile: PlaybackProfile) = withContext(Dispatchers.IO) {
        profileDao.update(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProfile(profile: PlaybackProfile) = withContext(Dispatchers.IO) {
        profileDao.delete(profile)
        // If deleted profile was active, activate the first remaining one
        val remaining = profileDao.getProfilesForSongSync(profile.songId)
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            profileDao.activateProfile(remaining.first().id, profile.songId)
        }
    }

    suspend fun activateProfile(profileId: Long, songId: Long) = withContext(Dispatchers.IO) {
        profileDao.activateProfile(profileId, songId)
    }

    suspend fun duplicateProfile(original: PlaybackProfile, newName: String): Long =
        withContext(Dispatchers.IO) {
            val copy = original.copy(
                id = 0,
                name = newName,
                isActive = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            profileDao.insert(copy)
        }

    suspend fun updateEq(profileId: Long, bands: List<Int>, presetName: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            profileDao.updateEq(profileId, bands.joinToString(","), presetName, enabled)
        }

    suspend fun updatePitchSpeed(profileId: Long, pitch: Float, speed: Float) =
        withContext(Dispatchers.IO) {
            profileDao.updatePitchSpeed(profileId, pitch, speed)
        }

    suspend fun updateLoop(profileId: Long, start: Long, end: Long, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            profileDao.updateLoop(profileId, start, end, enabled)
        }

    suspend fun updateAbRepeat(profileId: Long, a: Long, b: Long, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            profileDao.updateAbRepeat(profileId, a, b, enabled)
        }

    suspend fun updateTrim(profileId: Long, start: Long, end: Long) = withContext(Dispatchers.IO) {
        profileDao.updateTrim(profileId, start, end)
    }

    suspend fun updateVolume(profileId: Long, volume: Float) = withContext(Dispatchers.IO) {
        profileDao.updateVolume(profileId, volume)
    }

    // ─── Skip Regions ─────────────────────────────────────────────────────────

    fun getSkipRegions(songId: Long): LiveData<List<SkipRegion>> =
        skipRegionDao.getRegionsForSong(songId)

    suspend fun getEnabledSkipRegions(songId: Long): List<SkipRegion> =
        withContext(Dispatchers.IO) {
            skipRegionDao.getEnabledRegionsSync(songId)
        }

    suspend fun addSkipRegion(region: SkipRegion): Long = withContext(Dispatchers.IO) {
        skipRegionDao.insert(region)
    }

    suspend fun updateSkipRegion(region: SkipRegion) = withContext(Dispatchers.IO) {
        skipRegionDao.update(region)
    }

    suspend fun deleteSkipRegion(region: SkipRegion) = withContext(Dispatchers.IO) {
        skipRegionDao.delete(region)
    }

    suspend fun toggleSkipRegion(region: SkipRegion) = withContext(Dispatchers.IO) {
        skipRegionDao.setEnabled(region.id, !region.isEnabled)
    }
}

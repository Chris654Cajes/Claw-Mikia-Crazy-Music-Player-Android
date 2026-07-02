package com.mochimochi.clawmikiacrazy.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.model.PlaybackProfile
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileRepository(context: Context) {

    private val db = MusicDatabase.getDatabase(context)
    private val profileDao = db.playbackProfileDao()
    private val skipRegionDao = db.skipRegionDao()

    // ─── Profiles ─────────────────────────────────────────────────────────────

    fun getProfilesForSong(songId: Long): LiveData<List<PlaybackProfile>> =
        profileDao.getProfilesForSong(songId)

    fun getActiveProfileLiveData(songId: Long): LiveData<PlaybackProfile?> =
        profileDao.getActiveProfileLiveData(songId)

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
        val profile = PlaybackProfile(
            songId = songId,
            name = "Default",
            isActive = true,
            isDefault = true,
            pitchSemitones = 0f,
            playbackSpeed = 1.0f,
            volume = 1.0f,
            trimStart = 0L,
            trimEnd = -1L,
            loopStart = -1L,
            loopEnd = -1L,
            loopEnabled = false,
            abRepeatA = -1L,
            abRepeatB = -1L,
            abRepeatEnabled = false,
        )
        val id = profileDao.insert(profile)
        return profile.copy(id = id)
    }

    suspend fun createProfile(profile: PlaybackProfile): Long = withContext(Dispatchers.IO) {
        profileDao.insert(profile)
    }

    suspend fun updateProfile(profile: PlaybackProfile) = withContext(Dispatchers.IO) {
        if (profile.isDefault) return@withContext
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

    suspend fun renameProfile(profileId: Long, newName: String) = withContext(Dispatchers.IO) {
        val profile = profileDao.getProfileById(profileId)
        if (profile?.isDefault == true) return@withContext
        profileDao.updateName(profileId, newName)
    }

    suspend fun updatePitchSpeed(profileId: Long, pitch: Float, speed: Float) =
        withContext(Dispatchers.IO) {
            val profile = profileDao.getProfileById(profileId)
            if (profile?.isDefault == true) return@withContext
            profileDao.updatePitchSpeed(profileId, pitch, speed)
        }

    suspend fun updateLoop(profileId: Long, start: Long, end: Long, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            val profile = profileDao.getProfileById(profileId)
            if (profile?.isDefault == true) return@withContext
            profileDao.updateLoop(profileId, start, end, enabled)
        }

    suspend fun updateAbRepeat(profileId: Long, a: Long, b: Long, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            val profile = profileDao.getProfileById(profileId)
            if (profile?.isDefault == true) return@withContext
            profileDao.updateAbRepeat(profileId, a, b, enabled)
        }

    suspend fun updateTrim(profileId: Long, start: Long, end: Long) = withContext(Dispatchers.IO) {
        val profile = profileDao.getProfileById(profileId)
        if (profile?.isDefault == true) return@withContext
        profileDao.updateTrim(profileId, start, end)
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

    suspend fun deleteSkipRegion(region: SkipRegion) = withContext(Dispatchers.IO) {
        skipRegionDao.delete(region)
    }

    suspend fun toggleSkipRegion(region: SkipRegion) = withContext(Dispatchers.IO) {
        skipRegionDao.setEnabled(region.id, !region.isEnabled)
    }

    suspend fun deleteAllSkipRegions(songId: Long) = withContext(Dispatchers.IO) {
        skipRegionDao.deleteAllForSong(songId)
    }
}

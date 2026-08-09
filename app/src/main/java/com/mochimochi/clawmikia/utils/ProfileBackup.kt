package com.mochimochi.clawmikiacrazy.utils

import android.content.Context
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.model.PlaybackProfile
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs up / restores per-song playback profiles (and their skip regions) as JSON.
 * Only songs that own user-created ("new") profiles with updated states are exported.
 */
object ProfileBackup {

    private const val FORMAT = "clawmikia_profiles"
    private const val VERSION = 1

    data class ImportResult(
        val songsMatched: Int = 0,
        val profilesAdded: Int = 0,
        val profilesUpdated: Int = 0,
        val skipRegionsAdded: Int = 0
    )

    fun songKey(title: String, artist: String) =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    fun isProfileUpdated(p: PlaybackProfile): Boolean =
        p.pitchSemitones != 0f ||
                p.playbackSpeed != 1.0f ||
                p.volume != 1.0f ||
                p.trimStart != 0L ||
                p.trimEnd != -1L ||
                p.loopEnabled ||
                p.abRepeatEnabled ||
                p.bassBoostEnabled || p.bassBoostStrength != 0 ||
                p.reverbEnabled || p.reverbPreset != -1 ||
                p.loudnessEnabled || p.loudnessGain != 0 ||
                p.compressorEnabled ||
                p.replayGainEnabled || p.replayGainDb != 0f ||
                p.crossfadeDuration != 0

    /**
     * Builds the JSON document containing only songs that have at least one
     * user-created profile with updated state. Returns null when nothing qualifies.
     */
    suspend fun buildExportJson(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val db = MusicDatabase.getDatabase(context)
            val nonDefault = db.playbackProfileDao().getNonDefaultProfiles()
            if (nonDefault.isEmpty()) return@withContext null

            val songsById = db.songDao().getAllSongsSync().associateBy { it.id }
            val grouped = nonDefault
                .filter { songsById.containsKey(it.songId) }
                .groupBy { it.songId }

            if (grouped.isEmpty()) return@withContext null

            val root = JSONObject()
            root.put("format", FORMAT)
            root.put("version", VERSION)
            root.put("exportedAt", System.currentTimeMillis())

            val songsArray = JSONArray()
            var exportedSongs = 0
            for ((songId, profiles) in grouped) {
                val updatedProfiles = profiles.filter { isProfileUpdated(it) }
                if (updatedProfiles.isEmpty()) continue

                val song = songsById[songId] ?: continue
                val entry = JSONObject()
                entry.put("title", song.title)
                entry.put("artist", song.artist)

                val pArray = JSONArray()
                for (p in profiles) {
                    pArray.put(profileToJson(p))
                }
                entry.put("profiles", pArray)

                val regions = db.skipRegionDao().getRegionsForSongSync(songId)
                if (regions.isNotEmpty()) {
                    val rArray = JSONArray()
                    for (r in regions) rArray.put(regionToJson(r))
                    entry.put("skipRegions", rArray)
                }

                songsArray.put(entry)
                exportedSongs++
            }

            if (exportedSongs == 0) return@withContext null
            root.put("songs", songsArray)
            root
        } catch (e: Exception) {
            android.util.Log.e("ProfileBackup", "Export failed: ${e.message}")
            null
        }
    }

    /**
     * Applies a previously exported JSON document. For every entry that matches an
     * existing song (by title + artist), its profiles are added or updated and its
     * skip regions are replaced.
     */
    suspend fun importJson(context: Context, content: String): ImportResult =
        withContext(Dispatchers.IO) {
            val db = MusicDatabase.getDatabase(context)
            val profileDao = db.playbackProfileDao()
            val skipRegionDao = db.skipRegionDao()

            try {
                val root = JSONObject(content)
                if (root.optString("format") != FORMAT) return@withContext ImportResult()

                val existingByKey = db.songDao().getAllSongsSync()
                    .associateBy { songKey(it.title, it.artist) }
                val songs = root.optJSONArray("songs") ?: return@withContext ImportResult()

                var result = ImportResult()
                for (i in 0 until songs.length()) {
                    val entry = songs.optJSONObject(i) ?: continue
                    val title = entry.optString("title", "")
                    val artist = entry.optString("artist", "")
                    val song = existingByKey[songKey(title, artist)] ?: continue
                    val songId = song.id

                    val profiles = entry.optJSONArray("profiles")
                    var latestProfileId: Long? = null
                    var latestUpdatedAt = Long.MIN_VALUE
                    var activeFallbackId: Long? = null
                    var songProfilesDone = 0
                    if (profiles != null) {
                        for (j in 0 until profiles.length()) {
                            val pJson = profiles.optJSONObject(j) ?: continue
                            val name = pJson.optString("name", "").trim()
                            if (name.isEmpty()) continue

                            // ── IMPORTANT ───────────────────────────────────────────
                            // The song's Default profile is NEVER deleted, overwritten,
                            // or touched by import. Import never calls delete on
                            // playback_profiles; it only upserts user-created profiles.
                            val existing = profileDao.getProfileByName(songId, name)
                            if (existing?.isDefault == true) continue          // never modify a Default
                            if (name.equals(
                                    "Default",
                                    ignoreCase = true
                                )
                            ) continue  // never shadow a Default

                            val now = System.currentTimeMillis()
                            val profileId: Long
                            if (existing != null) {
                                profileDao.update(
                                    jsonToProfile(pJson, songId, existing)
                                        .copy(
                                            id = existing.id,
                                            isActive = existing.isActive,
                                            createdAt = existing.createdAt,
                                            updatedAt = now
                                        )
                                )
                                profileId = existing.id
                                result = result.copy(profilesUpdated = result.profilesUpdated + 1)
                            } else {
                                profileId = profileDao.insert(jsonToProfile(pJson, songId))
                                result = result.copy(profilesAdded = result.profilesAdded + 1)
                            }

                            // The newest profile (by last-modified time) becomes the current one.
                            val effectiveUpdatedAt = if (existing != null) now
                            else pJson.optLong("updatedAt", now)
                            if (effectiveUpdatedAt >= latestUpdatedAt) {
                                latestUpdatedAt = effectiveUpdatedAt
                                latestProfileId = profileId
                            }
                            if (activeFallbackId == null && pJson.optBoolean("isActive", false)) {
                                activeFallbackId = profileId
                            }
                            songProfilesDone++
                        }
                    }

                    var songMatched = songProfilesDone > 0

                    if (entry.has("skipRegions")) {
                        val regions = entry.optJSONArray("skipRegions")
                        if (regions != null && regions.length() > 0) {
                            skipRegionDao.deleteAllForSong(songId)
                            var added = 0
                            for (k in 0 until regions.length()) {
                                val rJson = regions.optJSONObject(k) ?: continue
                                skipRegionDao.insert(jsonToRegion(rJson, songId))
                                added++
                            }
                            result = result.copy(skipRegionsAdded = result.skipRegionsAdded + added)
                            songMatched = true
                        }
                    }

                    if (songMatched) {
                        // Every song always keeps its Default profile: it is never deleted,
                        // and it is recreated here if it is missing (e.g. after a full
                        // delete-all + re-scan wiped it away via FK cascade).
                        (latestProfileId ?: activeFallbackId)
                            ?.let { profileDao.activateProfile(it, songId) }

                        val profilesNow = profileDao.getProfilesForSongSync(songId)
                        val defaultId = profilesNow.firstOrNull { it.isDefault }?.id
                            ?: profileDao.insert(defaultProfile(songId))

                        if (profilesNow.none { it.isActive }) {
                            profileDao.activateProfile(defaultId, songId)
                        }

                        // Mirror the now-active profile onto the Song row so the
                        // updated-state badges (pitch / speed / trim) show up in
                        // every song list immediately after importing.
                        val activeNow = profileDao.getProfilesForSongSync(songId)
                            .firstOrNull { it.isActive }
                        if (activeNow != null) {
                            db.songDao().updatePitch(songId, activeNow.pitchSemitones)
                            db.songDao().updateSpeed(songId, activeNow.playbackSpeed)
                            db.songDao().updateTrim(songId, activeNow.trimStart, activeNow.trimEnd)
                        }
                        result = result.copy(songsMatched = result.songsMatched + 1)
                    }
                }
                result
            } catch (e: Exception) {
                android.util.Log.e("ProfileBackup", "Import failed: ${e.message}")
                ImportResult()
            }
        }

    // ─── Serialization helpers ───────────────────────────────────────────────

    private fun profileToJson(p: PlaybackProfile): JSONObject {
        val j = JSONObject()
        j.put("name", p.name)
        j.put("isActive", p.isActive)
        j.put("pitchSemitones", p.pitchSemitones.toDouble())
        j.put("playbackSpeed", p.playbackSpeed.toDouble())
        j.put("volume", p.volume.toDouble())
        j.put("trimStart", p.trimStart)
        j.put("trimEnd", p.trimEnd)
        j.put("loopStart", p.loopStart)
        j.put("loopEnd", p.loopEnd)
        j.put("loopEnabled", p.loopEnabled)
        j.put("abRepeatA", p.abRepeatA)
        j.put("abRepeatB", p.abRepeatB)
        j.put("abRepeatEnabled", p.abRepeatEnabled)
        j.put("bassBoostStrength", p.bassBoostStrength)
        j.put("bassBoostEnabled", p.bassBoostEnabled)
        j.put("reverbPreset", p.reverbPreset)
        j.put("reverbEnabled", p.reverbEnabled)
        j.put("loudnessGain", p.loudnessGain)
        j.put("loudnessEnabled", p.loudnessEnabled)
        j.put("compressorEnabled", p.compressorEnabled)
        j.put("compressorThreshold", p.compressorThreshold.toDouble())
        j.put("compressorRatio", p.compressorRatio.toDouble())
        j.put("compressorAttack", p.compressorAttack.toDouble())
        j.put("compressorRelease", p.compressorRelease.toDouble())
        j.put("replayGainDb", p.replayGainDb.toDouble())
        j.put("replayGainEnabled", p.replayGainEnabled)
        j.put("crossfadeDuration", p.crossfadeDuration)
        j.put("updatedAt", p.updatedAt)
        return j
    }

    private fun jsonToProfile(
        j: JSONObject,
        songId: Long,
        existing: PlaybackProfile? = null
    ): PlaybackProfile {
        val now = System.currentTimeMillis()
        return PlaybackProfile(
            id = existing?.id ?: 0,
            songId = songId,
            name = j.optString("name", "Profile"),
            isActive = existing?.isActive ?: false,
            isDefault = false,
            pitchSemitones = j.optDouble("pitchSemitones", 0.0).toFloat(),
            playbackSpeed = j.optDouble("playbackSpeed", 1.0).toFloat(),
            volume = j.optDouble("volume", 1.0).toFloat(),
            trimStart = j.optLong("trimStart", 0L),
            trimEnd = j.optLong("trimEnd", -1L),
            loopStart = j.optLong("loopStart", -1L),
            loopEnd = j.optLong("loopEnd", -1L),
            loopEnabled = j.optBoolean("loopEnabled", false),
            abRepeatA = j.optLong("abRepeatA", -1L),
            abRepeatB = j.optLong("abRepeatB", -1L),
            abRepeatEnabled = j.optBoolean("abRepeatEnabled", false),
            bassBoostStrength = j.optInt("bassBoostStrength", 0),
            bassBoostEnabled = j.optBoolean("bassBoostEnabled", false),
            reverbPreset = j.optInt("reverbPreset", -1),
            reverbEnabled = j.optBoolean("reverbEnabled", false),
            loudnessGain = j.optInt("loudnessGain", 0),
            loudnessEnabled = j.optBoolean("loudnessEnabled", false),
            compressorEnabled = j.optBoolean("compressorEnabled", false),
            compressorThreshold = j.optDouble("compressorThreshold", -18.0).toFloat(),
            compressorRatio = j.optDouble("compressorRatio", 4.0).toFloat(),
            compressorAttack = j.optDouble("compressorAttack", 10.0).toFloat(),
            compressorRelease = j.optDouble("compressorRelease", 100.0).toFloat(),
            replayGainDb = j.optDouble("replayGainDb", 0.0).toFloat(),
            replayGainEnabled = j.optBoolean("replayGainEnabled", false),
            crossfadeDuration = j.optInt("crossfadeDuration", 0),
            createdAt = existing?.createdAt ?: now,
            updatedAt = j.optLong("updatedAt", now)
        )
    }

    private fun regionToJson(r: SkipRegion): JSONObject {
        val j = JSONObject()
        j.put("label", r.label)
        j.put("startMs", r.startMs)
        j.put("endMs", r.endMs)
        j.put("isEnabled", r.isEnabled)
        return j
    }

    private fun jsonToRegion(j: JSONObject, songId: Long): SkipRegion {
        val now = System.currentTimeMillis()
        return SkipRegion(
            songId = songId,
            label = j.optString("label", ""),
            startMs = j.optLong("startMs", 0L),
            endMs = j.optLong("endMs", 0L),
            isEnabled = j.optBoolean("isEnabled", true),
            createdAt = now
        )
    }

    private fun defaultProfile(songId: Long): PlaybackProfile {
        val now = System.currentTimeMillis()
        return PlaybackProfile(
            songId = songId,
            name = "Default",
            isActive = false,
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
            createdAt = now,
            updatedAt = now
        )
    }
}

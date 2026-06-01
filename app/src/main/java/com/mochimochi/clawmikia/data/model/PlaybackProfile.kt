package com.mochimochi.clawmikia.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named playback configuration tied to a song.
 * Multiple profiles can exist per song (e.g. "Karaoke", "Chill", "Running").
 * The active profile is applied at playback start — original file is NEVER modified.
 */
@Entity(
    tableName = "playback_profiles",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId")]
)
data class PlaybackProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val name: String,                        // "Default", "Karaoke", "Running", etc.
    val isActive: Boolean = false,

    // Pitch & Speed
    val pitchSemitones: Float = 0f,          // -12.0 to +12.0 semitones (float for fine control)
    val playbackSpeed: Float = 1.0f,         // 0.25x to 4.0x

    // Volume
    val volume: Float = 1.0f,                // 0.0f to 1.0f

    // Trim / Timeline
    val trimStart: Long = 0L,                // ms
    val trimEnd: Long = -1L,                 // ms, -1 = song end

    // Loop
    val loopStart: Long = -1L,              // ms, -1 = disabled
    val loopEnd: Long = -1L,                // ms, -1 = disabled
    val loopEnabled: Boolean = false,

    // A-B Repeat
    val abRepeatA: Long = -1L,
    val abRepeatB: Long = -1L,
    val abRepeatEnabled: Boolean = false,

    // Effects
    val bassBoostStrength: Int = 0,          // 0–1000 (Android BassBoost range)
    val bassBoostEnabled: Boolean = false,
    val reverbPreset: Int = -1,              // -1 = off; maps to AudioEffect presets
    val reverbEnabled: Boolean = false,
    val loudnessGain: Int = 0,              // 0–1000 (Android LoudnessEnhancer)
    val loudnessEnabled: Boolean = false,
    val compressorEnabled: Boolean = false,
    val compressorThreshold: Float = -18f,  // dBFS
    val compressorRatio: Float = 4f,
    val compressorAttack: Float = 10f,      // ms
    val compressorRelease: Float = 100f,    // ms

    // Replay gain
    val replayGainDb: Float = 0f,
    val replayGainEnabled: Boolean = false,

    // Crossfade / Gapless
    val crossfadeDuration: Int = 0,         // seconds, 0 = disabled

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

package com.mochimochi.clawmikia.audio.dsp

import android.media.audiofx.*
import android.util.Log
import com.mochimochi.clawmikia.data.model.PlaybackProfile

/**
 * Manages all Android AudioEffect instances for a given audio session.
 * All effects are created lazily and released when the session ends.
 * Thread-safe via synchronized blocks on the session lock.
 */
class DSPProcessor(private val audioSessionId: Int) {

    private val TAG = "DSPProcessor"

    private var bassBoost: BassBoost? = null
    private var environmentalReverb: EnvironmentalReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    private val lock = Any()

    // ─── Bass Boost ───────────────────────────────────────────────────────────

    fun applyBassBoost(strength: Int, enabled: Boolean) = synchronized(lock) {
        try {
            if (!enabled) {
                bassBoost?.enabled = false
                return@synchronized
            }
            val bb = bassBoost ?: BassBoost(0, audioSessionId).also { bassBoost = it }
            bb.setStrength(strength.toShort().coerceIn(0, 1000))
            bb.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "BassBoost apply failed: ${e.message}")
        }
    }

    // ─── Reverb ───────────────────────────────────────────────────────────────

    fun applyReverb(presetIndex: Int, enabled: Boolean) = synchronized(lock) {
        try {
            if (!enabled || presetIndex < 0) {
                presetReverb?.enabled = false
                environmentalReverb?.enabled = false
                return@synchronized
            }
            val reverb = presetReverb ?: PresetReverb(0, audioSessionId).also { presetReverb = it }
            val preset = when (presetIndex) {
                0 -> PresetReverb.PRESET_NONE
                1 -> PresetReverb.PRESET_SMALLROOM
                2 -> PresetReverb.PRESET_MEDIUMROOM
                3 -> PresetReverb.PRESET_LARGEROOM
                4 -> PresetReverb.PRESET_MEDIUMHALL
                5 -> PresetReverb.PRESET_LARGEHALL
                6 -> PresetReverb.PRESET_PLATE
                else -> PresetReverb.PRESET_NONE
            }
            reverb.preset = preset.toShort()
            reverb.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Reverb apply failed: ${e.message}")
        }
    }

    // ─── Loudness Enhancer ────────────────────────────────────────────────────

    fun applyLoudnessEnhancer(gainMb: Int, enabled: Boolean) = synchronized(lock) {
        try {
            if (!enabled) {
                loudnessEnhancer?.enabled = false
                return@synchronized
            }
            val le =
                loudnessEnhancer ?: LoudnessEnhancer(audioSessionId).also { loudnessEnhancer = it }
            le.setTargetGain(gainMb.coerceIn(0, 1000))
            le.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer apply failed: ${e.message}")
        }
    }

    // ─── Apply Full Profile ───────────────────────────────────────────────────

    fun applyProfile(profile: PlaybackProfile) {
        applyBassBoost(profile.bassBoostStrength, profile.bassBoostEnabled)
        applyReverb(profile.reverbPreset, profile.reverbEnabled)
        applyLoudnessEnhancer(profile.loudnessGain, profile.loudnessEnabled)
    }

    fun disableAll() = synchronized(lock) {
        bassBoost?.enabled = false
        presetReverb?.enabled = false
        environmentalReverb?.enabled = false
        loudnessEnhancer?.enabled = false
    }

    fun release() = synchronized(lock) {
        runCatching { bassBoost?.release() }
        runCatching { presetReverb?.release() }
        runCatching { environmentalReverb?.release() }
        runCatching { loudnessEnhancer?.release() }
        bassBoost = null
        presetReverb = null
        environmentalReverb = null
        loudnessEnhancer = null
    }

    companion object {
        /** Human-readable names for preset reverb indices. */
        val REVERB_PRESET_NAMES = listOf(
            "None", "Small Room", "Medium Room", "Large Room",
            "Medium Hall", "Large Hall", "Plate"
        )
    }
}

package com.mochimochi.clawmikiacrazy.audio.dsp

import android.media.audiofx.*
import android.util.Log
import com.mochimochi.clawmikiacrazy.data.model.PlaybackProfile

/**
 * Manages all Android AudioEffect instances for a given audio session.
 * All effects are created lazily and released when the session ends.
 * Thread-safe via synchronized blocks on the session lock.
 */
class DSPProcessor(private val audioSessionId: Int) {

    private val TAG = "DSPProcessor"

    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var virtualizer: Virtualizer? = null
    private var environmentalReverb: EnvironmentalReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    private val lock = Any()

    fun applyEqualizerBand(band: Short, level: Short) = synchronized(lock) {
        try {
            val eq = equalizer ?: Equalizer(0, audioSessionId).also { equalizer = it }
            eq.setBandLevel(band, level)
            eq.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Equalizer apply failed: ${e.message}")
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) = synchronized(lock) {
        try {
            val eq = equalizer ?: Equalizer(0, audioSessionId).also { equalizer = it }
            eq.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Equalizer toggle failed: ${e.message}")
        }
    }

    fun getEqualizer(): Equalizer? {
        return synchronized(lock) {
            equalizer ?: try {
                Equalizer(0, audioSessionId).also { equalizer = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getBassBoostStrength(): Int = synchronized(lock) {
        return bassBoost?.roundedStrength?.toInt() ?: 0
    }

    fun getVirtualizerStrength(): Int = synchronized(lock) {
        return virtualizer?.roundedStrength?.toInt() ?: 0
    }

    // ─── Bass Boost ───────────────────────────────────────────────────────────

    fun applyBassBoost(strength: Int, enabled: Boolean) = synchronized(lock) {
        try {
            val bb = bassBoost ?: BassBoost(0, audioSessionId).also {
                it.enabled = true
                bassBoost = it 
            }
            bb.enabled = enabled
            if (enabled) {
                bb.setStrength(strength.toShort().coerceIn(0, 1000))
            }
            Log.d(TAG, "BassBoost applied: strength=$strength, enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "BassBoost apply failed: ${e.message}")
        }
    }

    fun applyVirtualizer(strength: Int, enabled: Boolean) = synchronized(lock) {
        try {
            val v = virtualizer ?: Virtualizer(0, audioSessionId).also {
                it.enabled = true
                virtualizer = it
            }
            v.enabled = enabled
            if (enabled) {
                v.setStrength(strength.toShort().coerceIn(0, 1000))
            }
            Log.d(TAG, "Virtualizer applied: strength=$strength, enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Virtualizer apply failed: ${e.message}")
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
            le.setTargetGain(gainMb.coerceIn(0, 2000))
            le.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer apply failed: ${e.message}")
        }
    }

    // ─── Apply Full Profile ───────────────────────────────────────────────────

    fun applyProfile(profile: PlaybackProfile) {
        applyBassBoost(profile.bassBoostStrength, profile.bassBoostEnabled)
        applyVirtualizer(0, false) // Profile doesn't have virtualizer field yet
        applyReverb(profile.reverbPreset, profile.reverbEnabled)
        applyLoudnessEnhancer(profile.loudnessGain, profile.loudnessEnabled)
    }

    fun disableAll() = synchronized(lock) {
        bassBoost?.enabled = false
        virtualizer?.enabled = false
        equalizer?.enabled = false
        presetReverb?.enabled = false
        environmentalReverb?.enabled = false
        loudnessEnhancer?.enabled = false
    }

    fun release() = synchronized(lock) {
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { equalizer?.release() }
        runCatching { presetReverb?.release() }
        runCatching { environmentalReverb?.release() }
        runCatching { loudnessEnhancer?.release() }
        bassBoost = null
        equalizer = null
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

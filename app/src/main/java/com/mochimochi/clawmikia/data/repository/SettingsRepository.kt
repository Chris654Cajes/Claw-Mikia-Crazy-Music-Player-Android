package com.mochimochi.clawmikiacrazy.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import com.mochimochi.clawmikiacrazy.MusicVaultApp

/**
 * Repository for app-wide user settings stored in SharedPreferences.
 * All settings are reactive via LiveData so UI auto-updates on change.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val KEY_FAVORITE_ICON = "favorite_icon_type"
        private const val KEY_VOLUME_STEP = "volume_step_percent"
        private const val KEY_PITCH_STEP = "pitch_step"
        private const val KEY_SPEED_STEP = "speed_step"
        private const val KEY_TRIM_STEP = "trim_step_seconds"
        private const val KEY_SKIP_STEP = "skip_step_seconds"
        private const val KEY_SOUND_ENVIRONMENT = "sound_environment"
        private const val KEY_VOLUME_LEVEL = "volume_level"
        private const val KEY_VOLUME_BOOST = "volume_boost"

        // Default values
        const val DEFAULT_FAVORITE_ICON = "heart"
        const val DEFAULT_VOLUME_STEP = 5f       // 5%
        const val DEFAULT_PITCH_STEP = 1.0f      // 1.0 semitones
        const val DEFAULT_SPEED_STEP = 1         // 1 unit
        const val DEFAULT_TRIM_STEP = 10.0f      // 10.0 seconds
        const val DEFAULT_SKIP_STEP = 5          // 5 seconds
        const val DEFAULT_SOUND_ENVIRONMENT = "Default"
        const val DEFAULT_VOLUME_LEVEL = 70      // 70%
        const val DEFAULT_VOLUME_BOOST = 0       // 0 milliBels
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(MusicVaultApp.PREFS_NAME, Context.MODE_PRIVATE)

    // ── LiveData wrappers ─────────────────────────────────────────────────────

    val favoriteIconLive: LiveData<String> =
        SharedPrefStringLiveData(prefs, KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON)

    val volumeLevelLive: LiveData<Int> =
        SharedPrefIntLiveData(prefs, KEY_VOLUME_LEVEL, DEFAULT_VOLUME_LEVEL)

    val volumeBoostLive: LiveData<Int> =
        SharedPrefIntLiveData(prefs, KEY_VOLUME_BOOST, DEFAULT_VOLUME_BOOST)

    // ── Direct getters ────────────────────────────────────────────────────────

    fun getFavoriteIcon(): String =
        prefs.getString(KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON) ?: DEFAULT_FAVORITE_ICON

    fun getVolumeStep(): Float = prefs.getFloat(KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP)
    fun getPitchStep(): Float = prefs.getFloat(KEY_PITCH_STEP, DEFAULT_PITCH_STEP)
    fun getSpeedStep(): Int = prefs.getInt(KEY_SPEED_STEP, DEFAULT_SPEED_STEP)
    fun getTrimStep(): Float = prefs.getFloat(KEY_TRIM_STEP, DEFAULT_TRIM_STEP)
    fun getSkipStep(): Int = prefs.getInt(KEY_SKIP_STEP, DEFAULT_SKIP_STEP)

    fun getSoundEnvironment(): String =
        prefs.getString(KEY_SOUND_ENVIRONMENT, DEFAULT_SOUND_ENVIRONMENT)
            ?: DEFAULT_SOUND_ENVIRONMENT

    fun getVolumeLevel(): Int = prefs.getInt(KEY_VOLUME_LEVEL, DEFAULT_VOLUME_LEVEL)
    fun getVolumeBoost(): Int = prefs.getInt(KEY_VOLUME_BOOST, DEFAULT_VOLUME_BOOST)

    // ── Setters ────────────────────────────────────────────────────────────────

    fun setFavoriteIcon(iconType: String) {
        prefs.edit().putString(KEY_FAVORITE_ICON, iconType).apply()
    }

    fun setVolumeStep(step: Float) {
        prefs.edit().putFloat(KEY_VOLUME_STEP, step).apply()
    }

    fun setPitchStep(step: Float) {
        prefs.edit().putFloat(KEY_PITCH_STEP, step).apply()
    }

    fun setSpeedStep(step: Int) {
        prefs.edit().putInt(KEY_SPEED_STEP, step).apply()
    }

    fun setTrimStep(step: Float) {
        prefs.edit().putFloat(KEY_TRIM_STEP, step).apply()
    }

    fun setSkipStep(step: Int) {
        prefs.edit().putInt(KEY_SKIP_STEP, step).apply()
    }

    fun setSoundEnvironment(env: String) {
        prefs.edit().putString(KEY_SOUND_ENVIRONMENT, env).apply()
    }

    fun setVolumeLevel(level: Int) {
        prefs.edit().putInt(KEY_VOLUME_LEVEL, level).apply()
    }

    fun setVolumeBoost(boostMb: Int) {
        prefs.edit().putInt(KEY_VOLUME_BOOST, boostMb).apply()
    }

    // ── Reset all to defaults ──────────────────────────────────────────────────

    fun resetAll() {
        prefs.edit()
            .putString(KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON)
            .putFloat(KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP)
            .putFloat(KEY_PITCH_STEP, DEFAULT_PITCH_STEP)
            .putInt(KEY_SPEED_STEP, DEFAULT_SPEED_STEP)
            .putFloat(KEY_TRIM_STEP, DEFAULT_TRIM_STEP)
            .putInt(KEY_SKIP_STEP, DEFAULT_SKIP_STEP)
            .putString(KEY_SOUND_ENVIRONMENT, DEFAULT_SOUND_ENVIRONMENT)
            .putInt(KEY_VOLUME_LEVEL, DEFAULT_VOLUME_LEVEL)
            .putInt(KEY_VOLUME_BOOST, DEFAULT_VOLUME_BOOST)
            .apply()
    }


    // ── LiveData implementations ──────────────────────────────────────────────

    private class SharedPrefStringLiveData(
        private val prefs: SharedPreferences,
        private val key: String,
        private val defValue: String,
    ) : LiveData<String>(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onActive() {
            super.onActive()
            value = prefs.getString(key, defValue) ?: defValue
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onInactive() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onInactive()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, changedKey: String?) {
            if (changedKey == key) value = sp.getString(key, defValue) ?: defValue
        }
    }

    private class SharedPrefIntLiveData(
        private val prefs: SharedPreferences,
        private val key: String,
        private val defValue: Int,
    ) : LiveData<Int>(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onActive() {
            super.onActive()
            value = prefs.getInt(key, defValue)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onInactive() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onInactive()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, changedKey: String?) {
            if (changedKey == key) value = sp.getInt(key, defValue)
        }
    }
}


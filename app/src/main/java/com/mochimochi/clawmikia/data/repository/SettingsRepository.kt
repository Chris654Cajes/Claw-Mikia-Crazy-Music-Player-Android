package com.mochimochi.clawmikia.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import com.mochimochi.clawmikia.MusicVaultApp

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

        // Default values
        const val DEFAULT_FAVORITE_ICON = "heart"
        const val DEFAULT_VOLUME_STEP = 5f       // 5%
        const val DEFAULT_PITCH_STEP = 1.0f      // 1.0 semitones
        const val DEFAULT_SPEED_STEP = 1         // 1 unit
        const val DEFAULT_TRIM_STEP = 10.0f      // 10.0 seconds
        const val DEFAULT_SKIP_STEP = 5          // 5 seconds
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(MusicVaultApp.PREFS_NAME, Context.MODE_PRIVATE)

    // ── LiveData wrappers ─────────────────────────────────────────────────────

    val favoriteIconLive: LiveData<String> =
        SharedPrefLiveData(prefs, KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON)
    val volumeStepLive: LiveData<Float> =
        SharedPrefLiveDataFloat(prefs, KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP)
    val pitchStepLive: LiveData<Float> =
        SharedPrefLiveDataFloat(prefs, KEY_PITCH_STEP, DEFAULT_PITCH_STEP)
    val speedStepLive: LiveData<Int> =
        SharedPrefLiveDataInt(prefs, KEY_SPEED_STEP, DEFAULT_SPEED_STEP)
    val trimStepLive: LiveData<Float> =
        SharedPrefLiveDataFloat(prefs, KEY_TRIM_STEP, DEFAULT_TRIM_STEP)
    val skipStepLive: LiveData<Int> =
        SharedPrefLiveDataInt(prefs, KEY_SKIP_STEP, DEFAULT_SKIP_STEP)

    // ── Direct getters ────────────────────────────────────────────────────────

    fun getFavoriteIcon(): String =
        prefs.getString(KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON) ?: DEFAULT_FAVORITE_ICON

    fun getVolumeStep(): Float = prefs.getFloat(KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP)
    fun getPitchStep(): Float = prefs.getFloat(KEY_PITCH_STEP, DEFAULT_PITCH_STEP)
    fun getSpeedStep(): Int = prefs.getInt(KEY_SPEED_STEP, DEFAULT_SPEED_STEP)
    fun getTrimStep(): Float = prefs.getFloat(KEY_TRIM_STEP, DEFAULT_TRIM_STEP)
    fun getSkipStep(): Int = prefs.getInt(KEY_SKIP_STEP, DEFAULT_SKIP_STEP)

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

    // ── Reset all to defaults ──────────────────────────────────────────────────

    fun resetAll() {
        prefs.edit()
            .putString(KEY_FAVORITE_ICON, DEFAULT_FAVORITE_ICON)
            .putFloat(KEY_VOLUME_STEP, DEFAULT_VOLUME_STEP)
            .putFloat(KEY_PITCH_STEP, DEFAULT_PITCH_STEP)
            .putInt(KEY_SPEED_STEP, DEFAULT_SPEED_STEP)
            .putFloat(KEY_TRIM_STEP, DEFAULT_TRIM_STEP)
            .putInt(KEY_SKIP_STEP, DEFAULT_SKIP_STEP)
            .apply()
    }

    // ── LiveData implementations ──────────────────────────────────────────────

    private class SharedPrefLiveData(
        private val prefs: SharedPreferences,
        private val key: String,
        private val defValue: String
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

    private class SharedPrefLiveDataFloat(
        private val prefs: SharedPreferences,
        private val key: String,
        private val defValue: Float
    ) : LiveData<Float>(), SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onActive() {
            super.onActive()
            value = prefs.getFloat(key, defValue)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onInactive() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onInactive()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, changedKey: String?) {
            if (changedKey == key) value = sp.getFloat(key, defValue)
        }
    }

    private class SharedPrefLiveDataInt(
        private val prefs: SharedPreferences,
        private val key: String,
        private val defValue: Int
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

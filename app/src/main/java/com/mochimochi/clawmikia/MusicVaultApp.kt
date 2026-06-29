package com.mochimochi.clawmikiacrazy

import android.app.Application
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class MusicVaultApp : Application() {

    companion object {
        const val PREFS_NAME = "music_vault_prefs"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE_ON = "shuffle_on"
        lateinit var instance: MusicVaultApp
    }

    lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyTheme()
    }

    fun applyTheme() {
        val isDark = prefs.getBoolean(KEY_DARK_MODE, true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO,
        )
    }
}

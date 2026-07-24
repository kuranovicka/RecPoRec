package com.recporec.app.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("recporec_settings", Context.MODE_PRIVATE)

    var backgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    var uninterruptedEnabled: Boolean
        get() = prefs.getBoolean(KEY_UNINTERRUPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNINTERRUPTED, value).apply()

    var shakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHAKE, value).apply()

    // Opšta (globalna) podešavanja glasa - važe za svaki dokument dok se ne prepiše posebno
    var globalLanguageTag: String?
        get() = prefs.getString(KEY_G_LANG, null)
        set(value) = prefs.edit().putString(KEY_G_LANG, value).apply()

    var globalVoiceName: String?
        get() = prefs.getString(KEY_G_VOICE, null)
        set(value) = prefs.edit().putString(KEY_G_VOICE, value).apply()

    var globalVoiceEngine: String?
        get() = prefs.getString(KEY_G_ENGINE, null)
        set(value) = prefs.edit().putString(KEY_G_ENGINE, value).apply()

    var globalSpeechRate: Float
        get() = prefs.getFloat(KEY_G_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_G_RATE, value).apply()

    var globalVolumePercent: Int
        get() = prefs.getInt(KEY_G_VOLUME, 100)
        set(value) = prefs.edit().putInt(KEY_G_VOLUME, value).apply()

    companion object {
        private const val KEY_BACKGROUND = "background_enabled"
        private const val KEY_UNINTERRUPTED = "uninterrupted_enabled"
        private const val KEY_SHAKE = "shake_enabled"
        private const val KEY_G_LANG = "global_language_tag"
        private const val KEY_G_VOICE = "global_voice_name"
        private const val KEY_G_ENGINE = "global_voice_engine"
        private const val KEY_G_RATE = "global_speech_rate"
        private const val KEY_G_VOLUME = "global_volume_percent"
    }
}

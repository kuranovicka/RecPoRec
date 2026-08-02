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

    /** "Automatski čitaj aktivni dokument" - glavni prekidač. */
    var autoReadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_ENABLED, value).apply()

    /** "app" = pri otvaranju aplikacije (poslednji aktivni dokument), "document" = pri
     * otvaranju BILO KOG dokumenta (onaj koji je upravo otvoren). */
    var autoReadTrigger: String
        get() = prefs.getString(KEY_AUTO_READ_TRIGGER, "app") ?: "app"
        set(value) = prefs.edit().putString(KEY_AUTO_READ_TRIGGER, value).apply()

    /** Da li je ikad zatraženo dozvola za stanje telefona (rezervni mehanizam za pozive) -
     * pitamo samo JEDNOM, čak i ako korisnik odbije, da ne dosađujemo pri svakom otvaranju. */
    var phoneStatePermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PHONE_STATE_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_STATE_ASKED, value).apply()

    /** Osetljivost drmanja: 0 = blago, 1 = srednje (podrazumevano), 2 = jako. */
    var shakeSensitivity: Int
        get() = prefs.getInt(KEY_SHAKE_SENSITIVITY, 1)
        set(value) = prefs.edit().putInt(KEY_SHAKE_SENSITIVITY, value).apply()

    /** Nacin pomeranja dugmadima * i # u citacu: "page", "min1", "min5" ili "min10". */
    var navigationMode: String
        get() = prefs.getString(KEY_NAVIGATION, "page") ?: "page"
        set(value) = prefs.edit().putString(KEY_NAVIGATION, value).apply()

    var soundFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    /** Pauza između rečenica tokom čitanja - podrazumevano isključena. */
    var sentencePauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENTENCE_PAUSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SENTENCE_PAUSE_ENABLED, value).apply()

    /** Trajanje pauze između rečenica u milisekundama (klizač, 0-1000). */
    var sentencePauseMs: Int
        get() = prefs.getInt(KEY_SENTENCE_PAUSE_MS, 300)
        set(value) = prefs.edit().putInt(KEY_SENTENCE_PAUSE_MS, value).apply()

    /** Pauza između pasusa (paragrafa) - odvojena od pauze između rečenica, podrazumevano
     * isključena. Kad je uključena, primenjuje se UMESTO obične pauze na kraju pasusa. */
    var paragraphPauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_PARAGRAPH_PAUSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PARAGRAPH_PAUSE_ENABLED, value).apply()

    /** Trajanje pauze između pasusa u milisekundama (klizač, 0-3000). */
    var paragraphPauseMs: Int
        get() = prefs.getInt(KEY_PARAGRAPH_PAUSE_MS, 800)
        set(value) = prefs.edit().putInt(KEY_PARAGRAPH_PAUSE_MS, value).apply()

    /** Kad se dokument do kraja pročita, automatski pređi na čitanje sledećeg u listi. */
    var autoNextDocumentEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply()

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

    var globalPitch: Float
        get() = prefs.getFloat(KEY_G_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_G_PITCH, value).apply()

    /** Vraća SVA opšta podešavanja glasa (jezik/glas/brzina/jačina/visina) na podrazumevano.
     * Ne dira podešavanja pojedinačnih dokumenata niti opšta podešavanja iz ekrana "Podešavanja". */
    fun resetVoiceSettingsToDefaults() {
        prefs.edit()
            .remove(KEY_G_LANG)
            .remove(KEY_G_VOICE)
            .remove(KEY_G_ENGINE)
            .remove(KEY_G_RATE)
            .remove(KEY_G_VOLUME)
            .remove(KEY_G_PITCH)
            .apply()
    }

    /** Vraća SVA podešavanja sa ekrana "Podešavanja" na podrazumevano. Ne dira opšta
     * podešavanja glasa niti podešavanja pojedinačnih dokumenata. */
    fun resetGeneralSettingsToDefaults() {
        prefs.edit()
            .remove(KEY_BACKGROUND)
            .remove(KEY_UNINTERRUPTED)
            .remove(KEY_SHAKE)
            .remove(KEY_SHAKE_SENSITIVITY)
            .remove(KEY_NAVIGATION)
            .remove(KEY_SOUND)
            .remove(KEY_SENTENCE_PAUSE_ENABLED)
            .remove(KEY_SENTENCE_PAUSE_MS)
            .remove(KEY_PARAGRAPH_PAUSE_ENABLED)
            .remove(KEY_PARAGRAPH_PAUSE_MS)
            .remove(KEY_AUTO_NEXT)
            .remove(KEY_AUTO_READ_ENABLED)
            .remove(KEY_AUTO_READ_TRIGGER)
            .apply()
    }

    companion object {
        private const val KEY_BACKGROUND = "background_enabled"
        private const val KEY_UNINTERRUPTED = "uninterrupted_enabled"
        private const val KEY_SHAKE = "shake_enabled"
        private const val KEY_PHONE_STATE_ASKED = "phone_state_permission_asked"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_NAVIGATION = "navigation_mode"
        private const val KEY_SOUND = "sound_feedback_enabled"
        private const val KEY_SENTENCE_PAUSE_ENABLED = "sentence_pause_enabled"
        private const val KEY_SENTENCE_PAUSE_MS = "sentence_pause_ms"
        private const val KEY_PARAGRAPH_PAUSE_ENABLED = "paragraph_pause_enabled"
        private const val KEY_PARAGRAPH_PAUSE_MS = "paragraph_pause_ms"
        private const val KEY_AUTO_NEXT = "auto_next_document_enabled"
        private const val KEY_G_LANG = "global_language_tag"
        private const val KEY_G_VOICE = "global_voice_name"
        private const val KEY_G_ENGINE = "global_voice_engine"
        private const val KEY_G_RATE = "global_speech_rate"
        private const val KEY_G_VOLUME = "global_volume_percent"
        private const val KEY_G_PITCH = "global_pitch"
        private const val KEY_AUTO_READ_ENABLED = "auto_read_enabled"
        private const val KEY_AUTO_READ_TRIGGER = "auto_read_trigger"
    }
}

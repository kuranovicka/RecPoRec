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

    /** "Rečnik izgovora" - ugrađeni (bundled) rečnik NIJE uključen po difoltu (niko ga ne
     * dobija nametnuto) - korisnik ga sam uključuje u podešavanjima ako želi. Ne utiče na
     * korisnikov sopstveni rečnik (PronunciationEntity), koji radi nezavisno od ovoga. */
    var builtInPronunciationDictionaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUILTIN_PRONUNCIATION_DICT, false)
        set(value) = prefs.edit().putBoolean(KEY_BUILTIN_PRONUNCIATION_DICT, value).apply()

    /** "Automatski čitaj aktivni dokument" - glavni prekidač. */
    var autoReadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_ENABLED, value).apply()

    /** "app" = pri otvaranju aplikacije (poslednji aktivni dokument), "document" = pri
     * otvaranju BILO KOG dokumenta (onaj koji je upravo otvoren). */
    var autoReadTrigger: String
        get() = prefs.getString(KEY_AUTO_READ_TRIGGER, "app") ?: "app"
        set(value) = prefs.edit().putString(KEY_AUTO_READ_TRIGGER, value).apply()

    /** Da li je korisnica POSLEDNJI put SAMA, svesno pauzirala čitanje (dugmetom, drmanjem...)
     * - ako jeste, automatsko čitanje (bilo koje vrste) NE treba samo da nastavi umesto nje,
     * u svim slučajevima. Vraća se na false čim ONA sama ponovo pokrene čitanje. */
    var userManuallyPaused: Boolean
        get() = prefs.getBoolean(KEY_USER_MANUALLY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_MANUALLY_PAUSED, value).apply()

    /** Ova cetiri polja "preziveljavaju" i gasenje procesa dok se ceka na budjenje/zakazano
     * citanje (za razliku od odgovarajucih polja u PlaybackController, koja su SAMO u
     * memoriji i nestaju ako Android u medjuvremenu ubije app - npr. tokom cele noci dok
     * ceka jutarnje budjenje). AlarmManager i dalje pouzdano budi PROCES na vreme, ali bez
     * ovoga ne bi bilo nacina da se zna KOJI dokument i KOJA vrsta budjenja je bila u pitanju
     * kad se ttsManager/currentDocument izgube zajedno sa ubijenim procesom. -1 = nista
     * zakazano. */
    var pendingWakeDocumentId: Long
        get() = prefs.getLong(KEY_PENDING_WAKE_DOC_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_PENDING_WAKE_DOC_ID, value).apply()

    var pendingWakeTargetElapsedMillis: Long
        get() = prefs.getLong(KEY_PENDING_WAKE_TARGET_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_WAKE_TARGET_ELAPSED, value).apply()

    /** ISTO vreme kao pendingWakeTargetElapsedMillis, ali u "wall clock" obliku
     * (System.currentTimeMillis(), ne SystemClock.elapsedRealtime()) - elapsedRealtime se
     * RESETUJE na 0 posle svakog restarta telefona, pa je beskoristan za ponovno zakazivanje
     * POSLE restarta. Ovo polje postoji bas za to: BootRescheduleReceiver ga koristi da vrati
     * "Probudi me u"/"Zakazi citanje" alarm koji bi inace bio tiho izgubljen restartom. */
    var pendingWakeTargetWallMillis: Long
        get() = prefs.getLong(KEY_PENDING_WAKE_TARGET_WALL, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_WAKE_TARGET_WALL, value).apply()

    var pendingWakeIsWakeTime: Boolean
        get() = prefs.getBoolean(KEY_PENDING_WAKE_IS_WAKE_TIME, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_WAKE_IS_WAKE_TIME, value).apply()

    var pendingWakeSuppressAlarm: Boolean
        get() = prefs.getBoolean(KEY_PENDING_WAKE_SUPPRESS_ALARM, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_WAKE_SUPPRESS_ALARM, value).apply()

    /** Brise sacuvano stanje budjenja - poziva se cim se budjenje/zakazano citanje otkaze ili
     * uspesno okine, da "zaboravljeno" stanje ne bi kasnije pogresno "oziveo" neki naredni,
     * nepovezani AlarmManager dogadjaj. */
    fun clearPendingWake() {
        prefs.edit()
            .remove(KEY_PENDING_WAKE_DOC_ID)
            .remove(KEY_PENDING_WAKE_TARGET_ELAPSED)
            .remove(KEY_PENDING_WAKE_TARGET_WALL)
            .remove(KEY_PENDING_WAKE_IS_WAKE_TIME)
            .remove(KEY_PENDING_WAKE_SUPPRESS_ALARM)
            .apply()
    }

    /** Da li su kontrole (sve osim dugmeta Kontrole i Pokreni/Pauziraj) sakrivene za TAČNO
     * OVAJ dokument - pamti se po dokumentu (ne globalno za app), jer korisnica moze da zeli
     * "cist" prikaz samo za jednu knjigu, ne za sve. Prezivljava zatvaranje/ponovno otvaranje
     * i dokumenta i cele aplikacije. */
    fun isControlsHiddenForDocument(documentId: Long): Boolean =
        prefs.getBoolean("$KEY_CONTROLS_HIDDEN_PREFIX$documentId", false)

    fun setControlsHiddenForDocument(documentId: Long, hidden: Boolean) {
        prefs.edit().putBoolean("$KEY_CONTROLS_HIDDEN_PREFIX$documentId", hidden).apply()
    }

    /** Da li je ikad zatraženo dozvola za stanje telefona (rezervni mehanizam za pozive) -
     * pitamo samo JEDNOM, čak i ako korisnik odbije, da ne dosađujemo pri svakom otvaranju. */
    var phoneStatePermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PHONE_STATE_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_STATE_ASKED, value).apply()

    /** Da li je ikad ponuđen izuzetak od štednje baterije - pitamo samo JEDNOM (ikad), bez
     * obzira da li je "Čitanje bez prekida" ukljuceno, jer bez ovoga sistem (posebno na
     * pojedinim proizvođačima kao Samsung) može da uguši servis za čitanje u pozadini pre nego
     * što notifikacija ili medijski tasteri stignu pouzdano da rade. */
    var batteryOptimizationAsked: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPT_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_OPT_ASKED, value).apply()

    /** Da li smo VEĆ JEDNOM prošli kroz sistemski zahtev za dozvolu obaveštenja - Android ume
     * da posle prve/druge odbijene ponude "trajno odbije" bez ikakvog daljeg dijaloga (nema
     * ni indikacije da se to desilo) - naredni requestPermission() pozivi tiho ne rade ništa.
     * Ako je ovo već true a dozvola i dalje nije data, znak je da treba PRIRUČNO uputiti
     * korisnicu na Podešavanja telefona, umesto da se ćutke ponovo (bezuspešno) pokuša isti
     * sistemski zahtev. */
    var notificationPermissionRequestedOnce: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_PERMISSION_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_REQUESTED, value).apply()

    /** Da li je ikad ponuđena dozvola za pun ekran preko zaključanog ekrana (za "Probudi me
     * u") - pitamo je SAD odmah pri prvom korišćenju, zajedno sa ostalim dozvolama, umesto da
     * čekamo da korisnica prvi put stvarno postavi buđenje (da je ne bi zbunilo iskakanje
     * dozvola u nezgodnom trenutku, npr. tokom poziva). */
    var fullScreenIntentAsked: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN_INTENT_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN_INTENT_ASKED, value).apply()

    /** Osetljivost drmanja: 0 = blago, 1 = srednje (podrazumevano), 2 = jako. */
    var shakeSensitivity: Int
        get() = prefs.getInt(KEY_SHAKE_SENSITIVITY, 1)
        set(value) = prefs.edit().putInt(KEY_SHAKE_SENSITIVITY, value).apply()

    /** Nacin pomeranja dugmadima * i # u citacu: "page", "min1", "min5" ili "min10". */
    var navigationMode: String
        get() = prefs.getString(KEY_NAVIGATION, "page") ?: "page"
        set(value) = prefs.edit().putString(KEY_NAVIGATION, value).apply()

    /** Poslednja odabrana kartica na glavnom ekranu (Sve/Novo/Zapoceto/Procitano) - pamti se
     * da bi se app otvorila na istoj kartici na kojoj je poslednji put ostavljena, umesto da
     * se uvek vraca na "Sve". Korisnicka ideja - "naš korisnik" (rec-po-rec grupa). */
    var lastLibraryTab: String
        get() = prefs.getString(KEY_LAST_LIBRARY_TAB, "all") ?: "all"
        set(value) = prefs.edit().putString(KEY_LAST_LIBRARY_TAB, value).apply()

    /** Broj rečenica po koraku kad je Navigacija postavljena na "Rečenice" - JEDAN broj,
     * koristi se i za prethodni i za sledeći korak (korisnicki zahtev - jednostavnije od
     * odvojenog pamcenja po pravcu). Uvek 1, 3 ili 5 (bira se iz spiska u Podesavanjima,
     * isto kao ostale Navigacija opcije - ne slobodan unos). */
    var sentenceNavigationCount: Int
        get() = prefs.getInt(KEY_SENTENCE_NAV_COUNT, 1)
        set(value) = prefs.edit().putInt(KEY_SENTENCE_NAV_COUNT, value).apply()

    /** Broj minuta po koraku kad je Navigacija na "Minuti" - konsolidovano iz tri odvojene
     * opcije (1/5/10 minuta) u jednu, sa pod-izborom - isti obrazac kao rečenice. */
    var minuteNavigationCount: Int
        get() = prefs.getInt(KEY_MINUTE_NAV_COUNT, 1)
        set(value) = prefs.edit().putInt(KEY_MINUTE_NAV_COUNT, value).apply()

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
    /** Korisnicki zahtev: "Vrati na zadano" se sad odnosi na SVA podesavanja odjednom (glas +
     * opsta podesavanja), umesto dva odvojena dugmeta koja resetuju samo deo. Jednostavnije. */
    fun resetAllSettingsToDefaults() {
        resetVoiceSettingsToDefaults()
        resetGeneralSettingsToDefaults()
    }

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
            .remove(KEY_SENTENCE_NAV_COUNT)
            .remove(KEY_MINUTE_NAV_COUNT)
            .remove(KEY_BUILTIN_PRONUNCIATION_DICT)
            .apply()
    }

    /** Izvozi SAMO prava, trajna podešavanja (glas, brzina, prekidači...) u JSON tekst -
     * NAMERNO isključuje: "_asked" zastavice (ponovo pitati na novom uređaju ima smisla, jer
     * stvarna dozvola tamo možda nije data), "pending_wake_*" (zakazano buđenje je vezano za
     * konkretan trenutak, besmisleno prenositi), i "controls_hidden_doc_*" (vezano za ID
     * dokumenta na OVOM uređaju, neće se poklapati na drugom). Koristi eksplicitnu listu
     * (whitelist), ne "izuzmi ovo" - sigurnije ako se doda neko novo, buduće podešavanje. */
    fun exportAsJson(): String {
        val json = org.json.JSONObject()
        val all = prefs.all
        for (key in EXPORTABLE_KEYS) {
            if (all.containsKey(key)) json.put(key, all[key])
        }
        return json.toString(2)
    }

    /** Uvozi podešavanja iz JSON teksta (izvezenog preko exportAsJson) - prepoznaje SAMO iste
     * one ključeve sa liste, ignoriše sve ostalo (npr. ako je fajl iz novije verzije aplikacije
     * sa poljima koja ova verzija ne zna da čita). Vraća broj uspešno primenjenih podešavanja. */
    fun importFromJson(jsonText: String): Int {
        val json = org.json.JSONObject(jsonText)
        val editor = prefs.edit()
        var count = 0
        for (key in EXPORTABLE_KEYS) {
            if (!json.has(key)) continue
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }

    companion object {
        private const val KEY_BACKGROUND = "background_enabled"
        private const val KEY_UNINTERRUPTED = "uninterrupted_enabled"
        private const val KEY_SHAKE = "shake_enabled"
        private const val KEY_BUILTIN_PRONUNCIATION_DICT = "builtin_pronunciation_dict_enabled"
        private const val KEY_PHONE_STATE_ASKED = "phone_state_permission_asked"
        private const val KEY_BATTERY_OPT_ASKED = "battery_optimization_asked"
        private const val KEY_NOTIF_PERMISSION_REQUESTED = "notification_permission_requested_once"
        private const val KEY_FULLSCREEN_INTENT_ASKED = "fullscreen_intent_asked"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_NAVIGATION = "navigation_mode"
        private const val KEY_LAST_LIBRARY_TAB = "last_library_tab"
        private const val KEY_SENTENCE_NAV_COUNT = "sentence_nav_count"
        private const val KEY_MINUTE_NAV_COUNT = "minute_nav_count"
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
        private const val KEY_USER_MANUALLY_PAUSED = "user_manually_paused"
        private const val KEY_PENDING_WAKE_DOC_ID = "pending_wake_doc_id"
        private const val KEY_PENDING_WAKE_TARGET_ELAPSED = "pending_wake_target_elapsed"
        private const val KEY_PENDING_WAKE_TARGET_WALL = "pending_wake_target_wall"
        private const val KEY_CONTROLS_HIDDEN_PREFIX = "controls_hidden_doc_"
        private const val KEY_PENDING_WAKE_IS_WAKE_TIME = "pending_wake_is_wake_time"
        private const val KEY_PENDING_WAKE_SUPPRESS_ALARM = "pending_wake_suppress_alarm"

        /** Prava, trajna podešavanja korisnice - vidi objašnjenje uz exportAsJson(). */
        private val EXPORTABLE_KEYS = listOf(
            KEY_BACKGROUND, KEY_UNINTERRUPTED, KEY_SHAKE, KEY_SHAKE_SENSITIVITY,
            KEY_NAVIGATION, KEY_SOUND, KEY_SENTENCE_PAUSE_ENABLED, KEY_SENTENCE_PAUSE_MS,
            KEY_PARAGRAPH_PAUSE_ENABLED, KEY_PARAGRAPH_PAUSE_MS, KEY_AUTO_NEXT,
            KEY_G_LANG, KEY_G_VOICE, KEY_G_ENGINE, KEY_G_RATE, KEY_G_VOLUME, KEY_G_PITCH,
            KEY_AUTO_READ_ENABLED, KEY_AUTO_READ_TRIGGER, KEY_SENTENCE_NAV_COUNT, KEY_MINUTE_NAV_COUNT,
            KEY_BUILTIN_PRONUNCIATION_DICT
        )
    }
}

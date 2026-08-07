package com.recporec.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.recporec.app.data.AppSettings
import com.recporec.app.tts.PlaybackController
import com.recporec.app.ui.ReaderActivity
import com.recporec.app.util.ShakeDetector

class ReadingService : MediaBrowserServiceCompat() {

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // Detekcija drmanja živi ovde (ne u ReaderActivity) da bi pauza/nastavak drmanjem
    // radili i dok je čitanje u pozadini. Namerno NE dira dodir ekrana ni MediaSession
    // stanje (to je pravilo problema sa TalkBack-om ranije) - čisto senzor -> toggle.
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null

    // Rezervni, DIREKTAN mehanizam za pauzu pri pozivu - odvojen od audio fokusa (koji se na
    // nekim uredjajima pokazao nepouzdanim, vidi TtsManager.pauseForCall/resumeForCall).
    // Opciono - ako korisnik nije dao dozvolu za stanje telefona, ova zastita jednostavno
    // ne radi, app inace normalno funkcionise (audio fokus i dalje pokusava da pauzira).
    private var telephonyManager: android.telephony.TelephonyManager? = null
    private var legacyPhoneStateListener: android.telephony.PhoneStateListener? = null
    private var telephonyCallback: Any? = null // TelephonyCallback, samo na API 31+

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        setupCallStateListener()
        mediaSession = MediaSessionCompat(this, "RecPoRecSession").apply {
            // Za sirok opseg Android verzija/uredjaja - eksplicitno oznaci da sesija obradjuje
            // medijske tastere i transportne komande (play/pauza/skip). Na novijim verzijama
            // AndroidX-a je ovo vec podrazumevano, ali ne smeta i pomaze na starijim/manje
            // uobicajenim uredjajima (razlog za ovo istrazivanje - spoljne Bluetooth
            // slusalice/tastature).
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    // Zastita od udvostrucene komande - neki Bluetooth uredjaji/AVRCP steka
                    // znaju povremeno da posalju PLAY i kad VEC svira, sto bi inace nasilno
                    // restartovalo trenutnu recenicu usred citanja.
                    if (PlaybackController.ttsManager?.isSpeaking != true) {
                        PlaybackController.resumeCancelingRestIfNeeded()
                        // KRITICNO: bez ovoga, MediaSession i dalje "misli" da je citanje
                        // pauzirano (staro stanje) - sledeci pritisak na JEDAN kombinovan
                        // play/pauza taster (kakav ima spoljna tastatura) bi sistem opet
                        // usmerio na onPause() umesto na onPlay(), jer sistem ROUTE-uje
                        // kombinovani taster prema NASEM POSLEDNJE PRIJAVLJENOM stanju, ne
                        // prema stvarnom. Prijava korisnika: "nastavak citanja nakon pauze
                        // ne funkcionise" na spoljnoj tastaturi - ovo je pravi uzrok.
                        PlaybackController.notifyPlaybackStateChanged()
                    }
                }
                override fun onPause() {
                    if (PlaybackController.ttsManager?.isSpeaking == true) {
                        PlaybackController.ttsManager?.pause()
                        AppSettings(this@ReadingService).userManuallyPaused = true
                        // Isti razlog kao gore - prijavi NOVO (pauzirano) stanje odmah, ne
                        // cekaj da nesto drugo to uradi.
                        PlaybackController.notifyPlaybackStateChanged()
                    }
                }
                // Neki uredjaji (posebno spoljne tastature) imaju POSEBAN taster "Stop",
                // razlicit od Pauze - bez ovoga bi taj taster tiho ne uradio nista. Ponasa se
                // isto kao Pauza (nemamo poseban koncept "potpuno zaustavi" razlicit od pauze).
                override fun onStop() {
                    if (PlaybackController.ttsManager?.isSpeaking == true) {
                        PlaybackController.ttsManager?.pause()
                        AppSettings(this@ReadingService).userManuallyPaused = true
                        PlaybackController.notifyPlaybackStateChanged()
                    }
                }
                // Tasteri za premotavanje na slušalicama/spoljnoj tastaturi - standardni
                // Android mehanizam za medijske tastere, isto kao play/pauza iznad. Nije
                // vezano za dodir ekrana pa ne remeti TalkBack, isto kao i drmanje.
                override fun onSkipToNext() { PlaybackController.stepNavigate(true, applicationContext) }
                override fun onSkipToPrevious() { PlaybackController.stepNavigate(false, applicationContext) }
                override fun onFastForward() { PlaybackController.stepNavigate(true, applicationContext) }
                override fun onRewind() { PlaybackController.stepNavigate(false, applicationContext) }
            })
            isActive = true
        }
        // KLJUCNA VEZA za MediaBrowserServiceCompat - bez ovoga, "pretvaranje" servisa u
        // MediaBrowserServiceCompat ne bi nista promenilo, sistem i dalje ne bi znao koja je
        // NASA sesija. Istrazivanje: Bluetooth/AVRCP stek na telefonu prepoznaje aplikaciju
        // kao "pravog" medijskog plejera pouzdanije kad ona ima ovaj zvanicni tip servisa
        // (isti onaj koji koriste npr. Android Auto integracije), ne samo obican MediaSession
        // unutar obicnog servisa (koji smo imali do sad, i koji je dovoljan za tastaturu, ali
        // mozda ne i za direktan dodir na Bluetooth slusalicama).
        sessionToken = mediaSession?.sessionToken
        PlaybackController.playbackStateListener = { refreshNotification() }
    }

    /** Deo standardnog MediaBrowserServiceCompat "ugovora" - MI ne nudimo pravo pretrazivanje
     * (nema liste poglavlja/knjiga za spoljne uredjaje da biraju), samo minimalan, prazan
     * koren - dovoljno da nas sistem prepozna kao legitimnog medijskog plejera. */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: android.os.Bundle?
    ): BrowserRoot = BrowserRoot("recporec_empty_root", null)

    /** Nemamo stvarno stablo za pretragu - uvek vraca praznu listu. */
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    private fun setupShakeDetector() {
        val settings = AppSettings(this)
        shakeDetector?.let { sensorManager?.unregisterListener(it) }
        shakeDetector = null
        if (settings.shakeEnabled) {
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                val threshold = when (settings.shakeSensitivity) {
                    0 -> 4.0f  // blago - lako se okine (spusteno sa 6.0)
                    2 -> 9.0f  // jako - treba odlucno drmnuti (spusteno sa 13.0)
                    else -> 6.0f // srednje (spusteno sa 9.0) - i posle par manjih spustanja i
                    // dalje trazilo prejak trzaj, ovaj put sece znacajnije, ne po malo
                }
                shakeDetector = ShakeDetector(shakeThreshold = threshold) {
                    val tts = PlaybackController.ttsManager
                    if (tts != null) {
                        if (tts.isSpeaking) {
                            tts.pause()
                            AppSettings(this).userManuallyPaused = true
                        } else {
                            PlaybackController.resumeCancelingRestIfNeeded()
                        }
                        refreshNotification()
                    }
                }
                sensorManager?.registerListener(shakeDetector, accel, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupCallStateListener() {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return // bezopasno - app radi normalno i bez ovoga

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val tm = telephonyManager ?: return

        fun handleCallState(state: Int) {
            when (state) {
                android.telephony.TelephonyManager.CALL_STATE_RINGING,
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                    PlaybackController.ttsManager?.pauseForCall()
                    refreshNotification()
                }
                android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                    PlaybackController.ttsManager?.resumeForCall()
                    refreshNotification()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state)
            }
            telephonyCallback = callback
            try {
                tm.registerTelephonyCallback(mainExecutor, callback)
            } catch (_: SecurityException) {
                // Dozvola formalno data ali odbijena od sistema iz nekog drugog razloga -
                // bezbedno odustajemo, audio fokus mehanizam i dalje pokusava da radi.
            }
        } else {
            val listener = object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
            }
            legacyPhoneStateListener = listener
            try {
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            } catch (_: SecurityException) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NAMERNO ne citamo iz intent extra-a ovde - ako sistem ubije servis (npr. zbog
        // nedostatka memorije dok je app dugo u pozadini) i kasnije ga sam ponovo pokrene,
        // Android zove onStartCommand() sa PRAZNIM (null) intentom, i tako bi se izgubila
        // informacija da li je "Citanje bez prekida" bilo ukljuceno - wake lock bi se
        // pogresno oslobodio bas kad je najpotrebniji. Citamo direktno iz trajno sacuvanog
        // podesavanja, koje ne zavisi od toga da li je intent sacuvan ili ne.
        val uninterrupted = AppSettings(this).uninterruptedEnabled
        startForeground(NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_EXIT) {
            // "Izlaz" iz same notifikacije - isti obrazac kao dugme Izlaz u aplikaciji
            // (DocumentListActivity): zaustavi citanje, oslobodi sve resurse, ugasi servis.
            PlaybackController.release()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // KRITICNO za Bluetooth slusalice sa fizickim dodirom (AVRCP): sirovi
            // ACTION_MEDIA_BUTTON dogadjaj mora RUCNO da se prosledi MediaSession-u da bi
            // stigao do onPlay()/onPause()/onSkip*() - standardni androidx MediaButtonReceiver
            // SAM PO SEBI to ne radi, samo pronadje NAS servis (zahvaljujuci intent-filteru u
            // manifestu) i preda mu dogadjaj - mi smo ti koji treba da ga prosledimo dalje.
            // Bez ovoga, dodir na slusalici stigne do servisa, ali se tu i zavrsi (tiho, bez
            // efekta - tacno ono sto je korisnica prijavila: "cuje se klik, nista vise").
            mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
        }

        setupShakeDetector()

        if (uninterrupted) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecPoRec:UninterruptedReading")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // maks 12h zaštita

            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RecPoRec:Wifi")
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
            } catch (_: Exception) { /* neki uređaji/verzije mogu odbiti - nastavljamo bez toga */ }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "recporec_playback"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Čitanje knjige", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val currentId = PlaybackController.currentDocument?.id ?: -1L
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, currentId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = PlaybackController.currentDocument?.title ?: "RečPoReč"
        val isSpeaking = PlaybackController.ttsManager?.isSpeaking == true

        // NOVO POKUSAJ za Bluetooth slusalice sa fizickim dodirom (AVRCP): do sad smo UVEK
        // prijavljivale poziciju kao "nepoznato" i NIKAD nismo postavljale trajanje - neki
        // Bluetooth uredjaji/AVRCP implementacije zahtevaju STVARNE (makar procenjene)
        // vrednosti da bi uopste tretirale sesiju kao "pravi" medijski sadrzaj kojim mogu da
        // upravljaju fizickim dodirom - bez toga, sistemski KEYCODE_MEDIA_* dogadjaji (kakve
        // salje spoljna tastatura) i dalje rade, ali direktan AVRCP dodir na slusalici moze da
        // bude ignorisan. Procena trajanja/pozicije koristi ISTU formulu (baseCharsPerMinute
        // = 800, brzina citanja) kao "Proteklo/preostalo vreme" na ekranu citaca - dovoljno
        // tacno za ovu svrhu, ne mora biti savrseno.
        val doc = PlaybackController.currentDocument
        val settingsForRate = AppSettings(this)
        val rate = (doc?.speechRate?.takeIf { it > 0f }) ?: settingsForRate.globalSpeechRate
        val safeRate = rate.coerceAtLeast(0.3f)
        val baseCharsPerMinute = 800f
        val totalChars = doc?.totalCharacters ?: 0
        val currentChars = (doc?.currentCharacterOffset ?: 0).coerceIn(0, totalChars)
        val totalDurationMs = if (totalChars > 0) {
            (totalChars / (baseCharsPerMinute * safeRate) * 60_000L).toLong()
        } else 0L
        val positionMs = if (totalChars > 0) {
            (currentChars / (baseCharsPerMinute * safeRate) * 60_000L).toLong()
        } else 0L

        mediaSession?.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, totalDurationMs)
                .build()
        )
        // KRITICNO: bez ovoga, Android u notifikaciji prikazuje dugmad (Pusti/Pauziraj) kao
        // VIZUELNO onemoguceno (sivo) iako PendingIntent iza njih i dalje radi kad se dodirne -
        // sistem "sivi" akcije koje MediaSession ne prijavi u svom setActions() skupu.
        // Prijava korisnice: "Za pusti kaze onemoguceno, ali kad kliknem, stvarno krene."
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (isSpeaking) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMs,
                    if (isSpeaking) 1f else 0f
                )
                .build()
        )
        // Notifikacija sad prikazuje i "Kratak predah"/"Zakazi citanje" pauzu, sa STVARNIM
        // preostalim vremenom (ne fiksnim brojem) - dosledno statusnoj liniji i brzoj precici
        // u citacu (koje isto odbrojavaju, bilo da je predah tek pocet ili produzen).
        val contentText = when {
            PlaybackController.isScheduledReadingActive() -> {
                val minutes = (PlaybackController.restRemainingSeconds + 59) / 60
                val minuteWord = when {
                    minutes % 100 in 11..14 -> "minuta"
                    minutes % 10 == 1 -> "minut"
                    minutes % 10 in 2..4 -> "minuta"
                    else -> "minuta"
                }
                "Čitanje pauzirano na $minutes $minuteWord."
            }
            isSpeaking -> "RečPoReč čita"
            else -> "RečPoReč - pauzirano"
        }

        val playPauseAction = if (isSpeaking) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pauziraj",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Pusti",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }
        val skipPreviousAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Prethodni zapis",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        )
        val skipNextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Sledeći zapis",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        )
        val exitPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ReadingService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, "Izlaz", exitPendingIntent
        )

        // NAPOMENA: otkad MediaSession prijavljuje SKIP_TO_NEXT/PREVIOUS (za spoljne Bluetooth
        // tastere), sistem (posebno Android 13+) sam prioritizuje prethodni/sledeci/pusti-pauziraj
        // u "sazetom" (compact) prikazu obavestenja - tu ima mesta za samo TRI dugmeta. Izlaz sad
        // dodajemo kao CETVRTO dugme, van tog sazetog prikaza - ostaje dostupno kad se obavestenje
        // razvuce/prosiri, ne nestaje, samo vise nije u prve tri pozicije.
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .addAction(skipPreviousAction)
            .addAction(playPauseAction)
            .addAction(skipNextAction)
            .addAction(exitAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            // Namerno UVEK "ongoing" (ne samo dok cita) - korisnica treba da vidi obavestenje
            // (i Pusti/Pauziraj/Izlaz dugmad) sve dok servis za citanje radi u pozadini, ne
            // samo dok je zvuk aktivan - inace bi moglo da se slucajno obrise povlacenjem dok
            // je pauzirano, i onda ne bi bilo naceina da se citanje nastavi iz obavestenja.
            .setOngoing(true)
            .build()
    }

    fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // NAMERNO nema vise sopstvenog onBind() override-a - MediaBrowserServiceCompat (bazna
    // klasa) sad sama obradjuje bind zahteve (potrebno da bi spoljni "browsing" klijenti,
    // npr. Bluetooth stek, mogli da se povezu). Nasa app se i dalje NE povezuje na ovaj
    // servis preko bindService() nigde - i dalje koristimo samo startService/
    // startForegroundService, sto ostaje potpuno nepromenjeno.

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        PlaybackController.playbackStateListener = null
        shakeDetector?.let { sensorManager?.unregisterListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        mediaSession?.release()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? android.telephony.TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                legacyPhoneStateListener?.let {
                    telephonyManager?.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 42
        const val ACTION_EXIT = "com.recporec.app.ACTION_EXIT"

        /** Napomena: parametar "uninterrupted" je NAMERNO uklonjen (bio je viska - slao se,
         * ali se nikad nije citao, jer onStartCommand() uvek cita direktno iz AppSettings,
         * ne iz intent extra-a - vidi objasnjenje tamo). Svi pozivaoci vec imaju tu vrednost
         * u podesavanjima, pa je prosledjivanje bilo redundantno. */
        fun start(context: Context) {
            val intent = Intent(context, ReadingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadingService::class.java))
        }
    }
}

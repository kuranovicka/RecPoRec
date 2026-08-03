package com.recporec.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.recporec.app.data.AppSettings
import com.recporec.app.tts.PlaybackController
import com.recporec.app.ui.ReaderActivity
import com.recporec.app.util.ShakeDetector

class ReadingService : Service() {

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
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlaybackController.resumeCancelingRestIfNeeded() }
                override fun onPause() {
                    PlaybackController.ttsManager?.pause()
                    AppSettings(this@ReadingService).userManuallyPaused = true
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
        PlaybackController.playbackStateListener = { refreshNotification() }
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

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(isSpeaking)
            .build()
    }

    fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        const val EXTRA_UNINTERRUPTED = "uninterrupted"

        fun start(context: Context, uninterrupted: Boolean) {
            val intent = Intent(context, ReadingService::class.java)
                .putExtra(EXTRA_UNINTERRUPTED, uninterrupted)
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

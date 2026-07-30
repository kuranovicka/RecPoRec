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

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        mediaSession = MediaSessionCompat(this, "RecPoRecSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlaybackController.ttsManager?.resume() }
                override fun onPause() { PlaybackController.ttsManager?.pause() }
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
                    0 -> 9.0f  // blago - lako se okine
                    2 -> 18.0f // jako - treba odlucno drmnuti
                    else -> 13.0f // srednje
                }
                shakeDetector = ShakeDetector(shakeThreshold = threshold) {
                    val tts = PlaybackController.ttsManager
                    if (tts != null) {
                        if (tts.isSpeaking) tts.pause() else tts.resume()
                        refreshNotification()
                    }
                }
                sensorManager?.registerListener(shakeDetector, accel, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uninterrupted = intent?.getBooleanExtra(EXTRA_UNINTERRUPTED, false) ?: false
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

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ReaderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = PlaybackController.currentDocument?.title ?: "RečPoReč"
        val isSpeaking = PlaybackController.ttsManager?.isSpeaking == true

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
            .setContentText("RečPoReč čita")
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

    override fun onDestroy() {
        PlaybackController.playbackStateListener = null
        shakeDetector?.let { sensorManager?.unregisterListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        mediaSession?.release()
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

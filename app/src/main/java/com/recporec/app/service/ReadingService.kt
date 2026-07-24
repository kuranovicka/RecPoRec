package com.recporec.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.recporec.app.tts.PlaybackController
import com.recporec.app.ui.ReaderActivity

class ReadingService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "RecPoRecSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlaybackController.ttsManager?.resume() }
                override fun onPause() { PlaybackController.ttsManager?.pause() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uninterrupted = intent?.getBooleanExtra(EXTRA_UNINTERRUPTED, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification())

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

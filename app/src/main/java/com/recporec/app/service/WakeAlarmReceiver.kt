package com.recporec.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.recporec.app.tts.PlaybackController
import com.recporec.app.ui.WakeAlarmActivity

/**
 * Prima signal od sistemskog AlarmManager-a tacno u trenutak kad "Probudi me u" treba da
 * zazvoni (ili se ponovi, ako prethodni pokusaj ostane bez reakcije). Ovo je REZERVNI,
 * POUZDANIJI mehanizam - AlarmManager.setAlarmClock() je izuzet od Doze/uspavljivanja u
 * pozadini (isti nacin na koji rade prave budilnik aplikacije), za razliku od naseg
 * unutrasnjeg brojaca u tickeru koji zavisi od toga da procesor ostane budan preko wake
 * lock-a - sto se na nekim uredjajima (potvrdjeno: Samsung, ali i drugi) pokazalo
 * nepouzdanim posle nekoliko minuta.
 *
 * VAZNO: NE pokrece ekran direktno (context.startActivity iz pozadinskog prijemnika) - to
 * Android sam blokira ("Background activity launch blocked", potvrdjeno u zvanicnoj
 * dokumentaciji i stvarnim gres_kama u sistemu). Umesto toga, salje notifikaciju sa punim
 * ekranom (setFullScreenIntent) - tacno onaj mehanizam koji Android trazi bas za pozive i
 * budilnike, i koji NIJE blokiran.
 */
class WakeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "recporec_wake_alarm"
        private const val NOTIFICATION_ID = 4271
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Kratak wake lock SAMO da bi ovaj kod stigao da se izvrsi - PlaybackController
        // sam drzi svoj wake lock dok alarm stvarno zvoni.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecPoRec:WakeAlarmReceiver")
        wl.acquire(30_000L)
        try {
            PlaybackController.onWakeAlarmFired(context.applicationContext)
            showFullScreenAlarmNotification(context)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun showFullScreenAlarmNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Buđenje uz knjigu", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm za \"Probudi me u\""
                setBypassDnd(true)
            }
            nm.createNotificationChannel(channel)
        }

        val activityIntent = Intent(context, WakeAlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Vreme je za čitanje!")
            .setContentText(PlaybackController.currentDocument?.title ?: "Reč po reč")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}

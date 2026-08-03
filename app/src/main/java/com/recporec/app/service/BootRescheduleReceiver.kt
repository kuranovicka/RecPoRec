package com.recporec.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.recporec.app.data.AppSettings
import com.recporec.app.tts.PlaybackController
import com.recporec.app.ui.WakeAlarmActivity

/**
 * STABILNOST: AlarmManager BRIŠE sve zakazane alarme kad se telefon restartuje (bilo koji
 * razlog - ažuriranje, prazna baterija pa punjenje, ručni restart) - to je normalno Android
 * ponašanje, ne bag. Bez ovog prijemnika, "Probudi me u" zakazano za sutra ujutru bi TIHO
 * nestalo ako se telefon restartuje u međuvremenu - ništa ne bi zazvonilo, bez ikakvog znaka
 * da se to desilo. Ovo je isti mehanizam koji prave budilnik aplikacije moraju da imaju.
 *
 * Koristi WALL-CLOCK vreme (pendingWakeTargetWallMillis), NE elapsedRealtime - ono se resetuje
 * na 0 posle svakog restarta, pa je beskorisno ovde.
 */
class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val ctx = context.applicationContext
        val settings = AppSettings(ctx)
        val docId = settings.pendingWakeDocumentId
        val targetWall = settings.pendingWakeTargetWallMillis
        if (docId == -1L || targetWall <= 0L) return
        if (targetWall <= System.currentTimeMillis()) {
            // Vreme je vec proslo dok je telefon bio ugasen/u restartu - nema bezbednog nacina
            // da se to sad nadoknadi bez iznenadnog alarma u pogresnom trenutku. Cisti se, kao
            // "propusteno buđenje", umesto da tiho ostane zauvek "na cekanju".
            settings.clearPendingWake()
            return
        }
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val receiverIntent = Intent(ctx, WakeAlarmReceiver::class.java)
            val receiverPendingIntent = PendingIntent.getBroadcast(
                ctx, PlaybackController.WAKE_ALARM_REQUEST_CODE, receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val showIntent = PendingIntent.getActivity(
                ctx, PlaybackController.WAKE_ALARM_REQUEST_CODE,
                Intent(ctx, WakeAlarmActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(targetWall, showIntent),
                receiverPendingIntent
            )
        } catch (_: Exception) {
            // Bezbedno ako ne uspe - korisnica ce primetiti da citanje/budjenje nije stiglo, i
            // moci ce rucno da ga ponovo zakaze.
        }
    }
}

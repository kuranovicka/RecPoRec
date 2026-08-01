package com.recporec.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
 * Ovaj receiver ne racuna niti odlucuje nista sam - samo "budi" pravu logiku
 * (PlaybackController) i pokazuje puni ekran preko zakljucanog ekrana, tacno kao pravi
 * budilnik.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Kratak wake lock SAMO da bi ovaj kod stigao da se izvrsi - PlaybackController
        // sam drzi svoj wake lock dok alarm stvarno zvoni.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecPoRec:WakeAlarmReceiver")
        wl.acquire(30_000L)
        try {
            PlaybackController.onWakeAlarmFired(context.applicationContext)

            val activityIntent = Intent(context, WakeAlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(activityIntent)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}

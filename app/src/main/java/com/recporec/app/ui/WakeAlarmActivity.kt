package com.recporec.app.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.recporec.app.tts.PlaybackController

/**
 * Puni ekran koji se prikazuje PREKO zaključanog ekrana kada "Probudi me u" treba da zazvoni
 * (ili se ponovi) - isti princip kao prave budilnik aplikacije. Namerno bez XML izgleda
 * (pravi se u kodu), da bi ostala potpuno nezavisna i jednostavna - ovo je hitan ekran, ne
 * treba mu ništa osim dva velika dugmeta i malo teksta.
 *
 * Sam ekran ne odlučuje ništa - samo poziva već postojeću logiku u PlaybackController-u
 * (istu koju koriste dugme, drmanje i medijski taster), i prati stanje da se sam zatvori
 * čim odmor prestane da bude aktivan, bez obzira odakle je to prekinuto.
 */
class WakeAlarmActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var watchdog: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notifikacija sa punim ekranom je vec odradila svoje (dovela nas ovde) - uklanjamo
        // je da ne ostane da "visi" u traci obavestenja.
        try {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(4271)
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()

        // Ako se odmor prekine na BILO KOJI drugi nacin (npr. korisnica ipak stigne do
        // notifikacije, ili drmanjem) dok je ovaj ekran otvoren, sam se zatvori - ne treba
        // da ostane da "visi" preko ekrana kad vise nema svrhe.
        watchdog = object : Runnable {
            override fun run() {
                if (!PlaybackController.isRestAlarmRinging()) {
                    finish()
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(watchdog, 500)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "Vreme je za čitanje!"
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            contentDescription = "Vreme je za čitanje. Alarm zvoni."
        }
        root.addView(title)

        val docTitle = TextView(this).apply {
            text = PlaybackController.currentDocument?.title ?: ""
            textSize = 20f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 24, 0, 64)
        }
        root.addView(docTitle)

        val stopButton = Button(this).apply {
            text = "▶ Zaustavi alarm i pusti knjigu"
            textSize = 22f
            setPadding(24, 64, 24, 64)
            contentDescription = "Zaustavi alarm i pusti knjigu"
            setOnClickListener {
                PlaybackController.resumeCancelingRestIfNeeded()
                finish()
            }
        }
        root.addView(stopButton)

        val spacer = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48)
        }
        root.addView(spacer)

        val snoozeButton = Button(this).apply {
            text = "⏰ Odloži 10 minuta"
            textSize = 22f
            setPadding(24, 64, 24, 64)
            contentDescription = "Odloži deset minuta"
            setOnClickListener {
                PlaybackController.extendRest()
                finish()
            }
        }
        root.addView(snoozeButton)

        setContentView(root)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }
}

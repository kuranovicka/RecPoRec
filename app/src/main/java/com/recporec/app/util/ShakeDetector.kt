package com.recporec.app.util

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val shakeThreshold: Float = 9.5f, // m/s^2 iznad gravitacije - "srednje" podrazumevano
    private val onShake: () -> Unit
) : SensorEventListener {

    private var lastShakeTime = 0L
    private val minIntervalMs = 1200L

    // DRUGACIJI PRISTUP (posle par pokusaja sa "uzastopnim ocitavanjima" koja su bila ili
    // prestroga ili prelabava): umesto da trazimo da senzor OSTANE iznad praga kroz N
    // ocitavanja zaredom, brojimo koliko puta senzor PREDJE prag (rastuce ivice, ne svako
    // pojedinacno ocitavanje dok je iznad) unutar kratkog vremenskog prozora. Pravo drmanje
    // rukom PRIRODNO osciluje (gore-dole-gore-dole), pa ovo prati stvaran pokret mnogo bolje
    // nego "ostani iznad bez prekida", i nije osetljivo na tacan trenutak uzorkovanja senzora.
    // 3 uspona za 500ms se pokazalo prestrogo (uz prag od 9-13 m/s^2, to trazi skoro 6
    // oscilacija u sekundi - brze od prirodnog drmanja rukom). Spusteno na 2 uspona za 700ms -
    // i dalje trazi bar dva "zamaha" (odbacuje pojedinacni slucajan udarac), ali dostizno.
    private val windowMs = 700L
    private val requiredPeaksInWindow = 2
    private val peakTimestamps = ArrayDeque<Long>()
    private var wasAboveThreshold = false

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        val isAboveNow = gForce > shakeThreshold
        if (isAboveNow && !wasAboveThreshold) {
            val now = System.currentTimeMillis()
            peakTimestamps.addLast(now)
            while (peakTimestamps.isNotEmpty() && now - peakTimestamps.first() > windowMs) {
                peakTimestamps.removeFirst()
            }
            if (peakTimestamps.size >= requiredPeaksInWindow) {
                peakTimestamps.clear()
                if (now - lastShakeTime > minIntervalMs) {
                    lastShakeTime = now
                    onShake()
                }
            }
        }
        wasAboveThreshold = isAboveNow
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

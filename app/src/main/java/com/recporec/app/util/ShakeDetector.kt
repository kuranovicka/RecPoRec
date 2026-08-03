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

    // VRACENO NA DVA nakon zajedno spustenih pragova (9.0/13.0) - kombinacija "samo JEDNO
    // ocitavanje" + nizi prag je bila PREOSETLJIVA: obicno nosenje/pomeranje telefona posle
    // pravog drmanja je povremeno samo prelazilo prag, sto se racunalo kao JOS JEDNO drmanje
    // (posto je drmanje prekidac pusti/pauziraj, taj "duh" okidac je tiho vracao stanje nazad
    // pre drugog namernog drmanja - Marina prijavila da "radi samo prvi put", sto se uklapa).
    // Dva uzastopna ocitavanja i dalje dozvoljavaju stvaran, ostar trzaj (koji obicno traje
    // vise od jednog ocitavanja na ~50Hz), ali odbacuju kratkotrajne slucajne vrhove.
    private var consecutiveOverThreshold = 0
    private val requiredConsecutive = 2

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        if (gForce > shakeThreshold) {
            consecutiveOverThreshold++
        } else {
            consecutiveOverThreshold = 0
        }
        if (consecutiveOverThreshold >= requiredConsecutive) {
            consecutiveOverThreshold = 0
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > minIntervalMs) {
                lastShakeTime = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

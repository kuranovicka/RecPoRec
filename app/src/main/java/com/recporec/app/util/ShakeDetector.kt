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

    // Trazi SAMO JEDNO ocitavanje iznad praga (ranije je trazilo DVA uzastopna, ali Marina
    // je na zivom testiranju primetila da prav, oštar trzaj cesto proizvede samo JEDAN kratak
    // vrh iznad praga pre nego sto padne nazad - narocito kod brzih uzastopnih pokusaja - pa
    // se DVA uzastopna cesto nisu ni desila iako je stvarno drmnula). Sam prag (magnituda) i
    // minIntervalMs ispod i dalje sprecavaju lazna okidanja od obicnog nosenja telefona.
    private var consecutiveOverThreshold = 0
    private val requiredConsecutive = 1

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

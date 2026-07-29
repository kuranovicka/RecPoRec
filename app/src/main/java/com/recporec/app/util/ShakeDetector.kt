package com.recporec.app.util

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val shakeThreshold: Float = 11.0f, // m/s^2 iznad gravitacije - "srednje" podrazumevano
    private val onShake: () -> Unit
) : SensorEventListener {

    private var lastShakeTime = 0L
    private val minIntervalMs = 1200L

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
        if (gForce > shakeThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > minIntervalMs) {
                lastShakeTime = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

package com.dashcam.blackbox.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * G-Sensor Collision & Sudden Impact Detection Engine.
 * Detects severe vehicle collisions, hard braking events, and rollover shocks.
 */
class ImpactDetector(context: Context) : SensorEventListener {

    interface ImpactListener {
        fun onImpactDetected(peakG: Float, impactType: String)
        fun onGForceUpdate(currentG: Float, lateralG: Float, longitudinalG: Float)
    }

    var listener: ImpactListener? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastIncidentTime = 0L
    private val debounceCooldownMs = 3000L // Prevent multi-triggering from single impact reverberation

    // Configurable impact threshold in Gs (2.2G = severe impact/collision shock)
    var impactThresholdG = 2.2f

    fun startListening() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val totalMag = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        val currentG = totalMag / 9.80665f
        val deltaG = abs(currentG - 1.0f) // Deviation from 1G gravity resting frame

        val lateralG = ax / 9.80665f
        val longitudinalG = ay / 9.80665f

        listener?.onGForceUpdate(currentG, lateralG, longitudinalG)

        val now = System.currentTimeMillis()
        if (deltaG >= impactThresholdG && (now - lastIncidentTime) > debounceCooldownMs) {
            lastIncidentTime = now
            val type = if (abs(longitudinalG) > abs(lateralG)) "FRONT/REAR COLLISION" else "SIDE IMPACT / ROLLOVER"
            listener?.onImpactDetected(currentG, type)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

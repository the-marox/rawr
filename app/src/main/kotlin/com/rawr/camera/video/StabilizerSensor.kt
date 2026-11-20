package com.rawr.camera.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.exp

/**
 * Gyro video stabilisation: integrates the gyroscope into the camera's turn, follows it with a smoothed path, and
 * sends the difference (the shake) to the native recorder with its timestamp, so each frame is corrected for the
 * moment it was captured (geometry/Stabilizer.h).
 *
 * Ported from apos-fandango's Rawr X14U fork (github.com/apos-fandango/rawr-xiaomi14u, commit 9bd6bac, GPL-3.0).
 * The 17U corrects pitch and yaw only, by moving the crop window inside the sensor margin; the margin comes from the
 * recording itself (NativeStabilizer.room).
 *
 * The smoothed path is an alpha-beta tracker (critically damped), so a steady pan is followed without lagging
 * behind; when the shake would need more margin than the crop leaves, the path is pulled along instead.
 * Camera axes for the back lenses (sensor turned 90°): image x = -device y, image y = -device x, forward = -device z.
 */
internal class StabilizerSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var enabled = false
    @Volatile private var seconds = .5f
    private var listening = false

    private var lastNanos = 0L
    private val angle = FloatArray(3)
    private val level = FloatArray(3)
    private val velocity = FloatArray(3)
    private var room = floatArrayOf(0f, 0f)
    private var roomCountdown = 0

    @Synchronized
    fun configure(on: Boolean, smoothingSeconds: Float) {
        seconds = smoothingSeconds.coerceAtLeast(.05f)
        val want = on && gyro != null
        if (want != enabled) {
            lastNanos = 0L
            angle.fill(0f); level.fill(0f); velocity.fill(0f)
        }
        enabled = want
        runCatching { NativeStabilizer.setEnabled(want) }
        if (want && !listening) {
            listening = manager?.registerListener(this, gyro, 5_000) == true
        } else if (!want && listening) {
            manager?.unregisterListener(this)
            listening = false
        }
    }

    fun close() = configure(false, .5f)

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val t = event.timestamp
        if (lastNanos == 0L) {
            lastNanos = t
            return
        }
        val dt = ((t - lastNanos) / 1e9f).coerceIn(0f, .05f)
        lastNanos = t
        if (dt <= 0f) return
        // The recording's shift room changes only with the recording size; read it a few times a second.
        if (--roomCountdown <= 0) {
            roomCountdown = 50
            room = runCatching { NativeStabilizer.room() }.getOrNull()?.takeIf { it.size == 2 } ?: room
        }
        // Device rates to camera axes (x right, y down, z forward in the image).
        val w = floatArrayOf(-event.values[1], -event.values[0], -event.values[2])
        val alpha = 1f - exp(-dt / seconds)
        val beta = alpha * alpha / (2f - alpha)
        val shake = FloatArray(3)
        for (i in 0..2) {
            angle[i] += w[i] * dt
            val predicted = level[i] + velocity[i] * dt
            val r = angle[i] - predicted
            level[i] = predicted + alpha * r
            velocity[i] += beta / dt * r
            shake[i] = angle[i] - level[i]
        }
        // Pitch (about x) moves the image vertically, yaw (about y) horizontally; roll isn't corrected here.
        clampAxis(0, shake, room[1] * .9f)
        clampAxis(1, shake, room[0] * .9f)
        clampAxis(2, shake, 0f)
        runCatching { NativeStabilizer.push(t, shake[0], shake[1], shake[2]) }
    }

    /** Keeps the shake on [axis] within [limit], pulling the smoothed path along when it would go past. */
    private fun clampAxis(axis: Int, shake: FloatArray, limit: Float) {
        val v = shake[axis]
        if (abs(v) <= limit) return
        val clamped = if (v < 0f) -limit else limit
        level[axis] = angle[axis] - clamped
        shake[axis] = clamped
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val PREFS = "rawr_17u"
        private const val KEY = "video_stabilisation"

        /** 0 Off, 1 Standard, 2 Strong. */
        fun mode(context: Context): Int =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, 2)

        fun setMode(context: Context, mode: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY, mode.coerceIn(0, 2)).apply()
        }

        /** Smoothing time constant per mode: longer follows the hand less. */
        fun smoothingSeconds(mode: Int): Float = if (mode >= 2) 1.0f else .45f
    }
}

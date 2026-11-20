package com.rawr.camera.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.round

/**
 * Gyro video stabilisation: integrates the gyroscope into the camera's turn, follows it with a smoothed path, and
 * sends the difference (the shake) to the native recorder with its timestamp, so each frame is corrected for the
 * moment it was captured (geometry/Stabilizer.h).
 *
 * The gyro integration and the alpha-beta smoothed path are ported from apos-fandango's Rawr X14U fork
 * (github.com/apos-fandango/rawr-xiaomi14u, commit 9bd6bac, GPL-3.0); the soft edge, the roll room and the horizon
 * levelling are this fork's.
 * The 17U warps the sampling window inside the sensor margin (shift and turn, sub-pixel, in the fast recording pass);
 * the margin comes from the recording itself (NativeStabilizer.room). The lens OIS is off while this runs
 * (StabilizerState). With horizon levelling, the turn follows the gravity sensor instead of the smoothed roll.
 *
 * The smoothed path is an alpha-beta tracker (critically damped), so a steady pan is followed without lagging
 * behind; when the shake would need more margin than the crop leaves, the path is pulled along instead.
 * Camera axes for the back lenses (sensor turned 90°): image x = -device y, image y = -device x, forward = -device z.
 */
internal class StabilizerSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile private var enabled = false
    @Volatile private var seconds = .5f
    @Volatile private var horizon = false
    private var listening = false
    private var listeningGravity = false
    // Horizon levelling: tilt of the scene's up from the image's vertical (radians, + = up leans to image right),
    // smoothed; NaN until the first gravity reading.
    @Volatile private var level = Float.NaN
    private var levelNanos = 0L

    private var lastNanos = 0L
    private val angle = FloatArray(3)
    private val smooth = FloatArray(3)
    private val velocity = FloatArray(3)
    private var room = floatArrayOf(0f, 0f, 0f)
    private var roomCountdown = 0

    @Synchronized
    fun configure(on: Boolean, smoothingSeconds: Float, levelHorizon: Boolean = false) {
        seconds = smoothingSeconds.coerceAtLeast(.05f)
        val want = on && gyro != null
        if (want != enabled) {
            lastNanos = 0L
            angle.fill(0f); smooth.fill(0f); velocity.fill(0f)
        }
        val wantHorizon = want && levelHorizon && gravity != null
        if (wantHorizon && !listeningGravity) {
            level = Float.NaN
            levelNanos = 0L
            listeningGravity = manager?.registerListener(this, gravity, 10_000) == true
        } else if (!wantHorizon && listeningGravity) {
            manager?.unregisterListener(this, gravity)
            listeningGravity = false
        }
        horizon = wantHorizon
        runCatching { NativeStabilizer.setLevelHorizon(wantHorizon) }
        enabled = want
        runCatching { NativeStabilizer.setEnabled(want) }
        StabilizerState.setActive(want)
        if (want && !listening) {
            listening = manager?.registerListener(this, gyro, 5_000) == true
        } else if (!want && listening) {
            manager?.unregisterListener(this, gyro)
            listening = false
        }
    }

    fun close() = configure(false, .5f)

    /**
     * Gravity in image axes is up = (-g_y, -g_x) (the sensor reports the reaction to gravity, so it points up). The
     * tilt is the angle of that up from the image's -y, folded to the nearer of the two landscape holds; past 45
     * degrees (portrait) the levelling fades out.
     */
    private fun onGravity(event: SensorEvent) {
        val upX = -event.values[1]
        val upY = -event.values[0]
        var tilt = atan2(upX, -upY)
        tilt -= (PI * round(tilt / PI)).toFloat()
        val target = if (abs(tilt) <= (PI / 4).toFloat()) tilt else 0f
        val t = event.timestamp
        val previous = level
        level = if (previous.isNaN() || levelNanos == 0L) target else {
            val dt = ((t - levelNanos) / 1e9f).coerceIn(0f, .1f)
            previous + (1f - exp(-dt / .08f)) * (target - previous)
        }
        levelNanos = t
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            onGravity(event)
            return
        }
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
            room = runCatching { NativeStabilizer.room() }.getOrNull()?.takeIf { it.size == 3 } ?: room
        }
        // Device rates to camera axes (x right, y down, z forward in the image).
        val w = floatArrayOf(-event.values[1], -event.values[0], -event.values[2])
        val alpha = 1f - exp(-dt / seconds)
        val beta = alpha * alpha / (2f - alpha)
        val shake = FloatArray(3)
        for (i in 0..2) {
            angle[i] += w[i] * dt
            val predicted = smooth[i] + velocity[i] * dt
            val r = angle[i] - predicted
            smooth[i] = predicted + alpha * r
            velocity[i] += beta / dt * r
            shake[i] = angle[i] - smooth[i]
        }
        // Pitch (about x) moves the image vertically, yaw (about y) horizontally, roll turns it. The native side
        // gives the turn whatever margin the shift leaves.
        softEdge(0, shake, room[1] * .9f, dt)
        softEdge(1, shake, room[0] * .9f, dt)
        softEdge(2, shake, room[2] * .9f, dt)
        clampAxis(0, shake, room[1] * .9f)
        clampAxis(1, shake, room[0] * .9f)
        clampAxis(2, shake, room[2] * .9f)
        // Levelling: the window turns by the tilt (native turns by -roll), whatever the hand's smoothed roll.
        val tilt = level
        if (horizon && !tilt.isNaN()) shake[2] = -tilt
        runCatching { NativeStabilizer.push(t, shake[0], shake[1], shake[2], angle[0], angle[1], angle[2]) }
    }

    /**
     * Past 60% of [limit], pulls the smoothed path towards the camera, harder the closer the shake gets to the edge
     * (time constant ~0.15 s at the edge). A turn then eases into the margin instead of hitting it: on a 17U walk
     * with turns the hard clamp alone was reached in 15-18% of frames and showed as jerks.
     */
    private fun softEdge(axis: Int, shake: FloatArray, limit: Float, dt: Float) {
        if (limit <= 0f) return
        val excess = ((abs(shake[axis]) / limit - .6f) / .4f).coerceIn(0f, 1f)
        if (excess <= 0f) return
        val pull = 1f - exp(-dt * excess * excess / .15f)
        smooth[axis] += pull * shake[axis]
        velocity[axis] *= 1f - pull
        shake[axis] = angle[axis] - smooth[axis]
    }

    /** Keeps the shake on [axis] within [limit], pulling the smoothed path along when it would go past. */
    private fun clampAxis(axis: Int, shake: FloatArray, limit: Float) {
        val v = shake[axis]
        if (abs(v) <= limit) return
        val clamped = if (v < 0f) -limit else limit
        smooth[axis] = angle[axis] - clamped
        shake[axis] = clamped
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val PREFS = "rawr_17u"
        private const val KEY = "video_stabilisation"
        private const val KEY_HORIZON = "video_level_horizon"

        /** 0 Off, 1 Standard, 2 Strong. */
        fun mode(context: Context): Int =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, 2)

        fun setMode(context: Context, mode: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY, mode.coerceIn(0, 2)).apply()
        }

        fun levelHorizon(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HORIZON, false)

        fun setLevelHorizon(context: Context, on: Boolean) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_HORIZON, on).apply()
        }

        /**
         * Smoothing time constant per mode: longer follows the hand less. 17U walking test (Nov 2026): 0.45 s was no
         * better than OIS alone, 1.0 s beat it; the crop used at most ~22 of its 120 px, so Strong goes to 2 s.
         */
        fun smoothingSeconds(mode: Int): Float = if (mode >= 2) 2.0f else 1.0f
    }
}

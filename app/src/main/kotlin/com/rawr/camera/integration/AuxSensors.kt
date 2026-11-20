package com.rawr.camera.integration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Reads the auxiliary sensors while the capture screen is in the foreground: those beside the rear cameras on
 * some phones (an ams TCS3408 colour/flicker sensor and a VI5300 laser time-of-flight sensor on the Xiaomi 15),
 * plus the standard barometer and rotation vector (compass) that many phones have.
 *
 * The sensors are vendor types, found by their string type; any app may read them (no permission).
 * Value layouts were measured on the Xiaomi 15 (HyperOS, Android 16):
 * - `xiaomi.sensor.back_cct_strm` (continuous, 10 Hz): [0] lux, [1] CCT K, [2..6] raw channels,
 *   [7] gain, [8] integration time ms, [9] CCT K. A CCT of ~662000 means "no measurement" (too dark).
 * - `xiaomi.sensor.back_cct` (on change): [0] CCT K, [1] lux.
 * - `xiaomi.sensor.back_lux_strm` / `back_lux`: [0] lux.
 * - `xiaomi.sensor.flicker` (~8 Hz): [0] dominant frequency in 7.8125 Hz steps; 7.8125 = no flicker.
 * - `xiaomi.sensor.tof` (continuous, 10 Hz): [0] distance mm (about -5 when covered, up to 4000).
 * - `TYPE_PRESSURE`: [0] hPa. `TYPE_ROTATION_VECTOR`: quaternion, heading from magnetic north.
 * - Front, beside the display (ams TCS3720): `xiaomi.sensor.front_cct_strm` (continuous) [0] CCT K, [8] lux;
 *   `xiaomi.sensor.front_cct` (on change) [0] CCT K, [3] lux; any phone's `TYPE_LIGHT` [0] lux.
 *
 * With a front camera ([setFrontCamera]) lux and colour temperature come from the front sensor and the rear ToF
 * is left out; flicker (a property of the room's lighting), pressure and heading are the same for both.
 *
 * Phones without these sensors simply report nothing: [available] is false and [reading] stays null.
 */
class AuxSensors(
    context: Context,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos
) {
    /** [stringType] null = the platform TYPE_LIGHT sensor. */
    private class LightSource(val stringType: String?, val luxIndex: Int, val cctIndex: Int?)

    /** One light sensor (rear or front) and its latest values. */
    private class LightSide(val sensor: Sensor, val source: LightSource) {
        @Volatile var lux: Float? = null
        @Volatile var cctK: Float? = null
        @Volatile var atNanos = 0L

        fun clear() {
            lux = null
            cctK = null
            atNanos = 0L
        }
    }

    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensors: List<Sensor> =
        runCatching { manager?.getSensorList(Sensor.TYPE_ALL).orEmpty() }.getOrDefault(emptyList())

    private fun find(type: String): Sensor? = sensors.firstOrNull { !it.isWakeUpSensor && it.stringType == type }

    private fun sensorFor(source: LightSource): Sensor? =
        if (source.stringType != null) find(source.stringType)
        else runCatching { manager?.getDefaultSensor(Sensor.TYPE_LIGHT) }.getOrNull()

    private val rearLight: LightSide? =
        REAR_LIGHT_SOURCES.firstNotNullOfOrNull { source -> sensorFor(source)?.let { LightSide(it, source) } }
    private val frontLight: LightSide? =
        FRONT_LIGHT_SOURCES.firstNotNullOfOrNull { source -> sensorFor(source)?.let { LightSide(it, source) } }

    /** The camera in use faces the photographer: read the front light sensor and drop the rear ToF. */
    @Volatile private var frontCamera = false
    private val light: LightSide? get() = if (frontCamera) frontLight else rearLight
    private val flickerSensor: Sensor? = find(FLICKER_TYPE)
    private val tofSensor: Sensor? = find(TOF_TYPE)
    private val pressureSensor: Sensor? = runCatching { manager?.getDefaultSensor(Sensor.TYPE_PRESSURE) }.getOrNull()
    private val rotationSensor: Sensor? = runCatching { manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }.getOrNull()

    /** True when the phone has any sensor this class knows how to read. */
    val available: Boolean
        get() = rearLight != null || frontLight != null || flickerSensor != null || tofSensor != null ||
            pressureSensor != null || rotationSensor != null

    private val classifier = FlickerClassifier()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var flickerRawHz: Float? = null
    @Volatile private var flicker: FlickerFrequency? = null
    @Volatile private var flickerAtNanos = 0L
    @Volatile private var tofDistanceMm: Float? = null
    @Volatile private var tofAtNanos = 0L
    @Volatile private var pressureHpa: Float? = null
    @Volatile private var pressureAtNanos = 0L
    @Volatile private var headingDeg: Float? = null
    @Volatile private var headingAtNanos = 0L

    private val mutableReading = MutableStateFlow<AuxSensorReading?>(null)

    /** Display stream: updates when a value moves enough to change what is shown; null when inactive. */
    val reading: StateFlow<AuxSensorReading?> = mutableReading.asStateFlow()

    /** The confirmed mains flicker, or null (none seen, no sensor, or inactive). */
    val flickerFrequency: Flow<FlickerFrequency?> = reading.map { it?.flicker }.distinctUntilChanged()

    private var listeners: List<SensorEventListener> = emptyList()

    private val flickerTimeout = Runnable {
        flickerRawHz = null
        flicker = null
        classifier.reset()
        publish()
    }

    @Synchronized
    fun setActive(active: Boolean) {
        if (!active) {
            listeners.forEach { manager?.unregisterListener(it) }
            listeners = emptyList()
            handler.removeCallbacks(flickerTimeout)
            classifier.reset()
            rearLight?.clear()
            frontLight?.clear()
            flickerRawHz = null
            flicker = null
            flickerAtNanos = 0L
            tofDistanceMm = null
            tofAtNanos = 0L
            pressureHpa = null
            pressureAtNanos = 0L
            headingDeg = null
            headingAtNanos = 0L
            mutableReading.value = null
            return
        }
        if (listeners.isNotEmpty() || manager == null) return
        val next = mutableListOf<SensorEventListener>()
        // Both light sensors run, so switching between rear and front cameras shows a reading at once.
        listOfNotNull(rearLight, frontLight).forEach { side ->
            val listener = lightListener(side)
            if (manager.registerListener(listener, side.sensor, SAMPLING_US, handler)) next += listener
        }
        flickerSensor?.let { sensor ->
            val listener = flickerListener()
            if (manager.registerListener(listener, sensor, SAMPLING_US, handler)) next += listener
        }
        tofSensor?.let { sensor ->
            val listener = tofListener()
            if (manager.registerListener(listener, sensor, SAMPLING_US, handler)) next += listener
        }
        pressureSensor?.let { sensor ->
            val listener = pressureListener()
            if (manager.registerListener(listener, sensor, SLOW_SAMPLING_US, handler)) next += listener
        }
        rotationSensor?.let { sensor ->
            val listener = headingListener()
            if (manager.registerListener(listener, sensor, SAMPLING_US, handler)) next += listener
        }
        listeners = next
    }

    /** Follows the camera in use; a front camera takes its light from the front sensor. Any thread. */
    fun setFrontCamera(front: Boolean) {
        if (frontCamera == front) return
        frontCamera = front
        handler.post { publish(force = true) }
    }

    /** The newest fresh values for capture metadata, or null when nothing is known. Any thread. */
    fun snapshot(): AuxSensorReading? {
        val now = nowNanos()
        val side = light
        val lightFresh = side != null && side.atNanos != 0L && now - side.atNanos <= LIGHT_MAX_AGE_NANOS
        val flickerFresh = flickerAtNanos != 0L && now - flickerAtNanos <= FLICKER_MAX_AGE_NANOS
        val tofFresh = tofAtNanos != 0L && now - tofAtNanos <= LIGHT_MAX_AGE_NANOS
        val pressureFresh = pressureAtNanos != 0L && now - pressureAtNanos <= PRESSURE_MAX_AGE_NANOS
        val headingFresh = headingAtNanos != 0L && now - headingAtNanos <= LIGHT_MAX_AGE_NANOS
        val reading = AuxSensorReading(
            lux = side?.lux.takeIf { lightFresh },
            cctK = side?.cctK.takeIf { lightFresh },
            flicker = flicker.takeIf { flickerFresh },
            flickerRawHz = flickerRawHz.takeIf { flickerFresh },
            flickerSensor = flickerFresh,
            tofDistanceMm = tofDistanceMm.takeIf { tofFresh && !frontCamera },
            pressureHpa = pressureHpa.takeIf { pressureFresh },
            headingMagneticDeg = headingDeg.takeIf { headingFresh }
        )
        return reading.takeUnless { it.isEmpty }
    }

    private fun lightListener(side: LightSide) = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            val source = side.source
            val l = values.getOrNull(source.luxIndex)?.takeIf { it.isFinite() && it >= 0f && it < MAX_LUX }
            val c = source.cctIndex?.let { values.getOrNull(it) }
                ?.takeIf { it.isFinite() && it in MIN_CCT_K..MAX_CCT_K && (l ?: 0f) >= MIN_CCT_LUX }
            side.lux = l
            side.cctK = c
            side.atNanos = nowNanos()
            if (side === light) publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun flickerListener() = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val hz = event.values.getOrNull(0)?.takeIf { it.isFinite() && it > 0f && it < MAX_FLICKER_HZ }
            flickerRawHz = hz
            flicker = classifier.update(hz ?: Float.NaN)
            flickerAtNanos = nowNanos()
            handler.removeCallbacks(flickerTimeout)
            handler.postDelayed(flickerTimeout, FLICKER_TIMEOUT_MS)
            publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun tofListener() = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            tofDistanceMm = event.values.getOrNull(0)?.takeIf { it.isFinite() && it in MIN_TOF_MM..MAX_TOF_MM }
            tofAtNanos = nowNanos()
            publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Pressure and heading only go into capture metadata: they do not touch the display stream.
    private fun pressureListener() = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            pressureHpa = event.values.getOrNull(0)?.takeIf { it.isFinite() && it in MIN_PRESSURE_HPA..MAX_PRESSURE_HPA }
            pressureAtNanos = nowNanos()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun headingListener() = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // values[4] is the estimated heading accuracy in radians when the sensor provides it (-1 otherwise).
            val accuracyRad = event.values.getOrNull(4)
            val unreliable = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                (accuracyRad != null && accuracyRad > MAX_HEADING_ERROR_RAD)
            headingDeg = if (unreliable) null else com.rawr.camera.location.CompassMath.rearLensHeadingDeg(event.values)
            headingAtNanos = nowNanos()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun publish(force: Boolean = false) {
        if (listeners.isEmpty()) return
        val side = light
        val next = AuxSensorReading(
            lux = side?.lux,
            cctK = side?.cctK,
            flicker = flicker,
            flickerRawHz = flickerRawHz,
            flickerSensor = flickerSensor != null,
            tofDistanceMm = tofDistanceMm.takeUnless { frontCamera }
        )
        val shown = mutableReading.value
        if (force || shown == null || shown.flicker != next.flicker || shown.flickerSensor != next.flickerSensor ||
            movedEnough(shown.lux, next.lux, LUX_STEP) || movedEnough(shown.cctK, next.cctK, CCT_STEP) ||
            movedEnough(shown.tofDistanceMm, next.tofDistanceMm, TOF_STEP)
        ) mutableReading.value = next
    }

    private companion object {
        val FRONT_LIGHT_SOURCES = listOf(
            LightSource("xiaomi.sensor.front_cct_strm", luxIndex = 8, cctIndex = 0),
            LightSource("xiaomi.sensor.front_cct", luxIndex = 3, cctIndex = 0),
            LightSource(null, luxIndex = 0, cctIndex = null)
        )
        val REAR_LIGHT_SOURCES = listOf(
            LightSource("xiaomi.sensor.back_cct_strm", luxIndex = 0, cctIndex = 1),
            LightSource("xiaomi.sensor.back_cct", luxIndex = 1, cctIndex = 0),
            LightSource("xiaomi.sensor.back_lux_strm", luxIndex = 0, cctIndex = null),
            LightSource("xiaomi.sensor.back_lux", luxIndex = 0, cctIndex = null)
        )
        const val FLICKER_TYPE = "xiaomi.sensor.flicker"
        const val TOF_TYPE = "xiaomi.sensor.tof"

        const val SAMPLING_US = 100_000
        const val SLOW_SAMPLING_US = 500_000
        const val PRESSURE_MAX_AGE_NANOS = 3_000_000_000L
        const val MIN_PRESSURE_HPA = 300f
        const val MAX_PRESSURE_HPA = 1_100f
        /** A heading the sensor itself rates worse than about 30 degrees is left out. */
        const val MAX_HEADING_ERROR_RAD = 0.52f
        const val LIGHT_MAX_AGE_NANOS = 1_000_000_000L
        const val FLICKER_MAX_AGE_NANOS = 2_000_000_000L
        const val FLICKER_TIMEOUT_MS = 2_000L

        const val MAX_LUX = 1_000_000f
        const val MAX_FLICKER_HZ = 5_000f
        const val MIN_CCT_K = 1_000f
        /** The VI5300 reads about -5 mm when covered and reports up to 4 m. */
        const val MIN_TOF_MM = 10f
        const val MAX_TOF_MM = 4_000f
        const val MAX_CCT_K = 25_000f

        /** Below this the colour channels are mostly noise and the CCT is not worth showing. */
        const val MIN_CCT_LUX = 2f

        /** Display hysteresis: 3 % in lux, 25 K in colour temperature, 3 % in distance. */
        const val LUX_STEP = 0.03f
        const val CCT_STEP = 25f
        const val TOF_STEP = 0.03f

        fun movedEnough(old: Float?, new: Float?, step: Float): Boolean {
            if (old == null || new == null) return (old == null) != (new == null)
            return if (step < 1f) abs(new - old) > step * maxOf(abs(old), 1f) else abs(new - old) >= step
        }
    }
}

/** Camera2 LENS_FACING of [cameraId] is FRONT; false when the camera is unknown. */
internal fun isFrontCamera(context: Context, cameraId: String): Boolean = runCatching {
    context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        ?.getCameraCharacteristics(cameraId)
        ?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
}.getOrDefault(false)

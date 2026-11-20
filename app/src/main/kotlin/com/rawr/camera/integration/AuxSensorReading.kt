package com.rawr.camera.integration

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToLong

/** Light flicker from mains lighting: twice the mains frequency (50 Hz mains -> 100 Hz flicker). */
enum class FlickerFrequency(val hz: Int, val mainsHz: Int) {
    Hz100(100, 50),
    Hz120(120, 60);

    /** One flicker period in nanoseconds (10 ms at 100 Hz, 8.33 ms at 120 Hz). */
    val periodNs: Long get() = 1_000_000_000L / hz
}

/**
 * What the phone's auxiliary sensors measure: beside the rear cameras the light (illuminance, colour
 * temperature, mains flicker) and the laser time-of-flight distance; elsewhere the barometric pressure and
 * the compass heading. Every field is null when the phone has no such sensor or the value is not
 * trustworthy (too dark for a colour temperature, a sentinel value, a stale reading, no ToF target, an
 * uncalibrated compass).
 */
data class AuxSensorReading(
    /** Incident illuminance at the back of the phone, lux. */
    val lux: Float? = null,
    /** Correlated colour temperature of the light, kelvin. */
    val cctK: Float? = null,
    /** Mains flicker, once confirmed over several readings; null = none confirmed. */
    val flicker: FlickerFrequency? = null,
    /** The sensor's latest dominant frequency (Hz), before classification. */
    val flickerRawHz: Float? = null,
    /** True when the phone has a flicker sensor, so a null [flicker] means "no flicker seen". */
    val flickerSensor: Boolean = false,
    /** Distance to what the laser AF time-of-flight sensor sees, millimetres. */
    val tofDistanceMm: Float? = null,
    /** Barometric pressure, hectopascals. */
    val pressureHpa: Float? = null,
    /** Compass direction of the rear lens axis from magnetic north, degrees (capture metadata only). */
    val headingMagneticDeg: Float? = null
) {
    /**
     * Incident-light exposure value at ISO 100: EV = log2(E * S / C) with the usual incident-meter
     * calibration C = 250, so EV100 = log2(lux / 2.5).
     */
    val ev100: Float? get() = lux?.takeIf { it > 0f }?.let { log2(it / 2.5f) }

    /**
     * JNI form for capture metadata: {lux, cctK, flickerHz, flickerRawHz, tofDistanceMm, pressureHpa}.
     * NaN = unknown; flickerHz 0 = the flicker sensor saw no mains flicker. The heading travels with the GPS
     * fix instead (EXIF GPSImgDirection), see CaptureGps.withLensHeading.
     */
    fun toNative(): FloatArray = floatArrayOf(
        lux ?: Float.NaN,
        cctK ?: Float.NaN,
        flicker?.hz?.toFloat() ?: if (flickerSensor) 0f else Float.NaN,
        flickerRawHz ?: Float.NaN,
        tofDistanceMm ?: Float.NaN,
        pressureHpa ?: Float.NaN
    )

    val isEmpty: Boolean
        get() = lux == null && cctK == null && flicker == null && flickerRawHz == null && tofDistanceMm == null &&
            pressureHpa == null && headingMagneticDeg == null
}

/**
 * Turns the flicker sensor's dominant-frequency stream into a stable mains decision.
 *
 * The sensor reports the strongest frequency as a multiple of its analysis bin (7.8125 Hz on the
 * Xiaomi 15's TCS3408), about eight times a second. Under steady daylight the value wanders at
 * random; under mains lighting it sits on the bin nearest 100 or 120 Hz. A frequency is confirmed
 * after [confirmCount] readings in a row near it and dropped after [releaseCount] readings in a row
 * that are not, so a single noisy reading neither switches nor clears the decision.
 */
class FlickerClassifier(
    private val confirmCount: Int = 3,
    private val releaseCount: Int = 5,
    private val toleranceHz: Float = DEFAULT_TOLERANCE_HZ
) {
    var detected: FlickerFrequency? = null
        private set
    private var candidate: FlickerFrequency? = null
    private var candidateRun = 0
    private var missRun = 0

    fun update(hz: Float): FlickerFrequency? {
        val match = classify(hz)
        if (match != null && match == candidate) candidateRun++ else {
            candidate = match
            candidateRun = if (match != null) 1 else 0
        }
        if (match != null && match == detected) {
            missRun = 0
        } else if (detected != null) {
            missRun++
        }
        when {
            match != null && match != detected && candidateRun >= confirmCount -> {
                detected = match
                missRun = 0
            }
            detected != null && missRun >= releaseCount -> detected = null
        }
        return detected
    }

    fun reset() {
        detected = null
        candidate = null
        candidateRun = 0
        missRun = 0
    }

    private fun classify(hz: Float): FlickerFrequency? {
        if (!hz.isFinite()) return null
        return FlickerFrequency.entries.firstOrNull { abs(hz - it.hz) <= toleranceHz }
    }

    companion object {
        /** One analysis bin of the TCS3408 (1000 Hz / 128). */
        const val DEFAULT_TOLERANCE_HZ = 7.8125f
    }
}

/** Banding check for a shutter time under mains flicker. */
object FlickerBanding {
    /** Exposures of at least this many flicker periods average the flicker out well enough. */
    private const val SAFE_PERIODS = 10.0

    /** How far from a whole number of periods still counts as in step (fraction of a period). */
    private const val TOLERANCE = 0.05

    /**
     * True when [exposureNs] is short enough to show bands and is not a whole number of flicker
     * periods (1/100, 1/50, 1/33 s... at 100 Hz).
     */
    fun bands(exposureNs: Long, flicker: FlickerFrequency): Boolean {
        if (exposureNs <= 0L) return false
        val periods = exposureNs.toDouble() / flicker.periodNs
        if (periods >= SAFE_PERIODS) return false
        if (periods < 1.0 - TOLERANCE) return true
        return abs(periods - kotlin.math.round(periods)) > TOLERANCE
    }

    /** The nearest banding-free shutter time: a whole number of periods, at least one. */
    fun nearestSafeExposureNs(exposureNs: Long, flicker: FlickerFrequency): Long {
        val periods = (exposureNs.toDouble() / flicker.periodNs).roundToLong().coerceAtLeast(1L)
        return periods * flicker.periodNs
    }
}

package com.rawr.camera.integration

import android.os.Build
import kotlin.math.abs

/** Readings the ToF has to offer before its first distance is published, so one invented value never lands. */
internal const val TOF_SETTLE_SAMPLES = 3

/** Readings the window keeps: at 10 Hz that is a half second, long enough to see a target settle or move. */
internal const val TOF_WINDOW_SAMPLES = 5

/** A window whose readings span more than this, or [TOF_SPREAD_SHARE] of the median, has no single target. */
internal const val TOF_SPREAD_MM = 50f
internal const val TOF_SPREAD_SHARE = 0.1f

/** Turns the temporal filter on or off for one session, on any device, whatever `ro.product.device` says. */
internal const val TOF_STABILITY_PROPERTY = "debug.rawr.tof_stability"

/** The light above which the L8's distance is withheld; 0 means no limit. Overrides [NEZHA_TOF_MAX_LUX]. */
internal const val TOF_MAX_LUX_PROPERTY = "debug.rawr.tof_max_lux"

/**
 * On the 17 Ultra the L8 reports 0.4-0.65 m in bright light that is not there, seen at 15,000-57,000 lux
 * (open sky, sunlit paving, a car in the sun: utalpt's tests). This is the lower end of that range, and it
 * is a starting point, not a calibration: the sweep in `docs/aux-sensors.md` is what settles it. Other
 * devices keep the VI5300's status as their only guard and get no limit.
 */
internal const val NEZHA_TOF_MAX_LUX = 15_000f

/**
 * The distances the ToF's last readings agree on, so a single invented reading cannot become a focus.
 *
 * The ST L8 answers every event with status 0, so the status alone cannot say whether the laser found a
 * target (see `tofDistanceForFocus` for the other half of the guard, the comparison with AF). Here the
 * readings themselves decide: none is offered until [TOF_SETTLE_SAMPLES] arrive, the median is offered
 * while they agree within [maxSpread], and a window that disagrees with itself is emptied rather than
 * averaged, because a spread that wide means the target is moving or the sensor is making it up.
 *
 * A missing reading (status invalid, out of range) empties the window too: it is evidence that the target
 * left, so the readings before it must not decide the distance after it.
 */
internal class TofWindow(
    private val capacity: Int = TOF_WINDOW_SAMPLES,
    private val settleSamples: Int = TOF_SETTLE_SAMPLES,
    private val maxSpread: (medianMm: Float) -> Float = { median ->
        maxOf(TOF_SPREAD_MM, TOF_SPREAD_SHARE * abs(median))
    }
) {
    private val recent = ArrayDeque<Float>()

    /** The distance to publish for this event: the median of the window, or null when the window has none. */
    fun accept(distanceMm: Float?): Float? {
        if (distanceMm == null) {
            recent.clear()
            return null
        }
        recent.addLast(distanceMm)
        while (recent.size > capacity) recent.removeFirst()
        if (recent.size < settleSamples) return null
        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else 0.5f * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
        if (sorted.last() - sorted.first() > maxSpread(median)) {
            recent.clear()
            return null
        }
        return median
    }

    /** Forgets what came before, for a camera that just started. */
    fun reset() = recent.clear()
}

/**
 * The ToF's last readings and the light they were taken in, as one policy: [TofWindow] on the distances and
 * the lux ceiling on the L8's band layout.
 *
 * Both parts are off unless the phone is the 17 Ultra or the property turns them on, so the Xiaomi 15 and
 * every other device keep the exact behaviour they had.
 */
internal class TofFilter(
    private val window: TofWindow?,
    private val maxLux: Float,
    private val layout: TofLayout
) {
    /** The distance to publish, or null when the readings or the light say there is none to trust. */
    fun update(distanceMm: Float?, lux: Float?): Float? {
        val gated = if (layout == TofLayout.Bands && maxLux > 0f && lux != null && lux >= maxLux) null
        else distanceMm
        return if (window == null) gated else window.accept(gated)
    }

    fun reset() = window?.reset()
}

/**
 * The filter a phone gets, from its device name and properties: null means no filtering at all.
 *
 * [TofFilter] needs the L8's four-band layout to mean anything, so the VI5300 keeps its own way. The
 * temporal window is on by default on `nezha` and off elsewhere; [TOF_STABILITY_PROPERTY] turns it on or
 * off for one session on any device, which is how the same scene is compared with and without it. The lux
 * ceiling follows [TOF_MAX_LUX_PROPERTY] when set, and [NEZHA_TOF_MAX_LUX] only on the 17 Ultra.
 */
internal fun tofFilterFor(
    device: String,
    layout: TofLayout,
    readProperty: (String) -> String?
): TofFilter? {
    if (layout != TofLayout.Bands) return null
    val onNezha = device.equals(NEZHA_DEVICE, ignoreCase = true)
    val enabled = readProperty(TOF_STABILITY_PROPERTY)?.let { it != "0" && it.lowercase() != "false" } ?: onNezha
    val maxLux = readProperty(TOF_MAX_LUX_PROPERTY)?.toFloatOrNull()
        ?: if (onNezha) NEZHA_TOF_MAX_LUX else 0f
    if (!enabled && maxLux <= 0f) return null
    return TofFilter(if (enabled) TofWindow() else null, maxLux, layout)
}

/** `ro.product.device` of the Xiaomi 17 Ultra, the phone the L8 numbers were measured on. */
internal const val NEZHA_DEVICE = "nezha"

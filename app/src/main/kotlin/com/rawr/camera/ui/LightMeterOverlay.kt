package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rawr.camera.integration.AuxSensorReading
import com.rawr.camera.integration.FlickerBanding
import com.rawr.camera.model.ExposureFormat
import com.rawr.camera.model.Orientation
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/** The rear light sensor readings, provided by the capture route; null where nothing provides them. */
internal val LocalAuxSensors = staticCompositionLocalOf<StateFlow<AuxSensorReading?>?> { null }

private val LightMeterWarning = Color(0xFFFFD84A)

/**
 * Text of the light meter pill, pure so it can be checked without Compose: the light on one line,
 * flicker and distance on the next (two short lines stay clear of the top-right scopes), then any warning.
 * Empty lines are not drawn.
 */
internal data class LightMeterText(val light: String, val detail: String, val warning: String?)

internal fun lightMeterText(reading: AuxSensorReading, exposureTimeNs: Long?): LightMeterText {
    val light = mutableListOf<String>()
    reading.lux?.let { light += "${formatLux(it)} lx" }
    reading.ev100?.let { light += "EV %.1f".format(Locale.ROOT, it) }
    reading.cctK?.let { light += "${(it / 10f).roundToInt() * 10}K" }
    val detail = mutableListOf<String>()
    when {
        reading.flicker != null -> detail += "~${reading.flicker.hz} Hz"
        reading.flickerSensor -> detail += "no flicker"
    }
    reading.tofDistanceMm?.let { detail += formatDistance(it) }
    val warning = reading.flicker?.let { flicker ->
        exposureTimeNs?.takeIf { FlickerBanding.bands(it, flicker) }?.let { ns ->
            "${ExposureFormat.formatShutterNs(ns)} will band · try " +
                ExposureFormat.formatShutterNs(FlickerBanding.nearestSafeExposureNs(ns, flicker))
        }
    }
    return LightMeterText(light.joinToString(" · "), detail.joinToString(" · "), warning)
}

private fun formatDistance(mm: Float): String =
    if (mm < 1000f) "%.2f m".format(Locale.ROOT, mm / 1000f) else "%.1f m".format(Locale.ROOT, mm / 1000f)

private fun formatLux(lux: Float): String = when {
    lux < 10f -> "%.1f".format(Locale.ROOT, lux)
    lux < 10_000f -> lux.roundToInt().toString()
    else -> "%.1fk".format(Locale.ROOT, lux / 1000f)
}

/**
 * Light meter pill: incident lux, EV at ISO 100, colour temperature and mains flicker from the
 * phone's rear light sensor and the laser ToF distance, plus a banding warning when the shutter is out
 * of step with the flicker. Draws nothing on phones without the sensors.
 */
@Composable
internal fun LightMeterOverlay(exposureTimeNs: Long?, orientation: Orientation, modifier: Modifier = Modifier) {
    val source = LocalAuxSensors.current ?: return
    val reading by source.collectAsStateWithLifecycle()
    val current = reading ?: return
    if (current.lux == null && current.cctK == null && !current.flickerSensor && current.tofDistanceMm == null) return
    val text = lightMeterText(current, exposureTimeNs)
    Column(
        modifier
            .uprightInLandscape(orientation)
            .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag(CaptureTestTags.LIGHT_METER)
    ) {
        listOf(text.light, text.detail).filter { it.isNotEmpty() }.forEach {
            Text(it, color = Color.White, fontFamily = CaptureMono, fontSize = 11.sp, style = ViewfinderTextStyle)
        }
        text.warning?.let {
            Text(it, color = LightMeterWarning, fontFamily = CaptureMono, fontSize = 11.sp, style = ViewfinderTextStyle)
        }
    }
}

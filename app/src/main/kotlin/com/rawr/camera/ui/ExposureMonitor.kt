package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.CaptureMonitorState
import com.rawr.camera.model.ExposureFormat
import com.rawr.camera.model.ExposureMode

@Composable
internal fun ExposureMonitor(state: CaptureMonitorState) {
    val meterLabel = if (state.exposureMode == ExposureMode.Manual) "METER" else "EV"
    // Same WB source as the compact strip and drawer rails
    // (live HAL estimate in auto/preset, requested sliders in manual),
    // so the three can never drift apart.
    val wbTint = state.whiteBalanceTint
    val wbValue = "${state.whiteBalanceTemperatureK}K ${if (wbTint >= 0) "+$wbTint" else "$wbTint"}"
    // Focus is always meters in the monitor (calibration flag only gates the
    // MF rail fallback); null before the first CaptureResult.
    val focusValue = state.focusDiopters?.let(ExposureFormat::formatFocusDistance) ?: "—"
    val fpsValue =
        when {
            state.rawFps != null && state.viewfinderFps != null ->
                "%.2f/%.2f".format(state.rawFps, state.viewfinderFps)
            state.rawFps != null -> "%.2f".format(state.rawFps)
            state.viewfinderFps != null -> "%.2f".format(state.viewfinderFps)
            else -> "—"
        }
    val boost = state.sensitivityBoost
    val boostValue = boost?.let { "×%.2f".format(it / 100f) } ?: "—"
    val boostActive = boost != null && boost > 100
    // Fixed slots are sized for these glyphs: the system font size must not grow them into each other.
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 1f)
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(CaptureTestTags.EXPOSURE_MONITOR)
            .background(CaptureColors.Surface)
            .height(CaptureDimens.MonitorHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed slots sized for each metric's widest value ("4870K +12", "1/8000", "12800", "29.97/29.97"...):
        // a value changing width never pushes the others, so the bar stays still.
        Spacer(Modifier.width(2.dp))
        MonitorMetric("WB", wbValue, modifier = Modifier.weight(12f))
        MonitorMetric("SS", state.exposureApplied.shutter?.displayLabel ?: "—", modifier = Modifier.weight(8.5f))
        MonitorMetric("ISO", state.exposureApplied.iso?.displayLabel ?: "—", modifier = Modifier.weight(8.5f))
        MonitorMetric(meterLabel, state.exposureApplied.evOrMeter?.displayLabel ?: "—", modifier = Modifier.weight(9f))
        MonitorMetric("FOC", focusValue, modifier = Modifier.weight(8.5f))
        MonitorMetric("FPS", fpsValue, modifier = Modifier.weight(14f))
        MonitorMetric(
            "BST",
            boostValue,
            valueColor = if (boostActive) Color.White else Color.White.copy(alpha = .45f),
            modifier = Modifier.weight(8f)
        )
        Spacer(Modifier.width(2.dp))
    }
    }
}

@Composable
internal fun MonitorMetric(label: String, value: String, valueColor: Color = Color.White, modifier: Modifier = Modifier) {
    // Tight boxes (no font padding) so CenterVertically is also optically
    // centered: caps-only mono glyphs otherwise sit above geometric center.
    val tight = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MonitorLabel(label)
        Text(
            " $value",
            maxLines = 1,
            softWrap = false,
            color = valueColor,
            fontFamily = CaptureMono,
            fontSize = 8.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.Medium,
            style = tight
        )
    }
}

@Composable
internal fun MonitorLabel(label: String) {
    Text(
        label,
        color = CaptureColors.Muted.copy(alpha = .72f),
        fontFamily = CaptureMono,
        fontSize = 6.sp,
        lineHeight = 6.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .4.sp,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    )
}

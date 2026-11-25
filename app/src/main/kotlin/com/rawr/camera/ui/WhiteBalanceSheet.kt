package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.SetWhiteBalanceTempTint
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.architecture.ToggleWhiteBalancePick
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.WhiteBalanceMode

/**
 * Rawr 17U fork: white balance as a strip in the FILM / LOOK sheets' format, just above the exposure row, so the
 * viewfinder stays in view while you set it (portrait; landscape keeps Ed's WhiteBalancePanel). The same actions
 * as Ed's panel: a row of chips (✕, PICK, AUTO, MANUAL, the camera's presets, SENSOR) and the TEMP / TINT rails
 * (WbRail, from WhiteBalancePanel.kt).
 */
@Composable
internal fun WhiteBalanceSheet(state: CaptureUiState, dispatch: CaptureDispatch, modifier: Modifier = Modifier) {
    val manualSupported = state.capabilities.manualWhiteBalanceSupported
    val presets = WhiteBalanceMode.entries.filter {
        it != WhiteBalanceMode.Auto && it != WhiteBalanceMode.ManualTempTint &&
            it in state.capabilities.supportedWhiteBalanceModes
    }.sortedBy(WhiteBalanceMode::awbValue)
    val modes = listOf(WhiteBalanceMode.Auto) +
        (if (manualSupported) listOf(WhiteBalanceMode.ManualTempTint) else emptyList()) + presets
    val sensorK = sensorWhiteBalanceK(LocalAuxSensors.current?.collectAsStateWithLifecycle()?.value?.cctK)
        ?.takeIf { manualSupported }
    Column(
        modifier
            .background(Color.Black.copy(alpha = .66f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }  // taps inside never reach the scrim
            .testTag(CaptureTestTags.WB_PANEL)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetTabChip("✕", active = false, tag = "wb_sheet_close") { dispatch(ToggleWhiteBalancePanel) }
            if (manualSupported) {
                SheetTabChip(
                    if (state.whiteBalancePickArmed) "PICK: TAP VIEW" else "PICK",
                    active = state.whiteBalancePickArmed,
                    tag = CaptureTestTags.whiteBalance("Pick")
                ) { dispatch(ToggleWhiteBalancePick) }
            }
            modes.forEach { mode ->
                SheetTabChip(
                    mode.shortLabel,
                    active = state.whiteBalanceMode == mode,
                    tag = CaptureTestTags.whiteBalance(mode.name)
                ) {
                    if (mode == WhiteBalanceMode.ManualTempTint) {
                        dispatch(SetWhiteBalanceTempTint(state.whiteBalanceTemperatureK, state.whiteBalanceTint))
                    } else {
                        dispatch(SetWhiteBalanceMode(mode))
                    }
                }
            }
            if (sensorK != null) {
                // Not a mode: locks manual WB at the light sensor's reading (tint 0); the rails fine-tune it.
                SheetTabChip("SENSOR ${sensorK}K", active = false, tag = CaptureTestTags.WB_SENSOR) {
                    dispatch(LockWhiteBalance(sensorK, 0))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)) {
            WbRail(
                title = "TEMP",
                current = "${state.whiteBalanceTemperatureK}K",
                previous = previousTempText(state.whiteBalanceTemperatureK),
                next = nextTempText(state.whiteBalanceTemperatureK),
                enabled = manualSupported,
                semanticsDescription =
                    "White balance temperature, ${state.whiteBalanceTemperatureK} kelvin. Drag to adjust.",
                value = state.whiteBalanceTemperatureK,
                min = WhiteBalanceMode.TEMP_MIN_K,
                max = WhiteBalanceMode.TEMP_MAX_K,
                step = 25,
                stepPx = 5.dp,
                detentEvery = 500,
                onStep = { dispatch(SetWhiteBalanceTempTint(it, state.whiteBalanceTint)) },
                modifier = Modifier.weight(1f).height(CaptureDimens.ExposureControlHeight)
            )
            WbRail(
                title = "TINT",
                current = formatTint(state.whiteBalanceTint),
                previous = previousTintText(state.whiteBalanceTint),
                next = nextTintText(state.whiteBalanceTint),
                enabled = manualSupported,
                semanticsDescription = "White balance tint, ${state.whiteBalanceTint}. Drag to adjust.",
                value = state.whiteBalanceTint,
                min = WhiteBalanceMode.TINT_MIN,
                max = WhiteBalanceMode.TINT_MAX,
                step = 1,
                stepPx = 8.dp,
                detentEvery = 5,
                onStep = { dispatch(SetWhiteBalanceTempTint(state.whiteBalanceTemperatureK, it)) },
                modifier = Modifier.weight(1f).height(CaptureDimens.ExposureControlHeight)
            )
        }
    }
}

package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rawr.camera.model.CaptureControlLayout
import com.rawr.camera.model.ControlSurfaceStyle
import com.rawr.camera.model.GridMode
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

@Composable
internal fun DisplayControlsSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("DisplayControls")) {
        SettingsGroup {
            GridModeSettingsRow(state.values.gridMode) { dispatch.invoke(SetGridMode(it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "DCP Quick Select",
                checked = state.values.showDcpQuickSelect,
                supportingText = "Show the DCP profile shortcut beside the lens selector on the capture screen.",
                testTag = SettingsTestTags.row("DisplayControls", "dcp_quick_select")
            ) { dispatch.invoke(SetShowDcpQuickSelect(it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Look · Params on viewfinder",
                checked = state.values.vfShowLook,
                supportingText = "LOOK / FILM and PARAMS buttons over the preview.",
                testTag = SettingsTestTags.row("DisplayControls", "vf_show_look")
            ) { dispatch.invoke(SetViewfinderControlVisible(VfControl.Look, it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Exposure row on viewfinder",
                checked = state.values.vfShowExposure,
                supportingText = "ISO, shutter, EV, WB and AF buttons over the preview.",
                testTag = SettingsTestTags.row("DisplayControls", "vf_show_exposure")
            ) { dispatch.invoke(SetViewfinderControlVisible(VfControl.Exposure, it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Info bar",
                checked = state.values.vfShowInfoBar,
                supportingText = "The exposure readout under the top bar.",
                testTag = SettingsTestTags.row("DisplayControls", "vf_show_info_bar")
            ) { dispatch.invoke(SetViewfinderControlVisible(VfControl.InfoBar, it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Eye button shows/hides these",
                checked = state.values.vfEyeControls,
                supportingText = "Adds VF CONTROLS to the MONITORING panel (eye) to toggle them while shooting.",
                testTag = SettingsTestTags.row("DisplayControls", "vf_eye_controls")
            ) { dispatch.invoke(SetEyeControlsViewfinder(it)) }
            SettingDivider()
            ViewfinderResolutionRow(state.values.viewfinderDivisor) { dispatch.invoke(SetViewfinderDivisor(it)) }
            SettingDivider()
            LensBarGroupingRow(state.values.lensBarGrouping) { dispatch.invoke(SetLensBarGrouping(it)) }
            SettingDivider()
            LensBarSizeRow(state.values.lensBarSize) { dispatch.invoke(SetLensBarSize(it)) }
            SettingDivider()
            SettingsRow(
                "Monitoring",
                "Focus peaking behavior",
                testTag = SettingsTestTags.row("DisplayControls", "monitoring"),
                onClick = {
                    dispatch.invoke(OpenSection(SettingsSection.Monitoring))
                }
            )
            SettingDivider()
            SettingsRow(
                "Control Style",
                "Capture control surface appearance",
                testTag = SettingsTestTags.row("DisplayControls", "control_style"),
                onClick = {
                    dispatch.invoke(OpenSection(SettingsSection.ControlStyle))
                }
            )
        }
    }
}

@Composable
internal fun MonitoringSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("Monitoring")) {
        SettingsGroup(description = "Monitoring overlays are activated from the Capture Screen.") {
            SelectorDropdownRow(
                state,
                ChoiceSelectorKind.PeakingSensitivity,
                dispatch,
                testTag = SettingsTestTags.row("Monitoring", "peaking_sensitivity")
            )
        }
    }
}

@Composable
internal fun ControlStyleSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val haptics = LocalSettingsHaptics.current
    SettingsPageContainer {
        SettingsGroup(
            title = "Capture Layout",
            description = "Classic keeps SS/ISO/EV sliders in the viewfinder. Compact moves WB/SS/ISO/EV to scrubbable buttons by the shutter, with multiframe and film sim pills."
        ) {
            SettingsSelectionRow(
                title = "Classic",
                selected = state.values.captureControlLayout == CaptureControlLayout.Classic,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetCaptureControlLayout(CaptureControlLayout.Classic))
                }
            )
            SettingDivider()
            SettingsSelectionRow(
                title = "Compact",
                selected = state.values.captureControlLayout == CaptureControlLayout.Compact,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetCaptureControlLayout(CaptureControlLayout.Compact))
                }
            )
        }
        SettingsGroup(
            description = "Changes only the visual material used by Capture Screen controls. Layout and interaction stay identical."
        ) {
            SettingsSelectionRow(
                title = "Frosted",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Frosted,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Frosted))
                }
            )
            SettingDivider()
            SettingsSelectionRow(
                title = "Basic",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Basic,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Basic))
                }
            )
        }
        val palette = com.rawr.camera.ui.CapturePalette
        SettingsGroup(title = "Button shape", description = "SQUARE is the Leica look. PILL turns square buttons into circles.") {
            com.rawr.camera.ui.ButtonShapeStyle.entries.forEachIndexed { index, style ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = style.label,
                    selected = palette.buttonShape == style,
                    testTag = SettingsTestTags.row("ControlStyle", "shape_${style.name.lowercase()}"),
                    onClick = {
                        haptics.detent()
                        palette.updateButtonShape(style)
                    }
                )
            }
        }
        SettingsGroup(title = "Viewfinder values") {
            SettingsSwitchRow(
                title = "Value backgrounds",
                checked = palette.valueBackgrounds,
                supportingText = "Boxes behind ISO, SS, EV, WB, LOOK and the lens buttons. Off: text with shadow only; the active one stays marked.",
                testTag = SettingsTestTags.row("ControlStyle", "value_backgrounds")
            ) { palette.updateValueBackgrounds(it) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Drag scale",
                checked = palette.dragScale,
                supportingText = "Scale of neighbouring values under the big readout while dragging.",
                testTag = SettingsTestTags.row("ControlStyle", "drag_scale")
            ) { palette.updateDragScale(it) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Tap opens slider bar",
                checked = palette.tapSliderBar,
                supportingText = "A single tap on ISO, SS, EV, WB or focus opens a horizontal bar above the row; drag left/right to set.",
                testTag = SettingsTestTags.row("ControlStyle", "tap_slider_bar")
            ) { palette.updateTapSliderBar(it) }
        }
        ExposureMemoryGroup()
        SettingsGroup(
            title = "Theme",
            description = "LEICA is the default. The others only change the accent; editing any colour below makes it CUSTOM."
        ) {
            com.rawr.camera.ui.ThemePreset.entries.forEachIndexed { index, preset ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = preset.label,
                    selected = com.rawr.camera.ui.CapturePalette.preset == preset,
                    testTag = SettingsTestTags.row("ControlStyle", "theme_${preset.name.lowercase()}"),
                    onClick = {
                        haptics.detent()
                        com.rawr.camera.ui.CapturePalette.selectPreset(preset)
                    }
                )
            }
        }
        SettingsGroup(title = "Colours", description = "Tap a colour to enter its hex value (RRGGBB).") {
            com.rawr.camera.ui.ThemeColor.entries.forEachIndexed { index, color ->
                if (index > 0) SettingDivider()
                ThemeColorRow(color)
            }
            SettingDivider()
            SettingsRow(
                "Reset to LEICA",
                testTag = SettingsTestTags.row("ControlStyle", "theme_reset"),
                onClick = {
                    haptics.detent()
                    com.rawr.camera.ui.CapturePalette.reset()
                }
            )
        }
    }
}

@Composable
private fun ThemeColorRow(color: com.rawr.camera.ui.ThemeColor) {
    val palette = com.rawr.camera.ui.CapturePalette
    val current = palette.color(color)
    var editing by remember { mutableStateOf(false) }
    SettingsRow(
        title = color.label,
        value = "#" + palette.hex(current),
        supportingText = color.description,
        testTag = SettingsTestTags.row("ControlStyle", "theme_color_${color.name.lowercase()}"),
        onClick = { editing = true },
        trailing = {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .size(28.dp)
                    .background(current, shape)
                    .border(1.dp, Color(0xFF5A5A5A), shape)
            )
        }
    )
    if (editing) {
        var text by remember { mutableStateOf(palette.hex(current)) }
        val parsed = palette.parseHex(text)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(color.label) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(7) },
                    singleLine = true,
                    isError = parsed == null,
                    prefix = { Text("#") },
                    supportingText = { Text(if (parsed == null) "6 hex digits, e.g. E2001A" else color.description) }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let { palette.setColor(color, it) }
                        editing = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GridModeSettingsRow(selected: GridMode, onSelect: (GridMode) -> Unit) {
    val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val labels =
        mapOf(
            GridMode.Off to "Off",
            GridMode.Thirds to "Rule of thirds",
            GridMode.FourByFour to "4 × 4",
            GridMode.Cross to "Crosshair"
        )
    Box {
        SettingsRow("Grid", labels.getValue(selected), onClick = { expanded.value = true })
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            GridMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(mode)) },
                    onClick = {
                        onSelect(mode)
                        expanded.value = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LensBarGroupingRow(value: LensBarGrouping, onSelect: (LensBarGrouping) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            "Lens Bar",
            value.label,
            supportingText = "Auto keeps every lens on one row while they fit, and falls back to one button per " +
                "lens with its modes above it when they don't. Grouped always does that; All lenses never does, " +
                "and scrolls sideways instead.",
            testTag = SettingsTestTags.row("DisplayControls", "lens_bar_grouping"),
            onClick = { expanded.value = true }
        )
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            LensBarGrouping.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onSelect(entry)
                        expanded.value = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LensBarSizeRow(value: LensBarSize, onSelect: (LensBarSize) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            "Lens Button Size",
            value.label,
            supportingText = "Scales the lens buttons and the area that answers a tap.",
            testTag = SettingsTestTags.row("DisplayControls", "lens_bar_size"),
            onClick = { expanded.value = true }
        )
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            LensBarSize.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onSelect(entry)
                        expanded.value = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ViewfinderResolutionRow(divisor: Int, onSelect: (Int) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    // Divisor of the preview size the viewfinder look renders at. Film sim
    // never renders above High, so Full and High look the same there.
    val labels = mapOf(1 to "Full", 2 to "High", 3 to "Balanced", 4 to "Fast")
    Box {
        SettingsRow(
            "Viewfinder Resolution",
            labels.getValue(divisor.coerceIn(1, 4)),
            supportingText = "Lower resolution reduces GPU load and heat. Photos and video are not affected.",
            testTag = SettingsTestTags.row("DisplayControls", "viewfinder_resolution"),
            onClick = { expanded.value = true }
        )
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            labels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded.value = false
                    }
                )
            }
        }
    }
}

/** Exposure memory lives with the camera controls (read by the viewfinder's camera coordinator). */
@Composable
private fun ExposureMemoryGroup() {
    val haptics = LocalSettingsHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("rawr_camera_controls", android.content.Context.MODE_PRIVATE) }
    var shared by remember { mutableStateOf(prefs.getBoolean("shared", false)) }
    SettingsGroup(
        title = "Exposure memory",
        description = "How exposure mode, ISO, shutter, EV and WB follow lens changes. Both are kept after closing the app."
    ) {
        SettingsSelectionRow(
            title = "Per camera",
            supportingText = "Each lens keeps its own values.",
            selected = !shared,
            testTag = SettingsTestTags.row("ControlStyle", "memory_per_camera"),
            onClick = {
                haptics.detent()
                shared = false
                prefs.edit().putBoolean("shared", false).apply()
            }
        )
        SettingDivider()
        SettingsSelectionRow(
            title = "Shared",
            supportingText = "One set for every lens, like one camera body (focus stays per lens).",
            selected = shared,
            testTag = SettingsTestTags.row("ControlStyle", "memory_shared"),
            onClick = {
                haptics.detent()
                shared = true
                prefs.edit().putBoolean("shared", true).apply()
            }
        )
    }
}

package com.rawr.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.ToggleFalseColor
import com.rawr.camera.architecture.ToggleOverlayArmed
import com.rawr.camera.architecture.ToggleScope
import com.rawr.camera.architecture.ToggleWaveformMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.OverlayMode
import com.rawr.camera.model.ScopeType

/** Compact top-bar controls for overlays and scope enablement. */
@Composable
internal fun MonitorPanel(state: CaptureUiState, dispatch: CaptureDispatch, modifier: Modifier) {
    val haptics = LocalCaptureHaptics.current
    val landscape = state.orientation == Orientation.Landscape

    CompactTopBarPalette(
        title = "MONITORING",
        orientation = state.orientation,
        modifier = modifier.testTag(CaptureTestTags.MONITOR_PANEL),
        leadingWeight = .62f,
        trailingWeight = .38f,
        // Two rows of choices under a caption: the shared 128dp default left a dead band below them,
        // and the eye stack has no room to spare now that VF CONTROLS carries the lens bar too.
        bodyHeight = 88.dp,
        leading = {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
            ) {
                Text(
                    "OVERLAY · ARM AUTO",
                    color = Color.White.copy(alpha = .35f),
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    maxLines = 1
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
                ) {
                    ArmChoice(
                        label = if (landscape) "PEAK" else "PEAKING",
                        armed = OverlayMode.Peaking in state.armedOverlays,
                        modifier = Modifier.weight(1f),
                        testTag = CaptureTestTags.monitorArm("peaking"),
                        onToggle = { dispatch(ToggleOverlayArmed(OverlayMode.Peaking)) }
                    )
                    ArmChoice(
                        label = if (landscape) "RAW HL" else "RAW HIGHLIGHTS",
                        armed = OverlayMode.RawHighlights in state.armedOverlays,
                        modifier = Modifier.weight(1f),
                        testTag = CaptureTestTags.monitorArm("raw_highlights"),
                        onToggle = { dispatch(ToggleOverlayArmed(OverlayMode.RawHighlights)) }
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
                ) {
                    ArmChoice(
                        label = if (landscape) "SHADOW" else "TONEMAP SHADOWS",
                        armed = OverlayMode.TonemapShadows in state.armedOverlays,
                        modifier = Modifier.weight(1f),
                        testTag = CaptureTestTags.monitorArm("tonemap_shadows"),
                        onToggle = { dispatch(ToggleOverlayArmed(OverlayMode.TonemapShadows)) }
                    )
                    ArmChoice(
                        label = if (landscape) "FALSE" else "FALSE COLOR",
                        armed = state.falseColorManual,
                        modifier = Modifier.weight(1f),
                        testTag = CaptureTestTags.monitorArm("false_color"),
                        onToggle = { dispatch(ToggleFalseColor) }
                    )
                }
            }
        },
        trailing = {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
            ) {
                Text(
                    "SCOPES",
                    color = Color.White.copy(alpha = .35f),
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp
                )
                ScopeType.entries.forEach { scope ->
                    val label =
                        when (scope) {
                            ScopeType.Waveform -> "WAVE"
                            ScopeType.Vectorscope -> if (landscape) "VECT" else "VECTOR"
                        }
                    val longPress: (() -> Unit)? =
                        when (scope) {
                            ScopeType.Waveform -> (
                                {
                                    haptics.selection()
                                    dispatch(ToggleWaveformMode)
                                }
                                )

                            ScopeType.Vectorscope -> null
                        }
                    SmallChoice(
                        label = label,
                        active = scope in state.activeScopes,
                        modifier = Modifier.fillMaxWidth().height(CaptureDimens.TopBarPaletteMinorChoiceHeight),
                        testTag = CaptureTestTags.scope(scope.name),
                        onLongClick = longPress
                    ) {
                        haptics.selection()
                        dispatch(ToggleScope(scope))
                    }
                }
            }
        }
    )
}

@Composable
private fun ArmChoice(
    label: String,
    armed: Boolean,
    modifier: Modifier,
    testTag: String? = null,
    onToggle: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    SmallChoice(
        label = label,
        active = armed,
        modifier = modifier.height(CaptureDimens.TopBarPaletteMajorChoiceHeight),
        testTag = testTag
    ) {
        haptics.selection()
        onToggle()
    }
}

/**
 * Under MONITORING: the viewfinder control groups to show, and the lens bar layout. Both are judged by
 * eye, and the bar stays visible below this panel, so they live here as well as in Settings › Display.
 */
@Composable
internal fun VfControlsPanel(
    quick: com.rawr.camera.model.ViewfinderQuickState,
    orientation: Orientation,
    onQuickEvent: (com.rawr.camera.architecture.CaptureQuickEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val width = if (orientation == Orientation.Landscape) CaptureDimens.TopBarPaletteLandscapeWidth
    else CaptureDimens.TopBarPalettePortraitWidth
    CaptureTopPanel(title = "VF CONTROLS", orientation = orientation, modifier = modifier.width(width)) {
        PanelCaption("SHOW")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
        ) {
            listOf(
                Triple(com.rawr.camera.settings.model.VfControl.Look, "LOOK", quick.showLook),
                Triple(com.rawr.camera.settings.model.VfControl.Exposure, "EXPO", quick.showExposure),
                Triple(com.rawr.camera.settings.model.VfControl.InfoBar, "INFO", quick.showInfoBar)
            ).forEach { (control, label, shown) ->
                SmallChoice(
                    label = label,
                    active = shown,
                    modifier = Modifier.weight(1f).height(CaptureDimens.TopBarPaletteMajorChoiceHeight),
                    testTag = "vf_controls_${control.name.lowercase()}"
                ) {
                    haptics.selection()
                    onQuickEvent(com.rawr.camera.architecture.CaptureQuickEvent.SetVfControlVisible(control, !shown))
                }
            }
            SmallChoice(
                label = "ALL",
                active = !quick.anyVfControlHidden,
                modifier = Modifier.weight(1f).height(CaptureDimens.TopBarPaletteMajorChoiceHeight),
                testTag = "vf_controls_all"
            ) {
                haptics.selection()
                onQuickEvent(com.rawr.camera.architecture.CaptureQuickEvent.ShowAllVfControls)
            }
        }
        // Portrait only: in landscape the card is rotated, so these rows would grow across the
        // screen and run off its edge. Settings › Display still holds both.
        if (orientation == Orientation.Portrait) {
            PanelCaption("LENS BAR")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
            ) {
                listOf(
                    com.rawr.camera.settings.model.LensBarGrouping.Auto to "AUTO",
                    com.rawr.camera.settings.model.LensBarGrouping.Grouped to "GROUPED",
                    com.rawr.camera.settings.model.LensBarGrouping.Flat to "ALL"
                ).forEach { (value, label) ->
                    SmallChoice(
                        label = label,
                        active = quick.lensBarGrouping == value,
                        modifier = Modifier.weight(1f).height(CaptureDimens.TopBarPaletteMinorChoiceHeight),
                        testTag = "lens_bar_grouping_${value.name.lowercase()}"
                    ) {
                        haptics.selection()
                        onQuickEvent(com.rawr.camera.architecture.CaptureQuickEvent.SetLensBarGrouping(value))
                    }
                }
            }
            PanelCaption("BUTTON SIZE")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CaptureDimens.TopBarPaletteInnerGap)
            ) {
                com.rawr.camera.settings.model.LensBarSize.entries.forEach { value ->
                    SmallChoice(
                        label = value.label.uppercase(),
                        active = quick.lensBarSize == value,
                        modifier = Modifier.weight(1f).height(CaptureDimens.TopBarPaletteMinorChoiceHeight),
                        testTag = "lens_bar_size_${value.name.lowercase()}"
                    ) {
                        haptics.selection()
                        onQuickEvent(com.rawr.camera.architecture.CaptureQuickEvent.SetLensBarSize(value))
                    }
                }
            }
        }
    }
}

/** Section caption inside a top panel, same weight as the MONITORING card's own captions. */
@Composable
private fun PanelCaption(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = .35f),
        fontFamily = CaptureMono,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        maxLines = 1
    )
}

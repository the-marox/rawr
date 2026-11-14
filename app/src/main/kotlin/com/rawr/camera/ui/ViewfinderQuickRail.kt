package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.HIGHLIGHT_THRESHOLD_DEFAULT
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.settings.model.CaptureSharpeningLevel
import com.rawr.camera.settings.model.RawrMaxShutter
import com.rawr.camera.settings.model.RawrMetering

/** Lower rank = kept first when the rail runs out of height. */
private class RailRank(val rank: Int) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@RailRank
}

private fun Modifier.railRank(rank: Int): Modifier = this.then(RailRank(rank))

/**
 * Right-edge rail above the compact strips, two vertical levels. Level 1 is
 * MTR / AE / HL (MTR only with Rawr AE). Tapping HL swaps the upper chips for
 * HL's own levels, MODE / THR / CMP (THR and CMP only for Opposed), and HL
 * becomes BACK. HL stays at the bottom either way so it never moves. Under
 * height pressure (scopes) chips drop by rank, top-most levels first.
 */
@Composable
internal fun ViewfinderQuickRail(
    quick: ViewfinderQuickState,
    spotActive: Boolean,
    onEvent: (CaptureQuickEvent) -> Unit,
    /** Width of the AF column below; chips are exactly one column wide so they stack over AF. */
    chipWidth: Dp,
    modifier: Modifier = Modifier
) {
    var highlightOpen by remember { mutableStateOf(false) }
    val enabled = !quick.locked
    val highlight = quick.highlight
    // Same inset as the param row, so the chips sit in its last (AF) column.
    RailLayout(modifier.padding(end = CaptureDimens.CompactParamRowEdgeInset)) {
        if (highlightOpen) {
            if (highlight.mode == HighlightQuickMode.Opposed) {
                // Spec ranges mirror the Settings sliders; coarser steps keep a scrub short.
                StripNumericItem(
                    key = "rail_highlight_compression",
                    shortLabel = "CMP",
                    value = highlight.compression,
                    display = highlight.compression.toInt().toString(),
                    isDefault = highlight.compression == highlight.compressionDefault,
                    defaultValue = highlight.compressionDefault,
                    min = 0f, max = 300f, step = 5f, decimals = 0,
                    onScrub = { onEvent(CaptureQuickEvent.SetHighlightCompression(it)) },
                    onReset = { onEvent(CaptureQuickEvent.SetHighlightCompression(highlight.compressionDefault)) },
                    modifier = Modifier.railRank(4).width(chipWidth).alpha(if (enabled) 1f else .4f),
                    testTag = CaptureTestTags.RAIL_HIGHLIGHT_COMPRESSION
                )
                StripNumericItem(
                    key = "rail_highlight_threshold",
                    shortLabel = "THR",
                    value = highlight.threshold,
                    display = "%.2f".format(java.util.Locale.US, highlight.threshold),
                    isDefault = highlight.threshold == HIGHLIGHT_THRESHOLD_DEFAULT,
                    defaultValue = HIGHLIGHT_THRESHOLD_DEFAULT,
                    min = .5f, max = 2f, step = .05f, decimals = 2,
                    onScrub = { onEvent(CaptureQuickEvent.SetHighlightThreshold(it)) },
                    onReset = { onEvent(CaptureQuickEvent.SetHighlightThreshold(HIGHLIGHT_THRESHOLD_DEFAULT)) },
                    modifier = Modifier.railRank(3).width(chipWidth).alpha(if (enabled) 1f else .4f),
                    testTag = CaptureTestTags.RAIL_HIGHLIGHT_THRESHOLD
                )
            }
            val nextMode = HighlightQuickMode.entries[(highlight.mode.ordinal + 1) % HighlightQuickMode.entries.size]
            RailChip(
                value = highlight.mode.railLabel(),
                label = "MODE",
                active = highlight.mode != HighlightQuickMode.Off,
                enabled = enabled,
                description = "Highlight reconstruction ${highlight.mode.name}. Tap for ${nextMode.name}.",
                tag = CaptureTestTags.RAIL_HIGHLIGHT_MODE,
                help = HELP_HL_MODE,
                modifier = Modifier.railRank(2),
                width = chipWidth
            ) { onEvent(CaptureQuickEvent.SetHighlightMode(nextMode)) }
        } else {
            if (quick.rawrAeEnabled) {
                val next = RawrMetering.entries[(quick.metering.ordinal + 1) % RawrMetering.entries.size]
                RailChip(
                    value = if (spotActive) "SPOT" else quick.metering.railLabel(),
                    label = "MTR",
                    active = spotActive,
                    enabled = enabled,
                    description = "Metering ${quick.metering.label}" +
                        (if (spotActive) ", overridden by spot" else "") + ". Tap for ${next.label}.",
                    tag = CaptureTestTags.RAIL_METERING,
                help = HELP_METERING,
                    modifier = Modifier.railRank(2),
                    width = chipWidth
                ) { onEvent(CaptureQuickEvent.SetMetering(next)) }
            }
            RailChip(
                value = if (quick.rawrAeEnabled) "RAWR" else "DEVICE",
                label = "AE",
                active = quick.rawrAeEnabled,
                enabled = enabled,
                description = "Auto exposure ${if (quick.rawrAeEnabled) "Rawr" else "Device"}. Tap to switch.",
                tag = CaptureTestTags.RAIL_AE,
                help = HELP_AE,
                modifier = Modifier.railRank(0),
                width = chipWidth
            ) { onEvent(CaptureQuickEvent.ToggleRawrAe) }
        }
        RailChip(
            value = if (highlightOpen) "BACK" else highlight.mode.railLabel(),
            label = "HL",
            active = !highlightOpen && highlight.mode != HighlightQuickMode.Off,
            enabled = true,
            description = if (highlightOpen) "Back from highlight reconstruction."
            else "Highlight reconstruction ${highlight.mode.name}. Tap to adjust.",
            tag = CaptureTestTags.RAIL_HIGHLIGHT,
                help = HELP_HL,
            modifier = Modifier.railRank(1),
            width = chipWidth
        ) { highlightOpen = !highlightOpen }
    }
}

/**
 * Children are emitted top to bottom. The best-ranked ones that fit the height
 * are kept, placed bottom-up in emission order and right-aligned.
 */
@Composable
private fun RailLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val ranks = measurables.mapIndexed { i, m -> (m.parentData as? RailRank)?.rank ?: i }
        // Same gap as between the exposure columns, between chips and under the rail.
        val gap = CaptureDimens.ControlGap.roundToPx()
        val shown = BooleanArray(placeables.size)
        var height = 0
        for (i in placeables.indices.sortedBy { ranks[it] }) {
            if (height + placeables[i].height + gap > constraints.maxHeight) break
            height += placeables[i].height + gap
            shown[i] = true
        }
        val width = placeables.filterIndexed { i, _ -> shown[i] }.maxOfOrNull { it.width } ?: 0
        layout(width, height) {
            var y = height
            for (i in placeables.indices.reversed()) {
                if (!shown[i]) continue
                y -= placeables[i].height + gap
                placeables[i].place(width - placeables[i].width, y)
            }
        }
    }
}

@Composable
private fun RailChip(
    value: String,
    label: String,
    active: Boolean,
    enabled: Boolean,
    description: String,
    tag: String,
    width: Dp,
    help: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    Box(
        modifier
            .width(width)
            .height(StripDimens.ParamRowHeight)
            .alpha(if (enabled) 1f else .4f)
            .testTag(tag)
            .viewfinderChip()
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = "Explain",
                onLongClick = { showHelp(help) }
            ) {
                haptics.selection()
                onClick()
            }.semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        StripTwoLineItem(
            valueText = value,
            valueColor = if (active) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f),
            labelText = label
        )
    }
}

private fun HighlightQuickMode.railLabel(): String = when (this) {
    HighlightQuickMode.Off -> "OFF"
    HighlightQuickMode.Propagation -> "PROP"
    HighlightQuickMode.Opposed -> "OPP"
}

/** Short enough for a 56dp chip at 10sp. */
private fun RawrMaxShutter.railLabel(): String = if (this == RawrMaxShutter.Auto) "AUTO" else label

private fun CaptureSharpeningLevel.railLabel(): String = when (this) {
    CaptureSharpeningLevel.Low -> "LOW"
    CaptureSharpeningLevel.Medium -> "MED"
    CaptureSharpeningLevel.Sharp -> "SHARP"
    CaptureSharpeningLevel.Sharpest -> "MAX"
}

private fun RawrMetering.railLabel(): String = when (this) {
    RawrMetering.Matrix -> "MATRIX"
    RawrMetering.CenterWeighted -> "CENTRE"
    RawrMetering.Average -> "AVG"
    RawrMetering.Highlight -> "HIGH"
}

private const val HELP_METERING =
    "Metering: how Rawr AE reads the scene. Matrix weighs the whole frame, Centre favours the middle, " +
        "Avg is a plain average, High protects the highlights. Tap the viewfinder for spot."
private const val HELP_AE =
    "Auto exposure: RAWR solves exposure in the app (with its own metering); DEVICE uses the phone's Camera2 AE."
private const val HELP_HL =
    "Highlight reconstruction: rebuilds clipped highlights. PROP spreads colour from unclipped neighbours, " +
        "OPP estimates the clipped channel from the others. Tap to cycle OFF / PROP / OPP; " +
        "OPP shows THR (where clipping starts) and CMP (highlight compression)."
private const val HELP_HL_MODE =
    "Highlight mode: OFF, PROP (propagation from neighbours) or OPP (opposed channels). " +
        "OPP adds THR (where clipping starts) and CMP (highlight compression)."

/**
 * Quick panel: a translucent card at the top-left of the viewfinder (clear of the scopes), opened with a swipe
 * down and closed with a swipe up or a tap outside. Fixed groups, each led by its main switch so nothing moves:
 * EXPOSURE (AE, MTR), HIGHLIGHTS (HL, THR, CMP - the sliders dim outside OPP) and LENS (FR, OWN MF, OWN LOOK).
 */
@Composable
internal fun ViewfinderQuickPanel(
    quick: ViewfinderQuickState,
    spotActive: Boolean,
    onEvent: (CaptureQuickEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = !quick.locked
    val highlight = quick.highlight
    val chipWidth = 72.dp
    val gap = CaptureDimens.ControlGap
    val rowArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)
    androidx.compose.foundation.layout.Column(
        modifier
            .background(Color.Black.copy(alpha = .62f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }  // taps inside never focus the viewfinder
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        PanelSection("EXPOSURE")
        androidx.compose.foundation.layout.Row(horizontalArrangement = rowArrangement) {
            RailChip(
                value = if (quick.rawrAeEnabled) "RAWR" else "DEVICE",
                label = "AE",
                active = quick.rawrAeEnabled,
                enabled = enabled,
                description = "Auto exposure ${if (quick.rawrAeEnabled) "Rawr" else "Device"}. Tap to switch.",
                tag = CaptureTestTags.RAIL_AE,
                width = chipWidth,
                help = HELP_AE
            ) { onEvent(CaptureQuickEvent.ToggleRawrAe) }
            // MTR depends on Rawr AE: dimmed and inert with Device AE, but it never moves.
            val next = RawrMetering.entries[(quick.metering.ordinal + 1) % RawrMetering.entries.size]
            RailChip(
                value = if (spotActive) "SPOT" else quick.metering.railLabel(),
                label = "MTR",
                active = spotActive && quick.rawrAeEnabled,
                enabled = enabled && quick.rawrAeEnabled,
                description = if (quick.rawrAeEnabled) "Metering ${quick.metering.label}" +
                    (if (spotActive) ", overridden by spot" else "") + ". Tap for ${next.label}."
                else "Metering applies to Rawr AE only.",
                tag = CaptureTestTags.RAIL_METERING,
                width = chipWidth,
                help = HELP_METERING
            ) { onEvent(CaptureQuickEvent.SetMetering(next)) }
            // SHUT: Rawr AE's slowest auto shutter (photo); dimmed with Device AE or in video.
            val shutEnabled = enabled && quick.rawrAeEnabled && !quick.isVideo
            val nextShutter = RawrMaxShutter.entries[(quick.maxShutter.ordinal + 1) % RawrMaxShutter.entries.size]
            RailChip(
                value = quick.maxShutter.railLabel(),
                label = "SHUT",
                active = quick.maxShutter != RawrMaxShutter.Auto && shutEnabled,
                enabled = shutEnabled,
                description = "Slowest auto shutter ${quick.maxShutter.label}. Tap for ${nextShutter.label}.",
                tag = "quick_panel_max_shutter",
                width = chipWidth,
                help = HELP_SHUT
            ) { onEvent(CaptureQuickEvent.SetMaxShutter(nextShutter)) }
        }
        PanelSection("HIGHLIGHTS")
        androidx.compose.foundation.layout.Row(horizontalArrangement = rowArrangement) {
            val nextMode = HighlightQuickMode.entries[(highlight.mode.ordinal + 1) % HighlightQuickMode.entries.size]
            RailChip(
                value = highlight.mode.railLabel(),
                label = "HL",
                active = highlight.mode != HighlightQuickMode.Off,
                enabled = enabled,
                description = "Highlight reconstruction ${highlight.mode.name}. Tap for ${nextMode.name}.",
                tag = CaptureTestTags.RAIL_HIGHLIGHT,
                width = chipWidth,
                help = HELP_HL
            ) { onEvent(CaptureQuickEvent.SetHighlightMode(nextMode)) }
            val opp = enabled && highlight.mode == HighlightQuickMode.Opposed
            StripNumericItem(
                key = "panel_highlight_threshold",
                shortLabel = "THR",
                value = highlight.threshold,
                display = "%.2f".format(java.util.Locale.US, highlight.threshold),
                isDefault = highlight.threshold == HIGHLIGHT_THRESHOLD_DEFAULT,
                defaultValue = HIGHLIGHT_THRESHOLD_DEFAULT,
                min = .5f, max = 2f, step = .05f, decimals = 2,
                onScrub = { if (opp) onEvent(CaptureQuickEvent.SetHighlightThreshold(it)) },
                onReset = { if (opp) onEvent(CaptureQuickEvent.SetHighlightThreshold(HIGHLIGHT_THRESHOLD_DEFAULT)) },
                modifier = Modifier.width(chipWidth).alpha(if (opp) 1f else .35f),
                testTag = CaptureTestTags.RAIL_HIGHLIGHT_THRESHOLD,
                help = HELP_THR
            )
            StripNumericItem(
                key = "panel_highlight_compression",
                shortLabel = "CMP",
                value = highlight.compression,
                display = highlight.compression.toInt().toString(),
                isDefault = highlight.compression == highlight.compressionDefault,
                defaultValue = highlight.compressionDefault,
                min = 0f, max = 300f, step = 5f, decimals = 0,
                onScrub = { if (opp) onEvent(CaptureQuickEvent.SetHighlightCompression(it)) },
                onReset = { if (opp) onEvent(CaptureQuickEvent.SetHighlightCompression(highlight.compressionDefault)) },
                modifier = Modifier.width(chipWidth).alpha(if (opp) 1f else .35f),
                testTag = CaptureTestTags.RAIL_HIGHLIGHT_COMPRESSION,
                help = HELP_CMP
            )
        }
        PanelSection("DETAIL")
        androidx.compose.foundation.layout.Row(horizontalArrangement = rowArrangement) {
            if (!quick.isVideo) {
                // OFF → LOW → MED → SHARP → MAX → OFF
                val levels = CaptureSharpeningLevel.entries
                val (nextOn, nextLevel) = when {
                    !quick.sharpeningEnabled -> true to levels.first()
                    quick.sharpeningLevel == levels.last() -> false to quick.sharpeningLevel
                    else -> true to levels[quick.sharpeningLevel.ordinal + 1]
                }
                RailChip(
                    value = if (quick.sharpeningEnabled) quick.sharpeningLevel.railLabel() else "OFF",
                    label = "SHARP",
                    active = quick.sharpeningEnabled,
                    enabled = enabled,
                    description = "Sharpening ${if (quick.sharpeningEnabled) quick.sharpeningLevel.label else "off"}. " +
                        "Tap for ${if (nextOn) nextLevel.label else "off"}.",
                    tag = "quick_panel_sharpening",
                    width = chipWidth,
                    help = HELP_SHARP
                ) { onEvent(CaptureQuickEvent.SetSharpening(nextOn, nextLevel)) }
            }
            RailChip(
                value = if (quick.denoiseEnabled) "ON" else "OFF",
                label = "NR",
                active = quick.denoiseEnabled,
                enabled = enabled,
                description = "Noise reduction ${if (quick.denoiseEnabled) "on" else "off"}. Tap to switch.",
                tag = "quick_panel_denoise",
                width = chipWidth,
                help = HELP_NR
            ) { onEvent(CaptureQuickEvent.ToggleDenoise) }
        }
        if (!quick.isVideo) {
            PanelSection("LENS ${quick.lensId.orEmpty()}".trim())
            androidx.compose.foundation.layout.Row(horizontalArrangement = rowArrangement) {
                // Frames for the lens in use, capped by its profile limit (a 1-frame lens has nothing to pick).
                val maxFrames = if (quick.frameLimit > 0) quick.frameLimit else 30
                val framesEnabled = enabled && quick.multiframeEnabled && maxFrames > 1
                val frames = quick.frames.coerceIn(1, maxFrames)
                FrameStepper(
                    frames = frames,
                    min = 1,
                    max = maxFrames,
                    enabled = framesEnabled,
                    width = chipWidth,
                    onChange = { onEvent(CaptureQuickEvent.SetMultiframeFrames(it)) }
                )
                RailChip(
                    value = if (quick.ownMultiframe) "ON" else "OFF",
                    label = "OWN MF",
                    active = quick.ownMultiframe,
                    enabled = enabled && quick.multiframeEnabled,
                    description = "Own multiframe settings for this lens: ${if (quick.ownMultiframe) "on" else "off"}. Tap to switch.",
                    tag = "quick_panel_own_mf",
                    width = chipWidth,
                    help = HELP_OWN_MF
                ) { onEvent(CaptureQuickEvent.ToggleOwnMultiframe) }
                RailChip(
                    value = if (quick.ownLook) "ON" else "OFF",
                    label = "OWN LOOK",
                    active = quick.ownLook,
                    enabled = enabled,
                    description = "Own look for this lens: ${if (quick.ownLook) "on" else "off"}. Tap to switch.",
                    tag = "quick_panel_own_look",
                    width = chipWidth,
                    help = HELP_OWN_LOOK
                ) { onEvent(CaptureQuickEvent.ToggleOwnLook) }
                ResetChip(lensId = quick.lensId.orEmpty(), enabled = enabled, width = chipWidth, onEvent = onEvent)
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
                .width(36.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = .55f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
    }
}

/** RESET: this camera back to automatic (UNDO on the card); long-press offers RESET ALL CAMERAS. */
@Composable
private fun ResetChip(lensId: String, enabled: Boolean, width: Dp, onEvent: (CaptureQuickEvent) -> Unit) {
    val haptics = LocalCaptureHaptics.current
    val showAction = rememberControlHelpAction()
    val lens = lensId.substringBefore(' ').ifEmpty { "This lens" }
    val undo = { what: String ->
        showAction("Reset: $what back to Auto (exposure, ISO, shutter, EV, WB, focus).", "UNDO") {
            onEvent(CaptureQuickEvent.UndoCameraControlsReset)
        }
    }
    Box(
        Modifier
            .width(width)
            .height(StripDimens.ParamRowHeight)
            .alpha(if (enabled) 1f else .35f)
            .background(Color.Transparent)
            .viewfinderChip()
            .testTag("quick_panel_reset")
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = "Reset all cameras",
                onLongClick = {
                    haptics.longPress()
                    showAction(HELP_RESET, "RESET ALL CAMERAS") {
                        onEvent(CaptureQuickEvent.ResetCameraControls(allCameras = true))
                        undo("every camera")
                    }
                }
            ) {
                haptics.selection()
                onEvent(CaptureQuickEvent.ResetCameraControls(allCameras = false))
                undo(lens)
            }
            .semantics { contentDescription = "Reset $lens exposure, white balance and focus to automatic" },
        contentAlignment = Alignment.Center
    ) {
        StripTwoLineItem(valueText = "↺ RESET", valueColor = CaptureColors.AccentSoft, labelText = "$lens → AUTO")
    }
}

@Composable
private fun PanelSection(title: String) {
    Text(
        title,
        color = Color.White.copy(alpha = .55f),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        style = ViewfinderTextStyle,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
    )
}

/** FR: frames with − / + taps (long-press: help). */
@Composable
private fun FrameStepper(frames: Int, min: Int, max: Int, enabled: Boolean, width: Dp, onChange: (Int) -> Unit) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    androidx.compose.foundation.layout.Row(
        Modifier
            .width(width)
            .height(StripDimens.ParamRowHeight)
            .alpha(if (enabled) 1f else .35f)
            .viewfinderChip()
            .testTag("quick_panel_frames")
            .semantics { contentDescription = "Frames $frames. Use minus and plus to change." },
        verticalAlignment = Alignment.CenterVertically
    ) {
        @Composable
        fun StepButton(symbol: String, target: Int) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(22.dp)
                    .fillMaxHeight()
                    .captureCombinedClickable(
                        shape = CaptureTextRippleShape,
                        enabled = enabled && target in min..max && target != frames,
                        role = Role.Button,
                        onLongClickLabel = "Explain",
                        onLongClick = { showHelp(HELP_FRAMES) }
                    ) {
                        haptics.selection()
                        onChange(target)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, color = Color.White.copy(alpha = .9f), fontSize = 16.sp, style = ViewfinderTextStyle)
            }
        }
        StepButton("−", frames - 1)
        androidx.compose.foundation.layout.Box(
            Modifier.weight(1f).fillMaxHeight()
                .captureCombinedClickable(
                    shape = CaptureTextRippleShape,
                    onLongClickLabel = "Explain",
                    onLongClick = { showHelp(HELP_FRAMES) }
                ) {},
            contentAlignment = Alignment.Center
        ) {
            StripTwoLineItem(valueText = frames.toString(), valueColor = Color.White.copy(alpha = .95f), labelText = "FR")
        }
        StepButton("+", frames + 1)
    }
}

private const val HELP_OWN_MF =
    "Own multiframe: ON gives this lens its own merge settings and frame count (FR then changes only this " +
        "lens); OFF uses the shared ones. FR is capped by the lens limit (200 MP: 3)."
private const val HELP_RESET =
    "Reset: tap puts this camera back to Auto - exposure mode, ISO, shutter, EV, WB and focus. Other cameras " +
        "keep what they had. Every camera reopens as you left it, even after closing the app."
private const val HELP_SHUT =
    "Shutter: the slowest shutter Rawr AE picks before raising ISO. AUTO uses 1/15 s (OIS keeps it sharp); " +
        "faster limits freeze motion at higher ISO. Needs Rawr AE."
private const val HELP_SHARP =
    "Sharpening: capture sharpening applied to photos (JPEG). OFF, LOW, MED, SHARP or MAX; tap to cycle."
private const val HELP_NR =
    "Noise reduction: wavelet denoise for the current mode (photo or video). Strength and detail are in Settings."
private const val HELP_OWN_LOOK =
    "Own look: ON keeps a separate profile, tone, AgX choice and film look for this lens (it starts from the " +
        "current look); OFF returns the lens to the shared look."
private const val HELP_THR =
    "Threshold: how bright a pixel must be before highlight reconstruction treats it as clipped. Lower catches " +
        "more of the highlights; 1.00 is the sensor clip point. Drag up/down; double-tap resets. Active with OPP."
private const val HELP_CMP =
    "Compression: how strongly rebuilt highlights are rolled off into white. Higher keeps more highlight " +
        "detail but flatter. Drag up/down; double-tap resets. Active with OPP."
private const val HELP_FRAMES =
    "Frames merged per multiframe photo on this lens, capped by the lens limit (200 MP: 3). With OWN MF on the " +
        "count is this lens's own; off, it is shared by the lenses without their own settings."

package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
 * Quick panel: MTR / AE / HL in a translucent card at the top-left of the viewfinder (clear of the scopes),
 * opened with a swipe down and closed with a swipe up or a tap outside. HL cycles OFF / PROP / OPP; with OPP a
 * second row shows its THR / CMP sliders.
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
    androidx.compose.foundation.layout.Column(
        modifier
            .background(Color.Black.copy(alpha = .62f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }  // taps inside never focus the viewfinder
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
        horizontalAlignment = Alignment.Start
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)
        ) {
            // MTR stays in place with Device AE (dimmed, not tappable) so the panel never changes width.
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
        }
        if (!quick.isVideo) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)
            ) {
                // Frames for the lens in use, capped by its profile limit (a 1-frame lens has nothing to pick).
                val maxFrames = if (quick.frameLimit > 0) quick.frameLimit else 30
                val framesEnabled = enabled && quick.multiframeEnabled && maxFrames > 1
                val frames = quick.frames.coerceIn(1, maxFrames)
                val defaultFrames = minOf(17, maxFrames)
                StripNumericItem(
                    key = "panel_frames",
                    shortLabel = "FR",
                    value = frames.toFloat(),
                    display = frames.toString(),
                    isDefault = frames == defaultFrames,
                    defaultValue = defaultFrames.toFloat(),
                    min = 1f, max = maxOf(2, maxFrames).toFloat(), step = 1f, decimals = 0,
                    onScrub = { if (framesEnabled) onEvent(CaptureQuickEvent.SetMultiframeFrames(it.toInt())) },
                    onReset = { if (framesEnabled) onEvent(CaptureQuickEvent.SetMultiframeFrames(defaultFrames)) },
                    modifier = Modifier.width(chipWidth).alpha(if (framesEnabled) 1f else .4f),
                    testTag = "quick_panel_frames"
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
            }
        }
        if (highlight.mode == HighlightQuickMode.Opposed) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)
            ) {
                StripNumericItem(
                    key = "panel_highlight_threshold",
                    shortLabel = "THR",
                    value = highlight.threshold,
                    display = "%.2f".format(java.util.Locale.US, highlight.threshold),
                    isDefault = highlight.threshold == HIGHLIGHT_THRESHOLD_DEFAULT,
                    defaultValue = HIGHLIGHT_THRESHOLD_DEFAULT,
                    min = .5f, max = 2f, step = .05f, decimals = 2,
                    onScrub = { onEvent(CaptureQuickEvent.SetHighlightThreshold(it)) },
                    onReset = { onEvent(CaptureQuickEvent.SetHighlightThreshold(HIGHLIGHT_THRESHOLD_DEFAULT)) },
                    modifier = Modifier.width(chipWidth).alpha(if (enabled) 1f else .4f),
                    testTag = CaptureTestTags.RAIL_HIGHLIGHT_THRESHOLD
                )
                StripNumericItem(
                    key = "panel_highlight_compression",
                    shortLabel = "CMP",
                    value = highlight.compression,
                    display = highlight.compression.toInt().toString(),
                    isDefault = highlight.compression == highlight.compressionDefault,
                    defaultValue = highlight.compressionDefault,
                    min = 0f, max = 300f, step = 5f, decimals = 0,
                    onScrub = { onEvent(CaptureQuickEvent.SetHighlightCompression(it)) },
                    onReset = { onEvent(CaptureQuickEvent.SetHighlightCompression(highlight.compressionDefault)) },
                    modifier = Modifier.width(chipWidth).alpha(if (enabled) 1f else .4f),
                    testTag = CaptureTestTags.RAIL_HIGHLIGHT_COMPRESSION
                )
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp)
                .width(36.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = .55f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
    }
}

private const val HELP_OWN_MF =
    "Own multiframe: ON gives this lens its own merge settings and frame count (FR then changes only this " +
        "lens); OFF uses the shared ones. FR is capped by the lens limit (200 MP: 3)."
private const val HELP_OWN_LOOK =
    "Own look: ON keeps a separate profile, tone, AgX choice and film look for this lens (it starts from the " +
        "current look); OFF returns the lens to the shared look."

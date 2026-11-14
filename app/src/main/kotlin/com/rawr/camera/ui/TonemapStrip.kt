package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.ScrubRenderExposure
import com.rawr.camera.architecture.ScrubTone
import com.rawr.camera.architecture.SetAgxControl
import com.rawr.camera.architecture.SetRenderExposureTenths
import com.rawr.camera.model.AGX_STRIP_EXPOSURE_KEY
import com.rawr.camera.model.agxStripState
import com.rawr.camera.settings.model.AgxCatalog
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.TonemapCatalog
import com.rawr.camera.model.TonemapParam
import com.rawr.camera.model.renderExposureTenthsOf
import kotlin.math.roundToInt

/**
 * Regular-tonemap (Film Sim OFF) companion to [FilmStrip].
 *
 * Surfaced identically: a collapsed profile/PARAMS header, a profile picker,
 * and a single PARAMS row exposing every [TonemapCatalog] tone slider. The
 * catalog owns the parameter set, order, labels and defaults, so this file
 * only presents them.
 */
private enum class TonemapStripMode { Collapsed, Profiles, Params }

@Composable
internal fun TonemapStrip(
    profiles: RenderProfileQuickState,
    state: CaptureUiState,
    onSelectProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    dispatch: CaptureDispatch,
    modifier: Modifier = Modifier
) {
    var mode by remember(profiles.log) { mutableStateOf(TonemapStripMode.Collapsed) }
    val profileLabel = profiles.options.firstOrNull { it.id == profiles.selectedId }?.label ?: "RAWR NTRL"
    // Render exposure counts too: the manual-mode EV button edits it from capture.
    val modified = !profiles.log && (state.renderExposureTenths != 0 ||
        TonemapCatalog.tone.any { !TonemapCatalog.isDefault(state, requireNotNull(it.param)) } ||
        profiles.agx?.modified == true)
    // AgX replaces the TONE row with darktable's sections (FilmConfigBlock levels).
    var agxSection by remember { mutableStateOf<String?>(null) }
    var agxSubsection by remember { mutableStateOf<String?>(null) }
    Column(
        modifier.testTag(CaptureTestTags.TONEMAP_STRIP),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val agx = profiles.agx
        // AgX's sections need more than one row: its block opens above the fixed LOOK/PARAMS row.
        if (mode == TonemapStripMode.Params && agx != null) {
            val quick = agxStripState(agx, state.renderExposureTenths, profileLabel)
            val section = quick.sections.firstOrNull { it.key == agxSection } ?: quick.sections.first()
            val subsection = section.subsections.firstOrNull { it.key == agxSubsection } ?: section.subsections.first()
            FilmConfigBlock(
                quick = quick,
                sectionKey = section.key,
                subsectionKey = subsection.key,
                onSection = {
                    agxSection = it
                    agxSubsection = null
                },
                onSubsection = { agxSubsection = it },
                onBack = { mode = TonemapStripMode.Collapsed },
                onOpenProfiles = { mode = TonemapStripMode.Profiles },
                l4Key = null,
                onOpenDiscrete = {},
                onCloseL4 = {},
                onScrubParam = { key, value -> dispatchAgx(dispatch, key, value) },
                onResetParam = { key -> dispatchAgx(dispatch, key, null) },
                onScrubDiscrete = { _, _ -> },
                onToggleFlag = { _, _ -> },
                kind = "AgX"
            )
        }
        TonemapFixedRow(
            mode = mode,
            profiles = profiles,
            state = state,
            profileLabel = profileLabel,
            modified = modified,
            paramsInline = agx == null,
            dispatch = dispatch,
            onSelectProfile = onSelectProfile,
            // LOOK and PARAMS never move: tapping one opens its content on the left, tapping it again closes.
            onToggle = { target -> mode = if (mode == target) TonemapStripMode.Collapsed else target }
        )
    }
}

/**
 * The exposure row's five columns: the open content (profiles, or the tone sliders) on the first four and
 * PARAMS over AF, with LOOK one row higher above PARAMS (hidden while PARAMS is open). The open button is
 * outlined in the accent colour.
 */
@Composable
private fun TonemapFixedRow(
    mode: TonemapStripMode,
    profiles: RenderProfileQuickState,
    state: CaptureUiState,
    profileLabel: String,
    modified: Boolean,
    paramsInline: Boolean,
    dispatch: CaptureDispatch,
    onSelectProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    onToggle: (TonemapStripMode) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .padding(bottom = CaptureDimens.ControlGap)
    ) {
        val widths = paramsCellWidths(maxWidth)
        val gap = CaptureDimens.ControlGap
        // LOOK sits above PARAMS (the old MTR/AE/HL rail slot), so the open content gets the first four columns.
        val contentWidth = widths[0] + widths[1] + widths[2] + widths[3] + gap * 3
        val sliderWidths = widths.subList(0, 4)
        val gapPx = with(LocalDensity.current) { gap.toPx() }
        val lookOpen = mode == TonemapStripMode.Profiles
        val paramsOpen = mode == TonemapStripMode.Params
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
        // LOOK open: a grid of the looks above the LOOK button (tap picks one and closes the grid).
        if (lookOpen) {
            val gridWidth = widths[1] + widths[2] + widths[3] + widths[4] + gap * 3
            val cellWidth = (gridWidth - gap * 2) / 3
            Column(
                Modifier
                    .width(gridWidth)
                    .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(14.dp))
                    .padding(gap),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                profiles.options.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowOptions.forEach { option ->
                            val isSelected = option.id == profiles.selectedId
                            Box(
                                Modifier
                                    .width(cellWidth)
                                    .height(CaptureDimens.CompactParamRowHeight)
                                    .viewfinderChip(active = isSelected)
                                    .testTag(CaptureTestTags.renderProfile(option.id.key))
                                    .captureClickable(shape = CaptureTextRippleShape, enabled = !profiles.locked) {
                                        haptics.selection()
                                        onSelectProfile(option.id)
                                        onToggle(TonemapStripMode.Profiles)
                                    }.semantics {
                                        contentDescription = "Render profile ${option.label}" +
                                            if (isSelected) ", active" else ""
                                        role = Role.RadioButton
                                        selected = isSelected
                                    }.padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    option.label,
                                    color = if (isSelected) CaptureColors.Accent else Color.White.copy(alpha = .85f),
                                    fontFamily = CaptureMono,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = ViewfinderTextStyle
                                )
                            }
                        }
                    }
                }
            }
        }
        // LOOK over PARAMS, visible in every state.
        run {
                Box(
                    Modifier
                        .width(widths[4])
                        .height(StripDimens.ParamRowHeight)
                        .testTag(CaptureTestTags.TONEMAP_STRIP_PROFILE)
                        .captureCombinedClickable(
                            shape = CaptureTextRippleShape,
                            onLongClickLabel = "Explain",
                            onLongClick = {
                                showHelp(
                                    "Look: the render profile (tone curve and colour) used for the JPEG and preview. " +
                                        "A dot means its parameters were changed. Tap to pick another."
                                )
                            }
                        ) {
                            haptics.selection()
                            onToggle(TonemapStripMode.Profiles)
                        }.semantics {
                            contentDescription = "Render profile $profileLabel. " +
                                if (lookOpen) "Tap to close." else "Tap to choose profile."
                            onClick {
                                haptics.selection()
                                onToggle(TonemapStripMode.Profiles)
                                true
                            }
                        }
                        .viewfinderChip(active = lookOpen),
                    contentAlignment = Alignment.Center
                ) {
                    StripTwoLineItem(
                        valueText = if (modified) "$profileLabel •" else profileLabel,
                        valueColor = if (modified) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f),
                        labelText = "LOOK"
                    )
                }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(contentWidth)
                    .height(StripDimens.ParamRowHeight),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (paramsOpen && paramsInline) {
                    LazyRow(
                        Modifier
                            .fillMaxSize()
                            .testTag(CaptureTestTags.TONEMAP_STRIP_PARAMS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        itemsIndexed(TonemapCatalog.all, key = { _, item -> item.jsonKey }) { index, descriptor ->
                            // Divider where the controls move to another stage of the render, drawn in the gap.
                            val startsGroup = index > 0 && TonemapCatalog.all[index - 1].group != descriptor.group
                            val cellModifier = Modifier
                                .width(sliderWidths[index % sliderWidths.size])
                                .then(
                                    if (startsGroup) {
                                        Modifier.drawBehind {
                                            drawLine(
                                                Color.White.copy(alpha = .35f),
                                                Offset(-gapPx / 2f, size.height * .25f),
                                                Offset(-gapPx / 2f, size.height * .75f),
                                                strokeWidth = 1.dp.toPx()
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                            if (descriptor.param == null) {
                                RenderExposureParamItem(descriptor, state, dispatch, cellModifier)
                            } else {
                                TonemapParamItem(descriptor, state, dispatch, cellModifier)
                            }
                        }
                    }
                }
            }
            if (!profiles.log) {
                StripParamsEntry(
                    { onToggle(TonemapStripMode.Params) },
                    Modifier.width(widths[4]).height(StripDimens.ParamRowHeight),
                    active = paramsOpen
                )
            } else {
                Box(Modifier.width(widths[4]))
            }
        }
        }
    }
}

@Composable
private fun TonemapParamItem(
    descriptor: TonemapParam,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier
) {
    val parameter = requireNotNull(descriptor.param)
    val committed = TonemapCatalog.valueOf(state, parameter)
    val latestCommitted by rememberUpdatedState(committed)
    // Single source for the ScrubTone delta base. Re-seeded only at drag
    // start (mirrors ToneScrubSurface): re-seeding on every committed change
    // would reset the base mid-drag when the async persist/sync round-trip
    // lands, inflating/duplicating steps under fast scrub.
    var gesture by remember(descriptor.jsonKey) { mutableIntStateOf(committed) }

    StripNumericItem(
        key = descriptor.jsonKey,
        shortLabel = descriptor.shortLabel,
        value = committed.toFloat(),
        display = signedTone(committed),
        isDefault = committed == descriptor.defaultValue.roundToInt(),
        defaultValue = descriptor.defaultValue,
        min = descriptor.minimum,
        max = descriptor.maximum,
        step = descriptor.step,
        decimals = descriptor.decimals,
        modifier = modifier,
        testTag = CaptureTestTags.tonemapParam(descriptor.jsonKey),
        onDragStarted = { gesture = latestCommitted },
        onScrub = { nextFloat ->
            val next = nextFloat.roundToInt().coerceIn(descriptor.minimum.roundToInt(), descriptor.maximum.roundToInt())
            val delta = next - gesture
            if (delta != 0) {
                gesture = next
                dispatch(ScrubTone(parameter, delta))
            }
        },
        onReset = {
            val current = latestCommitted
            gesture = descriptor.defaultValue.roundToInt()
            if (current != descriptor.defaultValue.roundToInt()) {
                dispatch(ScrubTone(parameter, descriptor.defaultValue.roundToInt() - current))
            }
        },
        format = { signedTone(it.roundToInt()) }
    )
}

/** Render exposure cell: same 0.1 EV state the full-Manual EV button scrubs. */
@Composable
private fun RenderExposureParamItem(
    descriptor: TonemapParam,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier
) {
    val committed = state.renderExposureTenths
    val latestCommitted by rememberUpdatedState(committed)
    // Delta base re-seeded only at drag start (see TonemapParamItem).
    var gesture by remember(descriptor.jsonKey) { mutableIntStateOf(committed) }

    StripNumericItem(
        key = descriptor.jsonKey,
        shortLabel = descriptor.shortLabel,
        value = committed / 10f,
        display = renderExposureLabel(committed),
        isDefault = committed == 0,
        defaultValue = descriptor.defaultValue,
        min = descriptor.minimum,
        max = descriptor.maximum,
        step = descriptor.step,
        decimals = descriptor.decimals,
        modifier = modifier,
        testTag = CaptureTestTags.tonemapParam(descriptor.jsonKey),
        onDragStarted = { gesture = latestCommitted },
        onScrub = { nextEv ->
            val next = renderExposureTenthsOf(nextEv)
            if (next != gesture) {
                dispatch(ScrubRenderExposure(next - gesture))
                gesture = next
            }
        },
        onReset = {
            val current = latestCommitted
            gesture = 0
            if (current != 0) dispatch(ScrubRenderExposure(-current))
        },
        format = { renderExposureLabel(renderExposureTenthsOf(it)) }
    )
}

/** AgX strip edits: the RANGE exposure cell is render exposure, every other key an AgX control. */
private fun dispatchAgx(dispatch: CaptureDispatch, key: String, value: Float?) {
    if (key == AGX_STRIP_EXPOSURE_KEY) {
        dispatch(SetRenderExposureTenths(value?.let { renderExposureTenthsOf(it) } ?: 0))
        return
    }
    val spec = AgxCatalog.byJsonKey(key) ?: return
    dispatch(SetAgxControl(spec.parameter, value?.let(spec::clamp)))
}

private fun signedTone(value: Int): String = if (value >= 0) "+$value" else "$value"

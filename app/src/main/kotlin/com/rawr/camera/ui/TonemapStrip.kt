package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
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
private enum class TonemapStripMode { Collapsed, Profiles, Params, Adv }

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
    // AgX: PARAMS opens a quick card; ADV › opens darktable's sections (RANGE, CURVE, LOOK, PRIM) as grids.
    var agxSection by remember { mutableStateOf<String?>(null) }
    var agxSubsection by remember { mutableStateOf<String?>(null) }
    Column(
        modifier.testTag(CaptureTestTags.TONEMAP_STRIP),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val agx = profiles.agx
        val agxQuick = agx?.let { agxStripState(it, state.renderExposureTenths, profileLabel) }
        val agxSectionState = agxQuick?.let { q -> q.sections.firstOrNull { it.key == agxSection } ?: q.sections.first() }
        val agxSubsectionState = agxSectionState?.let { sec ->
            sec.subsections.firstOrNull { it.key == agxSubsection } ?: sec.subsections.first()
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
            // From the AgX ADV editor, PARAMS closes too.
            onToggle = { target ->
                mode = if (mode == target || (target == TonemapStripMode.Params && mode == TonemapStripMode.Adv)) {
                    TonemapStripMode.Collapsed
                } else target
            },
            // AgX: PARAMS opens a quick card (the controls changed per scene); ADV › opens the full editor.
            card = if (agxQuick == null) null else when (mode) {
                TonemapStripMode.Params -> { _: Dp ->
                    FilmQuickCard(
                        quick = agxQuick,
                        onScrubParam = { key, value -> dispatchAgx(dispatch, key, value) },
                        onResetParam = { key -> dispatchAgx(dispatch, key, null) },
                        onToggleFlag = { _, _ -> },
                        onAdvanced = { mode = TonemapStripMode.Adv },
                        groups = AgxQuickGroups,
                        tagPrefix = "agx",
                        advDescription = "All AgX parameters",
                        helpFor = ::agxHelp
                    )
                }
                TonemapStripMode.Adv -> { cardWidth: Dp ->
                    FilmAdvCard(
                        quick = agxQuick,
                        sectionKey = agxSectionState?.key,
                        subsectionKey = agxSubsectionState?.key,
                        cardWidth = cardWidth,
                        onSection = {
                            agxSection = it
                            agxSubsection = null
                        },
                        onSubsection = { agxSubsection = it },
                        onBackToQuick = { mode = TonemapStripMode.Params },
                        sectionTag = { "capture_agx_section_$it" },
                        subsectionTag = { sec, sub -> "capture_agx_subsection_${sec}_$sub" },
                        backTag = "agx_adv_back"
                    )
                }
                else -> null
            },
            inlineRow = if (agxQuick != null && mode == TonemapStripMode.Adv && agxSubsectionState != null) {
                { widths ->
                    FilmNumericRow(
                        quick = agxQuick,
                        items = agxSubsectionState.items,
                        widths = widths,
                        onScrubParam = { key, value -> dispatchAgx(dispatch, key, value) },
                        onResetParam = { key -> dispatchAgx(dispatch, key, null) },
                        modifier = Modifier.fillMaxSize().testTag(CaptureTestTags.TONEMAP_STRIP_PARAMS),
                        helpFor = ::agxHelp
                    )
                }
            } else null
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
    onToggle: (TonemapStripMode) -> Unit,
    /** Card above LOOK (AgX quick card or ADV grids), given the four right columns' width. */
    card: (@Composable (Dp) -> Unit)? = null,
    /** Content of the four columns beside PARAMS when it is not the inline tone row (AgX ADV). */
    inlineRow: (@Composable (List<Dp>) -> Unit)? = null
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
        val paramsOpen = mode == TonemapStripMode.Params || mode == TonemapStripMode.Adv
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
        card?.let { content ->
            val cardWidth = widths[1] + widths[2] + widths[3] + widths[4] + gap * 3
            Box(Modifier.width(cardWidth)) { content(cardWidth) }
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
                if (paramsOpen && inlineRow != null) {
                    inlineRow(sliderWidths)
                } else if (paramsOpen && paramsInline) {
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

/** AgX PARAMS quick card: range, curve and look controls people change per scene; ADV › has the rest. */
private val AgxQuickGroups: List<Pair<String, List<FilmQuickEntry>>> = listOf(
    "RANGE" to listOf(
        FilmQuickEntry.Num(AGX_STRIP_EXPOSURE_KEY, "EV"),
        FilmQuickEntry.Num("whiteEv", "WHITE"),
        FilmQuickEntry.Num("blackEv", "BLACK")
    ),
    "CURVE" to listOf(
        FilmQuickEntry.Num("contrast", "CONTR"),
        FilmQuickEntry.Num("toePower", "TOE"),
        FilmQuickEntry.Num("shoulderPower", "SHLDR")
    ),
    "LOOK" to listOf(
        FilmQuickEntry.Num("lookSlope", "SLOPE"),
        FilmQuickEntry.Num("lookSaturation", "SAT"),
        FilmQuickEntry.Advanced
    )
)

/** Long-press explanation of each AgX strip control (darktable's AgX meanings), "Title: body". */
private fun agxHelp(key: String): String? {
    val tail = " Drag up/down to adjust; double-tap resets."
    val text = when (key) {
        AGX_STRIP_EXPOSURE_KEY -> "Exposure: brightens or darkens the render before the AgX curve. The RAW is untouched."
        "whiteEv" -> "White: how many stops above mid-grey become pure white. Higher keeps more highlight detail but flattens the image."
        "blackEv" -> "Black: how many stops below mid-grey become pure black. More negative keeps more shadow detail; closer to 0 deepens blacks."
        "contrast" -> "Contrast: steepness of the curve around the pivot. Higher gives punchier midtones."
        "toePower" -> "Toe: shape of the shadow roll-off into black. Higher makes the shadows fall off more abruptly."
        "shoulderPower" -> "Shoulder: shape of the highlight roll-off into white. Higher makes the highlights compress more abruptly."
        "pivotEv" -> "Pivot: the tone (stops from mid-grey) where contrast is anchored. Move it to centre contrast on darker or brighter tones."
        "pivotOutput" -> "Pivot output: how bright the pivot tone is rendered. Raise it to brighten the midtones."
        "shoulderStart" -> "Shoulder start: where the highlight roll-off begins. Lower starts compressing highlights earlier."
        "toeStart" -> "Toe start: where the shadow roll-off begins. Lower starts compressing shadows earlier."
        "targetWhite" -> "Target white: output level of the white point. Below 100% gives softer, matte highlights."
        "targetBlack" -> "Target black: output level of the black point. Above 0 lifts the blacks for a faded look."
        "gamma" -> "Gamma: gamma of the curve's output. Changes the overall midtone brightness; usually left as is."
        "lookSlope" -> "Slope: gain applied after the curve (like CDL slope). Higher brightens and adds contrast."
        "lookLift" -> "Lift: raises or lowers the shadows after the curve (like CDL offset)."
        "lookBrightness" -> "Brightness: power applied after the curve. Above 1 brightens the midtones."
        "lookSaturation" -> "Saturation: overall colour intensity after the curve."
        "preserveHue" -> "Preserve hue: how much original hues are kept through the curve. 100% keeps them; lower lets bright colours drift toward white, like film."
        "purity" -> "Purity: master saturation restored after the curve, for all primaries."
        "rotationReversal" -> "Reverse: master undo of the primary rotations after the curve."
        else -> null
    } ?: agxPrimaryHelp(key) ?: return null
    return text + tail
}

private fun agxPrimaryHelp(key: String): String? {
    val colour = listOf("red", "green", "blue").firstOrNull { key.startsWith(it) } ?: return null
    val name = colour.replaceFirstChar { it.uppercase() }
    val short = name.first()
    return when (key.removePrefix(colour)) {
        "Attenuation" -> "$short ATT: pulls the $colour primary toward white before the curve, so bright, saturated ${colour}s desaturate smoothly instead of clipping."
        "Rotation" -> "$short ROT: rotates the hue of the $colour primary before the curve, shifting how ${colour}s render."
        "Purity" -> "$short PUR: restores $colour saturation after the curve."
        "ReverseRotation" -> "$short REV: undoes the $colour hue rotation after the curve."
        else -> null
    }
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

/**
 * Rawr 17U fork: the look sheet opened by the LOOK button next to the shutter ([LookSheetState]), in the film sheet's
 * format (FilmStockSheet.kt): a row of chips - close, RESET/UNDO, LOOKS and the PARAMS groups - over the looks as
 * swipeable cards. A PARAMS group opens its controls in a card above the sheet: the tone controls by stage (TONE,
 * COLOUR, OUTPUT), or the AgX quick groups (RANGE, CURVE, LOOK) with ADV for every AgX parameter. It reuses Ed's
 * controls ([TonemapParamItem], [RenderExposureParamItem], [FilmQuickCard], [FilmAdvCard]); only the layout is new.
 */
@Composable
internal fun LookSheetPanel(
    profiles: RenderProfileQuickState,
    state: CaptureUiState,
    onSelectProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    dispatch: CaptureDispatch,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    val gap = CaptureDimens.ControlGap
    val profileLabel = profiles.options.firstOrNull { it.id == profiles.selectedId }?.label ?: "RAWR NTRL"
    val agxQuick = profiles.agx?.let { agxStripState(it, state.renderExposureTenths, profileLabel) }
    val paramTabs: List<String> = when {
        profiles.log -> emptyList()
        agxQuick != null -> AgxQuickGroups.map { it.first } + LOOK_SHEET_ADV
        else -> LookToneTabs.map { it.first }
    }
    var tab by remember { mutableStateOf(LOOK_SHEET_LOOKS) }
    if (tab != LOOK_SHEET_LOOKS && tab !in paramTabs) tab = LOOK_SHEET_LOOKS
    var agxSection by remember { mutableStateOf<String?>(null) }
    var agxSubsection by remember { mutableStateOf<String?>(null) }
    val modified = !profiles.log && (state.renderExposureTenths != 0 ||
        TonemapCatalog.tone.any { !TonemapCatalog.isDefault(state, requireNotNull(it.param)) } ||
        profiles.agx?.modified == true)
    // RESET: every tone control (and the AgX controls of an AgX look) back to its default; the values it replaced are
    // kept for 5 seconds, while the chip reads UNDO.
    var undo by remember { mutableStateOf<LookSnapshot?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            kotlinx.coroutines.delay(5000)
            undo = null
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
            .padding(bottom = gap),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // The open PARAMS group, above the sheet.
        val toneGroup = LookToneTabs.firstOrNull { it.first == tab }
        if (toneGroup != null && agxQuick == null && !profiles.log) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(14.dp))
                    .padding(gap)
            ) {
                val widths = paramsCellWidths(maxWidth)
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .height(StripDimens.ParamRowHeight)
                        .testTag(CaptureTestTags.TONEMAP_STRIP_PARAMS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    itemsIndexed(toneGroup.second, key = { _, item -> item.jsonKey }) { index, descriptor ->
                        val cell = Modifier.width(widths[index % widths.size])
                        if (descriptor.param == null) {
                            RenderExposureParamItem(descriptor, state, dispatch, cell)
                        } else {
                            TonemapParamItem(descriptor, state, dispatch, cell)
                        }
                    }
                }
            }
        } else if (agxQuick != null && tab == LOOK_SHEET_ADV) {
            val section = agxQuick.sections.firstOrNull { it.key == agxSection } ?: agxQuick.sections.firstOrNull()
            val subsection = section?.let { sec -> sec.subsections.firstOrNull { it.key == agxSubsection } ?: sec.subsections.firstOrNull() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widths = paramsCellWidths(maxWidth)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    FilmAdvCard(
                        quick = agxQuick,
                        sectionKey = section?.key,
                        subsectionKey = subsection?.key,
                        cardWidth = maxWidth,
                        onSection = {
                            agxSection = it
                            agxSubsection = null
                        },
                        onSubsection = { agxSubsection = it },
                        onBackToQuick = { tab = AgxQuickGroups.first().first },
                        sectionTag = { "capture_agx_section_$it" },
                        subsectionTag = { sec, sub -> "capture_agx_subsection_${sec}_$sub" },
                        backTag = "agx_adv_back"
                    )
                    if (subsection != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(StripDimens.ParamRowHeight)
                        ) {
                            FilmNumericRow(
                                quick = agxQuick,
                                items = subsection.items,
                                widths = widths,
                                onScrubParam = { key, value -> dispatchAgx(dispatch, key, value) },
                                onResetParam = { key -> dispatchAgx(dispatch, key, null) },
                                modifier = Modifier.fillMaxSize(),
                                helpFor = ::agxHelp
                            )
                        }
                    }
                }
            }
        } else if (agxQuick != null && tab != LOOK_SHEET_LOOKS) {
            FilmQuickCard(
                quick = agxQuick,
                onScrubParam = { key, value -> dispatchAgx(dispatch, key, value) },
                onResetParam = { key -> dispatchAgx(dispatch, key, null) },
                onToggleFlag = { _, _ -> },
                onAdvanced = { tab = LOOK_SHEET_ADV },
                groups = AgxQuickGroups.filter { it.first == tab },
                tagPrefix = "agx",
                advDescription = "All AgX parameters",
                helpFor = ::agxHelp
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(14.dp))
                .padding(vertical = 6.dp)
                .testTag(CaptureTestTags.TONEMAP_STRIP),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "close") {
                    LookSheetChip("✕", active = false, tag = "look_sheet_close", help = "Close: hides the look sheet; LOOK opens it again.", onHelp = showHelp) {
                        haptics.selection()
                        onClose()
                    }
                }
                val pending = undo
                if (modified || pending != null) {
                    item(key = "reset") {
                        LookSheetChip(
                            if (pending != null) "UNDO" else "RESET",
                            active = pending != null,
                            tag = "look_sheet_reset",
                            help = "Reset: the parameters of $profileLabel back to its defaults (the look stays). " +
                                "UNDO brings the previous values back for 5 seconds.",
                            onHelp = showHelp
                        ) {
                            haptics.selection()
                            if (pending != null) {
                                pending.restore(dispatch)
                                undo = null
                            } else {
                                undo = LookSnapshot.of(state, agxQuick)
                                resetLook(state, agxQuick, dispatch)
                            }
                        }
                    }
                }
                val tabs = listOf(LOOK_SHEET_LOOKS) + paramTabs
                items(tabs.size, key = { "tab_${tabs[it]}" }) { i ->
                    val t = tabs[i]
                    LookSheetChip(
                        t,
                        active = t == tab,
                        tag = "look_sheet_tab:$t",
                        help = if (t == LOOK_SHEET_LOOKS) "Looks: the render profile (tone curve and colour) for the JPEG and preview. Tap a card to use it."
                        else "$t: the look's parameters for this stage. Drag a value to change it; double-tap resets it.",
                        onHelp = showHelp
                    ) {
                        haptics.selection()
                        tab = t
                    }
                }
            }
            val options = profiles.options
            val start = options.indexOfFirst { it.id == profiles.selectedId }.coerceAtLeast(0)
            val list = remember { androidx.compose.foundation.lazy.LazyListState((start - 1).coerceAtLeast(0)) }
            LazyRow(
                state = list,
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(options.size, key = { options[it].id.key }) { i ->
                    val option = options[i]
                    LookCard(
                        top = lookCardKind(option.id),
                        name = option.label,
                        selected = option.id == profiles.selectedId,
                        enabled = !profiles.locked,
                        tag = CaptureTestTags.renderProfile(option.id.key)
                    ) {
                        haptics.selection()
                        onSelectProfile(option.id)
                    }
                }
            }
        }
    }
}

private const val LOOK_SHEET_LOOKS = "LOOKS"
private const val LOOK_SHEET_ADV = "ADV"

/** The tone controls by stage, as the look sheet's PARAMS tabs (catalog order inside each). */
private val LookToneTabs: List<Pair<String, List<TonemapParam>>> = listOf(
    "TONE" to listOf(
        TonemapCatalog.renderExposure,
        TonemapCatalog.highlights,
        TonemapCatalog.shadows,
        TonemapCatalog.midtones,
        TonemapCatalog.contrast
    ),
    "COLOUR" to listOf(TonemapCatalog.saturation, TonemapCatalog.vibrance),
    "OUTPUT" to listOf(TonemapCatalog.whites, TonemapCatalog.blacks)
)

private fun lookCardKind(id: com.rawr.camera.model.RenderProfileSelection): String = when (id) {
    is com.rawr.camera.model.RenderProfileSelection.Imported -> "LUT"
    is com.rawr.camera.model.RenderProfileSelection.Agx, is com.rawr.camera.model.RenderProfileSelection.SavedAgx -> "AGX"
    is com.rawr.camera.model.RenderProfileSelection.Log -> "LOG"
    is com.rawr.camera.model.RenderProfileSelection.BuiltIn -> "RAWR"
}

/** The values RESET replaced: tone controls, render exposure and (AgX look) the AgX controls by strip key. */
private class LookSnapshot(
    val tone: Map<com.rawr.camera.model.ToneParameter, Int>,
    val renderExposureTenths: Int,
    val agx: Map<String, Float>
) {
    fun restore(dispatch: CaptureDispatch) {
        // RESET left every tone control at its default, so the way back is the saved value minus the default.
        tone.forEach { (parameter, value) ->
            val neutral = TonemapCatalog.forParameter(parameter).defaultValue.roundToInt()
            if (value != neutral) dispatch(ScrubTone(parameter, value - neutral))
        }
        agx.forEach { (key, value) -> if (key != AGX_STRIP_EXPOSURE_KEY) dispatchAgx(dispatch, key, value) }
        dispatch(SetRenderExposureTenths(renderExposureTenths))
    }

    companion object {
        fun of(state: CaptureUiState, agxQuick: com.rawr.camera.model.FilmSimQuickState?) = LookSnapshot(
            tone = TonemapCatalog.tone.associate { requireNotNull(it.param) to TonemapCatalog.valueOf(state, requireNotNull(it.param)) },
            renderExposureTenths = state.renderExposureTenths,
            agx = agxQuick?.params?.mapValues { it.value.value }.orEmpty()
        )
    }
}

private fun resetLook(state: CaptureUiState, agxQuick: com.rawr.camera.model.FilmSimQuickState?, dispatch: CaptureDispatch) {
    TonemapCatalog.tone.forEach { descriptor ->
        val parameter = requireNotNull(descriptor.param)
        val current = TonemapCatalog.valueOf(state, parameter)
        val neutral = descriptor.defaultValue.roundToInt()
        if (current != neutral) dispatch(ScrubTone(parameter, neutral - current))
    }
    agxQuick?.params?.keys?.forEach { key -> if (key != AGX_STRIP_EXPOSURE_KEY) dispatchAgx(dispatch, key, null) }
    dispatch(SetRenderExposureTenths(0))
}

@Composable
private fun LookSheetChip(
    text: String,
    active: Boolean,
    tag: String,
    help: String,
    onHelp: (String) -> Unit,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .height(26.dp)
            .viewfinderChip(active = active)
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                onLongClickLabel = "Explain",
                onLongClick = { onHelp(help) }
            ) { onClick() }
            .padding(horizontal = if (text == "✕") 10.dp else 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) CaptureColors.Accent else Color.White.copy(alpha = .8f),
            fontFamily = CaptureMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (text == "✕") 11.sp else 10.sp,
            maxLines = 1,
            style = ViewfinderTextStyle
        )
    }
}

/** A look as a card, in the film sheet's card format: its kind on top, its name below. */
@Composable
private fun LookCard(top: String, name: String, selected: Boolean, enabled: Boolean, tag: String, onTap: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .width(84.dp)
            .height(46.dp)
            .clip(shape)
            .background(Color(0xFF1C1C1C))
            .border(if (selected) 2.dp else 1.dp, if (selected) CaptureColors.Accent else CaptureColors.Outline, shape)
            .captureClickable(shape = CaptureTextRippleShape, enabled = enabled) { onTap() }
            .semantics {
                contentDescription = "Render profile $name" + if (selected) ", active" else ""
                role = Role.RadioButton
                this.selected = selected
            }
            .testTag(tag)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            top,
            color = if (selected) CaptureColors.AccentSoft else Color.White.copy(alpha = .5f),
            fontFamily = CaptureMono,
            fontSize = 8.sp,
            maxLines = 1,
            style = ViewfinderTextStyle
        )
        Text(
            name,
            color = if (selected) Color.White else Color.White.copy(alpha = .85f),
            fontFamily = CaptureMono,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewfinderTextStyle
        )
    }
}

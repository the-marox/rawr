package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation as GestureOrientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.systemGestureExclusion
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.rounded.ChevronRight
import com.rawr.camera.ui.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.formatFilmStripValue

@Composable
internal fun StripParamsEntry(onOpen: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    Row(
        modifier
            .height(CaptureDimens.CompactParamRowHeight)
            .testTag(CaptureTestTags.FILM_STRIP_PARAMS)
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                onLongClickLabel = "Explain",
                onLongClick = {
                    showHelp(
                        "Params: adjust the current look or film - contrast, shadows, highlights, colour and more. " +
                            "Drag a value to change it; double-tap resets it."
                    )
                }
            ) {
                haptics.selection()
                onOpen()
            }.semantics {
                contentDescription = "Parameters. Tap to adjust film settings."
                onClick {
                    haptics.selection()
                    onOpen()
                    true
                }
            }
            .viewfinderChip(active)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ContrastIcon(Icons.Outlined.Tune, if (active) CaptureColors.Accent else Color.White.copy(alpha = .62f), 14.dp)
        Text(
            "PARAMS",
            color = if (active) CaptureColors.Accent else Color.White.copy(alpha = .62f),
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = .4.sp,
            maxLines = 1,
            style = ViewfinderTextStyle,
            modifier = Modifier.padding(start = 3.dp)
        )
    }
}

@Composable
internal fun StripNavigationRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = CaptureDimens.CompactParamRowEdgeInset)
    ) {
        val widths = paramsCellWidths(maxWidth)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StripBackButton(onBack, Modifier.width(widths[0]))
            content()
        }
    }
}

@Composable
internal fun StripBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .size(width = 32.dp, height = CaptureDimens.CompactParamRowHeight)
            .viewfinderChip()
            .testTag(CaptureTestTags.FILM_STRIP_BACK)
            .captureClickable(shape = CaptureTextRippleShape) {
                haptics.selection()
                onBack()
            }.semantics {
                contentDescription = "Back"
                onClick {
                    haptics.selection()
                    onBack()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        ContrastIcon(Icons.Rounded.ChevronRight, Color.White.copy(alpha = .62f), 16.dp)
    }
}

/** Match the five exposure columns, including the wider white-balance column. */
internal fun paramsCellWidths(viewportWidth: Dp): List<Dp> {
    val weights = CaptureDimens.CompactParamColumnWeights
    val unitWidth = ((viewportWidth - CaptureDimens.ControlGap * (weights.size - 1)) /
        weights.sum()).coerceAtLeast(0.dp)
    return weights.map { unitWidth * it }
}

/** Leading padding that parks the first parameter at the thumb edge with a peek of the next. */
internal fun parkedParamsPadding(
    viewportWidth: Dp,
    cellWidth: Dp = StripDimens.ParamMinWidth,
    spacing: Dp = StripDimens.ParamSpacing
): Dp {
    val visible = cellWidth * StripDimens.ParkedVisibleCells + spacing
    return (viewportWidth - visible).coerceAtLeast(0.dp)
}

@Composable
internal fun StripTwoLineItem(
    valueText: String,
    valueColor: Color,
    labelText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            valueText,
            color = valueColor,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewfinderTextStyle
        )
        Text(
            labelText,
            color = Color.White.copy(alpha = .62f),
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            lineHeight = 8.sp,
            letterSpacing = .4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewfinderTextStyle
        )
    }
}

/**
 * Two-line scrubbable film param (value above, short label below). VERTICAL
 * drag adjusts by spec steps with detent haptics (up = increase); double-tap
 * resets to the spec default. Vertical-only keeps edge items fully usable and
 * never fights the parent LazyRow's horizontal scroll. Zero-lag local readout
 * while dragging; committed value flows back through [value]/[display].
 */
@Composable
internal fun StripNumericItem(
    key: String,
    shortLabel: String,
    value: Float,
    display: String,
    isDefault: Boolean,
    defaultValue: Float,
    min: Float,
    max: Float,
    step: Float,
    decimals: Int,
    onScrub: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = CaptureTestTags.filmParam(key),
    format: (Float) -> String = { formatFilmStripValue(it, min, decimals) },
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    /** Long-press explanation (the help card); null keeps long-press inert. */
    help: String? = null
) {
    val haptics = LocalCaptureHaptics.current
    val showHelp = rememberControlHelp()
    var accumulated by remember(key) { mutableFloatStateOf(0f) }
    var gestureValue by remember(key) { mutableFloatStateOf(value) }
    var dragging by remember(key) { mutableStateOf(false) }
    var lastHaptic by remember(key) { mutableFloatStateOf(value) }
    val latestValue by rememberUpdatedState(value)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnDragStarted by rememberUpdatedState(onDragStarted)
    val latestOnDragStopped by rememberUpdatedState(onDragStopped)
    val stepPx = with(LocalDensity.current) { 2.dp.toPx() }
    // Coarse enough to feel mechanical, fine enough to reach every detent:
    // at least 5 spec steps, at most ~40 detents across the range.
    val hapticThreshold = maxOf(step * 5f, (max - min) / 40f).takeIf { it > 0f } ?: step

    val shownDisplay = if (dragging) format(gestureValue) else display
    val shownDefault = if (dragging) {
        val epsilon = 0.5f / Math.pow(10.0, decimals.coerceIn(0, 3).toDouble()).toFloat()
        kotlin.math.abs(gestureValue - defaultValue) <= epsilon
    } else {
        isDefault
    }
    val valueColor = if (!shownDefault) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f)
    if (dragging) {
        androidx.compose.runtime.SideEffect {
            // Scale ticks ~1/8 of the range apart (whole steps), clamped to the range.
            val span = (kotlin.math.round(((max - min) / 8f) / step) * step).coerceAtLeast(step)
            val labels = (-3..3).mapNotNull { k ->
                val v = gestureValue + k * span
                if (v < min - step / 2f || v > max + step / 2f) null else k to format(v.coerceIn(min, max))
            }
            ScrubReadout.publish(
                testTag, shortLabel, shownDisplay,
                labels.map { it.second }, labels.indexOfFirst { it.first == 0 }
            )
        }
    }

    val dragState = rememberDraggableState { delta ->
        // Vertical orientation reports down-positive; invert so up increases.
        accumulated += -delta
        val steps = (accumulated / stepPx).toInt()
        if (steps != 0) {
            val next = (gestureValue + steps * step).coerceIn(min, max)
            if (next != gestureValue) {
                gestureValue = next
                latestOnScrub(next)
                if (kotlin.math.abs(next - lastHaptic) >= hapticThreshold) {
                    lastHaptic = next
                    haptics.detent()
                }
            }
            accumulated -= steps * stepPx
        }
    }

    Box(
        modifier
            .widthIn(min = StripDimens.ParamMinWidth)
            .height(StripDimens.ParamRowHeight)
            .viewfinderChip(active = dragging)
            .testTag(testTag)
            .systemGestureExclusion()
            .captureCombinedClickable(
                shape = CaptureTextRippleShape,
                onClick = {},
                onLongClickLabel = help?.let { "Explain" },
                onLongClick = help?.let { text -> { showHelp(text) } },
                onDoubleClick = {
                    gestureValue = latestValue
                    haptics.selection()
                    onReset()
                }
            ).draggable(
                state = dragState,
                orientation = GestureOrientation.Vertical,
                onDragStarted = {
                    accumulated = 0f
                    gestureValue = latestValue
                    lastHaptic = latestValue
                    dragging = true
                    latestOnDragStarted()
                },
                onDragStopped = {
                    dragging = false
                    if (ScrubReadout.activeTag == testTag) ScrubReadout.activeTag = null
                    latestOnDragStopped()
                }
            ).semantics {
                contentDescription = "$shortLabel $shownDisplay. Scrub vertically to adjust; double tap to reset."
            },
        contentAlignment = Alignment.Center
    ) {
        StripTwoLineItem(
            valueText = shownDisplay,
            valueColor = valueColor,
            labelText = shortLabel
        )
        if (!shownDefault) {
            // Rawr 17U fork: a dot on a changed value, so it shows in every theme (in Mono the changed colour is white).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(4.dp)
                    .background(CaptureColors.AccentSoft, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

/** Dark drop shadow keeps viewfinder glyphs legible on bright and dark scenes alike. */
private val ViewfinderGlyphShadow =
    Shadow(color = Color.Black.copy(alpha = .85f), offset = Offset(1.5f, 1.5f), blurRadius = 5f)

internal val ViewfinderTextStyle = TextStyle(shadow = ViewfinderGlyphShadow)

@Composable
internal fun ContrastIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = .8f),
            modifier = Modifier.matchParentSize().offset(1.dp, 1.dp)
        )
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
    }
}

/** 17U: exposure cells as one thin line (value only; ISO keeps its tag). */
internal val LocalCompactParamOneLine = staticCompositionLocalOf { false }

@Composable
internal fun CompactParamShell(
    icon: ImageVector,
    title: String,
    valueText: String,
    locked: Boolean,
    testTag: String,
    semanticsDescription: String,
    modifier: Modifier = Modifier,
    scaleLabels: List<String> = emptyList(),
    scaleIndex: Int = -1
) {
    val contentColor = if (locked) CaptureColors.Accent else Color.White.copy(alpha = .62f)
    val valueColor = if (locked) CaptureColors.AccentSoft else Color.White.copy(alpha = .95f)
    val scrubbing = ScrubReadout.activeTag == testTag
    if (scrubbing) {
        androidx.compose.runtime.SideEffect {
            val (slice, at) = scaleAround(scaleLabels, scaleIndex)
            ScrubReadout.publish(testTag, title, valueText, slice, at)
        }
    }
    // Bare instrument styling (mirrors LensSelector): no card, no border —
    // icon + text only, accent carries the locked state.
    // Gesture exclusion keeps edge columns (ISO left, Focus right) scrubbing
    // instead of losing outward strokes to system navigation.
    Box(
        modifier
            .testTag(testTag)
            .systemGestureExclusion()
            .observeScrub(testTag)
            .viewfinderChip(active = scrubbing)
            .semantics { contentDescription = semanticsDescription },
        contentAlignment = Alignment.Center
    ) {
        if (LocalCompactParamOneLine.current) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 3.dp)
            ) {
                if (title == "ISO") {
                    Text(
                        "ISO ",
                        color = contentColor,
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
                Text(
                    valueText,
                    color = valueColor,
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ViewfinderTextStyle
                )
                if (locked) {
                    Box(
                        Modifier
                            .padding(start = 3.dp)
                            .size(4.dp)
                            .background(CaptureColors.Accent, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        } else {
        // Two-line stacked cell: value above, icon + title below.
        // Never rotated: keeps its orientation in every device posture.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                valueText,
                color = valueColor,
                fontFamily = CaptureMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ViewfinderTextStyle
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ContrastIcon(icon, contentColor, 14.dp)
                Text(
                    title,
                    color = contentColor,
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    letterSpacing = .4.sp,
                    maxLines = 1,
                    style = ViewfinderTextStyle,
                    modifier = Modifier.padding(start = 3.dp)
                )
                if (locked) {
                    Box(
                        Modifier
                            .padding(start = 3.dp)
                            .size(4.dp)
                            .background(CaptureColors.Accent, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        }
        }
    }
}

@Composable
internal fun CompactSidePill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    testTag: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
) {
    val haptics = LocalCaptureHaptics.current
    val contentColor = if (active) CaptureColors.OnAccent else Color.White.copy(alpha = .7f)
    // Boxed toggle (matches the lens buttons): dark when off, solid Leica red when on.
    val shape = CapturePalette.shape()
    val boxed = active || CapturePalette.valueBackgrounds
    Box(
        modifier
            .widthIn(min = 52.dp)
            .height(52.dp)
            .then(
                if (!boxed) Modifier else Modifier
                    .background(if (active) CaptureColors.Accent else Color(0xFF141414), shape)
                    .border(1.dp, if (active) CaptureColors.Accent else Color(0xFF262626), shape)
            )
            .testTag(testTag)
            .captureCombinedClickable(
                shape = CapturePillRippleShape,
                role = Role.Button,
                onClick = {
                    haptics.selection()
                    onClick()
                },
                onLongClickLabel = onLongClickLabel,
                onLongClick =
                    onLongClick?.let { action ->
                        {
                            haptics.longPress()
                            action()
                        }
                    }
            )
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Switch
                onClick {
                    haptics.selection()
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Text(
                label,
                color = contentColor,
                fontFamily = CaptureMono,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                letterSpacing = .5.sp,
                maxLines = 1
            )
        }
    }
}

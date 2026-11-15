package com.rawr.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * While a viewfinder control is scrubbed (finger down and moved), its title and current value are
 * mirrored in a large readout at the top of the viewfinder: the finger covers the control itself.
 */
internal object ScrubReadout {
    /** testTag of the control being scrubbed, null when none. */
    var activeTag by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var value by mutableStateOf("")
    /** Neighbouring values for the scale under the readout (empty: no scale) and the current one's index. */
    var scale by mutableStateOf<List<String>>(emptyList())
    var scaleIndex by mutableStateOf(-1)

    fun publish(tag: String, title: String, value: String, scale: List<String> = emptyList(), scaleIndex: Int = -1) {
        activeTag = tag
        this.title = title
        this.value = value
        this.scale = scale
        this.scaleIndex = scaleIndex
    }
}

/** Up to [radius] labels on each side of [index] in [labels]: the scale slice and the current index in it. */
internal fun scaleAround(labels: List<String>, index: Int, radius: Int = 3): Pair<List<String>, Int> {
    if (index !in labels.indices) return emptyList<String>() to -1
    val from = (index - radius).coerceAtLeast(0)
    val to = (index + radius).coerceAtMost(labels.lastIndex)
    return labels.subList(from, to + 1) to (index - from)
}

/** Translucent rounded backing for a viewfinder control; red outline while it is being scrubbed. */
internal fun Modifier.viewfinderChip(active: Boolean = false): Modifier {
    val shape = CapturePalette.shape()
    // Ornaments off: an inactive control is just its (shadowed) text; the active one keeps its outline.
    if (!CapturePalette.valueBackgrounds) {
        return if (active) this.border(1.dp, CaptureColors.Accent, shape) else this
    }
    return this
        .background(Color.Black.copy(alpha = .55f), shape)
        .border(1.dp, if (active) CaptureColors.Accent else CaptureColors.Outline, shape)
}

/**
 * Marks this control as scrubbed once the finger moves past touch slop, until it lifts. Observes the
 * Initial pass only, so the control's own drag handling is untouched.
 */
internal fun Modifier.observeScrub(tag: String): Modifier = pointerInput(tag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                moved = true
                ScrubReadout.activeTag = tag
            }
        }
        if (ScrubReadout.activeTag == tag) ScrubReadout.activeTag = null
    }
}

@Composable
internal fun ScrubReadoutOverlay(modifier: Modifier = Modifier) {
    // Keep the last value while fading out.
    var shownTitle by remember { mutableStateOf("") }
    var shownValue by remember { mutableStateOf("") }
    var shownScale by remember { mutableStateOf<List<String>>(emptyList()) }
    var shownIndex by remember { mutableStateOf(-1) }
    val visible = ScrubReadout.activeTag != null
    if (visible) {
        shownTitle = ScrubReadout.title
        shownValue = ScrubReadout.value
        shownScale = ScrubReadout.scale
        shownIndex = ScrubReadout.scaleIndex
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(220, delayMillis = 450)),
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(16.dp)
        val boxed = CapturePalette.valueBackgrounds
        Column(
            Modifier
                .widthIn(min = 150.dp)
                .then(
                    if (boxed) Modifier.background(Color.Black.copy(alpha = .62f), shape).border(1.dp, CaptureColors.Outline, shape)
                    else Modifier
                )
                .padding(horizontal = 28.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                shownTitle,
                color = Color.White.copy(alpha = .6f),
                fontFamily = CaptureMono,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                maxLines = 1
            )
            Text(
                shownValue,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                maxLines = 1
            )
            if (CapturePalette.dragScale && shownScale.size > 1 && shownIndex in shownScale.indices) {
                // Lens-ring style scale: neighbours in grey, the current value in red under an index tick.
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(width = 1.5.dp, height = 7.dp)
                        .background(CaptureColors.Accent)
                )
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    shownScale.forEachIndexed { i, label ->
                        Text(
                            label,
                            color = if (i == shownIndex) CaptureColors.Accent else Color.White.copy(alpha = .42f),
                            fontFamily = CaptureMono,
                            fontWeight = if (i == shownIndex) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

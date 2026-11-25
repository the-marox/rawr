package com.rawr.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Tune

/**
 * Rawr 17U fork: the LOOK button next to the shutter and the sheet it opens over the bottom of the viewfinder, so the
 * viewfinder itself stays clean (the LOOK and PARAMS chips were on it). The sheet is the film sheet while Film is on
 * (photo), else the look sheet (TonemapStrip.kt, [LookSheetPanel]): profiles as swipeable cards and the PARAMS
 * groups as tabs. The button sits in the capture controls and the sheet in the viewfinder strip, so the open state
 * and the button's label live here.
 */
internal object LookSheetState {
    var open by mutableStateOf(false)

    /** Name of the look (or film preset) in use, as the sheet host last saw it. */
    var label by mutableStateOf("RAWR NTRL")

    /** The look's parameters differ from its defaults (the dot on the button). */
    var modified by mutableStateOf(false)
}

/** Short form of a look name for the LOOK button: names over 8 characters keep their last word ("RAWR NTRL" → NTRL). */
internal fun lookButtonLabel(name: String): String {
    val trimmed = name.trim()
    if (trimmed.length <= 8) return trimmed
    return trimmed.substringAfterLast(' ').ifEmpty { trimmed }.take(10)
}

@Composable
internal fun LookSheetButton(modifier: Modifier = Modifier) {
    val open = LookSheetState.open
    val name = lookButtonLabel(LookSheetState.label)
    CompactSidePill(
        icon = Icons.Outlined.Tune,
        label = if (LookSheetState.modified) "$name •" else name,
        active = open,
        testTag = "capture_look_sheet_button",
        contentDescription = "Look ${LookSheetState.label}" +
            (if (LookSheetState.modified) ", changed" else "") +
            (if (open) ". Tap to close the look sheet." else ". Tap to choose the look and its parameters."),
        onClick = { LookSheetState.open = !open },
        modifier = modifier
    )
}

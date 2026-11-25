package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.settings.SettingsActivity
import com.rawr.camera.settings.model.SettingsSection
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

    /** Film is the render owner: a long press opens the film settings instead of the Look page. */
    var film by mutableStateOf(false)
}

/**
 * The look name on the button's two lines: the first word above, the rest below ("RAWR NTRL", "AgX Punchy",
 * "Kodak Double-X"); a one-word name is a single line. The button keeps its size: a long line ends in "…".
 */
internal fun lookButtonLines(name: String): Pair<String, String?> {
    val trimmed = name.trim().ifEmpty { "LOOK" }
    val space = trimmed.indexOf(' ')
    if (space < 0) return trimmed to null
    return trimmed.substring(0, space) to trimmed.substring(space + 1).trim()
}

@Composable
internal fun LookSheetButton(modifier: Modifier = Modifier) {
    val open = LookSheetState.open
    val context = LocalContext.current
    val haptics = LocalCaptureHaptics.current
    val (top, bottom) = lookButtonLines(LookSheetState.label)
    val contentColor = if (open) CaptureColors.OnAccent else Color.White.copy(alpha = .7f)
    // Same box as the multiframe and FILM buttons (CompactSidePill), at a fixed size so a long name never widens it.
    val shape = CapturePalette.shape()
    val boxed = open || CapturePalette.valueBackgrounds
    Box(
        modifier
            .size(52.dp)
            .then(
                if (!boxed) Modifier else Modifier
                    .background(if (open) CaptureColors.Accent else Color(0xFF141414), shape)
                    .border(1.dp, if (open) CaptureColors.Accent else Color(0xFF262626), shape)
            )
            .testTag("capture_look_sheet_button")
            .captureCombinedClickable(
                shape = CapturePillRippleShape,
                role = Role.Button,
                onLongClickLabel = "Look settings",
                onLongClick = {
                    haptics.longPress()
                    val section = if (LookSheetState.film) SettingsSection.FilmSim else SettingsSection.LookHub
                    context.startActivity(SettingsActivity.openIntent(context, section))
                }
            ) {
                haptics.selection()
                LookSheetState.open = !open
            }
            .semantics {
                contentDescription = "Look ${LookSheetState.label}" +
                    (if (LookSheetState.modified) ", changed" else "") +
                    (if (open) ". Tap to close the look sheet." else ". Tap to choose the look and its parameters.") +
                    " Long press for settings."
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            LookButtonLine(top, contentColor)
            if (bottom != null) LookButtonLine(bottom, contentColor)
        }
        if (LookSheetState.modified) {
            // The look's parameters were changed (as the dot on Ed's LOOK chip).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(5.dp)
                    .background(if (open) CaptureColors.OnAccent else CaptureColors.Accent, CircleShape)
            )
        }
    }
}

@Composable
private fun LookButtonLine(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontFamily = CaptureMono,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        lineHeight = 9.sp,
        letterSpacing = .3.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

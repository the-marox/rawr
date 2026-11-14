package com.rawr.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.Orientation

/** The control explanation currently shown over the viewfinder; [seq] restarts its timer. */
internal class ControlHelpState {
    var text by mutableStateOf<String?>(null)
        private set
    var seq by mutableIntStateOf(0)
        private set
    var actionLabel by mutableStateOf<String?>(null)
        private set
    var action: (() -> Unit)? = null
        private set

    fun show(value: String, label: String? = null, onAction: (() -> Unit)? = null) {
        text = value
        actionLabel = label?.takeIf { onAction != null }
        action = onAction
        seq++
    }

    fun dismiss() {
        text = null
    }

    fun runAction() {
        val run = action
        action = null
        actionLabel = null
        text = null
        run?.invoke()
    }
}

internal val LocalControlHelp = staticCompositionLocalOf<ControlHelpState?> { null }

private const val HELP_MIN_MS = 3_500L
private const val HELP_MS_PER_CHAR = 55L

/**
 * Centred, translucent card with a control's explanation ("Title: body"),
 * upright in landscape. Fades out on its own (longer texts stay longer) or on tap.
 */
@Composable
internal fun ControlHelpOverlay(state: ControlHelpState, orientation: Orientation, modifier: Modifier = Modifier) {
    val current = state.text
    // Keep the last text while the card fades out.
    var shown by remember { mutableStateOf("") }
    var shownAction by remember { mutableStateOf<String?>(null) }
    if (current != null) {
        shown = current
        shownAction = state.actionLabel
    }
    LaunchedEffect(state.seq) {
        val text = state.text ?: return@LaunchedEffect
        kotlinx.coroutines.delay(maxOf(HELP_MIN_MS, text.length * HELP_MS_PER_CHAR))
        state.dismiss()
    }
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = .96f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220), targetScale = .98f),
        modifier = modifier
    ) {
        val split = shown.indexOf(": ")
        val title = if (split in 1..32) shown.substring(0, split) else null
        val body = if (title != null) shown.substring(split + 2) else shown
        Column(
            Modifier
                .uprightInLandscape(orientation)
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    state.dismiss()
                }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    color = CaptureColors.AccentSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    style = ViewfinderTextStyle,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Text(
                body,
                color = Color.White.copy(alpha = .95f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Start,
                style = ViewfinderTextStyle
            )
            shownAction?.let { label ->
                Text(
                    label,
                    color = CaptureColors.AccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    style = ViewfinderTextStyle,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable { state.runAction() }
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                )
            }
        }
    }
}

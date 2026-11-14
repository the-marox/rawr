package com.rawr.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.rawr.camera.BuildConfig

/**
 * Debug and release-optimized benchmark builds publish every testTag as a `resource-id`, so adb scripts can
 * address controls by tag. Popup windows (dialogs, dropdowns) have their own root and are not covered.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun ExposeTestTags(content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "benchmark") {
        Box(Modifier.semantics { testTagsAsResourceId = true }) { content() }
    } else content()
}

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    CapturePalette.init(androidx.compose.ui.platform.LocalContext.current)
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = CaptureColors.Background,
                surface = CaptureColors.Surface,
                primary = CaptureColors.Accent,
                onPrimary = androidx.compose.ui.graphics.Color.White,
                primaryContainer = CaptureColors.Accent,
                onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
                secondary = CaptureColors.AccentSoft,
                onSecondary = androidx.compose.ui.graphics.Color.White,
                secondaryContainer = CaptureColors.Chip,
                onSecondaryContainer = androidx.compose.ui.graphics.Color.White,
                tertiary = CaptureColors.AccentSoft,
                onTertiary = androidx.compose.ui.graphics.Color.White,
                tertiaryContainer = CaptureColors.Chip,
                onTertiaryContainer = androidx.compose.ui.graphics.Color.White,
                surfaceVariant = CaptureColors.Chip,
                onSurfaceVariant = CaptureColors.TextSecondary,
                surfaceContainerLowest = CaptureColors.Background,
                surfaceContainerLow = CaptureColors.Panel,
                surfaceContainer =
                    androidx.compose.ui.graphics
                        .Color(0xFF161616),
                surfaceContainerHigh = CaptureColors.Chip,
                surfaceContainerHighest =
                    androidx.compose.ui.graphics
                        .Color(0xFF262626),
                outline =
                    androidx.compose.ui.graphics
                        .Color(0xFF5A5A5A),
                outlineVariant = CaptureColors.Outline,
                error = CaptureColors.Danger,
                onBackground =
                    androidx.compose.ui.graphics
                        .Color(0xFFF4F4F4),
                onSurface =
                    androidx.compose.ui.graphics
                        .Color(0xFFF4F4F4)
            ),
        typography =
            Typography(
                labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = CaptureMono),
                labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = CaptureMono),
                bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = CaptureMono)
            ),
        content = { ExposeTestTags(content) }
    )
}

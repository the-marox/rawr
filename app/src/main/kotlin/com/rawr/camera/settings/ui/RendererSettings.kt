package com.rawr.camera.settings.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rawr.camera.renderer.RendererActivity
import com.rawr.camera.renderer.RendererLauncherIcon

// Settings › Renderer: a way into the Renderer that doesn't need a launcher shortcut or the long press
// on the gallery thumbnail, and the same app drawer icon switch as the Renderer's own menu.
@Composable
internal fun RendererSettings() {
    val context = LocalContext.current
    var drawerIcon by remember { mutableStateOf(RendererLauncherIcon.isShown(context)) }
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("Renderer")) {
        SettingsGroup(description = "Develops DNGs: Rawr's own and DNGs from other apps.") {
            SettingsRow(
                "Open Renderer",
                testTag = SettingsTestTags.row("Renderer", "open"),
                onClick = { context.startActivity(Intent(context, RendererActivity::class.java)) }
            )
            SettingDivider()
            SettingsSwitchRow(
                title = "Icon in app drawer",
                checked = drawerIcon,
                supportingText = "Adds a Rawr Renderer icon to the launcher. Some launchers take a moment to show it.",
                testTag = SettingsTestTags.row("Renderer", "drawer_icon"),
                onCheckedChange = {
                    RendererLauncherIcon.setShown(context, it)
                    drawerIcon = it
                }
            )
        }
    }
}

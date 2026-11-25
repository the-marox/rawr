package com.rawr.camera.settings.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.rawr.camera.i18n.I18n
import com.rawr.camera.i18n.tr
import com.rawr.camera.storage.PickFile
import com.rawr.camera.storage.launchPicker

// Rawr 17U fork: UI language. Follows the system language when a bundled translation exists; a language file
// (JSON, English text as keys) can be exported, corrected and imported back.
@Composable
internal fun LanguageSettings() {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(PickFile()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read the file")
                I18n.importFile(context, text)
            }.onSuccess { count ->
                Toast.makeText(context, "${tr("Language file imported")}: $count", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "${tr("Unable to import language file")}: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Unable to write the file")
                stream.bufferedWriter().use { it.write(I18n.exportJson(context)) }
            }.onSuccess {
                Toast.makeText(context, tr("Language file exported"), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "${tr("Unable to export language file")}: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    SettingsGroup(
        title = "Language",
        description = "Base translations are machine-made. Export the language file, fix or fill the texts and import it back; empty texts stay in English."
    ) {
        DropdownChoiceRow(
            title = "Language",
            selected = I18n.language,
            values = I18n.choices(context),
            label = { I18n.displayName(context, it) },
            onSelect = { I18n.select(context, it) }
        )
        SettingDivider()
        SettingsRow(
            title = "Import language file",
            value = "A corrected or new translation (JSON)",
            onClick = { importer.launchPicker(context, arrayOf("application/json", "text/plain", "application/octet-stream")) }
        )
        SettingDivider()
        SettingsRow(
            title = "Export language file",
            value = "Every text with the current translation",
            onClick = { exporter.launch(I18n.exportFileName()) }
        )
    }
}

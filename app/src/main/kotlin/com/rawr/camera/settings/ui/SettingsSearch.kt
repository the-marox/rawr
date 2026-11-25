package com.rawr.camera.settings.ui

import com.rawr.camera.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rawr.camera.settings.architecture.OpenSection
import com.rawr.camera.settings.architecture.SettingsDispatch
import com.rawr.camera.settings.model.SettingsSection
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.rounded.Close
import com.rawr.camera.ui.icons.rounded.Search
import java.util.Locale

// Rawr 17U fork: settings search. The index (SettingsSearchIndexData) is generated at build time from the settings
// sources by the generateSettingsSearchIndex task in app/build.gradle.kts, so options Ed adds are found with no
// change here. A result opens its page and highlights the matching row (SettingsRow / SettingsGroup).

internal data class SettingsSearchEntry(
    val title: String,
    val section: SettingsSection,
    /** Pages above the one holding the option, e.g. "RAW development › Denoise". */
    val path: String,
    /** True when the entry is the page itself rather than an option on it. */
    val isPage: Boolean
)

internal val settingsSearchEntries: List<SettingsSearchEntry> by lazy {
    SettingsSearchIndexData.ENTRIES.mapNotNull { row ->
        val section = SettingsSection.entries.firstOrNull { it.name == row[1] } ?: return@mapNotNull null
        SettingsSearchEntry(title = row[0], section = section, path = row[2], isPage = row[0] == section.title)
    }
}

/**
 * Entries whose title and page path hold every word of [query] ("denoise strength" finds Strength on the Denoise
 * page). Matches in the title come first, then titles that start with the query, then pages before options.
 */
internal fun searchSettings(query: String, entries: List<SettingsSearchEntry> = settingsSearchEntries): List<SettingsSearchEntry> {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val first = words.first()
    return entries
        // The translated title and path match too, so a search works in the chosen language.
        .filter { entry -> entry.searchText().let { text -> words.all { it in text } } }
        .sortedWith(
            compareBy<SettingsSearchEntry>(
                { entry -> entry.titleText().let { title -> if (words.all { it in title }) 0 else 1 } },
                { entry -> entry.titleText().let { title -> if (title.startsWith(first) || tr(entry.title).lowercase(Locale.ROOT).startsWith(first)) 0 else 1 } },
                { if (it.isPage) 0 else 1 },
                { it.title.length }
            )
        )
        .distinctBy { it.title.lowercase(Locale.ROOT) to it.section }
        .take(60)
}

/** The row a search result asked for; rows with this title tint and scroll into view, then it clears. */
private fun SettingsSearchEntry.titleText(): String =
    (if (tr(title) == title) title else "$title ${tr(title)}").lowercase(Locale.ROOT)

private fun SettingsSearchEntry.searchText(): String =
    "${titleText()} $path ${if (tr(path) == path) "" else tr(path)}".lowercase(Locale.ROOT)

internal class SettingsHighlight {
    var title by mutableStateOf<String?>(null)
}

internal val LocalSettingsHighlight = staticCompositionLocalOf<SettingsHighlight?> { null }

@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(it.replace("\n", "")) },
        placeholder = { Text(tr("Search settings")) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.row("Home", "search"))
    )
}

@Composable
internal fun SettingsSearchResults(query: String, dispatch: SettingsDispatch, hasChosenLut: Boolean = true) {
    val highlight = LocalSettingsHighlight.current
    val results = searchSettings(query)
    Column {
        Text(
            if (results.isEmpty()) tr("No results").uppercase() else "${results.size} ${tr(if (results.size == 1) "Result" else "Results")}".uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsRowHorizontalPadding, bottom = 4.dp)
        )
        if (results.isNotEmpty()) {
            SettingsGroup {
                results.forEachIndexed { index, entry ->
                    SettingsRow(
                        title = entry.title,
                        value = entry.path.ifEmpty { "Settings" },
                        testTag = SettingsTestTags.row("Search", "result_$index"),
                        onClick = {
                            // The LUT Profile page edits the LUT chosen in Tone & Color; with none chosen, open that.
                            val toLuts = entry.section == SettingsSection.LutProfile && !hasChosenLut
                            highlight?.title = if (entry.isPage || toLuts) null else entry.title
                            dispatch.invoke(OpenSection(if (toLuts) SettingsSection.ImageTone else entry.section))
                        }
                    )
                    if (index != results.lastIndex) SettingDivider()
                }
            }
        }
    }
}

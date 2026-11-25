package com.rawr.camera.settings.ui

import androidx.compose.runtime.Composable
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

// Rawr 17U fork: the hub pages of the grouped settings home. They only link to Ed's pages (and reuse Ed's
// capture-sharpening rows), so options Ed adds to those pages show up here with no change. The home keeps every
// page reachable; SettingsReachabilityTest checks it.

@Composable
internal fun PhotoHubSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("PhotoHub")) {
        SettingsGroup {
            HubRow("Capture", "Lenses, multiframe, auto exposure, OIS, location", SettingsSection.Capture, dispatch)
            SettingDivider()
            HubRow("DNG", "RAW files and compression", SettingsSection.Dng, dispatch)
            SettingDivider()
            HubRow("JPEG", "Ultra HDR, colour space, chroma", SettingsSection.Jpeg, dispatch)
            SettingDivider()
            HubRow("Storage", "Where photos and videos are saved", SettingsSection.Storage, dispatch)
        }
    }
}

@Composable
internal fun LookHubSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val v = state.values
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("LookHub")) {
        SettingsGroup {
            HubRow("Tone & Color", "Tone mapping, look and tone controls", SettingsSection.ImageTone, dispatch)
            SettingDivider()
            ToggleSubmenuRow(
                title = "Film Simulation",
                checked = v.filmSimEnabled,
                testTag = SettingsTestTags.row("LookHub", "film_simulation"),
                onOpen = { dispatch.invoke(OpenSection(SettingsSection.FilmSim)) },
                onCheckedChange = { dispatch.invoke(SetFilmSimEnabled(it)) }
            )
            SettingDivider()
            HubRow("Dynamic Range", dynamicRangeSummary(v), SettingsSection.DynamicRange, dispatch)
            SettingDivider()
            // The LUT Profile page edits the LUT chosen in Tone & Color (Ed's flow); with none chosen it is empty, so
            // the row opens Tone & Color, where LUTs are imported and chosen.
            val lut = v.selectedLutProfile()
            HubRow(
                "LUT Profile",
                lut?.name ?: if (v.userLutProfiles.isEmpty()) "None imported · import in Tone & Color" else "None chosen · choose in Tone & Color",
                if (lut != null) SettingsSection.LutProfile else SettingsSection.ImageTone,
                dispatch
            )
        }
    }
}

@Composable
internal fun RawHubSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val v = state.values
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("RawHub")) {
        SettingsGroup(title = "Detail") {
            HubRow(
                "Demosaic",
                "Photo FCC ${v.photoFccSteps} · Video FCC ${if (v.videoFccEnabled) "on" else "off"}",
                SettingsSection.Demosaic,
                dispatch
            )
            SettingDivider()
            HubRow(
                "Denoise",
                photoVideoSummary(v.photoDenoise is DenoiseConfig.Wavelet, v.videoDenoiseEnabled),
                SettingsSection.Denoise,
                dispatch
            )
            SettingDivider()
            CaptureSharpeningRows(state, dispatch)
            SettingDivider()
            HubRow(
                "Defringe",
                photoVideoSummary(v.photoDefringeEnabled, v.videoDefringeEnabled),
                SettingsSection.Defringe,
                dispatch
            )
        }
        SettingsGroup(title = "Optics") {
            HubRow(
                "Lens shading",
                photoVideoSummary(v.photoLensShadingEnabled, v.videoLensShadingEnabled),
                SettingsSection.LensShading,
                dispatch
            )
            SettingDivider()
            HubRow(
                "Distortion correction",
                photoVideoSummary(v.distortionCorrectionEnabled, v.videoDistortionCorrectionEnabled),
                SettingsSection.Distortion,
                dispatch
            )
        }
        SettingsGroup(title = "Highlights") {
            HubRow(
                "Highlight reconstruction",
                photoVideoSummary(v.photoHighlightEnabled, v.videoHighlightEnabled),
                SettingsSection.HighlightReconstruction,
                dispatch
            )
        }
    }
}

@Composable
internal fun SystemHubSettings(dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("SystemHub")) {
        SettingsGroup {
            HubRow("Renderer", "Develop DNGs from Rawr and other apps", SettingsSection.Renderer, dispatch)
            SettingDivider()
            HubRow("Experimental", "GPU driver, zero copy, persistent engine", SettingsSection.Experimental, dispatch)
            SettingDivider()
            HubRow("Debug", "Report a problem, diagnostics", SettingsSection.Debug, dispatch)
            SettingDivider()
            HubRow("About", "Version, licences, backup and restore", SettingsSection.About, dispatch)
        }
    }
}

@Composable
private fun HubRow(title: String, summary: String, section: SettingsSection, dispatch: SettingsDispatch) {
    SettingsRow(
        title = title,
        value = summary,
        testTag = SettingsTestTags.row("Hub", section.name),
        onClick = { dispatch.invoke(OpenSection(section)) }
    )
}

package com.rawr.camera.model

import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.activeLensHasOwnLook
import com.rawr.camera.settings.model.activeLensHasOwnMultiframeTuning
import com.rawr.camera.settings.model.activeLensMergeFrameLimit
import com.rawr.camera.settings.model.activeMultiframeTuning

/** Highlight reconstruction as the viewfinder rail presents it: off, or one of the two methods. */
enum class HighlightQuickMode { Off, Propagation, Opposed }

/** Photo or video highlight reconstruction, whichever the current capture mode renders with. */
data class HighlightQuickState(
    val mode: HighlightQuickMode = HighlightQuickMode.Off,
    val threshold: Float = HIGHLIGHT_THRESHOLD_DEFAULT,
    val compression: Float = PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT,
    val compressionDefault: Float = PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT
)

data class DcpQuickState(
    val lensId: String,
    val profiles: List<com.rawr.camera.settings.model.LensDcp>,
    val selectedFile: String?
)

/** Capture-screen projection of the settings the compact viewfinder rail edits. */
data class ViewfinderQuickState(
    val rawrAeEnabled: Boolean = false,
    val metering: RawrMetering = RawrMetering.Matrix,
    val highlight: HighlightQuickState = HighlightQuickState(),
    val dcps: List<DcpQuickState> = emptyList(),
    /** Recording locks the rail like it locks the render profile. */
    val locked: Boolean = false,
    // Quick panel, second row (photo only): frames of the lens in use and its per-lens switches.
    val isVideo: Boolean = false,
    val lensId: String? = null,
    val multiframeEnabled: Boolean = false,
    val frames: Int = 17,
    /** Profile cap for the lens in use (0 = none). */
    val frameLimit: Int = 0,
    val ownMultiframe: Boolean = false,
    val ownLook: Boolean = false
)

internal const val HIGHLIGHT_THRESHOLD_DEFAULT = 1f
internal const val PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT = 45f
internal const val VIDEO_HIGHLIGHT_COMPRESSION_DEFAULT = 100f

internal fun SettingsValues.toViewfinderQuickState(
    deviceDefaults: List<com.rawr.camera.settings.model.LensProfile> = emptyList()
): ViewfinderQuickState {
    val enabled = if (isVideo) videoHighlightEnabled else photoHighlightEnabled
    val method = if (isVideo) videoHighlightMethod else photoHighlightMethod
    val compressionDefault =
        if (isVideo) VIDEO_HIGHLIGHT_COMPRESSION_DEFAULT else PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT
    return ViewfinderQuickState(
        dcps = if (!showDcpQuickSelect) emptyList() else (lensProfiles ?: deviceDefaults).map { DcpQuickState(it.name, it.image.availableDcps, it.image.dcp?.file) },
        rawrAeEnabled = rawrAe.enabled,
        metering = rawrAe.metering,
        highlight = HighlightQuickState(
            mode = when {
                !enabled -> HighlightQuickMode.Off
                method == 1 -> HighlightQuickMode.Opposed
                else -> HighlightQuickMode.Propagation
            },
            threshold = if (isVideo) videoHighlightThreshold else photoHighlightThreshold,
            compression = if (isVideo) videoHighlightCompression else photoHighlightCompression,
            compressionDefault = compressionDefault
        ),
        isVideo = isVideo,
        lensId = lastLensId,
        multiframeEnabled = experimentalMultiframeEnabled,
        frames = activeMultiframeTuning.maxFrames,
        frameLimit = activeLensMergeFrameLimit(deviceDefaults),
        ownMultiframe = activeLensHasOwnMultiframeTuning,
        ownLook = activeLensHasOwnLook
    )
}

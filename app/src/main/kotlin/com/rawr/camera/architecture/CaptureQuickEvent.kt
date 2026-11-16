package com.rawr.camera.architecture

import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.settings.model.CaptureSharpeningLevel
import com.rawr.camera.settings.model.RawrMaxShutter
import com.rawr.camera.settings.model.RawrMetering

/**
 * Typed commands for the compact viewfinder rail. Highlight events edit the
 * photo or video set, whichever the current capture mode renders with.
 */
sealed interface CaptureQuickEvent {
    data class SelectDcp(val lensId: String, val file: String?) : CaptureQuickEvent
    data object ToggleRawrAe : CaptureQuickEvent
    data class SetMetering(val metering: RawrMetering) : CaptureQuickEvent
    data class SetHighlightMode(val mode: HighlightQuickMode) : CaptureQuickEvent
    data class SetHighlightThreshold(val value: Float) : CaptureQuickEvent
    data class SetHighlightCompression(val value: Float) : CaptureQuickEvent
    /** Frames for the lens in use (its own multiframe tuning when it has one, else the shared one). */
    data class SetMultiframeFrames(val frames: Int) : CaptureQuickEvent
    data object ToggleOwnMultiframe : CaptureQuickEvent
    data object ToggleOwnLook : CaptureQuickEvent
    data class SetMaxShutter(val value: RawrMaxShutter) : CaptureQuickEvent
    /** Photo sharpening: off, or on at [level]. */
    data class SetSharpening(val enabled: Boolean, val level: CaptureSharpeningLevel) : CaptureQuickEvent
    /** Denoise master switch of the current capture mode. */
    data object ToggleDenoise : CaptureQuickEvent
    /** Exposure mode, ISO, shutter, EV, WB and focus back to automatic: the open camera, or every camera. */
    data class ResetCameraControls(val allCameras: Boolean) : CaptureQuickEvent
    data object UndoCameraControlsReset : CaptureQuickEvent
    data class SetVfControlVisible(val control: com.rawr.camera.settings.model.VfControl, val visible: Boolean) :
        CaptureQuickEvent
    data object ShowAllVfControls : CaptureQuickEvent
    /** Lens bar layout, edited from the eye panel so the bar itself is visible while choosing. */
    data class SetLensBarGrouping(val value: com.rawr.camera.settings.model.LensBarGrouping) :
        CaptureQuickEvent
    data class SetLensBarSize(val value: com.rawr.camera.settings.model.LensBarSize) : CaptureQuickEvent
}

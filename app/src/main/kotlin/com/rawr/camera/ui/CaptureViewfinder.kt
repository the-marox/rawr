package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawr.camera.architecture.ActivateSpotAe
import com.rawr.camera.architecture.CancelSelfTimer
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CloseFocusSelector
import com.rawr.camera.architecture.FocusAt
import com.rawr.camera.architecture.ToggleMonitorPanel
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.model.*

@Composable
internal fun CaptureViewfinder(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier,
    /**
     * Top-centered frame for controls and modal palettes (the photo 3:4 frame).
     * Keeps their geometry identical across modes and video resolutions when
     * the viewfinder box itself is taller. Null = this box.
     */
    slotFrame: DpSize? = null,
    /** Video mode hides the film strip (tonemap strip stays); film engine itself is untouched. */
    forceTonemapStrip: Boolean = false,
    /** Forces the compact strip content (used by the video overlay regardless of layout). */
    forceCompact: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {}
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        modifier
            .background(Color(0xFF1E1E1E))
            .testTag(CaptureTestTags.VIEWFINDER)
            .semantics { contentDescription = "Camera viewfinder" },
        contentAlignment = Alignment.Center
    ) {
        preview.content(Modifier.matchParentSize())
        if (!preview.nativeContent) {
            MonitoringOverlay(state.armedOverlays, state.falseColorManual, Modifier.matchParentSize())
        }
        GridOverlay(state.grid, Modifier.matchParentSize())

        var viewfinderSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // Focus selector swaps the bottom slot to focus controls instead of
        // overlaying them. Monitor/render/white-balance palettes remain modal.
        val focusOnly =
            state.focus.selectorOpen && !state.monitorPanelOpen &&
                !state.whiteBalancePanelOpen
        val modalOpen = state.monitorPanelOpen || state.whiteBalancePanelOpen
        // The rail shares the right edge with the compact scopes; keep it below them.
        val density = LocalDensity.current
        val railTopLimit = with(density) {
            val bottom = rightScopeBottom(
                viewfinderSize.width.toDp(), viewfinderSize.height.toDp(),
                state.orientation == Orientation.Landscape, state.activeScopes.size,
                preview.deviceRotationDegrees
            )
            if (bottom > 0.dp) bottom + 6.dp else 0.dp
        }
        val popupOpen = focusOnly || modalOpen
        var quickPanelOpen by remember { mutableStateOf(false) }
        val swipePx = with(LocalDensity.current) { 48.dp.toPx() }
        Box(
            Modifier
                .matchParentSize()
                .onSizeChanged { viewfinderSize = it }
                .then(
                    if (!popupOpen) {
                        Modifier.pointerInput(swipePx) {
                            // Swipe down opens the quick panel (MTR / AE / HL), swipe up closes it.
                            var travel = 0f
                            detectVerticalDragGestures(
                                onDragStart = { travel = 0f },
                                onVerticalDrag = { change, dy ->
                                    travel += dy
                                    if (travel > swipePx && !quickPanelOpen) {
                                        quickPanelOpen = true
                                        haptics.selection()
                                    } else if (travel < -swipePx && quickPanelOpen) {
                                        quickPanelOpen = false
                                        haptics.selection()
                                    }
                                    change.consume()
                                }
                            )
                        }.pointerInput(state.orientation, state.exposureControl.mode) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (quickPanelOpen) {
                                        quickPanelOpen = false  // a tap outside the panel only closes it
                                        return@detectTapGestures
                                    }
                                    if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                        haptics.selection()
                                        dispatch(FocusAt(offset.toNormalized(viewfinderSize)))
                                    }
                                },
                                onLongPress = { offset ->
                                    if (state.exposureControl.mode == ExposureMode.Auto &&
                                        viewfinderSize.width > 0 && viewfinderSize.height > 0
                                    ) {
                                        haptics.longPress()
                                        dispatch(ActivateSpotAe(offset.toNormalized(viewfinderSize)))
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        )

        ScopeLayer(state, dispatch, preview)
        Box(Modifier.matchParentSize().then(if (popupOpen) Modifier else Modifier.zIndex(2f))) {
            if (preview.nativeContent) preview.faceOverlay() else FaceBoxes(state.faceDetections)
            AfTarget(state, dispatch)
            SpotAeTarget(state, dispatch)
        }

        // Wrapper stays a direct child so the focus-only zIndex still lifts the
        // slot above the z20 dismiss scrim, and modal palettes still cover it.
        val controlFrameModifier = Modifier
            .align(Alignment.TopCenter)
            .then(if (slotFrame != null) Modifier.size(slotFrame) else Modifier.matchParentSize())
        Box(
            controlFrameModifier
                .then(if (focusOnly) Modifier.zIndex(21f) else Modifier)
        ) {
            ViewfinderBottomSlot(
                state = state,
                dispatch = dispatch,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = CaptureDimens.ViewfinderHorizontalPadding,
                        end = CaptureDimens.ViewfinderHorizontalPadding,
                        bottom = CaptureDimens.ViewfinderBottomPadding
                    ),
                forceCompact = forceCompact,
                forceTonemapStrip = forceTonemapStrip,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit
            )
        }

        if (popupOpen) {
            // Focus bottom controls sit above the scrim (z21 vs z20) so they stay
            // interactive; taps on the image area still dismiss. Modal palettes
            // keep full coverage.
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(20f)
                    .pointerInput(
                        state.focus.selectorOpen,
                        state.monitorPanelOpen,
                        state.whiteBalancePanelOpen
                    ) {
                        detectTapGestures(
                            onTap = {
                                if (state.focus.selectorOpen) dispatch(CloseFocusSelector)
                                if (state.monitorPanelOpen) dispatch(ToggleMonitorPanel)
                                if (state.whiteBalancePanelOpen) dispatch(ToggleWhiteBalancePanel)
                            }
                        )
                    }
            )
        }
        if (modalOpen) {
            // Palettes share the photo control frame, even when the video preview
            // bleeds below it. Keep the wrapper above the full-preview dismiss scrim.
            Box(controlFrameModifier.zIndex(21f)) {
                val paletteModifier = Modifier.topBarPaletteModifier(state, this)
                if (state.monitorPanelOpen) {
                    Column(paletteModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonitorPanel(state, dispatch, Modifier)
                        if (quick.eyeControls) {
                            VfControlsPanel(quick, state.orientation, onQuickEvent)
                        }
                    }
                }
                if (state.whiteBalancePanelOpen) {
                    WhiteBalancePanel(state, dispatch, paletteModifier)
                }
            }
        }
        if (state.captureFlash) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .12f)))
        Box(controlFrameModifier.zIndex(3f)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = quickPanelOpen && !popupOpen,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 3 },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 3 },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp)
            ) {
                ViewfinderQuickPanel(quick, state.spotAe.active, onQuickEvent, Modifier.uprightInLandscape(state.orientation))
            }
            if (state.capabilities.lensUnavailable) {
                LensUnavailableNotice(state.orientation, Modifier.align(Alignment.Center))
            } else if (state.capabilities.profileError.isNotEmpty()) {
                ProfileErrorNotice(state.capabilities.profileError, state.orientation, Modifier.align(Alignment.Center))
            }
            // One status pill for every capture wait, in one place: moving it
            // while it fades out left an empty pill behind.
            ScrubReadoutOverlay(Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
            CaptureStatusNotice(
                longExposure = state.longExposure,
                holdActive = state.bracketHoldActive,
                restoringPreview = state.restoringPreview,
                stacked = state.experimentalMultiframeEnabled && state.captureMode == CaptureMode.Photo,
                orientation = state.orientation,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                fullSizeBinnedStills = state.fullSizeBinnedStills,
                fullSizeBurstsToSingle = state.fullSizeBurstsToSingle,
                multiframeHint =
                    if (state.experimentalMultiframeEnabled && state.captureMode == CaptureMode.Photo)
                        multiframeHintText(state.multiframeAlgorithm, state.multiframeFrames)
                    else null,
                multiframeHintKey = listOf(state.selectedLensId, state.experimentalMultiframeEnabled,
                    state.multiframeAlgorithm, state.multiframeFrames)
            )
            LocalControlHelp.current?.let { help ->
                ControlHelpOverlay(help, state.orientation, Modifier.align(Alignment.Center))
            }
        }
        state.selfTimerRemainingMs?.let { remaining ->
            SelfTimerCountdownOverlay(
                remainingMs = remaining,
                orientation = state.orientation,
                onCancel = {
                    haptics.selection()
                    dispatch(CancelSelfTimer)
                },
                modifier = Modifier.matchParentSize().zIndex(3f)
            )
        }
    }
}

/**
 * Viewfinder bottom slot: focus controls, or the compact exposure/tonemap
 * strip, or the classic quick controls. Anchored to the bottom of the photo
 * 3:4 frame inside the viewfinder box, so its position is identical across
 * capture modes and video resolutions and switching never shifts
 * exposure/tonemap geometry.
 */
@Composable
internal fun ViewfinderBottomSlot(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier = Modifier,
    forceCompact: Boolean = false,
    forceTonemapStrip: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    railTopLimit: Dp = 0.dp
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)
    ) {
        if (state.focus.selectorOpen) {
            // Long-press on the focus area swaps the bottom slot: exposure
            // rails + AMSI/SHD-HL are hidden and replaced by focus mode
            // buttons + MF rail. Same slot, same heights, no overlay.
            FocusBottomControls(state, dispatch, Modifier.fillMaxWidth())
        } else if (forceCompact || state.captureLayout == CaptureControlLayout.Compact) {
            // V2: bare WB/SS/ISO/EV strip floats over the preview bottom
            // (scrimmed for legibility). Focus selector still swaps in
            // FocusBottomControls; scopes/overlays/targets are untouched.
            CompactViewfinderStrip(
                state,
                dispatch,
                Modifier.fillMaxWidth(),
                forceTonemapStrip = forceTonemapStrip,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit
            )
        } else {
            QuickControlsRow(state, dispatch)
            ExposureArea(state, dispatch)
        }
    }
}

private fun Modifier.topBarPaletteModifier(state: CaptureUiState, scope: BoxScope): Modifier = with(scope) { when {
    // Without scopes the palette hangs from the top of the frame (it used to float mid-frame).
    state.activeScopes.isEmpty() -> {
        this@topBarPaletteModifier.align(Alignment.TopCenter).padding(top = 12.dp)
    }

    state.orientation == Orientation.Portrait -> {
        this@topBarPaletteModifier
            .align(Alignment.BottomStart)
            .padding(bottom = CaptureDimens.TopBarPalettePortraitBottomPadding)
    }

    else -> {
        this@topBarPaletteModifier
            .align(Alignment.CenterStart)
            .offset(
                x = CaptureDimens.TopBarPaletteLandscapeStartOffset,
                y = CaptureDimens.TopBarPaletteLandscapeCrossOffset
            )
    }
}

}

private fun Offset.toNormalized(size: androidx.compose.ui.unit.IntSize) = NormalizedPoint(
    x = x / size.width,
    y = y / size.height
)

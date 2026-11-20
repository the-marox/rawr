package com.rawr.camera.settings.architecture

import com.rawr.camera.settings.model.*

/** Edits owned by the multiframe settings feature. */
internal fun reduceMultiframeSettings(
    state: SettingsUiState, action: SettingsApplicationAction
): SettingsUiState = when (action) {
    is SetMultiframeChromaDenoise -> {
        state.withValues(state.values.copy(multiframeChromaDenoise = action.enabled))
    }

    is SetMultiframeRextureNativeRgb -> {
        state.withValues(state.values.copy(multiframeRextureNativeRgb = action.enabled))
    }
    is SetMultiframeRextureDetailRestore -> {
        state.withValues(state.values.copy(multiframeRextureDetailRestore = action.enabled))
    }

    is SetMultiframeBaseFrameMode -> {
        state.withValues(state.values.copy(multiframeBaseFrameMode = action.value))
    }

    is SetMultiframeNumericValue -> {
        val spec = MultiframeSpecs.forParameter(action.parameter)
        if (!spec.accepts(action.value)) {
            state
        } else {
            state.withValues(state.values.editActiveMultiframeTuning { it.withValue(action.parameter, action.value) })
        }
    }

    is SetMultiframeMergeAlgorithm -> {
        state.withValues(state.values.editActiveMultiframeTuning { it.copy(mergeAlgorithm = action.value) })
    }
    is SetMultiframeLensOverride -> {
        val values = state.values
        val lens = values.lastLensId
        when {
            lens == null -> state
            action.enabled -> state.withValues(values.copy(
                multiframeTuningByLens = values.multiframeTuningByLens + (lens to values.multiframeTuningFor(lens))))
            else -> state.withValues(values.copy(multiframeTuningByLens = values.multiframeTuningByLens - lens))
        }
    }

    else -> error("Unsupported multiframe settings action: $action")
}

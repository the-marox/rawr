package com.rawr.camera.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** JNI side of the gyro video stabilisation (app/src/main/cpp/geometry/Stabilizer.h). */
internal object NativeStabilizer {
    init {
        System.loadLibrary("rawrcam_native")
    }

    external fun setEnabled(enabled: Boolean)
    /** Horizon levelling: the recorder zooms 1.1x and turns by the tilt from level before shifting. */
    external fun setLevelHorizon(enabled: Boolean)
    /** The shake (corrected) and the camera's integrated turn (raw, for the RawrStab trace), radians. */
    external fun push(timeNs: Long, pitch: Float, yaw: Float, roll: Float, rawPitch: Float, rawYaw: Float, rawRoll: Float)
    /** Room of the current recording as angles {x, y, roll} in radians; zeros when there is none. */
    external fun room(): FloatArray
}

/**
 * True while the gyro stabilisation runs (video mode, Stabilisation on). The lens OIS is turned off meanwhile
 * (integration/CaptureSettingsSync): the gyro sees the hand's whole turn, but with OIS on the lens has already taken
 * out most of the tremor, so correcting it again doubled it (17U test, Nov 2026). The saved OIS preference is kept.
 */
object StabilizerState {
    private val activeFlow = MutableStateFlow(false)
    val active: StateFlow<Boolean> = activeFlow
    internal fun setActive(on: Boolean) { activeFlow.value = on }
}

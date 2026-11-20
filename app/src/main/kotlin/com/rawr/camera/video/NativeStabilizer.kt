package com.rawr.camera.video

/** JNI side of the gyro video stabilisation (app/src/main/cpp/geometry/Stabilizer.h). */
internal object NativeStabilizer {
    init {
        System.loadLibrary("rawrcam_native")
    }

    external fun setEnabled(enabled: Boolean)
    external fun push(timeNs: Long, pitch: Float, yaw: Float, roll: Float)
    /** Shift room of the current recording as angles {x, y} in radians; zeros when there is none. */
    external fun room(): FloatArray
}

#include <jni.h>

#include <cmath>

#include "geometry/Stabilizer.h"

// Gyro video stabilisation (geometry/Stabilizer.h), driven by video/StabilizerSensor.kt.
extern "C" JNIEXPORT void JNICALL Java_com_rawr_camera_video_NativeStabilizer_setEnabled(JNIEnv*, jobject,
                                                                                       jboolean enabled) {
    const bool on = enabled == JNI_TRUE;
    if (rawrcam::geometry::stabilizerEnabled().exchange(on, std::memory_order_relaxed) != on)
        rawrcam::geometry::stabilizerTrack().clear();
}

extern "C" JNIEXPORT void JNICALL Java_com_rawr_camera_video_NativeStabilizer_push(JNIEnv*, jobject, jlong timeNs,
                                                                                 jfloat pitch, jfloat yaw,
                                                                                 jfloat roll) {
    if (!std::isfinite(pitch) || !std::isfinite(yaw) || !std::isfinite(roll)) return;
    rawrcam::geometry::stabilizerTrack().push({int64_t(timeNs), float(pitch), float(yaw), float(roll)});
}

// Shift room of the current recording as angles {x, y} (radians); zeros when there is none.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_rawr_camera_video_NativeStabilizer_room(JNIEnv* env, jobject) {
    const jfloat values[2] = {rawrcam::geometry::stabilizerRoomX().load(std::memory_order_relaxed),
                              rawrcam::geometry::stabilizerRoomY().load(std::memory_order_relaxed)};
    jfloatArray out = env->NewFloatArray(2);
    if (out) env->SetFloatArrayRegion(out, 0, 2, values);
    return out;
}

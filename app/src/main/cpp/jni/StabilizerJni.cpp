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

extern "C" JNIEXPORT void JNICALL Java_com_rawr_camera_video_NativeStabilizer_setLevelHorizon(JNIEnv*, jobject,
                                                                                            jboolean enabled) {
    rawrcam::geometry::stabilizerLevelHorizon().store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL Java_com_rawr_camera_video_NativeStabilizer_push(JNIEnv*, jobject, jlong timeNs,
                                                                                 jfloat pitch, jfloat yaw,
                                                                                 jfloat roll, jfloat rawPitch,
                                                                                 jfloat rawYaw, jfloat rawRoll) {
    if (!std::isfinite(pitch) || !std::isfinite(yaw) || !std::isfinite(roll) || !std::isfinite(rawPitch) ||
        !std::isfinite(rawYaw) || !std::isfinite(rawRoll))
        return;
    rawrcam::geometry::stabilizerTrack().push({int64_t(timeNs), float(pitch), float(yaw), float(roll), float(rawPitch),
                                               float(rawYaw), float(rawRoll)});
}

// Room of the current recording as angles {x, y, roll} (radians); zeros when there is none.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_rawr_camera_video_NativeStabilizer_room(JNIEnv* env, jobject) {
    const jfloat values[3] = {rawrcam::geometry::stabilizerRoomX().load(std::memory_order_relaxed),
                              rawrcam::geometry::stabilizerRoomY().load(std::memory_order_relaxed),
                              rawrcam::geometry::stabilizerRoomRoll().load(std::memory_order_relaxed)};
    jfloatArray out = env->NewFloatArray(3);
    if (out) env->SetFloatArrayRegion(out, 0, 3, values);
    return out;
}

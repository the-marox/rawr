#pragma once

#include <jni.h>

#include <cstdint>
#include <optional>

namespace rawrcam::jni {

struct CaptureGps {
    double latitude;
    double longitude;
    std::optional<double> altitude;
    std::optional<double> accuracy;
    std::int64_t fixTimeMillis;
    int providerCode;
    // Compass direction of the rear lens axis, degrees; true north when imageDirectionTrue, else magnetic.
    std::optional<double> imageDirectionDeg;
    bool imageDirectionTrue = false;
};

inline std::optional<CaptureGps> readCaptureGps(JNIEnv* env, jobject value) {
    if (!value || env->ExceptionCheck()) return std::nullopt;
    const jclass cls = env->GetObjectClass(value);
    if (!cls) return std::nullopt;
    jfieldID latitude, longitude, altitude, accuracy, fixTimeMillis, providerCode;
    const bool found =
        (latitude = env->GetFieldID(cls, "latitude", "D")) &&
        (longitude = env->GetFieldID(cls, "longitude", "D")) &&
        (altitude = env->GetFieldID(cls, "altitude", "Ljava/lang/Double;")) &&
        (accuracy = env->GetFieldID(cls, "accuracy", "Ljava/lang/Double;")) &&
        (fixTimeMillis = env->GetFieldID(cls, "fixTimeMillis", "J")) &&
        (providerCode = env->GetFieldID(cls, "providerCode", "I"));
    env->DeleteLocalRef(cls);
    if (!found) return std::nullopt;

    const auto optionalDouble = [&](jfieldID field) -> std::optional<double> {
        if (env->ExceptionCheck()) return std::nullopt;
        const jobject boxed = env->GetObjectField(value, field);
        if (!boxed) return std::nullopt;
        const jclass doubleClass = env->GetObjectClass(boxed);
        const jmethodID doubleValue = doubleClass ? env->GetMethodID(doubleClass, "doubleValue", "()D") : nullptr;
        std::optional<double> result;
        if (doubleValue) result = env->CallDoubleMethod(boxed, doubleValue);
        env->DeleteLocalRef(doubleClass);
        env->DeleteLocalRef(boxed);
        return result;
    };
    CaptureGps result{
        env->GetDoubleField(value, latitude), env->GetDoubleField(value, longitude), {}, {},
        env->GetLongField(value, fixTimeMillis), env->GetIntField(value, providerCode), {}, false};
    result.altitude = optionalDouble(altitude);
    result.accuracy = optionalDouble(accuracy);
    // Optional fields: an older object without them still yields the location.
    const jclass directionClass = env->GetObjectClass(value);
    const jfieldID imageDirection =
        directionClass ? env->GetFieldID(directionClass, "imageDirectionDeg", "Ljava/lang/Double;") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    const jfieldID imageDirectionTrue =
        directionClass ? env->GetFieldID(directionClass, "imageDirectionTrueNorth", "Z") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (directionClass) env->DeleteLocalRef(directionClass);
    if (imageDirection) result.imageDirectionDeg = optionalDouble(imageDirection);
    if (imageDirectionTrue) result.imageDirectionTrue = env->GetBooleanField(value, imageDirectionTrue) == JNI_TRUE;
    if (env->ExceptionCheck()) return std::nullopt;
    if (result.fixTimeMillis <= 0 || result.fixTimeMillis >= 253402300800000LL) result.fixTimeMillis = 0;
    if (result.providerCode < 1 || result.providerCode > 3) result.providerCode = 0;
    return result;
}

}  // namespace rawrcam::jni

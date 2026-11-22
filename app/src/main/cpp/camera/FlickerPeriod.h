#pragma once

#include <cstdint>
#include <string_view>

namespace rawrcam::camera {

// The light period rawr AE keeps its shutter in step with (RawrAutoExposure.h snapToFlicker): a shutter
// of whole periods integrates the same light whatever the phase, so the frame shows no bands.
//
// antibanding is the NDK AE antibanding preference (OFF=0, 50HZ=1, 60HZ=2, AUTO=3); Kotlin's Auto
// already turns mains flicker confirmed by the phone's flicker sensor into 50HZ or 60HZ. sceneFlicker
// is the camera's own mains detection (STATISTICS_SCENE_FLICKER: 1 = 50 Hz, 2 = 60 Hz). sensedPeriodNs
// is the period of the flicker the phone's flicker sensor confirmed, mains or not: LED lights driven by
// PWM flicker at a few hundred hertz, which the camera's 50/60 Hz detection does not know.
//
// Explicit 50/60 Hz win, Off is off, and under Auto the sensor's period comes before the camera's
// mains guess. A sensed period outside 1..25 ms (40..1000 Hz) is ignored.
inline constexpr int64_t kMinSensedFlickerPeriodNs = 1'000'000;
inline constexpr int64_t kMaxSensedFlickerPeriodNs = 25'000'000;

[[nodiscard]] inline int64_t rawrAeFlickerPeriodNs(uint8_t antibanding, int32_t sceneFlicker,
                                                   int64_t sensedPeriodNs) noexcept {
    if (antibanding == 1) return 10'000'000;
    if (antibanding == 2) return 8'333'333;
    if (antibanding != 3) return 0;
    if (sensedPeriodNs >= kMinSensedFlickerPeriodNs && sensedPeriodNs <= kMaxSensedFlickerPeriodNs)
        return sensedPeriodNs;
    return sceneFlicker == 1 ? 10'000'000 : sceneFlicker == 2 ? 8'333'333 : 0;
}

// Where a sensed period other than the mains ones may steer rawr AE. It is on only on the devices it
// was validated on, matched by ro.product.device; elsewhere the sensed period is ignored and rawr AE
// keeps the mains behaviour it had.
//
// `debug.rawr.sensed_flicker` overrides this on any device: "1" turns it on, "0" turns it off.
// `setting` is that property's first character, or '\0' when it is unset.
//
// Validated:
// - Xiaomi 15 ("dada"): xiaomi.sensor.flicker reports the dominant frequency in 7.8125 Hz bins.
[[nodiscard]] inline bool sensedFlickerAllowed(std::string_view productDevice, char setting) noexcept {
    if (setting == '1') return true;
    if (setting == '0') return false;
    return productDevice == "dada";
}

}  // namespace rawrcam::camera

#pragma once

#include <string_view>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace rawrcam::video {

// Where recordings may use the fractional reduction (video_pipeline/VideoCrop.h videoResampleRect):
// 1080p from a RAW between 1.25x and 2x its size, demosaiced whole and resampled down instead of
// cropped 1:1 from the middle. On only on the devices it was validated on, matched by
// ro.product.device; everywhere else recordings keep the 1:1 crop.
//
// `debug.rawr.video_resample` overrides this on any device: "1" turns it on, "0" turns it off.
// `setting` is that property's first character, or '\0' when it is unset.
//
// Validated:
// - Xiaomi 15 ("dada"): the 3264x2448 front camera records 1080p from its full width (scale 1.7).
[[nodiscard]] inline bool videoResampleAllowed(std::string_view productDevice, char setting) noexcept {
    if (setting == '1') return true;
    if (setting == '0') return false;
    return productDevice == "dada";
}

#if defined(__ANDROID__)
// The process-wide answer; the properties are read once. Every place that frames one recording
// (the RAW stage, the idle preview crop, tap and metering mapping) asks this, so they agree.
[[nodiscard]] inline bool videoResampleEnabled() noexcept {
    static const bool enabled = [] {
        char device[PROP_VALUE_MAX]{};
        char setting[PROP_VALUE_MAX]{};
        __system_property_get("ro.product.device", device);
        __system_property_get("debug.rawr.video_resample", setting);
        return videoResampleAllowed(device, setting[0]);
    }();
    return enabled;
}
#endif

}  // namespace rawrcam::video

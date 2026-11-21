#pragma once

#include <string_view>

namespace rawrcam::vulkan {

// Where the GLES RAW16 bridge (GlesRawBridge.h) may engage. It is off by default and on only on the
// devices it was validated on, matched by ro.product.device. Everywhere else the RAW16 ingress is
// exactly what it was before the bridge.
//
// `debug.rawr.gles_bridge` overrides this on any device: "1" turns the bridge on, "0" turns it off.
// `setting` is that property's first character, or '\0' when it is unset.
//
// Validated:
// - Xiaomi 15 ("dada": 24129PN74G/I/C, Adreno 830, driver 7cd9369a93). The Vulkan driver refuses every
//   RAW16/RAW10 AHB, while GLES reads the camera's RAW16 frame bit-exactly.
[[nodiscard]] inline bool glesBridgeAllowed(std::string_view productDevice, char setting) noexcept {
    if (setting == '1') return true;
    if (setting == '0') return false;
    return productDevice == "dada";
}

}  // namespace rawrcam::vulkan

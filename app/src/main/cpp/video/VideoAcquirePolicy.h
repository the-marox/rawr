#pragma once

#include <algorithm>
#include <cstdint>
#include <string_view>

namespace rawrcam::video {

// The short acquire wait is a diagnostic mitigation validated on Xiaomi 15
// (ro.product.device=dada). Other devices keep the original non-blocking path.
[[nodiscard]] inline std::uint32_t videoAcquireWaitMs(std::string_view productDevice,
                                                       std::string_view propertyValue) noexcept {
    if (productDevice != "dada" || propertyValue.empty()) return 0;
    std::uint32_t value = 0;
    for (const char ch : propertyValue) {
        if (ch < '0' || ch > '9') return 0;
        const auto digit = static_cast<std::uint32_t>(ch - '0');
        if (value > 100u) return 5u;
        value = value * 10u + digit;
        if (value > 5u) return 5u;
    }
    return value;
}

}  // namespace rawrcam::video

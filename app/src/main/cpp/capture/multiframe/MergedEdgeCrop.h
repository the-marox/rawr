#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace rawrcam::capture::multiframe {

// Raw edge crop (lens profile image.cropEdges) for the merged JPEG. The
// single-frame path drops padded raw rows before anything develops
// (capture/single/StillEdgeCrop.h); the merge runs on whole GPU frames, so
// its rendered RGBA8 (sensor-native: rotation is EXIF only) is cropped here
// instead, scaled from raw-buffer edges to the image's own size. Also used
// for the Ultra HDR gain map, which is a scaled copy of the same frame.
struct KeptRgba8 {
    const void* data = nullptr;
    std::size_t bytes = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::vector<std::uint8_t> storage;  // only when left/right columns are dropped
    bool cropped = false;
};

inline KeptRgba8 keepRgba8Edges(const void* data, std::size_t bytes, std::uint32_t width, std::uint32_t height,
                                std::uint32_t rawWidth, std::uint32_t rawHeight, std::uint32_t left,
                                std::uint32_t top, std::uint32_t right, std::uint32_t bottom) {
    KeptRgba8 out{data, bytes, width, height, {}, false};
    if (!data || width == 0 || height == 0 || rawWidth == 0 || rawHeight == 0) return out;
    if (!left && !top && !right && !bottom) return out;
    if (left + right >= rawWidth || top + bottom >= rawHeight) return out;
    // Tight RGBA8 planes one after the other: the gain map can carry a second,
    // capture-chromaticity plane of the same size (mapBytes = 2 x one plane).
    const std::size_t planeBytes = std::size_t(width) * height * 4u;
    const std::uint32_t planes = bytes == planeBytes * 2u ? 2u : 1u;
    if (bytes != planeBytes * planes) return out;
    const std::size_t rowBytes = std::size_t(width) * 4u;

    // Kept window in this image's pixels: origin and size each scaled and
    // rounded, so a 1/4-size gain map stays aligned with the main image.
    const double sx = double(width) / double(rawWidth), sy = double(height) / double(rawHeight);
    const auto x0 = std::uint32_t(std::lround(left * sx));
    const auto y0 = std::uint32_t(std::lround(top * sy));
    const auto kw = std::uint32_t(std::lround((rawWidth - left - right) * sx));
    const auto kh = std::uint32_t(std::lround((rawHeight - top - bottom) * sy));
    if (kw == 0 || kh == 0 || x0 + kw > width || y0 + kh > height) return out;

    const auto* src = static_cast<const std::uint8_t*>(data);
    if (planes == 1 && x0 == 0 && kw == width) {
        // Rows only: the kept rows are already contiguous.
        out.data = src + std::size_t(y0) * rowBytes;
        out.bytes = rowBytes * kh;
    } else {
        const std::size_t keptRow = std::size_t(kw) * 4u;
        const std::size_t keptPlane = keptRow * kh;
        out.storage.resize(keptPlane * planes);
        for (std::uint32_t p = 0; p < planes; ++p)
            for (std::uint32_t y = 0; y < kh; ++y)
                std::memcpy(out.storage.data() + p * keptPlane + std::size_t(y) * keptRow,
                            src + p * planeBytes + std::size_t(y0 + y) * rowBytes + std::size_t(x0) * 4u, keptRow);
        out.data = out.storage.data();
        out.bytes = out.storage.size();
    }
    out.width = kw;
    out.height = kh;
    out.cropped = true;
    return out;
}

}  // namespace rawrcam::capture::multiframe

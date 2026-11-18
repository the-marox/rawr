#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace rawrcam::encoding::jpeg {

// Photo aspect ratio (3:2, 16:9, 1:1, XPan...) applied to the JPEG only: the
// rendered RGBA8 (sensor-native, rotation is EXIF) is cropped here, at the
// writer, so every still path (single, Film, Rexture, strip, 200 MP) gets it
// and the DNG stays the full frame.
struct PixelCrop {
    std::uint32_t x = 0, y = 0, width = 0, height = 0;
};

// Largest centred num:den rectangle in width x height, even origin and size.
inline PixelCrop aspectCrop(std::uint32_t width, std::uint32_t height, std::uint32_t num, std::uint32_t den) {
    PixelCrop c{0, 0, width, height};
    if (num == 0 || den == 0 || width < 2 || height < 2) return c;
    const std::uint64_t w = width, h = height;
    if (w * den > h * num) {
        const auto nw = std::uint32_t(h * num / den) & ~1u;
        if (nw >= 2) c = {((width - nw) / 2u) & ~1u, 0, nw, height};
    } else if (w * den < h * num) {
        const auto nh = std::uint32_t(w * den / num) & ~1u;
        if (nh >= 2) c = {0, ((height - nh) / 2u) & ~1u, width, nh};
    }
    return c;
}

// The same window in an image of another size (the Ultra HDR gain map).
inline PixelCrop scaledCrop(const PixelCrop& c, std::uint32_t fromW, std::uint32_t fromH, std::uint32_t toW,
                            std::uint32_t toH) {
    if (fromW == 0 || fromH == 0) return {0, 0, toW, toH};
    const double sx = double(toW) / fromW, sy = double(toH) / fromH;
    PixelCrop out{std::uint32_t(std::lround(c.x * sx)), std::uint32_t(std::lround(c.y * sy)),
                  std::uint32_t(std::lround(c.width * sx)), std::uint32_t(std::lround(c.height * sy))};
    if (out.width == 0) out.width = 1;
    if (out.height == 0) out.height = 1;
    if (out.x + out.width > toW) out.x = toW - out.width;
    if (out.y + out.height > toH) out.y = toH - out.height;
    return out;
}

// Copies the window out of [planes] tightly packed RGBA8 planes laid one after
// the other (the gain map may carry a second, capture-chromaticity plane).
inline std::vector<unsigned char> cropPlanes(const unsigned char* src, std::uint32_t width, std::uint32_t height,
                                             std::uint32_t planes, const PixelCrop& c) {
    const std::size_t srcRow = std::size_t(width) * 4u, dstRow = std::size_t(c.width) * 4u;
    const std::size_t srcPlane = srcRow * height, dstPlane = dstRow * c.height;
    std::vector<unsigned char> out(dstPlane * planes);
    for (std::uint32_t p = 0; p < planes; ++p)
        for (std::uint32_t y = 0; y < c.height; ++y)
            std::memcpy(out.data() + p * dstPlane + std::size_t(y) * dstRow,
                        src + p * srcPlane + std::size_t(c.y + y) * srcRow + std::size_t(c.x) * 4u, dstRow);
    return out;
}

}  // namespace rawrcam::encoding::jpeg

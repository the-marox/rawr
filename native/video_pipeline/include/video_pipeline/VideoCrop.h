#pragma once

#include <cstdint>

namespace rawrcam::video {

// Recording source rect shared by the recording RAW stage and the idle
// viewfinder preview crop, so preview framing matches the recorded frame
// exactly. Single owner of the center-crop rule previously inline in
// VideoDemosaic: optional 2x Bayer reduction, then an even-aligned centered
// crop of the full RAW frame.
struct VideoSourceRect {
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool reduceCfa = false;
    // Demosaiced 1:1 over the whole rect, then resampled down to the output size
    // (videoResampleRect): neither 1:1 nor the 2x Bayer reduction.
    bool resample = false;
};

// Source rect in full-RAW pixels for a recording output size. Returns an
// empty rect (width/height 0) when the output does not fit the sensor.
[[nodiscard]] inline VideoSourceRect videoSourceRect(uint32_t rawWidth, uint32_t rawHeight,
                                                     uint32_t outWidth, uint32_t outHeight) noexcept {
    VideoSourceRect rect{};
    const bool reduce =
        uint64_t(outWidth) * 2u <= rawWidth && uint64_t(outHeight) * 2u <= rawHeight;
    const uint32_t sourceWidth = reduce ? outWidth * 2u : outWidth;
    const uint32_t sourceHeight = reduce ? outHeight * 2u : outHeight;
    if (sourceWidth == 0 || sourceHeight == 0 || sourceWidth > rawWidth || sourceHeight > rawHeight)
        return rect;
    rect.x = ((rawWidth - sourceWidth) / 2u) & ~1u;
    rect.y = ((rawHeight - sourceHeight) / 2u) & ~1u;
    rect.width = sourceWidth;
    rect.height = sourceHeight;
    rect.reduceCfa = reduce;
    return rect;
}

// Image area of the RAW once padded edges are dropped (lens profile
// image.cropEdges: rows or columns a vendor mode delivers with no image).
// Edges are CFA-period multiples, so the origin keeps the colour order.
// Empty (width/height 0) when nothing is dropped or the edges are invalid.
struct VideoKeptArea {
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    [[nodiscard]] bool valid() const noexcept { return width != 0 && height != 0; }
    bool operator==(const VideoKeptArea& o) const noexcept {
        return x == o.x && y == o.y && width == o.width && height == o.height;
    }
};

[[nodiscard]] inline VideoKeptArea videoKeptArea(uint32_t rawWidth, uint32_t rawHeight, uint32_t left, uint32_t top,
                                                 uint32_t right, uint32_t bottom) noexcept {
    if (!(left | top | right | bottom)) return {};
    if (uint64_t(left) + right >= rawWidth || uint64_t(top) + bottom >= rawHeight) return {};
    return {left, top, rawWidth - left - right, rawHeight - top - bottom};
}

// Fractional reduction. Between 1:1 and 2x the rule above crops the output's
// own size out of the middle: a 3264-wide front camera records 1080p from its
// central 1920 columns, about 1.7x narrower than its image. Instead, the
// largest centred area of the output's aspect is demosaiced 1:1 and resampled
// down by width / outWidth. Only for scales from kVideoMinResampleScale up to
// 2x (where the 2x reduction takes over): just under 1x+ (4K from a 4096-wide
// RAW) the 1:1 crop loses little and stays the sharpest. Empty when the rule
// above applies.
inline constexpr double kVideoMinResampleScale = 1.25;

[[nodiscard]] inline VideoSourceRect videoResampleRect(uint32_t width, uint32_t height, uint32_t outWidth,
                                                       uint32_t outHeight) noexcept {
    VideoSourceRect rect{};
    if (outWidth == 0 || outHeight == 0 || width == 0 || height == 0) return rect;
    const double sx = double(width) / outWidth, sy = double(height) / outHeight;
    const double scale = sx < sy ? sx : sy;
    if (scale < kVideoMinResampleScale || scale >= 2.0) return rect;
    const uint32_t sourceWidth = uint32_t(double(outWidth) * scale) & ~1u;
    const uint32_t sourceHeight = uint32_t(double(outHeight) * scale) & ~1u;
    if (sourceWidth > width || sourceHeight > height || sourceWidth <= outWidth || sourceHeight <= outHeight)
        return rect;
    rect.x = ((width - sourceWidth) / 2u) & ~1u;
    rect.y = ((height - sourceHeight) / 2u) & ~1u;
    rect.width = sourceWidth;
    rect.height = sourceHeight;
    rect.resample = true;
    return rect;
}

// Same rule, centred on the kept area instead of the whole RAW, so padded
// edges never reach a recording or its preview. An empty kept area is the
// whole RAW (the rule above, unchanged). allowResample enables the fractional
// reduction (videoResampleRect); every user of one recording passes the same.
[[nodiscard]] inline VideoSourceRect videoSourceRect(uint32_t rawWidth, uint32_t rawHeight, uint32_t outWidth,
                                                     uint32_t outHeight, const VideoKeptArea& kept,
                                                     bool allowResample = false) noexcept {
    const bool useKept =
        kept.valid() && uint64_t(kept.x) + kept.width <= rawWidth && uint64_t(kept.y) + kept.height <= rawHeight;
    const uint32_t baseX = useKept ? kept.x : 0u, baseY = useKept ? kept.y : 0u;
    const uint32_t baseWidth = useKept ? kept.width : rawWidth, baseHeight = useKept ? kept.height : rawHeight;
    VideoSourceRect rect = allowResample ? videoResampleRect(baseWidth, baseHeight, outWidth, outHeight)
                                         : VideoSourceRect{};
    if (rect.width == 0) rect = videoSourceRect(baseWidth, baseHeight, outWidth, outHeight);
    if (rect.width == 0) return rect;
    rect.x += baseX;
    rect.y += baseY;
    return rect;
}

// Recording aspect crop (cinema/panoramic, num:den) inside a base frame: the
// largest such frame, the cropped side floored to a multiple of 16 for the
// encoder. 0:0 leaves the frame. Kotlin's VideoAspect.fit is the same rule.
inline void videoAspectFit(uint32_t& width, uint32_t& height, uint32_t num, uint32_t den) noexcept {
    if (num == 0 || den == 0 || width == 0 || height == 0) return;
    const uint64_t w = width, h = height;
    if (w * den < h * num) {
        const auto fitted = uint32_t(w * den / num) & ~15u;
        if (fitted >= 16) height = fitted;
    } else if (w * den > h * num) {
        const auto fitted = uint32_t(h * num / den) & ~15u;
        if (fitted >= 16) width = fitted;
    }
}

// Scales a full-RAW-space rect into another source space (e.g. the half-res
// preview image). Exact for even halvings; otherwise truncates with even
// alignment and clamps inside the target. Returns empty when degenerate.
[[nodiscard]] inline VideoSourceRect scaleVideoSourceRect(const VideoSourceRect& rect, uint32_t fromWidth,
                                                          uint32_t fromHeight, uint32_t toWidth,
                                                          uint32_t toHeight) noexcept {
    VideoSourceRect out{};
    out.reduceCfa = rect.reduceCfa;
    out.resample = rect.resample;
    if (fromWidth == 0 || fromHeight == 0 || toWidth == 0 || toHeight == 0 || rect.width == 0 ||
        rect.height == 0)
        return VideoSourceRect{};
    const uint64_t x = (uint64_t(rect.x) * toWidth / fromWidth) & ~1u;
    const uint64_t y = (uint64_t(rect.y) * toHeight / fromHeight) & ~1u;
    uint64_t w = uint64_t(rect.width) * toWidth / fromWidth;
    uint64_t h = uint64_t(rect.height) * toHeight / fromHeight;
    if (x >= toWidth || y >= toHeight) return VideoSourceRect{};
    if (x + w > toWidth) w = toWidth - x;
    if (y + h > toHeight) h = toHeight - y;
    if (w == 0 || h == 0) return VideoSourceRect{};
    out.x = static_cast<uint32_t>(x);
    out.y = static_cast<uint32_t>(y);
    out.width = static_cast<uint32_t>(w);
    out.height = static_cast<uint32_t>(h);
    return out;
}

}  // namespace rawrcam::video

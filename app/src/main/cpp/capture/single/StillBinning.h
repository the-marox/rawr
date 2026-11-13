#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "imaging/RawSnapshot.h"

namespace rawrcam::capture {

// Stills above this develop their JPEG from a 2x2 same-colour binned copy (Xiaomi 200 MP -> 50 MP);
// the DNG keeps the full frame.
constexpr uint64_t kJpegBinPixels = 100'000'000;

inline bool jpegNeedsBinning(const imaging::RawSnapshot& frame) {
    return uint64_t(frame.width) * frame.height > kJpegBinPixels && frame.width % 4 == 0 && frame.height % 4 == 0 &&
           frame.raw16.size() >= size_t(frame.width) * frame.height * 2u;
}

// Bayer in, Bayer out at half size: each output pixel averages the four same-colour input pixels of its
// 4x4 neighbourhood, so the CFA order, black and white levels are unchanged.
inline std::shared_ptr<imaging::RawSnapshot> binBayerStill2x(const imaging::RawSnapshot& in) {
    auto out = std::make_shared<imaging::RawSnapshot>();
    const uint32_t w = in.width / 2, h = in.height / 2;
    out->requestId = in.requestId;
    out->timestampNs = in.timestampNs;
    out->width = w;
    out->height = h;
    out->packedRowStrideBytes = w * 2u;
    out->sourceRowStrideBytes = int32_t(w * 2u);
    out->sourcePixelStrideBytes = 2;
    out->metadata = in.metadata;
    out->colorState = in.colorState;
    if (in.metadata.cameraContext) {
        auto context = std::make_shared<metadata::CameraContextMetadata>(*in.metadata.cameraContext);
        context->geometry.rawBufferWidth = w;
        context->geometry.rawBufferHeight = h;
        context->cfaBlock = 1;
        out->metadata.cameraContext = context;
    }
    out->raw16.resize(size_t(w) * h * 2u);
    const auto* src = reinterpret_cast<const uint16_t*>(in.raw16.data());
    auto* dst = reinterpret_cast<uint16_t*>(out->raw16.data());
    const uint32_t inW = in.width;
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    const uint32_t step = (h + threads - 1) / threads;
    std::vector<std::thread> pool;
    for (uint32_t t = 0; t < threads; ++t) {
        const uint32_t a = t * step, b = std::min(h, a + step);
        if (a >= b) break;
        pool.emplace_back([=] {
            for (uint32_t y = a; y < b; ++y) {
                const uint32_t sy = (y >> 1) * 4u + (y & 1u);
                const uint16_t* r0 = src + size_t(sy) * inW;
                const uint16_t* r1 = src + size_t(sy + 2u) * inW;
                uint16_t* o = dst + size_t(y) * w;
                for (uint32_t x = 0; x < w; ++x) {
                    const uint32_t sx = (x >> 1) * 4u + (x & 1u);
                    o[x] = uint16_t((uint32_t(r0[sx]) + r0[sx + 2] + r1[sx] + r1[sx + 2] + 2u) >> 2);
                }
            }
        });
    }
    for (auto& th : pool) th.join();
    return out;
}

}  // namespace rawrcam::capture

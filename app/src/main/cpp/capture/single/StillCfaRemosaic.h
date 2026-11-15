#pragma once
#include <android/log.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <chrono>
#include <thread>

#include "capture/ReducedRawMetadata.h"
#include "imaging/CfaRemosaic.h"
#include "imaging/CfaRemosaicFast.h"
#include "imaging/RawSnapshot.h"
#include "capture/single/StillBinning.h"

namespace rawrcam::capture {
// A block-CFA lens (CameraContextMetadata::cfaBlock > 1) hands stills over as
// the block mosaic; remosaic the copy to Bayer before anything (journal, DNG,
// develop) reads it, like ingress does for the viewfinder.
inline void remosaicBlockCfaStill(imaging::RawSnapshot& frame) {
    const auto& context = frame.metadata.cameraContext;
    if (!context || context->cfaBlock <= 1u || reducedRawRequested(*context)) return;
    // Debug: `adb shell setprop debug.rawr.keep_block_cfa 1` keeps the raw block mosaic in the DNG
    // (looks pink in editors) so remosaic methods can be compared offline on real scenes.
    char keep[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.rawr.keep_block_cfa", keep) > 0 && keep[0] == '1') {
        __android_log_print(ANDROID_LOG_WARN, "RawrCamNative", "STILL_CFA_REMOSAIC_SKIPPED requestId=%llu (debug.rawr.keep_block_cfa)",
                            static_cast<unsigned long long>(frame.requestId));
        return;
    }
    const auto start = std::chrono::steady_clock::now();
    // All cores: 12 MP takes ~115 ms (Standard) / ~140-175 ms (High) at 8 threads on V2562.
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    const auto quality = context->cfaPhotoQuality == 2u ? imaging::CfaRemosaicQuality::High
                                                        : imaging::CfaRemosaicQuality::Standard;
    // 4x4 blocks at Standard (Xiaomi 200 MP): the generic remosaic takes ~16 s there, the fast path well under 1 s.
    const bool fast = context->cfaBlock == 4u && quality == imaging::CfaRemosaicQuality::Standard &&
                      frame.raw16.size() >= size_t(frame.width) * frame.height * 2u;
    long long jpegMs = -1;
    if (fast) {
        const auto& b = context->baselineBlackLevelPhysicalRggb;
        auto* raw = reinterpret_cast<uint16_t*>(frame.raw16.data());
        const auto model = imaging::buildBlockCfaModel(raw, frame.width, frame.height, context->cfaBlock,
                                                       context->rawPreviewCfa, 0.25f * (b[0] + b[1] + b[2] + b[3]),
                                                       context->baselineWhiteLevel, threads);
        // Too large for a full-size JPEG (200 MP): its 50 MP Bayer comes straight from the blocks, before the
        // remosaic below rewrites them for the DNG. On real scenes this removes the stair-stepped edges and
        // checker texture of binning the remosaicked frame (+2.5 dB on synthetic scenes). A TV screen's
        // sub-pixels can show as moire. `adb shell setprop debug.rawr.jpeg_from_blocks 0` bins the remosaicked
        // frame instead, for comparison.
        char fromBlocks[PROP_VALUE_MAX] = {};
        const bool binRemosaicked =
            __system_property_get("debug.rawr.jpeg_from_blocks", fromBlocks) > 0 && fromBlocks[0] == '0';
        if (jpegNeedsBinning(frame) && !binRemosaicked) {
            const auto jpegStart = std::chrono::steady_clock::now();
            auto half = halfSizeBayerSnapshot(frame);
            imaging::binBlockCfaToBayer(raw, model, reinterpret_cast<uint16_t*>(half->raw16.data()), threads);
            frame.jpegBayer = std::move(half);
            jpegMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - jpegStart)
                         .count();
        }
        imaging::remosaicBlockCfaWithModel(raw, model, threads);
    } else {
        imaging::remosaicBlockCfa(frame.raw16, frame.width, frame.height, context->cfaBlock, context->rawPreviewCfa,
                                  threads, quality);
    }
    [[maybe_unused]] const long long ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    __android_log_print(ANDROID_LOG_INFO, "RawrCamNative",
                        "STILL_CFA_REMOSAIC requestId=%llu raw=%ux%u block=%u quality=%s ms=%lld jpegBayerMs=%lld",
                        static_cast<unsigned long long>(frame.requestId), frame.width, frame.height, context->cfaBlock,
                        fast ? "fast" : quality == imaging::CfaRemosaicQuality::High ? "high" : "standard", ms, jpegMs);
}
}  // namespace rawrcam::capture

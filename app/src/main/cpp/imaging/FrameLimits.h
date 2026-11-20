#pragma once

#include <cstddef>
#include <cstdint>

namespace rawrcam::imaging {

// Application-wide realtime buffering contract. Every per-frame-slot subsystem
// must size and validate its resources from this single owner so preview,
// statistics, monitoring, diagnostics, and presentation cannot drift apart.
inline constexpr std::uint32_t kRealtimeFramesInFlight = 3;

inline constexpr std::size_t kRawReaderMaxImages = 6;
// 17U: the realtime (preview/video) RAW reader and its ingress queue are deeper than the other readers. A one-time
// 45-80 ms stall ~0.2 s into a take let a single waiting RAW frame be evicted (INGRESS_QUEUE_PRESSURE rawDropped=1);
// three waiting frames ride it out. Traced and fixed on the X14U by apos-fandango (github.com/apos-fandango/
// rawr-xiaomi14u, commits 39a49615 and b2d85abe): ~38 MB more at 4K. Full-size still readers keep
// kRawReaderMaxImages.
inline constexpr std::size_t kRealtimeRawReaderMaxImages = 8;
inline constexpr std::size_t kMaxIngressImages = 3;
inline constexpr std::size_t kMaxIngressMetadata = 8;
inline constexpr std::size_t kMaxPairerImages = 1;
inline constexpr std::size_t kMaxPairerMetadata = 3;
static_assert(kRealtimeFramesInFlight + kMaxIngressImages + kMaxPairerImages < kRealtimeRawReaderMaxImages,
              "RAW transport must retain one reader lease of headroom");

}  // namespace rawrcam::imaging

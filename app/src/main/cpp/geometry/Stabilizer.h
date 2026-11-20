#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

// Gyro video stabilisation. The shake track (StabilizerTrack) and the Kotlin
// gyro integrator (video/StabilizerSensor.kt) come from apos-fandango's Rawr
// X14U fork (github.com/apos-fandango/rawr-xiaomi14u, commit 9bd6bac, GPL-3.0).
// The 17U applies it in the recording fast path as a per-frame warp (shift and
// turn, sub-pixel) of the sampling window inside the sensor margin, in the
// fast pass itself (video_fast_yuv.comp, VIDEO_FAST_WARP).
namespace rawrcam::geometry {

// Small camera rotations (radians, image axes: x right, y down, z forward) of
// the shake: the camera's turn minus its smoothed path, at timeNs.
struct StabilizerSample {
    int64_t timeNs = 0;  // CLOCK_BOOTTIME (SensorEvent.timestamp)
    float pitch = 0.0f;  // about x
    float yaw = 0.0f;    // about y
    float roll = 0.0f;   // about z (the lens axis); with horizon levelling, the tilt from level instead
    // The camera's integrated turn (before smoothing), only for the RawrStab trace.
    float rawPitch = 0.0f, rawYaw = 0.0f, rawRoll = 0.0f;
};

class StabilizerTrack {
   public:
    void push(const StabilizerSample& s) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (count_ > 0 && s.timeNs <= ring_[(head_ + kSize - 1) % kSize].timeNs) return;
        ring_[head_] = s;
        head_ = (head_ + 1) % kSize;
        if (count_ < kSize) ++count_;
    }
    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        count_ = 0;
        head_ = 0;
    }
    // The shake at timeNs, interpolated; false with no sample within half a second.
    bool at(int64_t timeNs, StabilizerSample& out) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (count_ == 0) return false;
        const auto get = [&](size_t i) -> const StabilizerSample& {
            return ring_[(head_ + kSize - count_ + i) % kSize];
        };
        const auto& first = get(0);
        const auto& last = get(count_ - 1);
        constexpr int64_t kNear = 500'000'000;
        if (timeNs >= last.timeNs) {
            if (timeNs - last.timeNs > kNear) return false;
            out = last;
            return true;
        }
        if (timeNs <= first.timeNs) {
            if (first.timeNs - timeNs > kNear) return false;
            out = first;
            return true;
        }
        size_t lo = 0, hi = count_ - 1;
        while (hi - lo > 1) {
            const size_t mid = (lo + hi) / 2;
            if (get(mid).timeNs <= timeNs) lo = mid;
            else hi = mid;
        }
        const auto& a = get(lo);
        const auto& b = get(hi);
        const float t = float(double(timeNs - a.timeNs) / double(b.timeNs - a.timeNs));
        out.timeNs = timeNs;
        out.pitch = a.pitch + t * (b.pitch - a.pitch);
        out.yaw = a.yaw + t * (b.yaw - a.yaw);
        out.roll = a.roll + t * (b.roll - a.roll);
        out.rawPitch = a.rawPitch + t * (b.rawPitch - a.rawPitch);
        out.rawYaw = a.rawYaw + t * (b.rawYaw - a.rawYaw);
        out.rawRoll = a.rawRoll + t * (b.rawRoll - a.rawRoll);
        return true;
    }

   private:
    static constexpr size_t kSize = 1024;  // about 4 s at the 17U gyro's ~240 Hz
    mutable std::mutex mutex_;
    std::array<StabilizerSample, kSize> ring_{};
    size_t head_ = 0;
    size_t count_ = 0;
};

inline StabilizerTrack& stabilizerTrack() noexcept {
    static StabilizerTrack track;
    return track;
}
// On while Settings > Video Encoder > Stabilisation is on (video mode).
inline std::atomic<bool>& stabilizerEnabled() noexcept {
    static std::atomic<bool> value{false};
    return value;
}
// Capture time (CLOCK_BOOTTIME, middle of the exposure) of the video frame being recorded now.
inline std::atomic<int64_t>& stabilizerFrameTimeNs() noexcept {
    static std::atomic<int64_t> value{0};
    return value;
}
// The lens's focal length in RAW pixels (0 = unknown, no stabilisation).
inline std::atomic<float>& stabilizerFocalPx() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}
// Shift room the current recording leaves on each axis, as an angle (radians): the gyro integrator keeps the
// shake inside it, pulling its smoothed path along at the edge. 0 = no room (no stabilisation).
inline std::atomic<float>& stabilizerRoomX() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}
inline std::atomic<float>& stabilizerRoomY() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}
// Turn (roll) room of the current recording in radians, with no shift: the corners' margin over half the frame.
inline std::atomic<float>& stabilizerRoomRoll() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}

}  // namespace rawrcam::geometry

#pragma once
#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <memory>

#include <tonemap/TonemapEngine.h>

#include "imaging/FrameLimits.h"
#include "video_pipeline/VideoDemosaic.h"
#include "vulkan/ImageResources.h"

namespace rawrcam::video {

// 17U fast recording path (fork-owned): RAW -> P010 encoder buffer in one
// dispatch, with the render baked into a small per-frame LUT by the regular
// video tonemap (an RGBA16F engine instance). Replaces demosaic, WB, tonemap
// and the YUV pack for frames that need none of the neighbourhood stages
// (highlight reconstruction, denoise, defringe, FCC, distortion, DCP).
class VideoFastPath final {
   public:
    static constexpr uint32_t kLutSize = 65;
    static constexpr uint32_t kSlots = rawrcam::imaging::kRealtimeFramesInFlight;

    // tenBit: P010 encoder input, otherwise NV12.
    VideoFastPath(VkPhysicalDevice physical, VkDevice device, uint32_t width, uint32_t height, bool tenBit);
    ~VideoFastPath();
    VideoFastPath(const VideoFastPath&) = delete;
    VideoFastPath& operator=(const VideoFastPath&) = delete;

    // Stabilisation for one frame: rotation (cos and sin times a zoom scale <= 1) about the record crop's centre, then
    // a shift in sensor pixels.
    struct Warp {
        float cosAngle = 1.0f, sinAngle = 0.0f, shiftX = 0.0f, shiftY = 0.0f;
    };
    bool warpAvailable() const noexcept { return warpPipeline_ != VK_NULL_HANDLE; }

    struct EncoderTarget {
        VkBuffer buffer = VK_NULL_HANDLE;
        uint32_t yOffsetWords = 0, yStrideWords = 0, cOffsetWords = 0, cStrideWords = 0, swapUV = 0;
    };
    // `tone` carries the frame's matrix, params and highlight tap; its command
    // buffer, input, output, monitor and slot are filled in here.
    void record(VkCommandBuffer command, uint32_t frameSlot, const VideoDemosaic::Frame& frame,
                const VideoDemosaic& geometry, tonemap::TonemapEngine& bake, tonemap::TonemapRecordInfo tone,
                const EncoderTarget& target, uint32_t queueFamily, VkImageView monitorView, bool monitorEnabled, VkQueryPool stagePool,
                const Warp* warp = nullptr);

   private:
    struct HostBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
    };
    HostBuffer makeHostBuffer(VkDeviceSize bytes, VkBufferUsageFlags usage);
    void destroyHostBuffer(HostBuffer& buffer) noexcept;
    void destroy() noexcept;

    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t width_ = 0, height_ = 0;
    float shaperC_ = 0.0f, shaperLog2C_ = 0.0f, shaperScale_ = 0.0f;

    rawrcam::vulkan::OwnedImage grid_{};        // bake input: shaper grid, n x n*n RGBA16F
    rawrcam::vulkan::OwnedImage lut_{};         // bake output, sampled by the fused pass
    rawrcam::vulkan::OwnedImage bakeMonitor_{};  // unused monitor target the engine requires
    rawrcam::vulkan::OwnedImage dummyRaw_{};
    HostBuffer gridUpload_{};
    bool imagesInitialized_ = false;
    VkSampler sampler_ = VK_NULL_HANDLE;

    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkPipeline warpPipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    std::array<VkDescriptorSet, kSlots> sets_{};
    std::array<HostBuffer, kSlots> params_{};
    std::array<HostBuffer, kSlots> lsc_{};
};
}  // namespace rawrcam::video

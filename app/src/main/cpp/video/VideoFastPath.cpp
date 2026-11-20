#include "video/VideoFastPath.h"

#include "vulkan/VulkanDispatch.h"

#include <android/log.h>
#include <tonemap/TonemapEngine.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "video_fast_turn_nv12.h"
#include "video_fast_turn_p010.h"
#include "video_fast_warp_nv12.h"
#include "video_fast_warp_p010.h"
#include "video_fast_yuv_nv12.h"
#include "video_fast_yuv_p010.h"
#include "vulkan/HostMemory.h"

namespace rawrcam::video {
namespace {
void check(VkResult result, const char* what) {
    if (result != VK_SUCCESS) throw std::runtime_error(std::string(what) + " VkResult=" + std::to_string(result));
}
// Same layout as the VideoRawParams block of video_quad_core.glsl.
struct Push {
    float black[4];
    float invRange[4];
    float wb[4];
    uint32_t width, height, outWidth, outHeight;
    uint32_t cropX, cropY, pattern, stridePixels;
    uint32_t bufferEnabled, reduceCfa, lscEnabled, lscWidth, lscHeight, monitorEnabled;
};
static_assert(sizeof(Push) == 104);
struct Params {
    float gains[4];
    float shaper[4];
    uint32_t yuv0[4];
    uint32_t yuv1[4];
    float warp[4];  // VIDEO_FAST_WARP: cos, sin, shift x, shift y
};
static_assert(sizeof(Params) == 80);
constexpr VkDeviceSize kMaxLscBytes = 256 * 1024;
// Shaper domain: white-balanced camera RGB in [0, kShaperMax], log2 with a
// linear toe below kShaperToe. HL off clamps every channel to the lowest WB
// gain, so real input stays well under the top of the range.
constexpr float kShaperToe = 1.0f / 4096.0f;
constexpr float kShaperMax = 8.0f;

VkImageMemoryBarrier imageBarrier(VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags src,
                                  VkAccessFlags dst) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcAccessMask = src;
    b.dstAccessMask = dst;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}
}  // namespace

VideoFastPath::HostBuffer VideoFastPath::makeHostBuffer(VkDeviceSize bytes, VkBufferUsageFlags usage) {
    HostBuffer out;
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = bytes;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device_, &info, nullptr, &out.buffer), "fast path buffer");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, out.buffer, &req);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = rawrcam::vulkan::findCoherentHostMemoryType(physical_, req.memoryTypeBits);
    VkResult r = vkAllocateMemory(device_, &alloc, nullptr, &out.memory);
    if (r == VK_SUCCESS) r = vkBindBufferMemory(device_, out.buffer, out.memory, 0);
    if (r == VK_SUCCESS) r = vkMapMemory(device_, out.memory, 0, VK_WHOLE_SIZE, 0, &out.mapped);
    if (r != VK_SUCCESS) {
        destroyHostBuffer(out);
        check(r, "fast path buffer memory");
    }
    return out;
}

void VideoFastPath::destroyHostBuffer(HostBuffer& buffer) noexcept {
    if (buffer.mapped) vkUnmapMemory(device_, buffer.memory);
    if (buffer.buffer) vkDestroyBuffer(device_, buffer.buffer, nullptr);
    if (buffer.memory) vkFreeMemory(device_, buffer.memory, nullptr);
    buffer = {};
}

VideoFastPath::VideoFastPath(VkPhysicalDevice physical, VkDevice device, uint32_t width, uint32_t height,
                             bool tenBit)
    : physical_(physical), device_(device), width_(width), height_(height) {
    try {
        constexpr uint32_t n = kLutSize;
        shaperC_ = kShaperToe;
        shaperLog2C_ = std::log2(kShaperToe);
        shaperScale_ = 1.0f / (std::log2(kShaperMax + kShaperToe) - shaperLog2C_);
        grid_ = rawrcam::vulkan::createOwnedImage(physical, device, n, n * n, VK_FORMAT_R16G16B16A16_SFLOAT,
                                                  VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        lut_ = rawrcam::vulkan::createOwnedImage(physical, device, n, n * n, VK_FORMAT_R16G16B16A16_SFLOAT,
                                                 VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        bakeMonitor_ = rawrcam::vulkan::createOwnedImage(physical, device, (n + 1u) / 2u, (n * n + 1u) / 2u,
                                                         VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT);
        dummyRaw_ = rawrcam::vulkan::createOwnedImage(physical, device, 1, 1, VK_FORMAT_R16_UINT,
                                                      VK_IMAGE_USAGE_STORAGE_BIT);
        // Grid texel (r, g + n * b) holds the camera RGB whose shaped value is (r, g, b) / (n - 1).
        gridUpload_ = makeHostBuffer(VkDeviceSize(n) * n * n * 4 * sizeof(uint16_t), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        std::vector<_Float16> axis(n);
        for (uint32_t i = 0; i < n; ++i) {
            const float s = float(i) / float(n - 1u);
            axis[i] = _Float16(std::max(std::exp2(s / shaperScale_ + shaperLog2C_) - shaperC_, 0.0f));
        }
        auto* texels = static_cast<_Float16*>(gridUpload_.mapped);
        for (uint32_t b = 0; b < n; ++b)
            for (uint32_t g = 0; g < n; ++g)
                for (uint32_t r = 0; r < n; ++r) {
                    _Float16* t = texels + (size_t(b * n + g) * n + r) * 4u;
                    t[0] = axis[r];
                    t[1] = axis[g];
                    t[2] = axis[b];
                    t[3] = _Float16(1.0f);
                }

        VkSamplerCreateInfo sampler{};
        sampler.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sampler.magFilter = VK_FILTER_LINEAR;
        sampler.minFilter = VK_FILTER_LINEAR;
        sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sampler.maxLod = 0.0f;
        check(vkCreateSampler(device, &sampler, nullptr, &sampler_), "fast path sampler");

        const VkDescriptorType types[7] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,          VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                           VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,         VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                           VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                           VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        VkDescriptorSetLayoutBinding bindings[7]{};
        for (uint32_t i = 0; i < 7; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = types[i];
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 7;
        layoutInfo.pBindings = bindings;
        check(vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &setLayout_), "fast path set layout");
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
        VkPipelineLayoutCreateInfo pipelineLayout{};
        pipelineLayout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pipelineLayout.setLayoutCount = 1;
        pipelineLayout.pSetLayouts = &setLayout_;
        pipelineLayout.pushConstantRangeCount = 1;
        pipelineLayout.pPushConstantRanges = &range;
        check(vkCreatePipelineLayout(device, &pipelineLayout, nullptr, &pipelineLayout_), "fast path layout");

        const auto makePipeline = [&](const unsigned char* code, size_t size, VkPipeline& out, const char* what) {
            VkShaderModuleCreateInfo module{};
            module.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            module.codeSize = size;
            module.pCode = reinterpret_cast<const uint32_t*>(code);
            VkShaderModule shader = VK_NULL_HANDLE;
            check(vkCreateShaderModule(device, &module, nullptr, &shader), what);
            VkComputePipelineCreateInfo pipeline{};
            pipeline.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            pipeline.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pipeline.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            pipeline.stage.module = shader;
            pipeline.stage.pName = "main";
            pipeline.layout = pipelineLayout_;
            const VkResult created = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline, nullptr, &out);
            vkDestroyShaderModule(device, shader, nullptr);
            check(created, what);
        };
        makePipeline(tenBit ? video_fast_yuv_p010_spv : video_fast_yuv_nv12_spv,
                     tenBit ? video_fast_yuv_p010_spv_size : video_fast_yuv_nv12_spv_size, pipeline_,
                     "fast path pipeline");
        // The stabilised variant is optional: without it the recording runs unstabilised.
        try {
            makePipeline(tenBit ? video_fast_warp_p010_spv : video_fast_warp_nv12_spv,
                         tenBit ? video_fast_warp_p010_spv_size : video_fast_warp_nv12_spv_size, warpPipeline_,
                         "fast path warp pipeline");
        } catch (const std::exception& e) {
            warpPipeline_ = VK_NULL_HANDLE;
            __android_log_print(ANDROID_LOG_WARN, "RawrNativeVideo", "VIDEO_FAST_WARP_UNAVAILABLE %s", e.what());
        }
        try {
            makePipeline(tenBit ? video_fast_turn_p010_spv : video_fast_turn_nv12_spv,
                         tenBit ? video_fast_turn_p010_spv_size : video_fast_turn_nv12_spv_size, turnPipeline_,
                         "fast path turn pipeline");
        } catch (const std::exception& e) {
            turnPipeline_ = VK_NULL_HANDLE;
            __android_log_print(ANDROID_LOG_WARN, "RawrNativeVideo", "VIDEO_FAST_TURN_UNAVAILABLE %s", e.what());
        }

        VkDescriptorPoolSize sizes[4] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2 * kSlots},
                                         {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3 * kSlots},
                                         {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, kSlots},
                                         {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSlots}};
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = kSlots;
        poolInfo.poolSizeCount = 4;
        poolInfo.pPoolSizes = sizes;
        check(vkCreateDescriptorPool(device, &poolInfo, nullptr, &pool_), "fast path pool");
        std::array<VkDescriptorSetLayout, kSlots> layouts;
        layouts.fill(setLayout_);
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = pool_;
        allocate.descriptorSetCount = kSlots;
        allocate.pSetLayouts = layouts.data();
        check(vkAllocateDescriptorSets(device, &allocate, sets_.data()), "fast path sets");
        for (uint32_t i = 0; i < kSlots; ++i) {
            params_[i] = makeHostBuffer(sizeof(Params), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            lsc_[i] = makeHostBuffer(kMaxLscBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
        __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "VIDEO_FAST_PATH ready %ux%u lut=%u %s", width, height,
                            n, tenBit ? "p010" : "nv12");
    } catch (...) {
        destroy();
        throw;
    }
}

VideoFastPath::~VideoFastPath() { destroy(); }

void VideoFastPath::destroy() noexcept {
    if (!device_) return;
    for (auto& b : params_) destroyHostBuffer(b);
    for (auto& b : lsc_) destroyHostBuffer(b);
    destroyHostBuffer(gridUpload_);
    if (pool_) vkDestroyDescriptorPool(device_, pool_, nullptr);
    if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
    if (warpPipeline_) vkDestroyPipeline(device_, warpPipeline_, nullptr);
    if (turnPipeline_) vkDestroyPipeline(device_, turnPipeline_, nullptr);
    warpPipeline_ = VK_NULL_HANDLE;
    turnPipeline_ = VK_NULL_HANDLE;
    if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    if (setLayout_) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
    if (sampler_) vkDestroySampler(device_, sampler_, nullptr);
    pool_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    setLayout_ = VK_NULL_HANDLE;
    sampler_ = VK_NULL_HANDLE;
    rawrcam::vulkan::destroyOwnedImage(device_, grid_);
    rawrcam::vulkan::destroyOwnedImage(device_, lut_);
    rawrcam::vulkan::destroyOwnedImage(device_, bakeMonitor_);
    rawrcam::vulkan::destroyOwnedImage(device_, dummyRaw_);
}

void VideoFastPath::record(VkCommandBuffer command, uint32_t frameSlot, const VideoDemosaic::Frame& frame,
                           const VideoDemosaic& geometry, tonemap::TonemapEngine& bake,
                           tonemap::TonemapRecordInfo tone, const EncoderTarget& target, uint32_t queueFamily,
                           VkImageView monitorView, bool monitorEnabled, VkQueryPool stagePool, const Warp* warp) {
    if (frameSlot >= kSlots || !target.buffer || (!frame.rawBuffer && !frame.rawView) || frame.cfa > 3u ||
        frame.white <= 0.0f)
        throw std::invalid_argument("video fast path frame invalid");
    constexpr uint32_t n = kLutSize;
    const bool useBuffer = frame.rawBuffer != VK_NULL_HANDLE && frame.rawStridePixels >= geometry.rawWidth();
    const bool useLsc = frame.lensShading && frame.lensShadingWidth >= 2 && frame.lensShadingHeight >= 2 &&
                        frame.lensShadingCount == size_t(frame.lensShadingWidth) * frame.lensShadingHeight * 4u &&
                        frame.lensShadingCount * sizeof(float) <= kMaxLscBytes;
    if (useLsc) std::memcpy(lsc_[frameSlot].mapped, frame.lensShading, frame.lensShadingCount * sizeof(float));

    // 1. Image setup on first use, then the bake: the regular tonemap renders the shaper grid.
    if (!imagesInitialized_) {
        VkImageMemoryBarrier init[4] = {
            imageBarrier(grid_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                         VK_ACCESS_TRANSFER_WRITE_BIT),
            imageBarrier(lut_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT),
            imageBarrier(bakeMonitor_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0,
                         VK_ACCESS_SHADER_WRITE_BIT),
            imageBarrier(dummyRaw_.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0,
                         VK_ACCESS_SHADER_READ_BIT)};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                             nullptr, 4, init);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {n, n * n, 1};
        vkCmdCopyBufferToImage(command, gridUpload_.buffer, grid_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                               &copy);
        VkImageMemoryBarrier ready = imageBarrier(grid_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                                  VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                                                  VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 0, nullptr, 1, &ready);
        imagesInitialized_ = true;
    } else {
        // The previous frame's fused pass sampled the LUT.
        VkImageMemoryBarrier reuse = imageBarrier(lut_.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                                                  VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 0, nullptr, 1, &reuse);
    }
    tone.commandBuffer = command;
    tone.input = {grid_.view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_LAYOUT_GENERAL, n, n * n};
    tone.output = {lut_.view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_LAYOUT_GENERAL, n, n * n};
    tone.monitor = {bakeMonitor_.view, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_GENERAL, (n + 1u) / 2u,
                    (n * n + 1u) / 2u};
    tone.monitorEnabled = false;
    tone.frameSlot = frameSlot;
    bake.record(tone);
    VkImageMemoryBarrier baked = imageBarrier(lut_.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                                              VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    // The encoder owned the buffer before: take it back (contents are rewritten).
    VkBufferMemoryBarrier acquire{};
    acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    acquire.dstQueueFamilyIndex = queueFamily;
    acquire.buffer = target.buffer;
    acquire.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 1, &baked);
    // Stage timing: "demosaic" holds the bake, "tonemap" the fused pass; the rest is empty.
    if (stagePool) {
        for (uint32_t q : {13u, 0u, 1u, 2u, 3u, 4u, 5u, 10u, 11u, 14u})
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, q);
    }

    // 2. The fused RAW -> P010 pass.
    Params params{};
    const float gains[3] = {frame.wb[0], 0.5f * (frame.wb[1] + frame.wb[2]), frame.wb[3]};
    params.gains[0] = gains[0];
    params.gains[1] = gains[1];
    params.gains[2] = gains[2];
    params.gains[3] = std::max(std::min({gains[0], gains[1], gains[2]}), 1.0e-4f);
    params.shaper[0] = shaperC_;
    params.shaper[1] = shaperLog2C_;
    params.shaper[2] = shaperScale_;
    params.shaper[3] = float(n);
    params.yuv0[0] = target.yOffsetWords;
    params.yuv0[1] = target.yStrideWords;
    params.yuv0[2] = target.cOffsetWords;
    params.yuv0[3] = target.cStrideWords;
    params.yuv1[0] = target.swapUV;
    params.yuv1[1] = monitorEnabled ? 1u : 0u;
    // The turned variant only for a frame that turns or zooms (horizon levelling); a shift-only frame takes the
    // cheaper shift variant.
    const bool turned = warp != nullptr && warp->turn && turnPipeline_ != VK_NULL_HANDLE;
    const bool warped = warp != nullptr && (turned || warpPipeline_ != VK_NULL_HANDLE);
    const bool shiftOnly = warped && !turned;
    params.warp[0] = turned ? warp->cosAngle : 1.0f;
    params.warp[1] = turned ? warp->sinAngle : 0.0f;
    params.warp[2] = warped ? warp->shiftX : 0.0f;
    params.warp[3] = warped ? warp->shiftY : 0.0f;
    std::memcpy(params_[frameSlot].mapped, &params, sizeof(params));

    VkDescriptorImageInfo raw{VK_NULL_HANDLE, useBuffer ? dummyRaw_.view : frame.rawView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo rawBuffer{useBuffer ? frame.rawBuffer : lsc_[frameSlot].buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo uniform{params_[frameSlot].buffer, 0, sizeof(Params)};
    VkDescriptorBufferInfo lsc{lsc_[frameSlot].buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorImageInfo lut{sampler_, lut_.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo encoder{target.buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorImageInfo monitor{VK_NULL_HANDLE, monitorView, VK_IMAGE_LAYOUT_GENERAL};
    std::array<VkWriteDescriptorSet, 7> writes{};
    for (uint32_t i = 0; i < writes.size(); ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = sets_[frameSlot];
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
    }
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &raw;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &rawBuffer;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &uniform;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[3].pBufferInfo = &lsc;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[4].pImageInfo = &lut;
    writes[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[5].pBufferInfo = &encoder;
    writes[6].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[6].pImageInfo = &monitor;
    vkUpdateDescriptorSets(device_, uint32_t(writes.size()), writes.data(), 0, nullptr);

    Push push{};
    for (size_t i = 0; i < 4; ++i) {
        push.black[i] = frame.black[i];
        push.invRange[i] = 1.0f / std::max(frame.white - frame.black[i], 1.0f);
        push.wb[i] = frame.wb[i];
    }
    push.width = geometry.rawWidth();
    push.height = geometry.rawHeight();
    push.outWidth = width_;
    push.outHeight = height_;
    // Stabilisation (warp): the record crop stays put and the shader samples the warped position; the caller keeps
    // the warped window inside the margin.
    push.cropX = geometry.cropX();
    push.cropY = geometry.cropY();
    push.pattern = frame.cfa;
    push.stridePixels = frame.rawStridePixels;
    push.bufferEnabled = useBuffer ? 1u : 0u;
    push.reduceCfa = 0u;
    push.lscEnabled = useLsc ? 1u : 0u;
    push.lscWidth = frame.lensShadingWidth;
    push.lscHeight = frame.lensShadingHeight;
    push.monitorEnabled = monitorEnabled ? 1u : 0u;
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE,
                      turned ? turnPipeline_ : (shiftOnly ? warpPipeline_ : pipeline_));
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &sets_[frameSlot], 0,
                            nullptr);
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    // 16x16 threads, one 2x2 cell each: 32x32 output pixels per workgroup.
    vkCmdDispatch(command, (width_ + 31u) / 32u, (height_ + 31u) / 32u, 1);
    if (stagePool) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, 15);

    // Hand the buffer to the encoder.
    VkBufferMemoryBarrier release{};
    release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    release.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    release.srcQueueFamilyIndex = queueFamily;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    release.buffer = target.buffer;
    release.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                         nullptr, 1, &release, 0, nullptr);
}
}  // namespace rawrcam::video

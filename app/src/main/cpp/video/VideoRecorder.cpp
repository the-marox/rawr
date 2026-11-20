#include "video/VideoRecorder.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <string>

#include "raw_highlight/Coloropp.hpp"
#include "tonemap_video_linear.h"
#include "tonemap_video_rgb10a2.h"
#include "tonemap_video_rgba8.h"
#include "geometry/Stabilizer.h"
#include "video/VideoOutput.h"
#include "vulkan/VulkanContext.h"
namespace rawrcam::video {
namespace {
// Gyro stabilisation (geometry/Stabilizer.h): the crop shift that takes this frame's shake out, in RAW pixels, even
// (the CFA phase), inside the margin the crop leaves in the image area minus an 8-pixel apron for the demosaic.
// Publishes that margin as angles so the gyro integrator keeps its shake within it. {0, 0} when off.
std::array<int32_t, 2> stabilizerShift(const VideoDemosaic& demosaic, const VideoKeptArea& keptArea) {
    using namespace rawrcam::geometry;
    const float focal = stabilizerFocalPx().load(std::memory_order_relaxed);
    if (!stabilizerEnabled().load(std::memory_order_relaxed) || !(focal > 0.0f) || demosaic.sensorScale() != 1u) {
        stabilizerRoomX().store(0.0f, std::memory_order_relaxed);
        stabilizerRoomY().store(0.0f, std::memory_order_relaxed);
        return {0, 0};
    }
    const VideoKeptArea kept = keptArea.valid() ? keptArea
                                                : VideoKeptArea{0, 0, demosaic.rawWidth(), demosaic.rawHeight()};
    constexpr int32_t kApron = 8;
    const int32_t cx = int32_t(demosaic.cropX()), cy = int32_t(demosaic.cropY());
    const int32_t w = int32_t(demosaic.outputWidth()), h = int32_t(demosaic.outputHeight());
    const int32_t left = cx - int32_t(kept.x) - kApron, right = int32_t(kept.x + kept.width) - (cx + w) - kApron;
    const int32_t top = cy - int32_t(kept.y) - kApron, bottom = int32_t(kept.y + kept.height) - (cy + h) - kApron;
    const int32_t roomX = std::max(0, std::min(left, right)), roomY = std::max(0, std::min(top, bottom));
    stabilizerRoomX().store(float(roomX) / focal, std::memory_order_relaxed);
    stabilizerRoomY().store(float(roomY) / focal, std::memory_order_relaxed);
    // The crop moves in 2-pixel steps (the CFA phase). Hysteresis: it only moves when the wanted shift is more than
    // 1.5 px from where it is, so a shift hovering at a step boundary doesn't toggle 0/2 px from frame to frame.
    static std::array<int32_t, 2> applied{0, 0};
    static int64_t lastFrameNs = 0;
    const int64_t frameNs = stabilizerFrameTimeNs().load(std::memory_order_relaxed);
    if (frameNs - lastFrameNs > 500'000'000 || frameNs < lastFrameNs) applied = {0, 0};  // new recording
    lastFrameNs = frameNs;
    StabilizerSample shake;
    if (!stabilizerTrack().at(frameNs, shake)) return applied;
    // A camera turn about y moves the scene sideways in the image, about x up and down: the crop follows the scene.
    const float want[2] = {-shake.yaw * focal, shake.pitch * focal};
    const int32_t room[2] = {roomX & ~1, roomY & ~1};
    for (int i = 0; i < 2; ++i) {
        const float target = std::clamp(want[i], -float(room[i]), float(room[i]));
        if (std::fabs(target - float(applied[i])) > 1.5f)
            applied[i] = std::clamp(int32_t(std::lround(target * 0.5f)) * 2, -room[i], room[i]);
    }
    // Per-frame trace for checking the gyro/frame timing offline (`adb logcat -s RawrStab`).
    // raw = the camera's integrated turn as pixels (x, y) and roll in degrees, before smoothing.
    __android_log_print(ANDROID_LOG_INFO, "RawrStab",
                        "STAB t=%lld want=%.2f,%.2f applied=%d,%d room=%d,%d raw=%.2f,%.2f,%.3f focal=%.1f",
                        static_cast<long long>(frameNs), want[0], want[1], applied[0], applied[1], room[0], room[1],
                        -shake.rawYaw * focal, shake.rawPitch * focal, shake.rawRoll * 57.29578f, focal);
    return applied;
}
}  // namespace
rawr::post::StillDistortionCorrection VideoRecorder::distortionFor(const rawrcam::geometry::LensDistortionModel& lens,
                                                                  uint32_t rawWidth, uint32_t rawHeight) {
    const auto& demosaic = *resources_.demosaic_;
    const uint32_t scale = demosaic.sensorScale();
    const uint32_t cropX = demosaic.cropX(), cropY = demosaic.cropY();
    const uint32_t cropW = output_.extent_.width * scale, cropH = output_.extent_.height * scale;
    auto& c = distortionCache_;
    const bool same = c.valid && c.rawWidth == rawWidth && c.rawHeight == rawHeight && c.cropX == cropX &&
                      c.cropY == cropY && c.cropW == cropW && c.cropH == cropH && c.lens.k == lens.k &&
                      c.lens.fx == lens.fx && c.lens.cx == lens.cx && c.lens.outputScale == lens.outputScale &&
                      c.lens.width == lens.width && c.lens.height == lens.height;
    if (same) return c.correction;
    c = {lens, rawWidth, rawHeight, cropX, cropY, cropW, cropH, {}, true};
    const auto model = rawrcam::geometry::scaledTo(lens, rawWidth, rawHeight);
    if (!model) {  // another aspect: record as is
        __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "VIDEO_DISTORTION_SKIPPED lens=%ux%u raw=%ux%u",
                            lens.width, lens.height, rawWidth, rawHeight);
        return c.correction;
    }
    // Only the record crop is demosaiced: zoom just enough that every output
    // pixel samples inside it. The idle video viewfinder uses the same scale.
    const double outputScale = rawrcam::geometry::containedOutputScale(*model, cropX, cropY, cropW, cropH);
    auto& d = c.correction;
    d.enabled = true;
    d.model = rawr::post::StillDistortionCorrection::Model::Rational;
    d.intrinsic = {float(model->fx), float(model->fy), float(model->cx), float(model->cy), 0.0f};
    d.distortion = {float(model->k[0]), float(model->k[1]), float(model->k[2]), float(model->k[3]),
                    float(model->k[4])};
    d.rationalDenominator = {float(model->k[5]), float(model->k[6]), float(model->k[7])};
    d.outputFocal = {float(model->fx * outputScale), float(model->fy * outputScale)};
    d.frameOrigin = {float(cropX), float(cropY)};
    d.frameScale = float(scale);
    __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo",
                        "VIDEO_DISTORTION_CORRECTION raw=%ux%u crop=%u,%u %ux%u scale=%u outputScale=%.5f", rawWidth,
                        rawHeight, cropX, cropY, cropW, cropH, scale, outputScale);
    return c.correction;
}
void VideoRecorder::record(VkCommandBuffer command, uint32_t frameSlot, uint32_t imageIndex, uint32_t rawWidth,
                           uint32_t rawHeight, const VideoDemosaic::Frame& rawFrame,
                           const std::array<float, 9>& cameraToAp1, const tonemap::TonemapParams& params,
                           rawrcam::diagnostics::GpuTimingTracker& timing, const VideoProcessingConfig& activeConfig,
                           bool monitorEnabled, const std::function<VkCommandBuffer(VkCommandBuffer)>& splitSubmit,
                           const std::function<void(VkCommandBuffer, VkImageView, uint32_t, uint32_t)>& sceneMeter,
                           const rawrcam::geometry::LensDistortionModel* lensDistortion,
                           const color::DcpProfilePtr& dcp, float dcpWeight1, uint32_t lensMirror) {
    if (!resources_.tonemap_ || imageIndex >= output_.images_.size() || frameSlot >= resources_.monitor_.size() ||
        rawWidth < output_.extent_.width || rawHeight < output_.extent_.height)
        throw std::runtime_error("video output unavailable or RAW geometry invalid");
    std::array<VkImageMemoryBarrier, 4> begins{};
    uint32_t beginCount = 0;
    auto addBegin = [&](VkImage image, VkImageLayout oldLayout, VkAccessFlags access) {
        auto& b = begins[beginCount++];
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = oldLayout;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcAccessMask = access;
        b.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    };
    addBegin(output_.images_[imageIndex],
             output_.initialized_[imageIndex] ? output_.restingLayout() : VK_IMAGE_LAYOUT_UNDEFINED, 0);
    if (monitorEnabled)
        addBegin(resources_.monitor_[frameSlot].image,
                 resources_.monitorInitialized_[frameSlot] ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                                           : VK_IMAGE_LAYOUT_UNDEFINED,
                 resources_.monitorInitialized_[frameSlot] ? VK_ACCESS_SHADER_READ_BIT : 0);
    else if (!resources_.dummyMonitorInitialized_)
        addBegin(resources_.dummyMonitor_.image, VK_IMAGE_LAYOUT_UNDEFINED, 0);
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                         nullptr, 0, nullptr, beginCount, begins.data());
    if (!monitorEnabled) resources_.dummyMonitorInitialized_ = true;
    // The slot's previous submission has retired before the slot is reused.
    resources_.collectStageTiming(frameSlot);
    const VkQueryPool stagePool = resources_.stagePools_[frameSlot];
    vkCmdResetQueryPool(command, stagePool, 0, resources_.kStageQueries);
    vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, 12);
    if (rawFrame.rawBuffer && rawFrame.rawStridePixels < rawWidth)
        throw std::runtime_error("video RAW buffer stride is invalid");
    if (rawFrame.rawBuffer) {
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.buffer = rawFrame.rawBuffer;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 1, &barrier, 0, nullptr);
    }
    auto developedFrame = rawFrame;
    if (!activeConfig.lensShadingEnabled) {
        developedFrame.lensShading = nullptr;
        developedFrame.lensShadingCount = 0;
    }
    const auto finishFrame = [&] {
        if (monitorEnabled) {
            VkImageMemoryBarrier monitorReady{};
            monitorReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            monitorReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            monitorReady.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            monitorReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            monitorReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            monitorReady.image = resources_.monitor_[frameSlot].image;
            monitorReady.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                 0, 0, nullptr, 0, nullptr, 1, &monitorReady);
            resources_.monitorInitialized_[frameSlot] = true;
        }
        output_.initialized_[imageIndex] = true;
    };
    // 17U fast path: RAW -> P010 in one pass when no stage needs neighbouring
    // pixels after the demosaic (and no DCP/geometry). Otherwise the full path below.
    const char* fastReason = nullptr;
    if (!resources_.fast_ || !resources_.fastBake_)
        fastReason = "unavailable";
    else if (!output_.yuv() || imageIndex >= VideoOutput::kYuvImages || !output_.yuvFrames_[imageIndex].buffer)
        fastReason = "no_yuv";
    else if (activeConfig.highlightEnabled)
        fastReason = "highlight";
    else if (rawFrame.noiseProfileValid && activeConfig.waveletDenoiseStrength > 0.0f)
        fastReason = "denoise";
    else if (resources_.processingKey_.fccSteps != 0u || resources_.processingKey_.defringeStrength > 0.0f)
        fastReason = "fcc_defringe";
    else if (dcp)
        fastReason = "dcp";
    else if (lensDistortion || lensMirror != 0u)
        fastReason = "geometry";
    if (!resources_.fastLogged_) {
        resources_.fastLogged_ = true;
        __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "VIDEO_FAST_PATH active=%d reason=%s",
                            fastReason ? 0 : 1, fastReason ? fastReason : "none");
    }
    if (!fastReason) {
        tonemap::TonemapRecordInfo bakeTone{};
        bakeTone.cameraToWorkingColumnMajor3x3 = cameraToAp1.data();
        bakeTone.params = params;
        const auto& buffer = *output_.yuvFrames_[imageIndex].buffer;
        const auto shift = stabilizerShift(*resources_.demosaic_, resources_.processingKey_.kept);
        const VideoFastPath::EncoderTarget target{buffer.buffer,       buffer.yOffsetWords, buffer.yStrideWords,
                                                  buffer.cOffsetWords, buffer.cStrideWords, buffer.swapUV};
        resources_.fast_->record(command, frameSlot, developedFrame, *resources_.demosaic_, *resources_.fastBake_,
                                 bakeTone, target, output_.context_.queueFamily(),
                                 monitorEnabled ? resources_.monitor_[frameSlot].view : resources_.dummyMonitor_.view,
                                 monitorEnabled, stagePool, shift[0], shift[1]);
        timing.markVideoDemosaicDone(command, frameSlot);
        timing.markVideoPostDone(command, frameSlot);
        resources_.stagePending_[frameSlot] = true;
        finishFrame();
        return;
    }
    resources_.demosaic_->record(command, frameSlot, developedFrame);
    if (sceneMeter)
        sceneMeter(command, resources_.demosaic_->outputView(frameSlot), output_.extent_.width, output_.extent_.height);
    timing.markVideoDemosaicDone(command, frameSlot);
    vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, 13);
    // Submitting the frame in parts lets the GPU run the encoder's own
    // input conversion between them instead of queueing it behind one long
    // command buffer.
    if (splitSubmit) command = splitSubmit(command);
    const std::array<float, 3> gains{rawFrame.wb[0], 0.5f * (rawFrame.wb[1] + rawFrame.wb[2]), rawFrame.wb[3]};
    auto& stage = *resources_.post_[frameSlot];
    rawr::post::DenoiseRequest denoise{};
    if (rawFrame.noiseProfileValid) {
        denoise.strength = activeConfig.waveletDenoiseStrength;
        denoise.detail = activeConfig.waveletDenoiseDetail;
        denoise.forceY = activeConfig.waveletDenoiseForceY;
        denoise.maxScale = activeConfig.waveletDenoiseScales;
        denoise.noiseA = rawFrame.noiseA;
        denoise.noiseB = rawFrame.noiseB;
    }
    rawr::shading::LensShadingMapView shading{};
    if (developedFrame.lensShading) {
        shading = {developedFrame.lensShadingWidth, developedFrame.lensShadingHeight, developedFrame.lensShading,
                   developedFrame.lensShadingCount};
    }
    const rawr::highlight::ColoroppSensorGeometry geometry{rawWidth, rawHeight, resources_.demosaic_->cropX(),
                                                           resources_.demosaic_->cropY(),
                                                           resources_.demosaic_->sensorScale()};
    auto lensCorrection =
        lensDistortion ? distortionFor(*lensDistortion, rawWidth, rawHeight) : rawr::post::StillDistortionCorrection{};
    lensCorrection.mirror = lensMirror;
    stage.record(command, resources_.demosaic_->outputImage(frameSlot), resources_.demosaic_->outputView(frameSlot),
                 resources_.demosaic_->clipStateImage(frameSlot), resources_.demosaic_->clipStateView(frameSlot), gains,
                 activeConfig.highlightEnabled, lensCorrection, stagePool, false, activeConfig.highlightMethod,
                 activeConfig.highlightThreshold, activeConfig.highlightCompression,
                 std::max(params.aePostGain * std::exp2(params.exposureEV), 1.0e-6f), shading, rawFrame.cfa, denoise,
                 geometry, rawFrame.cameraToLinearSrgb.data());
    if (dcp) {
        auto& processor = resources_.dcp_[frameSlot];
        if (!processor) processor = std::make_unique<develop::rendered::DcpRenderStage>(
            resources_.context_.physicalDevice(), resources_.context_.device());
        processor->record(command, dcp, rawFrame.cameraToLinearSrgb, dcpWeight1,
            output_.extent_.width, output_.extent_.height, stage.outputView(), stage.sdrOutputView());
    }
    timing.markVideoPostDone(command, frameSlot);
    vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, 14);
    if (splitSubmit) command = splitSubmit(command);
    VkImageMemoryBarrier postReady{};
    postReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    postReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    postReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    postReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    postReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    postReady.image = stage.sdrOutputImage();
    postReady.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                         nullptr, 0, nullptr, 1, &postReady);
    tonemap::TonemapRecordInfo tone{};
    tone.commandBuffer = command;
    tone.input = {stage.sdrOutputView(), VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_LAYOUT_GENERAL, output_.extent_.width,
                  output_.extent_.height};
    tone.output = {output_.views_[imageIndex], output_.outputFormat_, VK_IMAGE_LAYOUT_GENERAL, output_.extent_.width,
                   output_.extent_.height};
    tone.monitor = {monitorEnabled ? resources_.monitor_[frameSlot].view : resources_.dummyMonitor_.view,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_LAYOUT_GENERAL, (output_.extent_.width + 1u) / 2u,
                    (output_.extent_.height + 1u) / 2u};
    tone.monitorEnabled = monitorEnabled;
    if (stage.coloroppToneRequired() && params.renderTransform == tonemap::RenderTransform::Existing) {
        tone.highlightCompression = rawr::highlight::coloroppToneCompression(activeConfig.highlightCompression);
        tone.highlightExposureGain = rawr::highlight::coloroppToneExposureGain(
            std::max(params.aePostGain * std::exp2(params.exposureEV), 1.0e-6f));
    }
    tone.frameSlot = frameSlot;
    tone.cameraToWorkingColumnMajor3x3 = cameraToAp1.data();
    tone.params = params;
    resources_.tonemap_->record(tone);
    vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stagePool, 15);
    resources_.stagePending_[frameSlot] = true;
    if (output_.yuv()) {
        // YUV encoder input: pack the tonemapped frame into the encoder buffer.
        output_.recordYuvPack(command, imageIndex);
    } else {
        VkImageMemoryBarrier end{};
        end.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        end.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        end.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        end.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        end.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        end.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        end.image = output_.images_[imageIndex];
        end.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                             nullptr, 0, nullptr, 1, &end);
    }
    finishFrame();
}
}  // namespace rawrcam::video

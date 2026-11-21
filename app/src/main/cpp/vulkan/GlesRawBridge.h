#pragma once
#include <memory>

#include "vulkan/RawAhbImporter.h"

namespace rawrcam::vulkan {
// RAW16 ingress for drivers whose Vulkan refuses camera RAW AHBs. The Xiaomi 15's Adreno 830 driver
// (7cd9369a93) fails vkAllocateMemory with VK_ERROR_INVALID_EXTERNAL_HANDLE for every RAW16/RAW10
// AHB, image or buffer, any usage or memory type, yet imports BLOB AHBs, and its GLES driver reads
// the RAW16 frame as an R16UI EGLImage bit-exactly. So GLES packs each frame into a BLOB AHB that
// Vulkan imports as a buffer, and the existing GPU copy (RawCpuUploadPool::gpuCopy) takes it from
// there: no CPU pixel copy. One BLOB per realtime frame slot; the camera frame is imported per frame.
class GlesRawBridge {
   public:
    GlesRawBridge();
    ~GlesRawBridge();
    // Packs `camera` into the slot's BLOB and returns it as a gpuCopy source. Waits `fence` (not
    // owned) and finishes the GLES work before returning. The slot must be retired. Throws on any
    // failure, leaving the caller's CPU upload in charge.
    ImportedRaw pack(VkDevice device, PFN_vkGetAndroidHardwareBufferPropertiesANDROID props, uint32_t slot,
                     AHardwareBuffer* camera, int fence, uint32_t w, uint32_t h);
    // Releases the EGL context and the BLOBs. Only with the device idle.
    void reset() noexcept;

   private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}  // namespace rawrcam::vulkan

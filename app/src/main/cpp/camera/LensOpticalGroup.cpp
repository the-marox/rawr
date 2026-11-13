#include "camera/LensOpticalGroup.h"

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>

#include <cmath>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>

namespace rawrcam::camera {

std::string lensOpticalGroup(const std::string& cameraId, const std::string& physicalCameraId) {
    const std::string id = physicalCameraId.empty() ? cameraId : physicalCameraId;
    static std::mutex mutex;
    static std::map<std::string, std::string> cache;
    std::lock_guard<std::mutex> lock(mutex);
    if (const auto it = cache.find(id); it != cache.end()) return it->second;

    std::string group = "cam:" + id;
    std::unique_ptr<ACameraManager, decltype(&ACameraManager_delete)> manager(ACameraManager_create(),
                                                                                ACameraManager_delete);
    ACameraMetadata* chars = nullptr;
    if (manager && ACameraManager_getCameraCharacteristics(manager.get(), id.c_str(), &chars) == ACAMERA_OK &&
        chars) {
        ACameraMetadata_const_entry facing{}, focal{}, size{};
        const bool hasFacing = ACameraMetadata_getConstEntry(chars, ACAMERA_LENS_FACING, &facing) == ACAMERA_OK &&
                               facing.count > 0;
        const bool hasFocal =
            ACameraMetadata_getConstEntry(chars, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &focal) == ACAMERA_OK &&
            focal.count > 0;
        const bool hasSize =
            ACameraMetadata_getConstEntry(chars, ACAMERA_SENSOR_INFO_PHYSICAL_SIZE, &size) == ACAMERA_OK &&
            size.count >= 2;
        if (hasFacing && hasFocal && hasSize) {
            char key[96];
            std::snprintf(key, sizeof key, "f%d:%.2f:%.2fx%.2f", static_cast<int>(facing.data.u8[0]),
                          static_cast<double>(focal.data.f[0]), static_cast<double>(size.data.f[0]),
                          static_cast<double>(size.data.f[1]));
            group = key;
        }
        ACameraMetadata_free(chars);
    }
    cache.emplace(id, group);
    return group;
}

}  // namespace rawrcam::camera

#pragma once

#include <string>

namespace rawrcam::camera {

// Groups capture-screen lenses by the physical optics they stream from: facing, focal length and
// sensor size, read from the camera's characteristics (the physical sub-camera's when one is set).
// A logical camera and the physical camera behind it (Xiaomi 0 and 2) therefore share a group.
// Falls back to the camera id when the characteristics can't be read. Results are cached.
[[nodiscard]] std::string lensOpticalGroup(const std::string& cameraId, const std::string& physicalCameraId);

}  // namespace rawrcam::camera

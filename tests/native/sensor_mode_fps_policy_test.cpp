// Fixed rates above 30 fps need a sensor mode at the RAW stream's own size (camera/SensorModeFpsPolicy.h).
// The tables are the Xiaomi 15's (dada), as its HAL reports them.
#include <cassert>
#include <cstdint>
#include <iostream>
#include <optional>
#include <vector>

#include "camera/SensorModeFpsPolicy.h"

using rawrcam::camera::parseQuicSensorModeTable;
using rawrcam::camera::sensorModeFpsCeiling;
using rawrcam::camera::sensorModeFpsCeilingAllowed;

namespace {

int32_t ceilingFor(const std::vector<int32_t>& table, int32_t width, int32_t height, int32_t keptRowsEnd,
                   std::optional<int32_t> forcedMode = std::nullopt) {
    return sensorModeFpsCeiling(parseQuicSensorModeTable(table.data(), static_cast<uint32_t>(table.size())), width,
                                height, keptRowsEnd, forcedMode);
}

// 14 mm (camera 3): the only 60 fps mode is 3456x2160.
const std::vector<int32_t> kUltraWide = {10,   3,    8160, 6144, 10,  4080, 3072, 30,  4080, 2296, 30,  3456,
                                         2160, 30,   3456, 2160, 60,  3680, 2760, 30,  4080, 3072, 21,  2560,
                                         1920, 30,   1920, 1080, 240, 1440, 1080, 10};
// 23 mm (camera 2): 4096x3072 only at 30 fps; 4096x2560 and 4096x2304 reach 60.
const std::vector<int32_t> kWide = {
    32,   3,    8192, 6144, 30,   4096, 3072, 30,   4096, 3072, 30,   4096, 3072, 30,   4096, 3072, 30,
    4096, 3072, 30,   4096, 3072, 30,   8192, 6144, 30,   4096, 2304, 30,   4096, 2304, 30,   4096, 2304,
    30,   4096, 2304, 30,   4096, 2304, 30,   4096, 2304, 30,   8192, 4608, 30,   4096, 2560, 30,   4096,
    2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 1840, 30,
    4096, 1840, 30,   4096, 1840, 30,   2560, 1920, 30,   4096, 2560, 60,   7680, 4320, 30,   1920, 1080,
    240,  1280, 720,  240,  1088, 612,  480,  8192, 6144, 12,   4096, 2304, 60};
// 59 mm (camera 4): 4096x3072 only at 30 fps; 4096x2560 reaches 60.
const std::vector<int32_t> kTele = {20,   3,    8192, 6144, 15,  4096, 3072, 30,   4096, 3072, 30,   4096, 3072,
                                    30,   8192, 6144, 15,   4096, 3072, 30,   4096, 3072, 30,   4096, 2560, 30,
                                    4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 60,   4096, 2304, 30,   4096,
                                    2304, 30,   4096, 2304, 30,   8192, 4608, 15,   4096, 2304, 30,   4096, 2304,
                                    30,   1920, 1080, 240,  1280, 720,  240,  4096, 2560, 30};

// Front (camera 1): 3264x2448 only at 30 fps; 3264x1836 reaches 60.
const std::vector<int32_t> kFront = {8,    3,    6528, 4896, 15,   3264, 2448, 30,  3264, 2448, 30,  3264,
                                     1836, 30,   3264, 1836, 30,   3264, 1836, 60,  1632, 918,  120, 1632,
                                     1224, 10};

}  // namespace

int main() {
    // Full-size RAW streams stop at 30 fps.
    assert(ceilingFor(kUltraWide, 4080, 3072, 3072) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 3072) == 30);
    assert(ceilingFor(kTele, 4096, 3072, 3072) == 30);
    assert(ceilingFor(kFront, 3264, 2448, 2448) == 30);
    // Cropping the rows a same-width 60 fps mode leaves stale opens 60 fps (23 mm, 59 mm, front).
    assert(ceilingFor(kWide, 4096, 3072, 2560) == 60);
    assert(ceilingFor(kTele, 4096, 3072, 2560) == 60);
    assert(ceilingFor(kFront, 3264, 2448, 2448 - 612) == 60);
    // A crop that keeps more rows than the 60 fps mode writes does not.
    assert(ceilingFor(kWide, 4096, 3072, 2561) == 30);
    assert(ceilingFor(kFront, 3264, 2448, 1837) == 30);
    // 14 mm: the 60 fps mode is narrower, so no crop helps.
    assert(ceilingFor(kUltraWide, 4080, 3072, 2160) == 30);
    assert(ceilingFor(kUltraWide, 4080, 3072, 0) == 30);
    // A request key holding the sensor in a 30 fps mode keeps the stream at 30 (mode 1 is the
    // 23 mm's 4096x3072; 20 is the DCG lens's 4096x2560), whatever the crop.
    assert(ceilingFor(kWide, 4096, 3072, 2560, 1) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 2560, 20) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 2560, 25) == 60);
    // An index past the table is ignored.
    assert(ceilingFor(kWide, 4096, 3072, 2560, 99) == 60);
    assert(ceilingFor(kWide, 4096, 3072, 2560, -1) == 60);
    // Slower full-size modes never pull the ceiling under 30 fps.
    assert(ceilingFor(kUltraWide, 8160, 6144, 6144) == 30);
    assert(ceilingFor(kWide, 8192, 6144, 6144, 32) == 30);
    // A size the table does not list is not limited.
    assert(ceilingFor(kUltraWide, 4000, 3000, 3000) == 0);
    assert(ceilingFor(kWide, 4096, 2560, 2560) == 60);

    // Malformed tables parse as empty, so nothing is limited.
    assert(parseQuicSensorModeTable(nullptr, 0).empty());
    assert(parseQuicSensorModeTable(kUltraWide.data(), 1).empty());
    assert(parseQuicSensorModeTable(kUltraWide.data(), static_cast<uint32_t>(kUltraWide.size() - 1)).empty());
    const std::vector<int32_t> twoFields = {1, 2, 4080, 3072};
    assert(parseQuicSensorModeTable(twoFields.data(), static_cast<uint32_t>(twoFields.size())).empty());
    const std::vector<int32_t> noModes = {0, 3};
    assert(parseQuicSensorModeTable(noModes.data(), static_cast<uint32_t>(noModes.size())).empty());
    // Entries keep the table's order, invalid ones included, so current_mode indexes them.
    const std::vector<int32_t> holes = {2, 3, 0, 0, 0, 4080, 3072, 30};
    const auto kept = parseQuicSensorModeTable(holes.data(), static_cast<uint32_t>(holes.size()));
    assert(kept.size() == 2 && kept[1].width == 4080);
    // Extra fields per mode are skipped.
    const std::vector<int32_t> fourFields = {2, 4, 4080, 3072, 30, 7, 3456, 2160, 60, 7};
    const auto wide = parseQuicSensorModeTable(fourFields.data(), static_cast<uint32_t>(fourFields.size()));
    assert(wide.size() == 2 && wide[1].width == 3456 && wide[1].height == 2160 && wide[1].fps == 60);
    assert(parseQuicSensorModeTable(kUltraWide.data(), static_cast<uint32_t>(kUltraWide.size())).size() == 10);
    assert(parseQuicSensorModeTable(kWide.data(), static_cast<uint32_t>(kWide.size())).size() == 32);

    // Xiaomi 15: validated, on by default, and the property can still turn it off.
    assert(sensorModeFpsCeilingAllowed("dada", '\0'));
    assert(sensorModeFpsCeilingAllowed("dada", '1'));
    assert(!sensorModeFpsCeilingAllowed("dada", '0'));
    // Any other device: off unless opted in by hand. Only the exact codename matches.
    for (const char* device : {"", "other", "dada_eea", "dad", "DADA"}) {
        assert(!sensorModeFpsCeilingAllowed(device, '\0'));
        assert(sensorModeFpsCeilingAllowed(device, '1'));
        assert(!sensorModeFpsCeilingAllowed(device, '0'));
        assert(!sensorModeFpsCeilingAllowed(device, 'x'));
    }
    std::cout << "sensor_mode_fps_policy_test: ok\n";
    return 0;
}

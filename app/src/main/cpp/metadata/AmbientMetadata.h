#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <string>

#include "metadata/LevelMetadata.h"

namespace rawrcam::metadata {

// Conditions at the shutter press measured by the phone's auxiliary sensors (not by the camera itself).
// Every field is optional; phones without a given sensor record nothing for it.
//   lux:           incident illuminance at the back of the phone (rear light sensor).
//   cctK:          correlated colour temperature of that light, kelvin.
//   flickerHz:     confirmed mains flicker, 100 or 120; 0 when the flicker sensor saw none.
//   flickerRawHz:  the flicker sensor's latest dominant frequency, before classification.
//   tofDistanceMm: what the laser AF time-of-flight sensor measured, millimetres.
//   pressureHpa:   barometric pressure, hectopascals.
struct CaptureAmbient {
    std::optional<float> lux;
    std::optional<float> cctK;
    std::optional<int> flickerHz;
    std::optional<float> flickerRawHz;
    std::optional<float> tofDistanceMm;
    std::optional<float> pressureHpa;

    bool empty() const { return !lux && !cctK && !flickerHz && !flickerRawHz && !tofDistanceMm && !pressureHpa; }
};

// JNI form: {lux, cctK, flickerHz, flickerRawHz[, tofDistanceMm[, pressureHpa]]}, NaN = unknown. Values outside a
// plausible range are dropped; a missing array, a short array or nothing usable means nothing was measured.
inline std::optional<CaptureAmbient> captureAmbientFromValues(const float* values, std::size_t count) {
    if (!values || count < 4) return std::nullopt;
    CaptureAmbient ambient;
    if (std::isfinite(values[0]) && values[0] >= 0.0f && values[0] < 1.0e6f) ambient.lux = values[0];
    if (std::isfinite(values[1]) && values[1] >= 1000.0f && values[1] <= 25000.0f) ambient.cctK = values[1];
    if (std::isfinite(values[2])) {
        const long hz = std::lround(values[2]);
        if (hz == 0 || hz == 100 || hz == 120) ambient.flickerHz = static_cast<int>(hz);
    }
    if (std::isfinite(values[3]) && values[3] > 0.0f && values[3] < 5000.0f) ambient.flickerRawHz = values[3];
    if (count >= 5 && std::isfinite(values[4]) && values[4] > 0.0f && values[4] <= 10000.0f)
        ambient.tofDistanceMm = values[4];
    if (count >= 6 && std::isfinite(values[5]) && values[5] >= 300.0f && values[5] <= 1100.0f)
        ambient.pressureHpa = values[5];
    if (ambient.empty()) return std::nullopt;
    return ambient;
}

inline std::string formatDecimal(float value, int decimals) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.*f", decimals, static_cast<double>(value));
    return buf;
}

// EXIF 2.31 Pressure (RATIONAL, hPa): tenths of a hectopascal.
inline std::array<std::uint32_t, 2> pressureRational(float hpa) {
    return {static_cast<std::uint32_t>(std::lround(static_cast<double>(hpa) * 10.0)), 10};
}

// rawr: XMP properties for the ambient values, without the namespace declaration (see rawrXmpAttributes).
// Pressure is not repeated here: it is a standard EXIF tag.
inline std::string ambientXmpProperties(const CaptureAmbient& ambient) {
    std::string out;
    if (ambient.lux) out += " rawr:AmbientLux=\"" + formatDecimal(*ambient.lux, 1) + "\"";
    if (ambient.cctK) out += " rawr:AmbientColorTemperature=\"" + formatDecimal(*ambient.cctK, 0) + "\"";
    if (ambient.flickerHz) out += " rawr:LightFlicker=\"" + std::to_string(*ambient.flickerHz) + "\"";
    // Metres, like EXIF SubjectDistance (which already carries the lens focus distance).
    if (ambient.tofDistanceMm)
        out += " rawr:TofDistance=\"" + formatDecimal(*ambient.tofDistanceMm / 1000.0f, 3) + "\"";
    return out;
}

// Every rawr: property of a capture as rdf:Description attributes, under a single namespace declaration.
// Empty when there is nothing to record.
inline std::string rawrXmpAttributes(const std::optional<CaptureLevel>& level,
                                     const std::optional<CaptureAmbient>& ambient) {
    const std::string ambientProperties = ambient ? ambientXmpProperties(*ambient) : std::string();
    if (!level && ambientProperties.empty()) return {};
    std::string out = std::string(" xmlns:rawr=\"") + kLevelXmpNamespace + "\"";
    if (level) out += levelXmpProperties(*level);
    return out + ambientProperties;
}

// A complete XMP packet for TIFF/DNG tag 700 carrying the level and the ambient values; empty when there are none.
inline std::string captureXmpPacket(const std::optional<CaptureLevel>& level,
                                    const std::optional<CaptureAmbient>& ambient) {
    const std::string attributes = rawrXmpAttributes(level, ambient);
    if (attributes.empty()) return {};
    return std::string("<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                       "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                       " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                       "  <rdf:Description rdf:about=\"\"") +
           attributes +
           "/>\n"
           " </rdf:RDF>\n"
           "</x:xmpmeta>\n"
           "<?xpacket end=\"w\"?>";
}

}  // namespace rawrcam::metadata

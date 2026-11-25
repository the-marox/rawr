#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace rawrcam::diagnostics {

// One "<field> <kB>" line out of /proc/self/status, in kB; 0 when the field is
// missing or the file cannot be read. No allocation, safe on any thread.
inline std::uint64_t readProcStatusKb(const char* field) noexcept {
    FILE* status = std::fopen("/proc/self/status", "r");
    if (status == nullptr) return 0;
    const std::size_t fieldLength = std::strlen(field);
    char line[160];
    // %llu takes an unsigned long long; std::uint64_t is unsigned long on LP64,
    // which -Wformat rejects under -Werror. Read into the scanf type, convert after.
    unsigned long long kb = 0;
    bool found = false;
    while (std::fgets(line, sizeof(line), status) != nullptr) {
        if (std::strncmp(line, field, fieldLength) != 0) continue;
        if (std::sscanf(line + fieldLength, "%llu", &kb) == 1) {
            found = true;
            break;
        }
        kb = 0;
    }
    std::fclose(status);
    return found ? static_cast<std::uint64_t>(kb) : 0;
}

// What the process has resident right now: the CPU-side buffers, the journal,
// the strip stitching, the DNG encoder. Vulkan and gralloc arena memory is not
// in it - it shows as "Graphics" in dumpsys meminfo - which is why the two
// readings are meant to be taken side by side.
inline std::uint64_t readRssBytes() noexcept { return readProcStatusKb("VmRSS:") * 1024u; }

// Peak resident set size since the process started: the number that says how
// close a burst came to the kernel's low-memory kill, and it stays readable
// after the arena has been freed.
inline std::uint64_t readPeakRssBytes() noexcept { return readProcStatusKb("VmHWM:") * 1024u; }

// The "%.1f" the existing memory logs print, so every line uses one unit.
inline double megabytes(std::uint64_t bytes) noexcept { return static_cast<double>(bytes) / (1024.0 * 1024.0); }

}  // namespace rawrcam::diagnostics

// Fractional reduction of recordings (video_pipeline/VideoCrop.h videoResampleRect) and where it is
// allowed (video/VideoResamplePolicy.h).
#include <cassert>
#include <cmath>
#include <iostream>

#include "video/VideoResamplePolicy.h"
#include "video_pipeline/VideoCrop.h"

using rawrcam::video::scaleVideoSourceRect;
using rawrcam::video::VideoKeptArea;
using rawrcam::video::videoKeptArea;
using rawrcam::video::videoResampleAllowed;
using rawrcam::video::videoResampleRect;
using rawrcam::video::videoSourceRect;

int main() {
    // Xiaomi 15 front, 1080p: the 1:1 rule crops the central 1920 of 3264 columns...
    const auto crop = videoSourceRect(3264, 2448, 1920, 1080, VideoKeptArea{});
    assert(!crop.resample && !crop.reduceCfa && crop.width == 1920 && crop.height == 1080);
    // ...the fractional reduction takes the whole width at 16:9 and scales it by 1.7.
    const auto full = videoSourceRect(3264, 2448, 1920, 1080, VideoKeptArea{}, true);
    assert(full.resample && !full.reduceCfa);
    assert(full.width == 3264 && full.height == 1836 && full.x == 0 && full.y == 306);
    assert(std::fabs(float(full.width) / 1920.0f - float(full.height) / 1080.0f) < 0.002f);
    // At 60 fps the front's kept area is 3264x1836 (rows 0..1835): the whole of it.
    const auto kept = videoKeptArea(3264, 2448, 0, 0, 0, 612);
    const auto sixty = videoSourceRect(3264, 2448, 1920, 1080, kept, true);
    assert(sixty.resample && sixty.x == 0 && sixty.y == 0 && sixty.width == 3264 && sixty.height == 1836);
    // A cinema aspect on 1080p (1920x800): still the full width.
    const auto scope = videoSourceRect(3264, 2448, 1920, 800, VideoKeptArea{}, true);
    assert(scope.resample && scope.width == 3264 && scope.height == 1360);

    // Unchanged where the old rule already serves: 2x and up reduces (rear 1080p), just above 1x
    // stays 1:1 (4K from 4096), and an output larger than the RAW does not fit.
    const auto rear = videoSourceRect(4096, 3072, 1920, 1080, VideoKeptArea{}, true);
    assert(rear.reduceCfa && !rear.resample && rear.width == 3840);
    const auto uhd = videoSourceRect(4096, 3072, 3840, 2160, VideoKeptArea{}, true);
    assert(!uhd.resample && !uhd.reduceCfa && uhd.width == 3840);
    assert(videoSourceRect(3264, 2448, 3840, 2160, VideoKeptArea{}, true).width == 0);
    assert(videoResampleRect(4096, 3072, 3840, 2160).width == 0);  // 1.07x: below the threshold
    assert(videoResampleRect(3840, 2160, 1920, 1080).width == 0);  // exactly 2x: the Bayer reduction
    assert(videoResampleRect(3264, 2448, 0, 1080).width == 0);

    // The preview keeps the flag when it scales the rect to its own size.
    const auto preview = scaleVideoSourceRect(full, 3264, 2448, 1632, 1224);
    assert(preview.resample && preview.width == 1632 && preview.height == 918);

    // Validated devices only, unless the property says otherwise.
    assert(videoResampleAllowed("dada", '\0'));
    assert(!videoResampleAllowed("dada", '0'));
    for (const char* device : {"", "other", "dada_eea", "DADA"}) {
        assert(!videoResampleAllowed(device, '\0'));
        assert(videoResampleAllowed(device, '1'));
        assert(!videoResampleAllowed(device, 'x'));
    }
    std::cout << "video_resample_rect_test: ok\n";
    return 0;
}

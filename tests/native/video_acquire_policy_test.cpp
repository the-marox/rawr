#include <cassert>
#include <iostream>

#include "video/VideoAcquirePolicy.h"

using rawrcam::video::videoAcquireWaitMs;

int main() {
    assert(videoAcquireWaitMs("dada", "") == 0);
    assert(videoAcquireWaitMs("dada", "0") == 0);
    assert(videoAcquireWaitMs("dada", "2") == 2);
    assert(videoAcquireWaitMs("dada", "5") == 5);
    assert(videoAcquireWaitMs("dada", "6") == 5);
    assert(videoAcquireWaitMs("dada", "999") == 5);
    assert(videoAcquireWaitMs("dada", "-1") == 0);
    assert(videoAcquireWaitMs("dada", "abc") == 0);
    assert(videoAcquireWaitMs("other", "2") == 0);
    std::cout << "video_acquire_policy_test: ok\n";
    return 0;
}

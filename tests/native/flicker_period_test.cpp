// The light period rawr AE keeps its shutter in step with (camera/FlickerPeriod.h).
#include <cassert>
#include <iostream>

#include "camera/FlickerPeriod.h"

using rawrcam::camera::rawrAeFlickerPeriodNs;
using rawrcam::camera::sensedFlickerAllowed;

int main() {
    // Explicit 50/60 Hz win over anything sensed; Off is off.
    assert(rawrAeFlickerPeriodNs(1, 0, 4'000'000) == 10'000'000);
    assert(rawrAeFlickerPeriodNs(2, 1, 4'000'000) == 8'333'333);
    assert(rawrAeFlickerPeriodNs(0, 1, 4'000'000) == 0);
    // Auto: the sensor's period (a 250 Hz PWM LED) before the camera's mains guess.
    assert(rawrAeFlickerPeriodNs(3, 1, 4'000'000) == 4'000'000);
    assert(rawrAeFlickerPeriodNs(3, 0, 4'000'000) == 4'000'000);
    // Nothing sensed: the camera's mains detection, as before.
    assert(rawrAeFlickerPeriodNs(3, 1, 0) == 10'000'000);
    assert(rawrAeFlickerPeriodNs(3, 2, 0) == 8'333'333);
    assert(rawrAeFlickerPeriodNs(3, 0, 0) == 0);
    // Out-of-range sensed periods are ignored (above 1000 Hz, below 40 Hz).
    assert(rawrAeFlickerPeriodNs(3, 1, 999'999) == 10'000'000);
    assert(rawrAeFlickerPeriodNs(3, 0, 25'000'001) == 0);
    assert(rawrAeFlickerPeriodNs(3, 0, -5) == 0);
    // Validated devices only, unless the property says otherwise.
    assert(sensedFlickerAllowed("dada", '\0'));
    assert(!sensedFlickerAllowed("dada", '0'));
    for (const char* device : {"", "other", "dada_eea", "DADA"}) {
        assert(!sensedFlickerAllowed(device, '\0'));
        assert(sensedFlickerAllowed(device, '1'));
        assert(!sensedFlickerAllowed(device, 'x'));
    }
    std::cout << "flicker_period_test: ok\n";
    return 0;
}

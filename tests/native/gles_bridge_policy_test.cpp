// Where the GLES RAW16 bridge may engage (vulkan/GlesRawBridgePolicy.h): only on validated devices,
// unless debug.rawr.gles_bridge forces it on ('1') or off ('0').
#include <cassert>
#include <iostream>

#include "vulkan/GlesRawBridgePolicy.h"

using rawrcam::vulkan::glesBridgeAllowed;

int main() {
    // Xiaomi 15: validated, on by default, and the property can still turn it off.
    assert(glesBridgeAllowed("dada", '\0'));
    assert(glesBridgeAllowed("dada", '1'));
    assert(!glesBridgeAllowed("dada", '0'));
    // Any other device: off unless opted in by hand. Only the exact codename matches.
    for (const char* device : {"", "other", "dada_eea", "dad", "DADA"}) {
        assert(!glesBridgeAllowed(device, '\0'));
        assert(glesBridgeAllowed(device, '1'));
        assert(!glesBridgeAllowed(device, '0'));
        // Any other value is "unset".
        assert(!glesBridgeAllowed(device, 'x'));
    }
    std::cout << "gles_bridge_policy_test: ok\n";
    return 0;
}

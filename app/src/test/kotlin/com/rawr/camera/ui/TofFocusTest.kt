package com.rawr.camera.ui

import com.rawr.camera.model.ManualFocusCapability
import kotlin.test.*

class TofFocusTest {
    // A lens that focuses down to 10 cm (10 D), with a calibrated distance readout.
    private val lens = ManualFocusCapability(supported = true, distanceReadoutTrustworthy = true, minimumFocusDistance = 10f)

    @Test fun distanceBecomesTheMatchingManualFocusPosition() {
        assertEquals(0.9f, tofFocusNormalized(1000f, lens)!!, 1e-4f)   // 1 m = 1 D
        assertEquals(0.8f, tofFocusNormalized(500f, lens)!!, 1e-4f)    // 0.5 m = 2 D
        assertEquals(0.975f, tofFocusNormalized(4000f, lens)!!, 1e-4f) // 4 m = 0.25 D
    }

    @Test fun closerThanTheLensFocusesLandsOnItsClosestPosition() {
        assertEquals(0f, tofFocusNormalized(50f, lens)!!)
        val limited = lens.copy(minNormalized = 0.2f)
        assertEquals(0.2f, tofFocusNormalized(50f, limited)!!)
    }

    @Test fun unusableWithoutADistanceOrACalibratedLens() {
        assertNull(tofFocusNormalized(null, lens))
        assertNull(tofFocusNormalized(Float.NaN, lens))
        assertNull(tofFocusNormalized(0f, lens))
        assertNull(tofFocusNormalized(1000f, lens.copy(distanceReadoutTrustworthy = false)))
        assertNull(tofFocusNormalized(1000f, lens.copy(supported = false)))
        assertNull(tofFocusNormalized(1000f, lens.copy(minimumFocusDistance = 0f)))
    }

    @Test fun labelIsShort() {
        assertEquals("TOF 45cm", tofFocusLabel(452f))
        assertEquals("TOF 1.2m", tofFocusLabel(1234f))
        assertEquals("TOF 4.0m", tofFocusLabel(4000f))
    }
}

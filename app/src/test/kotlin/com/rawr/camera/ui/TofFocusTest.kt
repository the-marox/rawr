package com.rawr.camera.ui

import com.rawr.camera.integration.AuxSensorReading
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.FocusUiState
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

    // The 17U's L8 on open sky: every band a "valid" 540-630 mm, while AF holds focus at 12.5 m (0.08 D).
    private val sky = AuxSensorReading(tofDistanceMm = 540f, tofFarthestMm = 630f)
    private val afFar = FocusUiState(mode = FocusMode.Af, appliedFocusDiopters = 0.08f, afFocused = true)

    @Test fun aNearReadingIsDroppedWhileAfConfirmsAFarFocus() {
        assertNull(tofDistanceForFocus(sky, afFar, lens))
        assertNull(tofDistanceForFocus(sky, afFar.copy(mode = FocusMode.AfLock), lens))
        assertNull(tofDistanceForFocus(sky, afFar.copy(appliedFocusDiopters = 0f), lens))   // AF at infinity
    }

    @Test fun theReadingStaysUnlessAfPositivelySaysFar() {
        assertEquals(540f, tofDistanceForFocus(sky, afFar.copy(afFocused = false), lens))        // scanning or failed
        assertEquals(540f, tofDistanceForFocus(sky, afFar.copy(mode = FocusMode.Mf), lens))      // the user set focus
        assertEquals(540f, tofDistanceForFocus(sky, afFar.copy(appliedFocusDiopters = 0.5f), lens)) // AF at 2 m
        assertEquals(540f, tofDistanceForFocus(sky, afFar.copy(appliedFocusDiopters = null), lens))
        assertEquals(540f, tofDistanceForFocus(sky, afFar, lens.copy(distanceReadoutTrustworthy = false)))
    }

    @Test fun aBandWithAFarTargetKeepsTheReading() {
        // A hand over the top half (bands 0-1) in front of a wall at 1.1 m: a real scene, not the sky.
        val hand = AuxSensorReading(tofDistanceMm = 105f, tofFarthestMm = 1122f)
        assertEquals(105f, tofDistanceForFocus(hand, afFar, lens))
        // A single-zone sensor carries no farthest band: its distance stands in.
        assertNull(tofDistanceForFocus(AuxSensorReading(tofDistanceMm = 600f), afFar, lens))
        assertEquals(1500f, tofDistanceForFocus(AuxSensorReading(tofDistanceMm = 1500f), afFar, lens))
        assertNull(tofDistanceForFocus(AuxSensorReading(), afFar, lens))
        assertNull(tofDistanceForFocus(null, afFar, lens))
    }

    @Test fun labelIsShort() {
        assertEquals("TOF 45cm", tofFocusLabel(452f))
        assertEquals("TOF 1.2m", tofFocusLabel(1234f))
        assertEquals("TOF 4.0m", tofFocusLabel(4000f))
    }
}

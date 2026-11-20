package com.rawr.camera.integration

import kotlin.test.*

class AuxSensorsTest {
    // Dominant frequencies the Xiaomi 15 flicker sensor reported (7.8125 Hz bins).
    private val led50HzMains = 101.5625f
    private val noFlicker = 7.8125f
    private val daylight = listOf(15.625f, 54.6875f, 585.9375f, 23.4375f, 312.5f, 39.0625f)

    private fun FlickerClassifier.feed(vararg hz: Float): FlickerFrequency? {
        var last: FlickerFrequency? = null
        hz.forEach { last = update(it) }
        return last
    }

    @Test fun mainsFlickerIsConfirmedAfterThreeReadings() {
        val c = FlickerClassifier()
        assertNull(c.feed(led50HzMains, led50HzMains))
        assertEquals(FlickerFrequency.Hz100, c.update(led50HzMains))
        assertEquals(50, c.detected!!.mainsHz)
    }

    @Test fun neighbouringBinsCountAsTheSameFlicker() {
        val c = FlickerClassifier()
        assertEquals(FlickerFrequency.Hz100, c.feed(93.75f, 101.5625f, 93.75f))
        val sixty = FlickerClassifier()
        assertEquals(FlickerFrequency.Hz120, sixty.feed(117.1875f, 125f, 117.1875f))
    }

    @Test fun steadyLightAndWanderingDaylightAreNotFlicker() {
        val dark = FlickerClassifier()
        repeat(20) { assertNull(dark.update(noFlicker)) }
        val day = FlickerClassifier()
        // One reading that happens to land near 100 Hz does not confirm anything.
        assertNull(day.feed(*daylight.toFloatArray(), led50HzMains, *daylight.toFloatArray()))
    }

    @Test fun oneNoisyReadingNeitherClearsNorSwitches() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(54.6875f, led50HzMains))
        assertEquals(FlickerFrequency.Hz100, c.feed(125f, led50HzMains))
    }

    @Test fun flickerIsReleasedAfterFiveMissesInARow() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(noFlicker, noFlicker, noFlicker, noFlicker))
        assertNull(c.update(noFlicker))
    }

    @Test fun aNewMainsFrequencyTakesOverOnceConfirmed() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertEquals(FlickerFrequency.Hz100, c.feed(117.1875f, 117.1875f))
        assertEquals(FlickerFrequency.Hz120, c.update(117.1875f))
    }

    @Test fun invalidReadingsCountAsMisses() {
        val c = FlickerClassifier()
        c.feed(led50HzMains, led50HzMains, led50HzMains)
        assertNull(c.feed(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN))
        c.reset()
        assertNull(c.detected)
    }

    @Test fun shuttersOutOfStepWithTheFlickerBand() {
        val f = FlickerFrequency.Hz100
        assertTrue(FlickerBanding.bands(8_000_000L, f))       // 1/125 s
        assertTrue(FlickerBanding.bands(1_000_000L, f))       // 1/1000 s
        assertTrue(FlickerBanding.bands(16_666_667L, f))      // 1/60 s
        assertFalse(FlickerBanding.bands(10_000_000L, f))     // 1/100 s
        assertFalse(FlickerBanding.bands(20_000_000L, f))     // 1/50 s
        assertFalse(FlickerBanding.bands(30_303_030L, f))     // 1/33 s, within 5 % of three periods
        assertFalse(FlickerBanding.bands(250_000_000L, f))    // 1/4 s averages the flicker out
        assertFalse(FlickerBanding.bands(0L, f))
        assertFalse(FlickerBanding.bands(8_333_333L, FlickerFrequency.Hz120))  // 1/120 s at 60 Hz mains
        assertTrue(FlickerBanding.bands(10_000_000L, FlickerFrequency.Hz120))  // 1/100 s at 60 Hz mains
    }

    @Test fun nearestSafeShutterIsAWholeNumberOfPeriods() {
        val f = FlickerFrequency.Hz100
        assertEquals(10_000_000L, FlickerBanding.nearestSafeExposureNs(8_000_000L, f))
        assertEquals(10_000_000L, FlickerBanding.nearestSafeExposureNs(1_000_000L, f))
        assertEquals(20_000_000L, FlickerBanding.nearestSafeExposureNs(16_666_667L, f))
    }

    @Test fun incidentEvAtIso100() {
        assertEquals(0f, AuxSensorReading(lux = 2.5f).ev100!!, 1e-4f)
        assertEquals(4.35f, AuxSensorReading(lux = 51f).ev100!!, 0.01f)
        assertNull(AuxSensorReading(lux = 0f).ev100)
        assertNull(AuxSensorReading().ev100)
    }

    @Test fun nativeFormSeparatesUnknownFromNoFlicker() {
        val none = AuxSensorReading(lux = 51.1f, cctK = 3329f, flickerRawHz = 7.8125f, flickerSensor = true).toNative()
        assertEquals(51.1f, none[0])
        assertEquals(3329f, none[1])
        assertEquals(0f, none[2])
        assertEquals(7.8125f, none[3])
        val led = AuxSensorReading(flicker = FlickerFrequency.Hz100, flickerSensor = true).toNative()
        assertEquals(100f, led[2])
        val unknown = AuxSensorReading(lux = 10f).toNative()
        assertTrue(unknown[1].isNaN() && unknown[2].isNaN() && unknown[3].isNaN() && unknown[4].isNaN())
        assertEquals(218f, AuxSensorReading(tofDistanceMm = 218f).toNative()[4])
        assertFalse(AuxSensorReading(tofDistanceMm = 218f).isEmpty)
        assertEquals(1013.2f, AuxSensorReading(pressureHpa = 1013.2f).toNative()[5])
        assertTrue(unknown[5].isNaN())
        // The heading is not in the native array (it travels with the GPS fix) but still counts as a reading.
        assertEquals(6, AuxSensorReading(headingMagneticDeg = 90f).toNative().size)
        assertFalse(AuxSensorReading(headingMagneticDeg = 90f).isEmpty)
        assertTrue(AuxSensorReading().isEmpty)
    }
}

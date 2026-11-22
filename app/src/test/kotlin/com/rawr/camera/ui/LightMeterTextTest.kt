package com.rawr.camera.ui

import com.rawr.camera.integration.AuxSensorReading
import com.rawr.camera.integration.FlickerFrequency
import kotlin.test.*

class LightMeterTextTest {
    @Test fun ledUnderFiftyHertzMainsAtABandingShutter() {
        val reading = AuxSensorReading(lux = 51.1f, cctK = 3329f, flicker = FlickerFrequency.Hz100, flickerSensor = true)
        val text = lightMeterText(reading, exposureTimeNs = 8_000_000L)
        assertEquals("51 lx · EV 4.4 · 3330K", text.light)
        assertEquals("~100 Hz", text.detail)
        assertEquals("1/125 will band · try 1/100", text.warning)
    }

    @Test fun pwmFlickerIsShownWithItsOwnWarning() {
        val reading = AuxSensorReading(lux = 120f, flickerPwmHz = 250.4f, flickerSensor = true)
        val text = lightMeterText(reading, exposureTimeNs = 3_000_000L)
        assertEquals("~250 Hz PWM", text.detail)
        assertEquals("1/333 will band · try 1/250", text.warning)
        assertNull(lightMeterText(reading, exposureTimeNs = 8_000_000L).warning)
    }

    @Test fun noWarningWhenTheShutterIsInStep() {
        val reading = AuxSensorReading(lux = 51.1f, flicker = FlickerFrequency.Hz100, flickerSensor = true)
        assertNull(lightMeterText(reading, exposureTimeNs = 20_000_000L).warning)
        assertNull(lightMeterText(reading, exposureTimeNs = null).warning)
    }

    @Test fun steadyLightSaysNoFlickerAndDimLightKeepsADecimal() {
        val text = lightMeterText(AuxSensorReading(lux = 9.8f, flickerRawHz = 7.8125f, flickerSensor = true), 8_000_000L)
        assertEquals("9.8 lx · EV 2.0", text.light)
        assertEquals("no flicker", text.detail)
        assertNull(text.warning)
    }

    @Test fun flickerAndDistanceShareTheSecondLine() {
        // Xiaomi 15, hand in front of the lens: the ToF matched the lens focus distance (0.10 m).
        val text = lightMeterText(AuxSensorReading(lux = 30f, cctK = 4862f, flickerSensor = true, tofDistanceMm = 100f), null)
        assertEquals("30 lx · EV 3.6 · 4860K", text.light)
        assertEquals("no flicker · 0.10 m", text.detail)
        val far = lightMeterText(AuxSensorReading(tofDistanceMm = 3384f), null)
        assertEquals("", far.light)
        assertEquals("3.4 m", far.detail)
    }

    @Test fun brightDaylightIsShownInKilolux() {
        val text = lightMeterText(AuxSensorReading(lux = 32_500f), null)
        assertEquals("32.5k lx · EV 13.7", text.light)
        assertEquals("", text.detail)
    }
}

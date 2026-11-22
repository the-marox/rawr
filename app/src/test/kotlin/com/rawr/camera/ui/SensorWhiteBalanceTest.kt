package com.rawr.camera.ui

import kotlin.test.*

class SensorWhiteBalanceTest {
    @Test fun sensorTemperatureLandsOnTheRailSteps() {
        assertEquals(4850, sensorWhiteBalanceK(4862f))
        assertEquals(3325, sensorWhiteBalanceK(3329f))
        assertEquals(5200, sensorWhiteBalanceK(5200f))
    }

    @Test fun sensorTemperatureStaysInTheRailRange() {
        assertEquals(2000, sensorWhiteBalanceK(1500f))
        assertEquals(10000, sensorWhiteBalanceK(14000f))
    }

    @Test fun noReadingNoChoice() {
        assertNull(sensorWhiteBalanceK(null))
        assertNull(sensorWhiteBalanceK(Float.NaN))
        assertNull(sensorWhiteBalanceK(0f))
    }
}

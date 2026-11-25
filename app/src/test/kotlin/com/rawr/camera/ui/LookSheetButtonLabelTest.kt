package com.rawr.camera.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rawr 17U fork: the LOOK button's two lines (LookSheet.kt). */
class LookSheetButtonLabelTest {
    @Test fun firstWordAboveTheRest() {
        assertEquals("RAWR" to "NTRL", lookButtonLines("RAWR NTRL"))
        assertEquals("AgX" to "Punchy", lookButtonLines("AgX Punchy"))
        assertEquals("Kodak" to "Double-X", lookButtonLines("Kodak Double-X"))
        assertEquals("Leica" to "M9", lookButtonLines("Leica M9"))
    }

    @Test fun oneWordIsOneLine() {
        assertEquals("sRGB" to null, lookButtonLines("sRGB"))
        assertEquals("AgX" to null, lookButtonLines(" AgX "))
        assertEquals("LOOK" to null, lookButtonLines(""))
    }
}

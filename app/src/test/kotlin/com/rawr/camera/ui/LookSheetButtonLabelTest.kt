package com.rawr.camera.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rawr 17U fork: the LOOK button's short name (LookSheet.kt). */
class LookSheetButtonLabelTest {
    @Test fun shortNamesStayWhole() {
        assertEquals("AgX RAWR", lookButtonLabel("AgX RAWR"))
        assertEquals("sRGB", lookButtonLabel("sRGB"))
        assertEquals("Leica M9", lookButtonLabel("Leica M9"))
    }

    @Test fun longNamesKeepTheirLastWord() {
        assertEquals("NTRL", lookButtonLabel("RAWR NTRL"))
        assertEquals("Punchy", lookButtonLabel("AgX Punchy"))
        assertEquals("Double-X", lookButtonLabel("Kodak Double-X"))
        assertEquals("Portra400X", lookButtonLabel("Portra400XYZW"))
    }
}

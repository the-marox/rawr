package com.rawr.camera.i18n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rawr 17U fork: UI translation lookups. */
class I18nTest {
    @AfterTest fun reset() = I18n.install(emptyMap())

    @Test fun englishWhenNothingIsLoaded() {
        I18n.install(emptyMap())
        assertEquals("Exposure", tr("Exposure"))
    }

    @Test fun exactCapitalsAndJoinedTexts() {
        I18n.install(mapOf("Exposure" to "Exposição", "Photo" to "Foto", "Video" to "Vídeo"))
        assertEquals("Exposição", tr("Exposure"))
        assertEquals("EXPOSIÇÃO", tr("EXPOSURE"))
        assertEquals("Foto · Vídeo", tr("Photo · Video"))
        assertEquals("Unknown", tr("Unknown"))
    }

    @Test fun halfTranslatedJoinsStayEnglish() {
        I18n.install(mapOf("Photo" to "Foto"))
        assertEquals("Photo · Other", tr("Photo · Other"))
        assertEquals("Foto · 12", tr("Photo · 12"))
    }

    @Test fun identityEntryStopsTheCapitalsFallback() {
        I18n.install(mapOf("Film" to "Filme", "FILM" to "FILM"))
        assertEquals("FILM", tr("FILM"))
        assertEquals("Filme", tr("Film"))
    }
}

package com.rawr.camera.settings.ui

import com.rawr.camera.settings.model.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rawr 17U fork: the settings search index is generated from the sources and finds pages and options. */
class SettingsSearchTest {
    @Test fun generatedIndexHoldsPagesAndOptions() {
        assertTrue(settingsSearchEntries.size > 50, "index too small: ${settingsSearchEntries.size}")
        assertTrue(settingsSearchEntries.any { it.isPage && it.section == SettingsSection.Denoise })
        assertTrue(settingsSearchEntries.any { !it.isPage && it.section == SettingsSection.VideoEncoder })
    }

    @Test fun pageNamedLikeTheQueryComesFirst() {
        val first = searchSettings("denoise").first()
        assertEquals(SettingsSection.Denoise, first.section)
        assertTrue(first.isPage)
    }

    @Test fun wordsCanMatchThePagePath() {
        assertTrue(searchSettings("denoise strength").any { it.section == SettingsSection.Denoise && !it.isPage })
        assertTrue(searchSettings("   ").isEmpty())
    }
}

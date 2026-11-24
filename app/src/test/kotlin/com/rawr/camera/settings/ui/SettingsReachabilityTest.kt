package com.rawr.camera.settings.ui

import com.rawr.camera.settings.model.SettingsSection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rawr 17U fork: every settings page stays reachable from the grouped home. The links are read from the
 * settings UI sources (OpenSection(SettingsSection.X) and the home's SettingsHomeEntry list), so a page Ed adds
 * upstream without a way in fails this test at the next sync instead of silently disappearing.
 */
class SettingsReachabilityTest {
    // Pages opened another way: Image (Ed's former hub, split into Look and RAW development; still the back
    // target of its subpages), CameraDevice (an alias of Capture), OIS (a switch on the Capture page).
    private val notLinked = setOf(SettingsSection.Image, SettingsSection.CameraDevice, SettingsSection.Ois)

    @Test fun everySettingsPageIsReachableFromHome() {
        val dir = listOf("src/main/kotlin/com/rawr/camera/settings/ui", "app/src/main/kotlin/com/rawr/camera/settings/ui")
            .map(::File).first { it.isDirectory }
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        val links = Regex("""(?:OpenSection\(|SettingsHomeEntry\(|HubRow\([^)]*?)SettingsSection\.(\w+)""")
        val linked = sources.values.flatMap { text -> links.findAll(text).map { it.groupValues[1] } }.toSet()
        val missing = SettingsSection.entries.filter { it !in notLinked && it.name !in linked }
        assertTrue(missing.isEmpty(), "settings pages with no way in from the home: $missing")
    }
}

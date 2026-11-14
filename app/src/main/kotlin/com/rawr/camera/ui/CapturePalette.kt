package com.rawr.camera.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Theme presets: LEICA is today's palette; the others only swap the two accents. */
internal enum class ThemePreset(val label: String, val accent: Long, val accentSoft: Long) {
    Leica("LEICA", 0xFFE2001A, 0xFFFF4D5E),
    Mono("MONO", 0xFFEDEDED, 0xFFBDBDBD),
    Steel("STEEL", 0xFF7F9BB3, 0xFFA9BED0),
    Sage("SAGE", 0xFF8FA88A, 0xFFB3C7AE),
    Custom("CUSTOM", 0xFFE2001A, 0xFFFF4D5E)
}

/** Editable theme colours; neutrals default to the Leica greys. */
internal enum class ThemeColor(val label: String, val description: String, val leica: Long) {
    Accent("Accent", "Selected buttons, outlines, sliders", 0xFFE2001A),
    AccentSoft("Accent soft", "Changed values, the • dot", 0xFFFF4D5E),
    Background("Background", "Behind the controls", 0xFF000000),
    Panel("Panel", "Strip cards, quick panel", 0xFF111111),
    Chip("Chip", "Buttons and tiles", 0xFF1E1E1E),
    TextSecondary("Text secondary", "Section titles, hints", 0xFFA6A6A6)
}

/**
 * Process-wide theme state read by [CaptureColors]. Snapshot state, so every screen recomposes when it changes
 * (Settings and the viewfinder share one process). Kept in its own SharedPreferences so it is ready synchronously
 * before the first frame of any activity.
 */
internal object CapturePalette {
    private var prefs: SharedPreferences? = null
    var preset by mutableStateOf(ThemePreset.Leica)
        private set
    private val overrides = mutableStateMapOf<ThemeColor, Color>()

    fun color(c: ThemeColor): Color = overrides[c] ?: Color(defaultFor(preset, c))

    fun defaultFor(p: ThemePreset, c: ThemeColor): Long = when (c) {
        ThemeColor.Accent -> p.accent
        ThemeColor.AccentSoft -> p.accentSoft
        else -> c.leica
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("rawr_theme", Context.MODE_PRIVATE)
        prefs = p
        preset = p.getString(KEY_PRESET, null)?.let { name -> ThemePreset.entries.firstOrNull { it.name == name } }
            ?: ThemePreset.Leica
        overrides.clear()
        p.getString(KEY_COLORS, null).orEmpty().split(',').forEach { entry ->
            val (name, hex) = entry.split('=').takeIf { it.size == 2 } ?: return@forEach
            val key = ThemeColor.entries.firstOrNull { it.name == name } ?: return@forEach
            hex.toLongOrNull(16)?.let { overrides[key] = Color(it or 0xFF000000) }
        }
    }

    /** A preset replaces every colour; CUSTOM keeps the current colours as its starting point. */
    fun selectPreset(p: ThemePreset) {
        if (p == ThemePreset.Custom) {
            ThemeColor.entries.forEach { c -> overrides[c] = color(c) }
        } else {
            overrides.clear()
        }
        preset = p
        save()
    }

    /** Editing a colour makes the theme CUSTOM. */
    fun setColor(c: ThemeColor, value: Color) {
        if (preset != ThemePreset.Custom) ThemeColor.entries.forEach { overrides.putIfAbsent(it, color(it)) }
        overrides[c] = value
        preset = ThemePreset.Custom
        save()
    }

    fun reset() = selectPreset(ThemePreset.Leica)

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_PRESET, preset.name)
            .putString(KEY_COLORS, overrides.entries.joinToString(",") { (k, v) ->
                k.name + "=" + "%08X".format(v.toArgb())
            })
            .apply()
    }

    fun hex(color: Color): String = "%06X".format(color.toArgb() and 0xFFFFFF)

    /** "#RRGGBB" or "RRGGBB" → opaque colour, null if invalid. */
    fun parseHex(text: String): Color? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toLongOrNull(16)?.let { Color(it or 0xFF000000) }
    }

    private const val KEY_PRESET = "preset"
    private const val KEY_COLORS = "colors"
}
